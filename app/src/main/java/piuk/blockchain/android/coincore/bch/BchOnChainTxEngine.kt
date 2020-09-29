package piuk.blockchain.android.coincore.bch

import info.blockchain.api.data.UnspentOutputs
import info.blockchain.balance.CryptoCurrency
import info.blockchain.balance.CryptoValue
import info.blockchain.balance.Money
import info.blockchain.wallet.exceptions.HDWalletException
import info.blockchain.wallet.payment.Payment
import info.blockchain.wallet.payment.SpendableUnspentOutputs
import info.blockchain.wallet.util.FormatsUtil
import io.reactivex.Completable
import io.reactivex.Single
import io.reactivex.rxkotlin.Singles
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.NetworkParameters
import piuk.blockchain.android.coincore.CryptoAddress
import piuk.blockchain.android.coincore.FeeLevel
import piuk.blockchain.android.coincore.PendingTx
import piuk.blockchain.android.coincore.TxOptionValue
import piuk.blockchain.android.coincore.TxValidationFailure
import piuk.blockchain.android.coincore.ValidationState
import piuk.blockchain.android.coincore.impl.txEngine.OnChainTxEngineBase
import piuk.blockchain.android.coincore.updateTxValidity
import piuk.blockchain.androidcore.data.bitcoincash.BchDataManager
import piuk.blockchain.androidcore.data.fees.FeeDataManager
import piuk.blockchain.androidcore.data.payload.PayloadDataManager
import piuk.blockchain.androidcore.data.payments.SendDataManager
import piuk.blockchain.androidcore.utils.extensions.then
import piuk.blockchain.androidcore.utils.helperfunctions.unsafeLazy
import timber.log.Timber
import java.math.BigInteger

private const val STATE_UTXO = "bch_utxo"

private val PendingTx.unspentOutputBundle: SpendableUnspentOutputs
    get() = (this.engineState[STATE_UTXO] as SpendableUnspentOutputs)

class BchOnChainTxEngine(
    private val feeDataManager: FeeDataManager,
    private val networkParams: NetworkParameters,
    private val sendDataManager: SendDataManager,
    private val bchDataManager: BchDataManager,
    private val payloadDataManager: PayloadDataManager,
    requireSecondPassword: Boolean
) : OnChainTxEngineBase(
    requireSecondPassword
) {

    private val bchSource: BchCryptoWalletAccount by unsafeLazy {
        sourceAccount as BchCryptoWalletAccount
    }

    private val bchTarget: CryptoAddress by unsafeLazy {
        txTarget as CryptoAddress
    }

    override fun assertInputsValid() {
        require(txTarget is CryptoAddress)
        require((txTarget as CryptoAddress).asset == CryptoCurrency.BCH)
        require(asset == CryptoCurrency.BCH)
    }

    override val feeOptions: Set<FeeLevel>
        get() = setOf(FeeLevel.Regular)

    override fun doInitialiseTx(): Single<PendingTx> =
        Single.just(
            PendingTx(
                amount = CryptoValue.ZeroBch,
                available = CryptoValue.ZeroBch,
                fees = CryptoValue.ZeroBch,
                feeLevel = FeeLevel.Regular,
                selectedFiat = userFiat
            )
        )

    override fun doUpdateAmount(amount: Money, pendingTx: PendingTx): Single<PendingTx> {
        require(amount is CryptoValue)
        require(amount.currency == CryptoCurrency.BCH)

        return Singles.zip(
            getUnspentApiResponse(bchSource.internalAccount.xpub),
            getDynamicFeePerKb(pendingTx)
        ) { coins, feePerKb ->
            updatePendingTx(amount, pendingTx, feePerKb, coins)
        }
    }

    private fun getUnspentApiResponse(address: String): Single<UnspentOutputs> =
        if (bchDataManager.getAddressBalance(address) > CryptoValue.ZeroBch.toBigInteger()) {
            sendDataManager.getUnspentBchOutputs(address).singleOrError()
        } else {
            Single.error(Throwable("No BCH funds"))
        }

    private fun updatePendingTx(
        amount: CryptoValue,
        pendingTx: PendingTx,
        feePerKb: CryptoValue,
        coins: UnspentOutputs
    ): PendingTx {
        val sweepBundle = sendDataManager.getMaximumAvailable(
            cryptoCurrency = CryptoCurrency.BCH,
            unspentCoins = coins,
            feePerKb = feePerKb.toBigInteger(),
            useNewCoinSelection = true
        )

        val maxAvailable = sweepBundle.left

        val unspentOutputs = sendDataManager.getSpendableCoins(
            unspentCoins = coins,
            paymentAmount = amount,
            feePerKb = feePerKb.toBigInteger(),
            useNewCoinSelection = true
        )

        return pendingTx.copy(
            amount = amount,
            available = CryptoValue.fromMinor(CryptoCurrency.BCH, maxAvailable),
            fees = CryptoValue.fromMinor(CryptoCurrency.BCH, unspentOutputs.absoluteFee),
            engineState = mapOf(
                STATE_UTXO to unspentOutputs
            )
        )
    }

    private fun getDynamicFeePerKb(pendingTx: PendingTx): Single<CryptoValue> =
        feeDataManager.bchFeeOptions
            .map { feeOptions ->
                when (pendingTx.feeLevel) {
                    FeeLevel.Regular -> feeToCrypto(feeOptions.regularFee)
                    FeeLevel.None -> CryptoValue.ZeroBch
                    FeeLevel.Priority -> feeToCrypto(feeOptions.priorityFee)
                    FeeLevel.Custom -> TODO() // feeToCrypto(view!!.getCustomFeeValue())
                }
            }.singleOrError()

    private fun feeToCrypto(feePerKb: Long): CryptoValue =
        CryptoValue.fromMinor(CryptoCurrency.BCH, (feePerKb * 1000).toBigInteger())

    override fun doValidateAmount(pendingTx: PendingTx): Single<PendingTx> =
        validateAmounts(pendingTx)
            .then { validateSufficientFunds(pendingTx) }
            .updateTxValidity(pendingTx)

    private fun validateAmounts(pendingTx: PendingTx): Completable =
        Completable.fromCallable {
            val amount = pendingTx.amount.toBigInteger()
            if (amount < Payment.DUST || amount > 2_100_000_000_000_000L.toBigInteger() || amount <= BigInteger.ZERO) {
                throw TxValidationFailure(ValidationState.INVALID_AMOUNT)
            }
        }

    private fun validateSufficientFunds(pendingTx: PendingTx): Completable =
        Completable.fromCallable {
            if (pendingTx.available < pendingTx.amount || pendingTx.unspentOutputBundle.spendableOutputs.isEmpty()) {
                throw TxValidationFailure(ValidationState.INSUFFICIENT_FUNDS)
            }
        }

    override fun doBuildConfirmations(pendingTx: PendingTx): Single<PendingTx> =
        Single.just(
            pendingTx.copy(
                options = listOf(
                    TxOptionValue.From(from = sourceAccount.label),
                    TxOptionValue.To(to = txTarget.label),
                    TxOptionValue.Fee(fee = pendingTx.fees,
                        exchange = pendingTx.fees.toFiat(exchangeRates, userFiat)),
                    TxOptionValue.FeedTotal(
                        amount = pendingTx.amount,
                        fee = pendingTx.fees,
                        exchangeFee = pendingTx.fees.toFiat(exchangeRates, userFiat),
                        exchangeAmount = pendingTx.amount.toFiat(exchangeRates, userFiat)
                    )
                )
            )
        )

    override fun doValidateAll(pendingTx: PendingTx): Single<PendingTx> =
        validateAddress()
            .then { validateAmounts(pendingTx) }
            .then { validateSufficientFunds(pendingTx) }
            .updateTxValidity(pendingTx)

    private fun validateAddress() =
        Completable.fromCallable {
            if (!FormatsUtil.isValidBCHAddress(networkParams, bchTarget.address) &&
                !FormatsUtil.isValidBitcoinAddress(networkParams, bchTarget.address)) {
                throw TxValidationFailure(ValidationState.INVALID_ADDRESS)
            }
        }

    override fun doExecute(pendingTx: PendingTx, secondPassword: String): Completable =
        Singles.zip(
            getBchChangeAddress(),
            getBchKeys(pendingTx, secondPassword)
        ).flatMap { (changeAddress, keys) ->
            sendDataManager.submitBchPayment(
                pendingTx.unspentOutputBundle,
                keys,
                getFullBitcoinCashAddressFormat(bchTarget.address),
                changeAddress,
                pendingTx.fees.toBigInteger(),
                pendingTx.amount.toBigInteger()
            ).singleOrError()
        }.doOnSuccess {
            // logPaymentSentEvent(true, CryptoCurrency.BCH, pendingTransaction.bigIntAmount)
            incrementBchReceiveAddress(pendingTx)
        }.doOnError { e ->
            Timber.e("BCH Send failed: $e")
            // logPaymentSentEvent(false, BCH.BTC, pendingTransaction.bigIntAmount)
        }.ignoreElement()

    private fun getFullBitcoinCashAddressFormat(cashAddress: String): String {
        return if (!cashAddress.startsWith(networkParams.bech32AddressPrefix) &&
            FormatsUtil.isValidBCHAddress(networkParams, cashAddress)
        ) {
            networkParams.bech32AddressPrefix + networkParams.bech32AddressSeparator.toChar() + cashAddress
        } else {
            cashAddress
        }
    }

    private fun getBchChangeAddress(): Single<String> {
        val position =
            bchDataManager.getAccountMetadataList().indexOfFirst { it.xpub == bchSource.internalAccount.xpub }
        return bchDataManager.getNextChangeCashAddress(position).singleOrError()
    }

    private fun getBchKeys(pendingTx: PendingTx, secondPassword: String): Single<List<ECKey>> {
        if (payloadDataManager.isDoubleEncrypted) {
            payloadDataManager.decryptHDWallet(secondPassword)
            bchDataManager.decryptWatchOnlyWallet(payloadDataManager.mnemonic)
        }

        val hdAccountList = bchDataManager.getAccountList()
        val acc = hdAccountList.find {
            it.node.serializePubB58(networkParams) == bchSource.internalAccount.xpub
        } ?: throw HDWalletException("No matching private key found for ${bchSource.internalAccount.xpub}")

        return Single.just(
            bchDataManager.getHDKeysForSigning(
                acc,
                pendingTx.unspentOutputBundle.spendableOutputs
            )
        )
    }

    private fun incrementBchReceiveAddress(pendingTx: PendingTx) {
        val xpub = bchSource.internalAccount.xpub
        bchDataManager.incrementNextChangeAddress(xpub)
        bchDataManager.incrementNextReceiveAddress(xpub)
        updateInternalBchBalances(pendingTx, xpub)
    }

    private fun updateInternalBchBalances(pendingTx: PendingTx, xpub: String) {
        try {
            bchDataManager.subtractAmountFromAddressBalance(xpub, pendingTx.totalSent.toBigInteger())
        } catch (e: Exception) {
            Timber.e(e)
        }
    }
}

private val PendingTx.totalSent: Money
    get() = amount + fees