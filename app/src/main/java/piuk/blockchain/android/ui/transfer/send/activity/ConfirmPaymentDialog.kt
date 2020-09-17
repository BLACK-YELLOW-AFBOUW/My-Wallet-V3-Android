package piuk.blockchain.android.ui.transfer.send.activity

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import com.blockchain.koin.scopedInject
import com.blockchain.notifications.analytics.Analytics
import com.blockchain.notifications.analytics.SendAnalytics
import kotlinx.android.synthetic.main.dialog_confirm_transaction.*
import org.apache.commons.lang3.NotImplementedException
import org.koin.android.ext.android.inject
import piuk.blockchain.android.R
import piuk.blockchain.android.ui.account.PaymentConfirmationDetails
import piuk.blockchain.androidcoreui.ui.base.BaseDialogFragment
import piuk.blockchain.androidcoreui.ui.base.UiState
import piuk.blockchain.androidcoreui.utils.extensions.gone
import piuk.blockchain.androidcoreui.utils.extensions.visible

class ConfirmPaymentDialog : BaseDialogFragment<ConfirmPaymentView, ConfirmPaymentPresenter>(),
    ConfirmPaymentView {

    private val confirmPaymentPresenter: ConfirmPaymentPresenter by scopedInject()
    private val analytics: Analytics by inject()

    private lateinit var onSendClicked: () -> Unit
    private lateinit var onFeeChangeClicked: () -> Unit

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(
            R.layout.dialog_confirm_transaction,
            container,
            false
        ).apply {
            isFocusableInTouchMode = true
            requestFocus()
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        val window = dialog?.window ?: return
        val params = window.attributes
        params.width = WindowManager.LayoutParams.MATCH_PARENT
        params.height = WindowManager.LayoutParams.MATCH_PARENT
        window.attributes = params

        dialog?.setCancelable(true)
        dialog?.window!!.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

        dialog?.window!!.statusBarColor =
            ContextCompat.getColor(activity!!, R.color.primary_navy_dark)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        toolbar.setNavigationOnClickListener { dismiss() }
        button_change_fee.setOnClickListener { onFeeChangeClicked() }
        button_send.setOnClickListener {
            onSendClicked()
            analytics.logEvent(SendAnalytics.SummarySendClick(paymentDetails.crypto))
        }

        if (!arguments!!.getBoolean(SHOW_FEE_CHOICE, true)) {
            button_change_fee.gone()
        }

        onViewReady()
    }

    override fun setFromLabel(fromLabel: String) {
        from_address.text = fromLabel
    }

    override fun setToLabel(toLabel: String) {
        to_address.text = toLabel
    }

    override fun setAmount(amount: String) {
        send_amount.text = amount
    }

    override fun setFee(fee: String) {
        send_fees.text = fee
    }

    override fun setTotals(totalCrypto: String, totalFiat: String) {
        primary_total.text = totalCrypto
        secondary_total.text = totalFiat
    }

    override fun setFiatTotalOnly(totalFiat: String) {
        primary_total.text = totalFiat
    }

    override fun setContactNote(contactNote: String) {
        contact_note.text = contactNote
        contact_note.visible()
        description_header.visible()
    }

    override fun setContactNoteDescription(contactNoteDescription: String) {
        description_header.text = contactNoteDescription
    }

    override fun closeDialog() {
        dismiss()
    }

    override fun getPaymentDetails(): PaymentConfirmationDetails {
        return arguments?.getParcelable(PAYMENT_DETAILS)
            ?: throw IllegalArgumentException("No payment details provided")
    }

    override fun getContactNote(): String? {
        return arguments?.getString(CONTACT_NOTE)
    }

    override fun getContactNoteDescription(): String? {
        return arguments?.getString(CONTACT_NOTE_DESCRIPTION)
    }

    override fun setWarning(warning: String) {
        layout_warning.visible()
        warning_title.text = warning
    }

    override fun setWarningSubText(warningSubText: String) {
        warning_sub_text.text = warningSubText
    }

    override fun setUiState(uiState: Int) {
        when (uiState) {
            UiState.LOADING -> {
                loading_layout.visible()
                main_layout.gone()
            }
            UiState.CONTENT -> {
                loading_layout.gone()
                main_layout.visible()
            }
            UiState.EMPTY,
            UiState.FAILURE ->
                throw NotImplementedException("State $uiState hasn't been implemented yet")
        }
    }

    override fun createPresenter() = confirmPaymentPresenter

    override fun getMvpView(): ConfirmPaymentView = this

    companion object {
        private const val PAYMENT_DETAILS = "PAYMENT_DETAILS"
        private const val CONTACT_NOTE = "CONTACT_NOTE"
        private const val CONTACT_NOTE_DESCRIPTION = "CONTACT_NOTE_DESCRIPTION"
        private const val SHOW_FEE_CHOICE = "SHOW_FEE_CHOICE"

        fun newInstance(
            details: PaymentConfirmationDetails,
            note: String?,
            noteDescription: String?,
            showFeeChoice: Boolean,
            doOnSendClicked: () -> Unit,
            doOnFeeChange: () -> Unit = {}
        ): ConfirmPaymentDialog {
            return ConfirmPaymentDialog().apply {
                setStyle(DialogFragment.STYLE_NO_TITLE, R.style.FullscreenDialog)
                arguments = Bundle().apply {
                    putParcelable(PAYMENT_DETAILS, details)
                    putBoolean(SHOW_FEE_CHOICE, showFeeChoice)

                    if (note != null) {
                        putString(CONTACT_NOTE, note)
                    }
                    if (noteDescription != null) {
                        putString(CONTACT_NOTE_DESCRIPTION, noteDescription)
                    }

                    onSendClicked = doOnSendClicked
                    onFeeChangeClicked = doOnFeeChange
                }
            }
        }
    }
}