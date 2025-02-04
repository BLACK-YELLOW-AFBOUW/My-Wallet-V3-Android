package piuk.blockchain.android.ui.dashboard.model

import com.blockchain.coincore.AccountBalance
import com.blockchain.coincore.AccountGroup
import com.blockchain.coincore.AssetAction
import com.blockchain.coincore.AssetFilter
import com.blockchain.coincore.Coincore
import com.blockchain.coincore.FiatAccount
import com.blockchain.coincore.SingleAccount
import com.blockchain.coincore.fiat.LinkedBanksFactory
import com.blockchain.core.price.ExchangeRatesDataManager
import com.blockchain.core.price.HistoricalRate
import com.blockchain.logging.CrashLogger
import com.blockchain.nabu.Feature
import com.blockchain.nabu.datamanagers.CustodialWalletManager
import com.blockchain.nabu.datamanagers.NabuUserIdentity
import com.blockchain.nabu.datamanagers.custodialwalletimpl.PaymentMethodType
import com.blockchain.nabu.models.data.LinkBankTransfer
import com.blockchain.notifications.analytics.Analytics
import com.blockchain.preferences.CurrencyPrefs
import com.blockchain.preferences.SimpleBuyPrefs
import com.blockchain.remoteconfig.FeatureFlag
import info.blockchain.balance.AssetInfo
import info.blockchain.balance.CryptoCurrency
import info.blockchain.balance.CryptoValue
import info.blockchain.balance.isErc20
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.kotlin.Singles
import io.reactivex.rxjava3.kotlin.plusAssign
import io.reactivex.rxjava3.kotlin.subscribeBy
import io.reactivex.rxjava3.kotlin.zipWith
import io.reactivex.rxjava3.schedulers.Schedulers
import piuk.blockchain.android.simplebuy.SimpleBuyAnalytics
import piuk.blockchain.android.ui.dashboard.navigation.DashboardNavigationAction
import piuk.blockchain.android.ui.settings.LinkablePaymentMethods
import piuk.blockchain.android.ui.transactionflow.TransactionFlow
import piuk.blockchain.androidcore.data.payload.PayloadDataManager
import piuk.blockchain.androidcore.utils.extensions.emptySubscribe
import timber.log.Timber
import java.util.concurrent.TimeUnit

class DashboardGroupLoadFailure(msg: String, e: Throwable) : Exception(msg, e)
class DashboardBalanceLoadFailure(msg: String, e: Throwable) : Exception(msg, e)

class DashboardActionAdapter(
    private val coincore: Coincore,
    private val payloadManager: PayloadDataManager,
    private val exchangeRates: ExchangeRatesDataManager,
    private val currencyPrefs: CurrencyPrefs,
    private val custodialWalletManager: CustodialWalletManager,
    private val linkedBanksFactory: LinkedBanksFactory,
    private val simpleBuyPrefs: SimpleBuyPrefs,
    private val userIdentity: NabuUserIdentity,
    private val analytics: Analytics,
    private val crashLogger: CrashLogger,
    private val featureFlag: FeatureFlag
) {
    fun fetchActiveAssets(model: DashboardModel): Disposable =
        Singles.zip(
            featureFlag.enabled.map { enabled ->
                if (enabled) {
                    coincore.activeCryptoAssets().map { it.asset }
                } else {
                    coincore.availableCryptoAssets()
                }
            },
            coincore.fiatAssets.accountGroup()
                .map { g -> g.accounts }
                .switchIfEmpty(Maybe.just(emptyList()))
                .toSingle()
        ).subscribeBy(
            onSuccess = { (cryptoAssets, fiatAssets) ->
                model.process(
                    DashboardIntent.UpdateAllAssetsAndBalances(
                        cryptoAssets,
                        fiatAssets.filterIsInstance<FiatAccount>()
                    )
                )
            },
            onError = {
                Timber.e("Error getting ordering - $it")
            }
        )

    fun fetchAvailableAssets(model: DashboardModel): Disposable =
        Single.fromCallable {
            coincore.availableCryptoAssets()
        }.subscribeBy(
            onSuccess = { assets ->
                model.process(DashboardIntent.AssetListUpdate(assets))
            },
            onError = {
                Timber.e("Error getting ordering - $it")
            }
        )

    fun fetchAssetPrice(model: DashboardModel, asset: AssetInfo): Disposable =
        exchangeRates.getPricesWith24hDelta(asset)
            .subscribeBy(
                onNext = {
                    model.process(
                        DashboardIntent.AssetPriceUpdate(
                            asset = asset,
                            prices24HrWithDelta = it
                        )
                    )
                }
            )

    // We have a problem here, in that pax init depends on ETH init
    // Ultimately, we want to init metadata straight after decrypting (or creating) the wallet
    // but we can't move that somewhere sensible yet, because 2nd password. When we remove that -
    // which is on the radar - then we can clean up the entire app init sequence.
    // But for now, we'll catch any pax init failure here, unless ETH has initialised OK. And when we
    // get a valid ETH balance, will try for a PX balance. Yeah, this is a nasty hack TODO: Fix this
    fun refreshBalances(
        model: DashboardModel,
        balanceFilter: AssetFilter,
        state: DashboardState
    ): Disposable {
        val cd = CompositeDisposable()

        state.assetMapKeys
            .filter { !it.isErc20() }
            .forEach { asset ->
                cd += refreshAssetBalance(asset, model, balanceFilter)
                    .ifEthLoadedGetErc20Balance(model, balanceFilter, cd, state)
                    .ifEthFailedThenErc20Failed(asset, model, state)
                    .emptySubscribe()
            }

        state.fiatAssets.fiatAccounts
            .values.forEach {
                cd += refreshFiatAssetBalance(it.account, model)
            }

        return cd
    }

    private fun Maybe<AccountGroup>.logGroupLoadError(asset: AssetInfo, filter: AssetFilter) =
        this.doOnError { e ->
            crashLogger.logException(
                DashboardGroupLoadFailure("Cannot load group for ${asset.displayTicker} - $filter:", e)
            )
        }

    private fun Observable<AccountBalance>.logBalanceLoadError(asset: AssetInfo, filter: AssetFilter) =
        this.doOnError { e ->
            crashLogger.logException(
                DashboardBalanceLoadFailure("Cannot load balance for ${asset.displayTicker} - $filter:", e)
            )
        }

    private fun refreshAssetBalance(
        asset: AssetInfo,
        model: DashboardModel,
        balanceFilter: AssetFilter
    ): Single<CryptoValue> =
        coincore[asset].accountGroup(balanceFilter)
            .logGroupLoadError(asset, balanceFilter)
            .flatMapObservable { group ->
                group.balance
                    .logBalanceLoadError(asset, balanceFilter)
            }
            .doOnError { e ->
                Timber.e("Failed getting balance for ${asset.displayTicker}: $e")
                model.process(DashboardIntent.BalanceUpdateError(asset))
            }
            .doOnNext { accountBalance ->
                Timber.d("Got balance for ${asset.displayTicker}")
                model.process(DashboardIntent.BalanceUpdate(asset, accountBalance))
            }
            .retryOnError()
            .firstOrError()
            .map {
                it.total as CryptoValue
            }

    private fun <T> Observable<T>.retryOnError() =
        this.retryWhen { f ->
            f.take(RETRY_COUNT)
                .delay(RETRY_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }

    private fun Single<CryptoValue>.ifEthLoadedGetErc20Balance(
        model: DashboardModel,
        balanceFilter: AssetFilter,
        disposables: CompositeDisposable,
        state: DashboardState
    ) = this.doOnSuccess { value ->
        if (value.currency == CryptoCurrency.ETHER) {
            state.erc20Assets.forEach {
                disposables += refreshAssetBalance(it, model, balanceFilter)
                    .emptySubscribe()
            }
        }
    }

    private fun Single<CryptoValue>.ifEthFailedThenErc20Failed(
        asset: AssetInfo,
        model: DashboardModel,
        state: DashboardState
    ) = this.doOnError {
        if (asset.networkTicker == CryptoCurrency.ETHER.networkTicker) {
            state.erc20Assets.forEach {
                model.process(DashboardIntent.BalanceUpdateError(it))
            }
        }
    }

    private fun refreshFiatAssetBalance(
        account: FiatAccount,
        model: DashboardModel
    ): Disposable =
        account.balance
            .firstOrError() // Ideally we shouldn't need this, but we need to kill existing subs on refresh first TODO
            .subscribeBy(
                onSuccess = { balances ->
                    model.process(
                        DashboardIntent.FiatBalanceUpdate(balances.total, balances.totalFiat)
                    )
                },
                onError = {
                    Timber.e("Error while loading fiat balances $it")
                }
            )

    fun refreshPrices(model: DashboardModel, crypto: AssetInfo): Disposable =
        coincore[crypto].getPricesWith24hDelta()
            .map { pricesWithDelta -> DashboardIntent.AssetPriceUpdate(crypto, pricesWithDelta) }
            .subscribeBy(
                onSuccess = { model.process(it) },
                onError = { Timber.e(it) }
            )

    fun refreshPriceHistory(model: DashboardModel, asset: AssetInfo): Disposable =
        if (asset.startDate != null) {
            coincore[asset].lastDayTrend()
        } else {
            Single.just(FLATLINE_CHART)
        }.map { DashboardIntent.PriceHistoryUpdate(asset, it) }
            .subscribeBy(
                onSuccess = { model.process(it) },
                onError = { Timber.e(it) }
            )

    fun checkForCustodialBalance(model: DashboardModel, crypto: AssetInfo): Disposable {
        return coincore[crypto].accountGroup(AssetFilter.Custodial)
            .flatMapObservable { it.balance }
            .subscribeBy(
                onNext = {
                    model.process(DashboardIntent.UpdateHasCustodialBalanceIntent(crypto, !it.total.isZero))
                },
                onError = { model.process(DashboardIntent.UpdateHasCustodialBalanceIntent(crypto, false)) }
            )
    }

    fun hasUserBackedUp(): Single<Boolean> = Single.just(payloadManager.isBackedUp)

    fun cancelSimpleBuyOrder(orderId: String): Disposable {
        return custodialWalletManager.deleteBuyOrder(orderId)
            .subscribeBy(
                onComplete = { simpleBuyPrefs.clearBuyState() },
                onError = { error ->
                    analytics.logEvent(SimpleBuyAnalytics.BANK_DETAILS_CANCEL_ERROR)
                    Timber.e(error)
                }
            )
    }

    fun launchBankTransferFlow(model: DashboardModel, currency: String = "", action: AssetAction) =
        userIdentity.isEligibleFor(Feature.SimpleBuy)
            .zipWith(coincore.fiatAssets.accountGroup().toSingle())
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeBy(
                onSuccess = { (isEligible, fiatGroup) ->
                    model.process(
                        if (isEligible) {
                            val selectedFiatCurrency = if (currency.isNotEmpty()) {
                                currency
                            } else {
                                currencyPrefs.selectedFiatCurrency
                            }
                            val selectedAccount = fiatGroup.accounts.first {
                                (it as FiatAccount).fiatCurrency == selectedFiatCurrency
                            }

                            DashboardIntent.LaunchBankTransferFlow(
                                selectedAccount,
                                action,
                                false
                            )
                        } else {
                            DashboardIntent.ShowPortfolioSheet(DashboardNavigationAction.FiatFundsNoKyc)
                        }
                    )
                },
                onError = {
                    Timber.e(it)
                    model.process(DashboardIntent.ShowPortfolioSheet(DashboardNavigationAction.FiatFundsNoKyc))
                }
            )

    fun getBankDepositFlow(
        model: DashboardModel,
        targetAccount: SingleAccount,
        action: AssetAction,
        shouldLaunchBankLinkTransfer: Boolean
    ): Disposable {
        require(targetAccount is FiatAccount)
        return handleFiatDeposit(targetAccount, shouldLaunchBankLinkTransfer, model, action)
    }

    private fun handleFiatDeposit(
        targetAccount: FiatAccount,
        shouldLaunchBankLinkTransfer: Boolean,
        model: DashboardModel,
        action: AssetAction
    ) = Singles.zip(
        linkedBanksFactory.eligibleBankPaymentMethods(targetAccount.fiatCurrency).map { paymentMethods ->
            // Ignore any WireTransferMethods In case BankLinkTransfer should launch
            paymentMethods.filter { it == PaymentMethodType.BANK_TRANSFER || !shouldLaunchBankLinkTransfer }
        },
        linkedBanksFactory.getNonWireTransferBanks().map {
            it.filter { bank -> bank.currency == targetAccount.fiatCurrency }
        }
    ).doOnSubscribe {
        model.process(DashboardIntent.LongCallStarted)
    }.flatMap { (paymentMethods, linkedBanks) ->
        when {
            linkedBanks.isEmpty() -> {
                handleNoLinkedBanks(
                    targetAccount,
                    action,
                    LinkablePaymentMethodsForAction.LinkablePaymentMethodsForDeposit(
                        linkablePaymentMethods = LinkablePaymentMethods(
                            targetAccount.fiatCurrency,
                            paymentMethods
                        )
                    )
                )
            }
            linkedBanks.size == 1 -> {
                Single.just(
                    FiatTransactionRequestResult.LaunchDepositFlow(
                        preselectedBankAccount = linkedBanks.first(),
                        action = action,
                        targetAccount = targetAccount
                    )
                )
            }
            else -> {
                Single.just(
                    FiatTransactionRequestResult.LaunchDepositFlowWithMultipleAccounts(
                        action = action,
                        targetAccount = targetAccount
                    )
                )
            }
        }
    }.doOnTerminate {
        model.process(DashboardIntent.LongCallEnded)
    }.subscribeBy(
        onSuccess = {
            handlePaymentMethodsUpdate(it, model, targetAccount, action)
        },
        onError = {
            Timber.e("Error loading bank transfer info $it")
        }
    )

    private fun handlePaymentMethodsUpdate(
        fiatTxRequestResult: FiatTransactionRequestResult?,
        model: DashboardModel,
        fiatAccount: FiatAccount,
        action: AssetAction
    ) {
        when (fiatTxRequestResult) {
            is FiatTransactionRequestResult.LaunchDepositFlowWithMultipleAccounts -> {
                model.process(
                    DashboardIntent.UpdateLaunchDialogFlow(
                        TransactionFlow(
                            target = fiatAccount,
                            action = action
                        )
                    )
                )
            }
            is FiatTransactionRequestResult.LaunchDepositFlow -> {
                model.process(
                    DashboardIntent.UpdateLaunchDialogFlow(
                        TransactionFlow(
                            target = fiatAccount,
                            sourceAccount = fiatTxRequestResult.preselectedBankAccount,
                            action = action
                        )
                    )
                )
            }
            is FiatTransactionRequestResult.LaunchWithdrawalFlowWithMultipleAccounts -> {
                model.process(
                    DashboardIntent.UpdateLaunchDialogFlow(
                        TransactionFlow(
                            sourceAccount = fiatAccount,
                            action = action
                        )
                    )
                )
            }
            is FiatTransactionRequestResult.LaunchWithdrawalFlow -> {
                model.process(
                    DashboardIntent.UpdateLaunchDialogFlow(
                        TransactionFlow(
                            sourceAccount = fiatAccount,
                            target = fiatTxRequestResult.preselectedBankAccount,
                            action = action
                        )
                    )
                )
            }
            is FiatTransactionRequestResult.LaunchBankLink -> {
                model.process(
                    DashboardIntent.LaunchBankLinkFlow(
                        fiatTxRequestResult.linkBankTransfer,
                        fiatAccount,
                        action
                    )
                )
            }
            is FiatTransactionRequestResult.NotSupportedPartner -> {
                // TODO Show an error
            }
            is FiatTransactionRequestResult.LaunchPaymentMethodChooser -> {
                model.process(
                    DashboardIntent.ShowLinkablePaymentMethodsSheet(
                        fiatAccount = fiatAccount,
                        paymentMethodsForAction = fiatTxRequestResult.paymentMethodForAction
                    )
                )
            }
            is FiatTransactionRequestResult.LaunchDepositDetailsSheet -> {
                model.process(DashboardIntent.ShowBankLinkingSheet(fiatTxRequestResult.targetAccount))
            }
        }
    }

    private fun handleNoLinkedBanks(
        targetAccount: FiatAccount,
        action: AssetAction,
        paymentMethodForAction: LinkablePaymentMethodsForAction
    ) =
        when {
            paymentMethodForAction.linkablePaymentMethods.linkMethods.containsAll(
                listOf(PaymentMethodType.BANK_TRANSFER, PaymentMethodType.BANK_ACCOUNT)
            ) -> {
                Single.just(
                    FiatTransactionRequestResult.LaunchPaymentMethodChooser(
                        paymentMethodForAction
                    )
                )
            }
            paymentMethodForAction.linkablePaymentMethods.linkMethods.contains(PaymentMethodType.BANK_TRANSFER) -> {
                linkBankTransfer(targetAccount.fiatCurrency).map {
                    FiatTransactionRequestResult.LaunchBankLink(
                        linkBankTransfer = it,
                        action = action
                    ) as FiatTransactionRequestResult
                }.onErrorReturn {
                    FiatTransactionRequestResult.NotSupportedPartner
                }
            }
            paymentMethodForAction.linkablePaymentMethods.linkMethods.contains(PaymentMethodType.BANK_ACCOUNT) -> {
                Single.just(FiatTransactionRequestResult.LaunchDepositDetailsSheet(targetAccount))
            }
            else -> {
                Single.just(FiatTransactionRequestResult.NotSupportedPartner)
            }
        }

    fun linkBankTransfer(currency: String): Single<LinkBankTransfer> =
        custodialWalletManager.linkToABank(currency)

    fun getBankWithdrawalFlow(
        model: DashboardModel,
        sourceAccount: SingleAccount,
        action: AssetAction,
        shouldLaunchBankLinkTransfer: Boolean
    ): Disposable {
        require(sourceAccount is FiatAccount)

        return Singles.zip(
            linkedBanksFactory.eligibleBankPaymentMethods(sourceAccount.fiatCurrency).map { paymentMethods ->
                // Ignore any WireTransferMethods In case BankLinkTransfer should launch
                paymentMethods.filter { it == PaymentMethodType.BANK_TRANSFER || !shouldLaunchBankLinkTransfer }
            },
            linkedBanksFactory.getAllLinkedBanks().map {
                it.filter { bank -> bank.currency == sourceAccount.fiatCurrency }
            }
        ).flatMap { (paymentMethods, linkedBanks) ->
            when {
                linkedBanks.isEmpty() -> {
                    handleNoLinkedBanks(
                        sourceAccount,
                        action,
                        LinkablePaymentMethodsForAction.LinkablePaymentMethodsForWithdraw(
                            LinkablePaymentMethods(
                                sourceAccount.fiatCurrency,
                                paymentMethods
                            )
                        )
                    )
                }
                linkedBanks.size == 1 -> {
                    Single.just(
                        FiatTransactionRequestResult.LaunchWithdrawalFlow(
                            preselectedBankAccount = linkedBanks.first(),
                            action = action,
                            sourceAccount = sourceAccount
                        )
                    )
                }
                else -> {
                    Single.just(
                        FiatTransactionRequestResult.LaunchWithdrawalFlowWithMultipleAccounts(
                            action = action,
                            sourceAccount = sourceAccount
                        )
                    )
                }
            }
        }.subscribeBy(
            onSuccess = {
                handlePaymentMethodsUpdate(it, model, sourceAccount, action)
            },
            onError = {
                // TODO Add error state to Dashboard
            }
        )
    }

    companion object {
        private val FLATLINE_CHART = listOf(
            HistoricalRate(rate = 1.0, timestamp = 0),
            HistoricalRate(rate = 1.0, timestamp = System.currentTimeMillis() / 1000)
        )

        private const val RETRY_INTERVAL_MS = 3000L
        private const val RETRY_COUNT = 3L
    }
}