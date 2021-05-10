package piuk.blockchain.android.ui.login

import piuk.blockchain.android.ui.base.mvi.MviIntent

sealed class LoginIntents : MviIntent<LoginState> {

    data class UpdateEmail(private val email: String) : LoginIntents() {
        override fun reduce(oldState: LoginState): LoginState =
            oldState.copy(
                email = email,
                currentStep = if (email.isBlank()) {
                    LoginStep.SELECT_METHOD
                } else {
                    LoginStep.ENTER_EMAIL
                }
            )
    }

    data class SendEmail(val selectedEmail: String) : LoginIntents() {
        override fun reduce(oldState: LoginState): LoginState =
            oldState.copy(
                email = selectedEmail,
                currentStep = LoginStep.SEND_EMAIL
            )
    }

    object ShowEmailSent : LoginIntents() {
        override fun reduce(oldState: LoginState): LoginState =
            oldState.copy(
                currentStep = LoginStep.VERIFY_DEVICE
            )
    }

    object ShowEmailFailed : LoginIntents() {
        override fun reduce(oldState: LoginState): LoginState =
            oldState.copy(
                currentStep = LoginStep.SHOW_EMAIL_ERROR
            )
    }

    object StartPinEntry : LoginIntents() {
        override fun reduce(oldState: LoginState): LoginState =
            oldState.copy(
                currentStep = LoginStep.ENTER_PIN
            )
    }

    data class LoginWithQr(val qrString: String) : LoginIntents() {
        override fun reduce(oldState: LoginState): LoginState =
            oldState.copy(
                currentStep = LoginStep.LOG_IN
            )
    }

    data class ShowScanError(private val shouldRestartApp: Boolean) : LoginIntents() {
        override fun reduce(oldState: LoginState): LoginState =
            oldState.copy(
                currentStep = LoginStep.SHOW_SCAN_ERROR,
                shouldRestartApp = shouldRestartApp
            )
    }
}