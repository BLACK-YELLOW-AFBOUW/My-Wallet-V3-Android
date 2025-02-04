package piuk.blockchain.android.ui.start

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import com.blockchain.componentlib.carousel.CarouselViewType
import com.blockchain.featureflags.GatedFeature
import com.blockchain.featureflags.InternalFeatureFlagApi
import com.blockchain.koin.scopedInject
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import org.koin.android.ext.android.inject
import piuk.blockchain.android.BuildConfig
import piuk.blockchain.android.R
import piuk.blockchain.android.data.connectivity.ConnectivityStatus
import piuk.blockchain.android.databinding.ActivityLandingBinding
import piuk.blockchain.android.databinding.ActivityLandingOnboardingBinding
import piuk.blockchain.android.ui.base.MvpActivity
import piuk.blockchain.android.ui.createwallet.CreateWalletActivity
import piuk.blockchain.android.ui.customviews.toast
import piuk.blockchain.android.ui.login.LoginAnalytics
import piuk.blockchain.android.ui.recover.AccountRecoveryActivity
import piuk.blockchain.android.ui.recover.AccountRecoveryAnalytics
import piuk.blockchain.android.ui.recover.RecoverFundsActivity
import piuk.blockchain.android.urllinks.WALLET_STATUS_URL
import piuk.blockchain.android.util.StringUtils
import piuk.blockchain.android.util.copyHashOnLongClick
import piuk.blockchain.android.util.visible

class LandingActivity : MvpActivity<LandingView, LandingPresenter>(), LandingView {

    override val presenter: LandingPresenter by scopedInject()
    override val view: LandingView = this

    private val internalFlags: InternalFeatureFlagApi by inject()
    private val compositeDisposable = CompositeDisposable()

    private val binding: ActivityLandingBinding by lazy {
        ActivityLandingBinding.inflate(layoutInflater)
    }

    private val onboardingBinding: ActivityLandingOnboardingBinding by lazy {
        ActivityLandingOnboardingBinding.inflate(layoutInflater)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme_MainActivity)
        super.onCreate(savedInstanceState)

        if (internalFlags.isFeatureEnabled(GatedFeature.NEW_ONBOARDING)) {
            setContentView(onboardingBinding.root)

            with(onboardingBinding) {

                btnCreate.setOnClickListener { launchCreateWalletActivity() }

                // Mock prices for now
                carousel.submitList(
                    listOf(
                        CarouselViewType.ValueProp(
                            com.blockchain.componentlib.R.drawable.carousel_brokerage,
                            this@LandingActivity.getString(R.string.landing_value_prop_one)
                        ),
                        CarouselViewType.ValueProp(
                            com.blockchain.componentlib.R.drawable.carousel_rewards,
                            this@LandingActivity.getString(R.string.landing_value_prop_two_1)
                        ),
                        CarouselViewType.ValueProp(
                            com.blockchain.componentlib.R.drawable.carousel_security,
                            this@LandingActivity.getString(R.string.landing_value_prop_three)
                        )
                        /* TODO: Add back in once live prices are implemented
                        CarouselViewType.PriceList(
                            this@LandingActivity.getString(R.string.landing_value_prop_four),
                            this@LandingActivity.getString(R.string.landing_live_prices)
                        ) */
                    )
                )
                carousel.setCarouselIndicator(carouselIndicators)
                carousel.startAutoplay(CAROUSEL_PAGE_TIME)
            }
        } else {
            setContentView(binding.root)

            with(binding) {
                btnCreate.setOnClickListener { launchCreateWalletActivity() }

                if (!ConnectivityStatus.hasConnectivity(this@LandingActivity)) {
                    showConnectivityWarning()
                } else {
                    presenter.checkForRooted()
                }

                textVersion.text =
                    "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.COMMIT_HASH}"

                textVersion.copyHashOnLongClick(this@LandingActivity)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (internalFlags.isFeatureEnabled(GatedFeature.NEW_ONBOARDING)) {
            setupSSOControls(
                onboardingBinding.btnLoginRestore.rightButton, onboardingBinding.btnLoginRestore.leftButton
            )
        } else {
            setupSSOControls(binding.btnLogin, binding.btnRecover)
        }
    }

    override fun onStop() {
        compositeDisposable.clear()
        super.onStop()
    }

    private fun launchSSOAccountRecoveryFlow() =
        startActivity(Intent(this, AccountRecoveryActivity::class.java))

    private fun setupSSOControls(loginButton: Button, recoverButton: Button) {
        loginButton.setOnClickListener {
            launchSSOLoginActivity()
        }
        setupRecoverButton(recoverButton)
    }

    private fun setupRecoverButton(recoverButton: Button) {
        recoverButton.apply {
                text = getString(R.string.restore_wallet_cta)
                setOnClickListener { launchSSOAccountRecoveryFlow() }
        }
    }

    private fun launchCreateWalletActivity() {
        CreateWalletActivity.start(this)
    }

    private fun launchLoginActivity() {
        analytics.logEvent(LoginAnalytics.LoginClicked())
        startActivity(Intent(this, LoginActivity::class.java))
    }

    private fun launchSSOLoginActivity() {
        analytics.logEvent(LoginAnalytics.LoginClicked())
        startActivity(Intent(this, piuk.blockchain.android.ui.login.LoginActivity::class.java))
    }

    private fun startRecoverFundsActivity() = RecoverFundsActivity.start(this)

    private fun showConnectivityWarning() =
        showAlert(AlertDialog.Builder(this, R.style.AlertDialogStyle)
            .setMessage(getString(R.string.check_connectivity_exit))
            .setCancelable(false)
            .setNegativeButton(R.string.exit) { _, _ -> finishAffinity() }
            .setPositiveButton(R.string.retry) { _, _ ->
                val intent = Intent(this, LandingActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
            .create()
        )

    private fun showFundRecoveryWarning() =
        showAlert(AlertDialog.Builder(this, R.style.AlertDialogStyle)
            .setTitle(R.string.app_name)
            .setMessage(R.string.recover_funds_warning_message_1)
            .setPositiveButton(R.string.dialog_continue) { _, _ ->
                analytics.logEvent(AccountRecoveryAnalytics.RecoveryOptionSelected(isCustodialAccount = false))
                startRecoverFundsActivity()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> clearAlert() }
            .create()
        )

    override fun showIsRootedWarning() =
        showAlert(AlertDialog.Builder(this, R.style.AlertDialogStyle)
            .setMessage(R.string.device_rooted)
            .setCancelable(false)
            .setPositiveButton(R.string.dialog_continue) { _, _ -> clearAlert() }
            .create()
        )

    override fun showApiOutageMessage() {
        val warningLayout = if (internalFlags.isFeatureEnabled(GatedFeature.NEW_ONBOARDING)) {
            onboardingBinding.layoutWarning
        } else {
            binding.layoutWarning
        }
        warningLayout.root.visible()
        val learnMoreMap = mapOf<String, Uri>("learn_more" to Uri.parse(WALLET_STATUS_URL))
        warningLayout.warningMessage.apply {
            movementMethod = LinkMovementMethod.getInstance()
            text = StringUtils.getStringWithMappedAnnotations(
                this@LandingActivity, R.string.wallet_issue_message, learnMoreMap
            )
        }
    }

    override fun showToast(message: String, toastType: String) = toast(message, toastType)

    companion object {
        @JvmStatic
        fun start(context: Context) {
            Intent(context, LandingActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(this)
            }
        }

        private const val CAROUSEL_PAGE_TIME = 3000L
    }
}
