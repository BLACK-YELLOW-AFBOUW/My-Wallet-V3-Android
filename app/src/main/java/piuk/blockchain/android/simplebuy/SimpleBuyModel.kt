package piuk.blockchain.android.simplebuy

import com.blockchain.preferences.SimpleBuyPrefs
import com.blockchain.swap.nabu.datamanagers.OrderState
import com.blockchain.swap.nabu.datamanagers.PaymentMethod
import com.blockchain.swap.nabu.datamanagers.SimpleBuyPairs
import com.blockchain.swap.nabu.models.simplebuy.EverypayPaymentAttrs
import com.google.gson.Gson
import info.blockchain.balance.CryptoCurrency
import io.reactivex.Completable
import io.reactivex.Scheduler
import io.reactivex.disposables.Disposable
import io.reactivex.rxkotlin.subscribeBy
import piuk.blockchain.android.cards.partners.CardActivator
import piuk.blockchain.android.cards.partners.EverypayCardActivator
import piuk.blockchain.android.ui.base.mvi.MviModel
import piuk.blockchain.androidcore.utils.extensions.thenSingle
import java.lang.IllegalStateException

class SimpleBuyModel(
    private val prefs: SimpleBuyPrefs,
    initialState: SimpleBuyState,
    scheduler: Scheduler,
    private val gson: Gson,
    private val cardActivators: List<CardActivator>,
    private val interactor: SimpleBuyInteractor
) : MviModel<SimpleBuyState, SimpleBuyIntent>(
    gson.fromJson(prefs.simpleBuyState(), SimpleBuyState::class.java) ?: initialState,
    scheduler) {

    override fun performAction(previousState: SimpleBuyState, intent: SimpleBuyIntent): Disposable? =
        when (intent) {
            is SimpleBuyIntent.FetchBuyLimits ->
                interactor.fetchBuyLimitsAndSupportedCryptoCurrencies(intent.fiatCurrency)
                    .subscribeBy(
                        onSuccess = { pairs ->
                            val selectedCryptoCurrency =
                                getSelectedCryptoCurrency(pairs, intent.fiatCurrency, previousState)

                            process(SimpleBuyIntent.UpdatedBuyLimitsAndSupportedCryptoCurrencies(pairs,
                                selectedCryptoCurrency))

                            selectedCryptoCurrency?.let {
                                process(SimpleBuyIntent.NewCryptoCurrencySelected(it))
                            }
                        },
                        onError = { process(SimpleBuyIntent.ErrorIntent()) }
                    )
            is SimpleBuyIntent.FetchPredefinedAmounts ->
                interactor.fetchPredefinedAmounts(intent.fiatCurrency)
                    .subscribeBy(
                        onSuccess = { process(it) },
                        onError = { process(SimpleBuyIntent.ErrorIntent()) }
                    )
            is SimpleBuyIntent.FetchSupportedFiatCurrencies ->
                interactor.fetchSupportedFiatCurrencies()
                    .subscribeBy(
                        onSuccess = { process(it) },
                        onError = { process(SimpleBuyIntent.ErrorIntent()) }
                    )
            is SimpleBuyIntent.CancelOrder -> (previousState.id?.let {
                interactor.cancelOrder(it)
            } ?: Completable.complete())
                .subscribeBy(
                    onComplete = { process(SimpleBuyIntent.OrderCanceled) },
                    onError = { process(SimpleBuyIntent.ErrorIntent()) }
                )
            is SimpleBuyIntent.FetchBankAccount ->
                when {
                    previousState.bankAccount != null -> {
                        process(SimpleBuyIntent.BankAccountUpdated(previousState.bankAccount))
                        null
                    }
                    previousState.selectedPaymentMethod?.isBank() == true ->
                        interactor.fetchBankAccount(previousState.fiatCurrency).subscribeBy(
                            onSuccess = { process(it) },
                            onError = { process(SimpleBuyIntent.ErrorIntent()) }
                        )
                    else -> null
                }
            is SimpleBuyIntent.CancelOrderIfAnyAndCreatePendingOne -> (previousState.id?.let {
                interactor.cancelOrder(it)
            } ?: Completable.complete()).thenSingle {
                interactor.createOrder(
                    previousState.selectedCryptoCurrency
                        ?: throw IllegalStateException("Missing Cryptocurrency "),
                    previousState.order.amount ?: throw IllegalStateException("Missing amount"),
                    previousState.selectedPaymentMethod?.id?.takeIf {
                        it != PaymentMethod.BANK_PAYMENT_ID &&
                                it != PaymentMethod.UNDEFINED_CARD_PAYMENT_ID
                    },
                    previousState.selectedPaymentMethod?.paymentMethodType
                        ?: throw IllegalStateException("Missing Payment Method"),
                    true
                )
            }
                .subscribeBy(
                    onSuccess = {
                        process(it)
                    },
                    onError = {
                        process(SimpleBuyIntent.ErrorIntent())
                    }
                )
            is SimpleBuyIntent.FetchKycState -> interactor.pollForKycState(previousState.fiatCurrency)
                .subscribeBy(
                    onSuccess = { process(it) },
                    onError = { /*never fails. will return SimpleBuyIntent.KycStateUpdated(KycState.FAILED)*/ }
                )
            is SimpleBuyIntent.FetchQuote -> interactor.fetchQuote(
                previousState.selectedCryptoCurrency,
                previousState.order.amount
            ).subscribeBy(
                onSuccess = { process(it) },
                onError = { process(SimpleBuyIntent.ErrorIntent()) }
            )
            is SimpleBuyIntent.BuyButtonClicked -> interactor.checkTierLevel(previousState.fiatCurrency)
                .subscribeBy(
                    onSuccess = { process(it) },
                    onError = { process(SimpleBuyIntent.ErrorIntent()) }
                )
            is SimpleBuyIntent.UpdateExchangeRate -> interactor.exchangeRate(intent.currency)
                .subscribeBy(
                    onSuccess = { process(it) },
                    onError = { }
                )
            is SimpleBuyIntent.NewCryptoCurrencySelected -> interactor.exchangeRate(intent.currency)
                .subscribeBy(
                    onSuccess = { process(it) },
                    onError = { }
                )
            is SimpleBuyIntent.FetchSuggestedPaymentMethod -> interactor.fetchPaymentMethods(
                intent.fiatCurrency,
                intent.selectedPaymentMethodId
            )
                .subscribeBy(
                    onSuccess = {
                        process(it)
                    },
                    onError = {
                        process(SimpleBuyIntent.ErrorIntent())
                    }
                )
            is SimpleBuyIntent.MakeCardPayment ->
                interactor.fetchOrder(intent.orderId)
                    .subscribeBy({
                        process(SimpleBuyIntent.ErrorIntent())
                    }, {
                        process(SimpleBuyIntent.OrderPriceUpdated(it.price))
                        it.attributes?.everypay?.let { attrs ->
                            if (attrs.paymentState == EverypayPaymentAttrs.WAITING_3DS &&
                                it.state == OrderState.AWAITING_FUNDS
                            ) {
                                process(SimpleBuyIntent.Open3dsAuth(
                                    attrs.paymentLink,
                                    EverypayCardActivator.redirectUrl
                                ))
                                process(SimpleBuyIntent.ResetEveryPayAuth)
                            } else {
                                process(SimpleBuyIntent.CheckOrderStatus)
                            }
                        } ?: kotlin.run {
                            process(SimpleBuyIntent.ErrorIntent()) // todo handle case of partner not supported
                        }
                    })
            is SimpleBuyIntent.ConfirmOrder -> interactor.confirmOrder(
                previousState.id ?: throw IllegalStateException("Order Id not available"),
                cardActivators.firstOrNull {
                    previousState.selectedPaymentMethod?.partner == it.partner
                }?.paymentAttributes()
            )
                .subscribeBy(
                    onSuccess = {
                        process(SimpleBuyIntent.OrderCreated(it))
                    },
                    onError = { process(SimpleBuyIntent.ErrorIntent()) }
                )
            is SimpleBuyIntent.CheckOrderStatus -> interactor.pollForOrderStatus(
                previousState.id ?: throw IllegalStateException("Order Id not available")
            ).subscribeBy(
                onSuccess = {
                    if (it.state == OrderState.FINISHED)
                        process(SimpleBuyIntent.CardPaymentSucceeded)
                    else if (it.state == OrderState.AWAITING_FUNDS || it.state == OrderState.PENDING_EXECUTION) {
                        process(SimpleBuyIntent.CardPaymentPending)
                    } else process(SimpleBuyIntent.ErrorIntent())
                },
                onError = {
                    process(SimpleBuyIntent.ErrorIntent())
                }
            )
            else -> null
        }

    private fun getSelectedCryptoCurrency(
        pairs: SimpleBuyPairs,
        fiatCurrency: String,
        previousState: SimpleBuyState
    ): CryptoCurrency? =
        previousState.selectedCryptoCurrency ?: pairs.pairs.firstOrNull {
            it.fiatCurrency == fiatCurrency
        }?.cryptoCurrency

    override fun onStateUpdate(s: SimpleBuyState) {
        prefs.updateSimpleBuyState(gson.toJson(s))
    }
}