package com.blockchain.coincore.loader

import com.blockchain.core.custodial.TradingBalanceDataManager
import com.blockchain.core.chains.erc20.Erc20DataManager
import com.blockchain.core.interest.InterestBalanceDataManager
import com.blockchain.core.price.ExchangeRatesDataManager
import com.blockchain.featureflags.InternalFeatureFlagApi
import com.blockchain.logging.CrashLogger
import com.blockchain.nabu.UserIdentity
import com.blockchain.nabu.datamanagers.CustodialWalletManager
import com.blockchain.preferences.CurrencyPrefs
import com.blockchain.preferences.WalletStatus
import com.blockchain.wallet.DefaultLabels
import info.blockchain.balance.AssetInfo
import info.blockchain.balance.isCustodial
import info.blockchain.balance.isCustodialOnly
import info.blockchain.balance.isErc20
import io.reactivex.rxjava3.core.Completable
import com.blockchain.coincore.AssetAction
import com.blockchain.coincore.CryptoAsset
import com.blockchain.coincore.custodialonly.DynamicOnlyTradingAsset
import com.blockchain.coincore.erc20.Erc20Asset
import piuk.blockchain.androidcore.data.fees.FeeDataManager
import piuk.blockchain.androidcore.data.payload.PayloadDataManager
import io.reactivex.rxjava3.core.Single
import com.blockchain.coincore.CoincoreInitFailure
import com.blockchain.coincore.NonCustodialSupport
import piuk.blockchain.androidcore.utils.extensions.thenSingle
import thepit.PitLinking
import timber.log.Timber
import java.lang.IllegalStateException

// This is a rubbish regex, but it'll do until I'm provided a better one
private const val defaultCustodialAddressValidation = "[a-zA-Z0-9]{15,}"

internal class DynamicAssetLoader(
    private val nonCustodialAssets: Set<CryptoAsset>,
    private val assetCatalogue: AssetCatalogueImpl,
    private val payloadManager: PayloadDataManager,
    private val erc20DataManager: Erc20DataManager,
    private val feeDataManager: FeeDataManager,
    private val walletPreferences: WalletStatus,
    private val custodialManager: CustodialWalletManager,
    private val exchangeRates: ExchangeRatesDataManager,
    private val tradingBalances: TradingBalanceDataManager,
    private val interestBalances: InterestBalanceDataManager,
    private val currencyPrefs: CurrencyPrefs,
    private val labels: DefaultLabels,
    private val pitLinking: PitLinking,
    private val crashLogger: CrashLogger,
    private val identity: UserIdentity,
    private val features: InternalFeatureFlagApi
) : AssetLoader {

    private val assetMap = mutableMapOf<AssetInfo, CryptoAsset>()

    override operator fun get(asset: AssetInfo): CryptoAsset =
        assetMap[asset] ?: attemptLoadAsset(asset)

    private fun attemptLoadAsset(assetInfo: AssetInfo): CryptoAsset =
        when {
            assetInfo.isErc20() -> loadErc20Asset(assetInfo)
            assetInfo.isCustodialOnly -> loadCustodialOnlyAsset(assetInfo)
            else -> throw IllegalStateException("Unknown asset type enabled: ${assetInfo.networkTicker}")
        }.also {
            check(!assetMap.containsKey(assetInfo)) { "Asset already loaded" }
            assetMap[assetInfo] = it
        }

    override fun initAndPreload(): Completable =
        assetCatalogue.initialise(nonCustodialAssets.map { it.asset }.toSet())
            .doOnSubscribe { crashLogger.logEvent("Coincore init started") }
            .flatMap { supportedAssets ->
                // We need to make sure than any l1 assets - notably ETH - is initialised before
                // create any l2s. So that things like balance calls will work
                initNonCustodialAssets(nonCustodialAssets)
                    // Do not load the non-custodial assets here otherwise they become DynamicOnlyTradingAsset
                    // and the non-custodial accounts won't show up.
                    .thenSingle { doLoadAssets(supportedAssets.minus(nonCustodialAssets.map { it.asset })) }
            }
            .map { nonCustodialAssets + it }
            .doOnSuccess { assetList -> assetMap.putAll(assetList.associateBy { it.asset }) }
            .doOnError { Timber.e("init failed") }
            .ignoreElement()

    private fun initNonCustodialAssets(assetList: Set<CryptoAsset>): Completable =
        Completable.concat(
            assetList.filterIsInstance<NonCustodialSupport>()
                .map { asset ->
                    Completable.defer { asset.initToken() }
                        .doOnError {
                            crashLogger.logException(
                                CoincoreInitFailure("Failed init: ${(asset as CryptoAsset).asset.networkTicker}", it)
                        )
                    }
            }.toList()
        )

    private fun doLoadAssets(
        dynamicAssets: Set<AssetInfo>
    ): Single<List<CryptoAsset>> {
        val erc20assets = dynamicAssets.filter { it.isErc20() }
        val custodialAssets = dynamicAssets.filter { it.isCustodial && !erc20assets.contains(it) }

        // Those two sets should NOT overlap
        check(erc20assets.intersect(custodialAssets).isEmpty())

        return Single.zip(
            loadErc20Assets(erc20assets),
            loadCustodialOnlyAssets(custodialAssets)
        ) { erc20List, custodialList ->
            erc20List + custodialList
        }
    }

    private fun loadCustodialOnlyAssets(
        custodialAssets: Iterable<AssetInfo>
    ): Single<List<CryptoAsset>> =
        Single.zip(
            tradingBalances.getActiveAssets(),
            interestBalances.getActiveAssets()
        ) { activeTrading, activeInterest ->
            activeInterest + activeTrading
        }.map { activeAssets ->
            custodialAssets.filter { activeAssets.contains(it) }
        }.map { activeSupportedAssets ->
            activeSupportedAssets.map { asset ->
                loadCustodialOnlyAsset(asset)
            }
        }

    private fun loadErc20Assets(
        erc20Assets: Iterable<AssetInfo>
    ): Single<List<CryptoAsset>> =
        Single.zip(
            tradingBalances.getActiveAssets(),
            interestBalances.getActiveAssets(),
            erc20DataManager.getActiveAssets()
        ) { activeTrading, activeInterest, activeNoncustodial ->
            activeInterest + activeTrading + activeNoncustodial
        }.map { activeAssets ->
            erc20Assets.filter { activeAssets.contains(it) }
        }.map { activeSupportedAssets ->
            activeSupportedAssets.map { asset ->
                loadErc20Asset(asset)
            }
        }

    private fun loadCustodialOnlyAsset(assetInfo: AssetInfo): CryptoAsset {
        return DynamicOnlyTradingAsset(
            asset = assetInfo,
            payloadManager = payloadManager,
            custodialManager = custodialManager,
            tradingBalances = tradingBalances,
            interestBalances = interestBalances,
            exchangeRates = exchangeRates,
            currencyPrefs = currencyPrefs,
            labels = labels,
            pitLinking = pitLinking,
            crashLogger = crashLogger,
            identity = identity,
            features = features,
            addressValidation = defaultCustodialAddressValidation,
            availableActions = assetActions
        )
    }

    private fun loadErc20Asset(assetInfo: AssetInfo): CryptoAsset {
        require(assetInfo.isErc20())
        return Erc20Asset(
            asset = assetInfo,
            payloadManager = payloadManager,
            erc20DataManager = erc20DataManager,
            feeDataManager = feeDataManager,
            exchangeRates = exchangeRates,
            currencyPrefs = currencyPrefs,
            custodialManager = custodialManager,
            tradingBalances = tradingBalances,
            interestBalances = interestBalances,
            crashLogger = crashLogger,
            labels = labels,
            pitLinking = pitLinking,
            walletPreferences = walletPreferences,
            identity = identity,
            features = features,
            availableCustodialActions = assetActions,
            availableNonCustodialActions = assetActions
        )
    }

    override val loadedAssets: List<CryptoAsset>
        get() = assetMap.values.toList()

    companion object {
        private val assetActions =
            setOf(
                AssetAction.Buy,
                AssetAction.Sell,
                AssetAction.Swap,
                AssetAction.Send,
                AssetAction.Receive,
                AssetAction.ViewActivity,
                AssetAction.InterestDeposit
            )
    }
}
