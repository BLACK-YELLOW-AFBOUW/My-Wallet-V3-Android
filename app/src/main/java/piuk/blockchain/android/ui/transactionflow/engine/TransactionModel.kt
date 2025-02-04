package piuk.blockchain.android.ui.transactionflow.engine

import com.blockchain.banking.BankPaymentApproval
import com.blockchain.coincore.AssetAction
import com.blockchain.coincore.BlockchainAccount
import com.blockchain.coincore.CryptoAccount
import com.blockchain.coincore.CryptoAddress
import com.blockchain.coincore.FiatAccount
import com.blockchain.coincore.NeedsApprovalException
import com.blockchain.coincore.NullAddress
import com.blockchain.coincore.NullCryptoAccount
import com.blockchain.coincore.PendingTx
import com.blockchain.coincore.TransactionTarget
import com.blockchain.coincore.TxConfirmationValue
import com.blockchain.coincore.TxValidationFailure
import com.blockchain.coincore.ValidationState
import com.blockchain.coincore.fiat.LinkedBankAccount
import com.blockchain.core.price.ExchangeRate
import com.blockchain.logging.CrashLogger
import com.blockchain.nabu.models.data.LinkBankTransfer
import info.blockchain.balance.AssetInfo
import info.blockchain.balance.CryptoValue
import info.blockchain.balance.FiatValue
import info.blockchain.balance.Money
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.kotlin.subscribeBy
import piuk.blockchain.android.ui.base.mvi.MviModel
import piuk.blockchain.android.ui.base.mvi.MviState
import piuk.blockchain.android.ui.customviews.CurrencyType
import piuk.blockchain.androidcore.data.api.EnvironmentConfig
import timber.log.Timber
import java.util.Stack

enum class TransactionStep(val addToBackStack: Boolean = false) {
    ZERO,
    ENTER_PASSWORD,
    SELECT_SOURCE(true),
    ENTER_ADDRESS(true),
    SELECT_TARGET_ACCOUNT(true),
    ENTER_AMOUNT(true),
    CONFIRM_DETAIL,
    IN_PROGRESS,
    CLOSED
}

enum class TransactionErrorState {
    NONE,
    INVALID_PASSWORD,
    INVALID_ADDRESS,
    ADDRESS_IS_CONTRACT,
    INSUFFICIENT_FUNDS,
    INVALID_AMOUNT,
    BELOW_MIN_LIMIT,
    PENDING_ORDERS_LIMIT_REACHED,
    OVER_SILVER_TIER_LIMIT,
    OVER_GOLD_TIER_LIMIT,
    NOT_ENOUGH_GAS,
    UNEXPECTED_ERROR,
    TRANSACTION_IN_FLIGHT,
    TX_OPTION_INVALID,
    UNKNOWN_ERROR
}

sealed class BankLinkingState {
    object NotStarted : BankLinkingState()
    class Success(val bankTransferInfo: LinkBankTransfer) : BankLinkingState()
    class Error(val e: Throwable) : BankLinkingState()
}

sealed class TxExecutionStatus {
    object NotStarted : TxExecutionStatus()
    object InProgress : TxExecutionStatus()
    object Completed : TxExecutionStatus()
    class ApprovalRequired(val approvalData: BankPaymentApproval) : TxExecutionStatus()
    data class Error(val exception: Throwable) : TxExecutionStatus()
}

fun BlockchainAccount.getZeroAmountForAccount() =
    when (this) {
        is CryptoAccount -> CryptoValue.zero(this.asset)
        is LinkedBankAccount -> FiatValue.zero(this.currency)
        is FiatAccount -> FiatValue.zero(this.fiatCurrency)
        else -> throw IllegalStateException("Account is not a crypto, bank or fiat account")
    }

data class TransactionState(
    override val action: AssetAction = AssetAction.Send,
    val currentStep: TransactionStep = TransactionStep.ZERO,
    val sendingAccount: BlockchainAccount = NullCryptoAccount(),
    val selectedTarget: TransactionTarget = NullAddress,
    val fiatRate: ExchangeRate? = null,
    val targetRate: ExchangeRate? = null,
    val passwordRequired: Boolean = false,
    val secondPassword: String = "",
    val nextEnabled: Boolean = false,
    val setMax: Boolean = false,
    override val errorState: TransactionErrorState = TransactionErrorState.NONE,
    val pendingTx: PendingTx? = null,
    val allowFiatInput: Boolean = false,
    val executionStatus: TxExecutionStatus = TxExecutionStatus.NotStarted,
    val stepsBackStack: Stack<TransactionStep> = Stack(),
    val availableTargets: List<TransactionTarget> = emptyList(),
    val currencyType: CurrencyType? = null,
    val availableSources: List<BlockchainAccount> = emptyList(),
    val linkBankState: BankLinkingState = BankLinkingState.NotStarted
) : MviState, TransactionFlowStateInfo {

    // workaround for using engine without cryptocurrency source
    override val sendingAsset: AssetInfo
        get() = (sendingAccount as? CryptoAccount)?.asset ?: throw IllegalStateException(
            "Trying to use cryptocurrency with non-crypto source"
        )
    override val minLimit: Money?
        get() = pendingTx?.minLimit

    override val maxLimit: Money?
        get() = pendingTx?.maxLimit

    override val amount: Money
        get() = pendingTx?.amount ?: sendingAccount.getZeroAmountForAccount()

    override val availableBalance: Money
        get() = pendingTx?.availableBalance ?: sendingAccount.getZeroAmountForAccount()

    val canGoBack: Boolean
        get() = stepsBackStack.isNotEmpty()

    val targetCount: Int
        get() = availableTargets.size

    val maxSpendable: Money
        get() {
            return pendingTx?.let {
                val available = availableToAmountCurrency(it.availableBalance, amount)
                Money.min(
                    available,
                    it.maxLimit ?: available
                )
            } ?: sendingAccount.getZeroAmountForAccount()
        }

    private fun availableToAmountCurrency(available: Money, amount: Money): Money =
        when (amount) {
            is FiatValue -> fiatRate?.convert(available) ?: FiatValue.zero(amount.currencyCode)
            is CryptoValue -> available
            else -> throw IllegalStateException("Unknown money type")
        }

    // If the current amount is the amount that was specified in a CryptoAddress target, then return that amount.
    // This is a hack to allow the enter amount screen to uypdate to the initial amount, if one is specified,
    // without getting stuck in an update loop
    fun initialAmountToSet(): CryptoValue? {
        return if (selectedTarget is CryptoAddress) {
            val amount = selectedTarget.amount

            if (amount != null && amount.isPositive && amount == pendingTx?.amount) {
                selectedTarget.amount
            } else {
                null
            }
        } else {
            null
        }
    }
}

class TransactionModel(
    initialState: TransactionState,
    mainScheduler: Scheduler,
    private val interactor: TransactionInteractor,
    private val errorLogger: TxFlowErrorReporting,
    environmentConfig: EnvironmentConfig,
    crashLogger: CrashLogger
) : MviModel<TransactionState, TransactionIntent>(
    initialState,
    mainScheduler,
    environmentConfig,
    crashLogger
) {
    override fun performAction(previousState: TransactionState, intent: TransactionIntent): Disposable? {
        Timber.v("!TRANSACTION!> Transaction Model: performAction: %s", intent.javaClass.simpleName)

        return when (intent) {
            is TransactionIntent.InitialiseWithSourceAccount -> processAccountsListUpdate(
                previousState,
                intent.fromAccount,
                intent.action
            )
            is TransactionIntent.InitialiseWithNoSourceOrTargetAccount -> processSourceAccountsListUpdate(
                intent.action, NullAddress
            )
            is TransactionIntent.InitialiseWithTargetAndNoSource -> processSourceAccountsListUpdate(
                intent.action, intent.target
            )
            is TransactionIntent.ReInitialiseWithTargetAndNoSource -> processSourceAccountsListUpdate(
                intent.action, intent.target, true
            )
            is TransactionIntent.ValidatePassword -> processPasswordValidation(intent.password)
            is TransactionIntent.UpdatePasswordIsValidated -> null
            is TransactionIntent.UpdatePasswordNotValidated -> null
            is TransactionIntent.PrepareTransaction -> null
            is TransactionIntent.AvailableSourceAccountsListUpdated -> null
            is TransactionIntent.SourceAccountSelected -> processAccountsListUpdate(
                previousState,
                intent.sourceAccount,
                previousState.action
            )
            is TransactionIntent.ExecuteTransaction -> processExecuteTransaction(previousState.secondPassword)
            is TransactionIntent.ValidateInputTargetAddress ->
                processValidateAddress(intent.targetAddress, intent.expectedCrypto)
            is TransactionIntent.TargetAddressValidated -> null
            is TransactionIntent.TargetAddressInvalid -> null
            is TransactionIntent.InitialiseWithSourceAndTargetAccount -> {
                processTargetSelectionConfirmed(
                    sourceAccount = intent.fromAccount,
                    amount = getInitialAmountFromTarget(intent),
                    transactionTarget = intent.target,
                    action = intent.action
                )
            }
            is TransactionIntent.TargetSelected ->
                processTargetSelectionConfirmed(
                    sourceAccount = previousState.sendingAccount,
                    amount = previousState.amount,
                    transactionTarget = previousState.selectedTarget,
                    action = previousState.action
                )
            is TransactionIntent.TargetSelectionUpdated -> null
            is TransactionIntent.InitialiseWithSourceAndPreferredTarget ->
                processAccountsListUpdate(previousState, intent.fromAccount, intent.action)
            is TransactionIntent.PendingTransactionStarted -> null
            is TransactionIntent.TargetAccountSelected -> null
            is TransactionIntent.FatalTransactionError -> null
            is TransactionIntent.AmountChanged -> processAmountChanged(intent.amount)
            is TransactionIntent.ModifyTxOption -> processModifyTxOptionRequest(intent.confirmation)
            is TransactionIntent.PendingTxUpdated -> null
            is TransactionIntent.DisplayModeChanged -> null
            is TransactionIntent.UpdateTransactionComplete -> null
            is TransactionIntent.ReturnToPreviousStep -> null
            is TransactionIntent.ShowTargetSelection -> null
            is TransactionIntent.FetchFiatRates -> processGetFiatRate()
            is TransactionIntent.FetchTargetRates -> processGetTargetRate()
            is TransactionIntent.FiatRateUpdated -> null
            is TransactionIntent.CryptoRateUpdated -> null
            is TransactionIntent.ValidateTransaction -> processValidateTransaction()
            is TransactionIntent.EnteredAddressReset -> null
            is TransactionIntent.AvailableAccountsListUpdated -> null
            is TransactionIntent.ShowMoreAccounts -> null
            is TransactionIntent.UseMaxSpendable -> null
            is TransactionIntent.SetFeeLevel -> processSetFeeLevel(intent)
            is TransactionIntent.InvalidateTransaction -> processInvalidateTransaction()
            is TransactionIntent.InvalidateTransactionKeepingTarget -> processInvalidationAndNavigate(previousState)
            is TransactionIntent.ResetFlow -> {
                interactor.reset()
                null
            }
            is TransactionIntent.RefreshSourceAccounts -> processSourceAccountsListUpdate(
                previousState.action, previousState.selectedTarget
            )
            is TransactionIntent.NavigateBackFromEnterAmount ->
                processTransactionInvalidation(previousState.action)
            is TransactionIntent.StartLinkABank -> processLinkABank(previousState)
            is TransactionIntent.LinkBankInfoSuccess -> null
            is TransactionIntent.LinkBankFailed -> null
            is TransactionIntent.ClearBackStack -> null
            is TransactionIntent.ApprovalRequired -> null
            is TransactionIntent.ClearSelectedTarget -> null
        }
    }

    private fun getInitialAmountFromTarget(
        intent: TransactionIntent.InitialiseWithSourceAndTargetAccount
    ): Money {
        val amountSource = intent.target as? CryptoAddress
        return if (amountSource != null) {
            amountSource.amount ?: intent.fromAccount.getZeroAmountForAccount()
        } else {
            intent.fromAccount.getZeroAmountForAccount()
        }
    }

    private fun processTransactionInvalidation(assetAction: AssetAction): Disposable? {
        process(
            when (assetAction) {
                AssetAction.FiatDeposit -> TransactionIntent.InvalidateTransactionKeepingTarget
                else -> TransactionIntent.InvalidateTransaction
            }
        )
        return null
    }

    private fun processLinkABank(previousState: TransactionState): Disposable =
        interactor.linkABank((previousState.selectedTarget as FiatAccount).fiatCurrency)
            .subscribeBy(
                onSuccess = { bankTransfer ->
                    process(TransactionIntent.LinkBankInfoSuccess(bankTransfer))
                },
                onError = {
                    process(TransactionIntent.LinkBankFailed(it))
                }
            )

    private fun processAccountsListUpdate(
        previousState: TransactionState,
        fromAccount: BlockchainAccount,
        action: AssetAction
    ): Disposable? =
        if (previousState.selectedTarget is NullAddress) {
            interactor.getTargetAccounts(fromAccount, action).subscribeBy(
                onSuccess = {
                    process(TransactionIntent.AvailableAccountsListUpdated(it))
                },
                onError = {
                    Timber.e("Error getting target accounts $it")
                }
            )
        } else {
            process(TransactionIntent.TargetSelected)
            null
        }

    private fun processSourceAccountsListUpdate(
        action: AssetAction,
        transactionTarget: TransactionTarget,
        shouldResetBackStack: Boolean = false
    ): Disposable =
        interactor.getAvailableSourceAccounts(action, transactionTarget).subscribeBy(
            onSuccess = {
                process(
                    TransactionIntent.AvailableSourceAccountsListUpdated(it)
                )
                if (shouldResetBackStack) {
                    process(TransactionIntent.ClearBackStack)
                }
            },
            onError = {
                Timber.e("Error getting available accounts for action $action")
            }
        )

    override fun onScanLoopError(t: Throwable) {
        super.onScanLoopError(TxFlowLogError.LoopFail(t))
        Timber.e(t, "!TRANSACTION!> Transaction Model: state error")
        throw t
    }

    override fun onStateUpdate(s: TransactionState) {
        Timber.v("!TRANSACTION!> Transaction Model: state update -> %s", s)
    }

    private fun processInvalidateTransaction(): Disposable =
        interactor.invalidateTransaction()
            .subscribeBy(
                onComplete = {
                    process(TransactionIntent.ReturnToPreviousStep)
                },
                onError = { t ->
                    errorLogger.log(TxFlowLogError.ResetFail(t))
                    process(TransactionIntent.FatalTransactionError(t))
                }
            )

    private fun processInvalidationAndNavigate(state: TransactionState): Disposable =
        interactor.invalidateTransaction()
            .subscribeBy(
                onComplete = {
                    process(
                        TransactionIntent.ReInitialiseWithTargetAndNoSource(
                            state.action, state.selectedTarget, state.passwordRequired
                        )
                    )
                },
                onError = { t ->
                    errorLogger.log(TxFlowLogError.ResetFail(t))
                    process(TransactionIntent.FatalTransactionError(t))
                }
            )

    private fun processPasswordValidation(password: String) =
        interactor.validatePassword(password)
            .subscribeBy(
                onSuccess = {
                    process(
                        if (it) {
                            TransactionIntent.UpdatePasswordIsValidated(password)
                        } else {
                            TransactionIntent.UpdatePasswordNotValidated
                        }
                    )
                },
                onError = { /* Error! What to do? Abort? Or... */ }
            )

    private fun processValidateAddress(
        address: String,
        expectedAsset: AssetInfo
    ): Disposable =
        interactor.validateTargetAddress(address, expectedAsset)
            .subscribeBy(
                onSuccess = {
                    process(TransactionIntent.TargetAddressValidated(it))
                },
                onError = { t ->
                    errorLogger.log(TxFlowLogError.AddressFail(t))
                    when (t) {
                        is TxValidationFailure -> process(TransactionIntent.TargetAddressInvalid(t))
                        else -> process(TransactionIntent.FatalTransactionError(t))
                    }
                }
            )

    // At this point we can build a transactor object from coincore and configure
    // the state object a bit more; depending on whether it's an internal, external,
    // bitpay or BTC Url address we can set things like note, amount, fee schedule
    // and hook up the correct processor to execute the transaction.
    private fun processTargetSelectionConfirmed(
        sourceAccount: BlockchainAccount,
        amount: Money,
        transactionTarget: TransactionTarget,
        action: AssetAction
    ): Disposable =
        interactor.initialiseTransaction(sourceAccount, transactionTarget, action)
            .doOnFirst {
                if (it.validationState == ValidationState.UNINITIALISED ||
                    it.validationState == ValidationState.CAN_EXECUTE
                ) {
                    onFirstUpdate(amount)
                }
            }
            .subscribeBy(
                onNext = {
                    process(TransactionIntent.PendingTxUpdated(it))
                },
                onError = {
                    Timber.e("!TRANSACTION!> Processor failed: $it")
                    errorLogger.log(TxFlowLogError.TargetFail(it))
                    process(TransactionIntent.FatalTransactionError(it))
                }
            )

    private fun onFirstUpdate(amount: Money) {
        process(TransactionIntent.PendingTransactionStarted(interactor.canTransactFiat))
        process(TransactionIntent.FetchFiatRates)
        process(TransactionIntent.FetchTargetRates)
        process(TransactionIntent.AmountChanged(amount))
    }

    private fun processAmountChanged(amount: Money): Disposable =
        interactor.updateTransactionAmount(amount)
            .subscribeBy(
                onError = {
                    Timber.e("!TRANSACTION!> Unable to get update available balance")
                    errorLogger.log(TxFlowLogError.BalanceFail(it))
                    process(TransactionIntent.FatalTransactionError(it))
                }
            )

    private fun processSetFeeLevel(intent: TransactionIntent.SetFeeLevel): Disposable =
        interactor.updateTransactionFees(intent.feeLevel, intent.customFeeAmount)
            .subscribeBy(
                onError = {
                    Timber.e("!TRANSACTION!> Unable to set TX fee level")
                    errorLogger.log(TxFlowLogError.FeesFail(it))
                    process(TransactionIntent.FatalTransactionError(it))
                }
            )

    private fun processExecuteTransaction(secondPassword: String): Disposable =
        interactor.verifyAndExecute(secondPassword)
            .subscribeBy(
                onComplete = {
                    process(TransactionIntent.UpdateTransactionComplete)
                },
                onError = {
                    if (it is NeedsApprovalException) {
                        interactor.updateFiatDepositState(it.bankPaymentData)
                        process(TransactionIntent.ApprovalRequired(it.bankPaymentData))
                    } else {
                        Timber.d("!TRANSACTION!> Unable to execute transaction: $it")
                        errorLogger.log(TxFlowLogError.ExecuteFail(it))
                        process(TransactionIntent.FatalTransactionError(it))
                    }
                }
            )

    private fun processModifyTxOptionRequest(newConfirmation: TxConfirmationValue): Disposable =
        interactor.modifyOptionValue(
            newConfirmation
        ).subscribeBy(
            onError = {
                Timber.e("Failed updating Tx options")
            }
        )

    private fun processGetFiatRate(): Disposable =
        interactor.startFiatRateFetch()
            .subscribeBy(
                onNext = { process(TransactionIntent.FiatRateUpdated(it)) },
                onComplete = { Timber.d("Fiat exchange Rate completed") },
                onError = { Timber.e("Failed getting fiat exchange rate") }
            )

    private fun processGetTargetRate(): Disposable =
        interactor.startTargetRateFetch()
            .subscribeBy(
                onNext = { process(TransactionIntent.CryptoRateUpdated(it)) },
                onComplete = { Timber.d("Target exchange Rate completed") },
                onError = { Timber.e("Failed getting target exchange rate") }
            )

    private fun processValidateTransaction(): Disposable? =
        interactor.validateTransaction()
            .subscribeBy(
                onError = {
                    Timber.e("!TRANSACTION!> Unable to validate transaction: $it")
                    errorLogger.log(TxFlowLogError.ValidateFail(it))
                    process(TransactionIntent.FatalTransactionError(it))
                },
                onComplete = {
                    Timber.d("!TRANSACTION!> Tx validation complete")
                }
            )

    override fun distinctIntentFilter(
        previousIntent: TransactionIntent,
        nextIntent: TransactionIntent
    ): Boolean {
        return when (previousIntent) {
            // Allow consecutive ReturnToPreviousStep intents
            is TransactionIntent.ReturnToPreviousStep -> {
                if (nextIntent is TransactionIntent.ReturnToPreviousStep) {
                    false
                } else {
                    super.distinctIntentFilter(previousIntent, nextIntent)
                }
            }
            else -> super.distinctIntentFilter(previousIntent, nextIntent)
        }
    }
}

private var firstCall = true
fun <T> Observable<T>.doOnFirst(onAction: (T) -> Unit): Observable<T> {
    firstCall = true
    return this.doOnNext {
        if (firstCall) {
            onAction.invoke(it)
            firstCall = false
        }
    }
}
