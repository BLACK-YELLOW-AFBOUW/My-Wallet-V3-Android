package piuk.blockchain.android.ui.recover

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.annotation.StringRes
import androidx.appcompat.widget.Toolbar
import com.blockchain.annotations.CommonCode
import com.blockchain.koin.scopedInject
import piuk.blockchain.android.R
import piuk.blockchain.android.databinding.ActivityRecoverFundsBinding
import piuk.blockchain.android.ui.auth.PinEntryActivity
import piuk.blockchain.android.ui.base.BaseMvpActivity
import piuk.blockchain.android.ui.createwallet.CreateWalletActivity
import piuk.blockchain.android.ui.customviews.ToastCustom
import piuk.blockchain.android.ui.customviews.dialogs.MaterialProgressDialog
import piuk.blockchain.android.util.ViewUtils
import java.util.Locale

internal class RecoverFundsActivity : BaseMvpActivity<RecoverFundsView, RecoverFundsPresenter>(), RecoverFundsView {

    override val alwaysDisableScreenshots: Boolean
        get() = true

    private val recoverFundsPresenter: RecoverFundsPresenter by scopedInject()

    private val binding: ActivityRecoverFundsBinding by lazy {
        ActivityRecoverFundsBinding.inflate(layoutInflater)
    }
    private var progressDialog: MaterialProgressDialog? = null
    private val recoveryPhrase: String
        get() = binding.fieldPassphrase.text.toString().toLowerCase(Locale.US).trim()

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(binding.root)

        val toolbar = findViewById<Toolbar>(R.id.toolbar_general)
        setupToolbar(toolbar, R.string.recover_funds)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        with(binding) {
            buttonContinue.setOnClickListener { presenter?.onContinueClicked(recoveryPhrase) }
            fieldPassphrase.setOnEditorActionListener { _, i, _ ->
                if (i == EditorInfo.IME_ACTION_GO) {
                    presenter?.onContinueClicked(recoveryPhrase)
                }
                true
            }
        }
        onViewReady()
    }

    override fun gotoCredentialsActivity(recoveryPhrase: String) {
        val intent = Intent(this, CreateWalletActivity::class.java)
        intent.putExtra(CreateWalletActivity.RECOVERY_PHRASE, recoveryPhrase)
        startActivity(intent)
    }

    override fun startPinEntryActivity() {
        ViewUtils.hideKeyboard(this)
        PinEntryActivity.startAfterWalletCreation(this)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    override fun showError(@StringRes message: Int) {
        ToastCustom.makeText(this, getString(message), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR)
    }

    override fun onDestroy() {
        super.onDestroy()
        dismissProgressDialog()
    }

    @CommonCode("Move to base")
    override fun showProgressDialog(@StringRes messageId: Int) {
        dismissProgressDialog()

        if (isFinishing) return

        progressDialog = MaterialProgressDialog(this).apply {
            setCancelable(false)
            setMessage(getString(messageId))
            show()
        }
    }

    override fun createPresenter(): RecoverFundsPresenter = recoverFundsPresenter
    override fun getView(): RecoverFundsView = this

    companion object {
        fun start(ctx: Context) {
            ctx.startActivity(Intent(ctx, RecoverFundsActivity::class.java))
        }
    }
}
