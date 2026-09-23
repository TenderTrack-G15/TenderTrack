package za.ac.tendertrack.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import za.ac.tendertrack.core.Validate
import za.ac.tendertrack.core.friendlyMessage
import za.ac.tendertrack.data.ServiceLocator
import za.ac.tendertrack.data.model.Profile
import za.ac.tendertrack.data.model.UserRole
import za.ac.tendertrack.data.repo.AdminRepository
import za.ac.tendertrack.data.repo.AuthRepository
import za.ac.tendertrack.data.repo.SIGN_IN_ROLES
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.Dimens
import za.ac.tendertrack.ui.viewModelFactory

/**
 * Who a sign-in page is for. One screen serves both, because only the wording
 * and the roles allowed differ.
 *
 * Government staff and suppliers sign in on separate pages so that each is sent
 * to the right place, and so a supplier who taps the wrong one is told plainly
 * rather than being refused with no explanation.
 */
enum class SignInAudience {
    GOVERNMENT, SUPPLIER;

    val title: String get() = if (this == GOVERNMENT) "Government official login" else "Supplier login"

    val eyebrow: String get() = if (this == GOVERNMENT) "Government officials" else "Registered suppliers"

    val heading: String get() = if (this == GOVERNMENT) "Sign in to TenderTrack" else "Sign in to your supplier account"

    val subtitle: String
        get() = if (this == GOVERNMENT) "Procurement officers and administrators"
        else "Track your registration and browse published tenders"

    /** The message when the account belongs on the other page. */
    val wrongPage: String
        get() = if (this == GOVERNMENT)
            "Oops, you're a supplier. Please use the supplier login on the welcome screen."
        else
            "Oops, you're a government official. Please use the Government Official login on the welcome screen."

    val switchLabel: String get() = if (this == GOVERNMENT) "Supplier? Sign in here" else "Government official? Sign in here"
}

/**
 * Why this account may not sign in on this page, or null if it may.
 *
 * Suppliers and government staff have separate pages, so each is sent to the
 * right screens and anyone on the wrong page is told which one to use. Roles
 * whose screens are not built yet are turned away here rather than being shown
 * an empty app.
 */
fun signInRefusal(audience: SignInAudience, role: UserRole): String? {
    val isSupplier = role == UserRole.SUPPLIER
    return when {
        isSupplier != (audience == SignInAudience.SUPPLIER) -> audience.wrongPage
        role !in SIGN_IN_ROLES ->
            "This account is registered as ${role.displayName}. Screens for that role are not in " +
                "this version of TenderTrack yet."
        else -> null
    }
}

// ---------------------------------------------------------------------------
// State + ViewModel
// ---------------------------------------------------------------------------

data class SignInUiState(
    val email: String = "",
    val password: String = "",
    val emailError: String? = null,
    val passwordError: String? = null,
    val formError: String? = null,
    val submitting: Boolean = false
)

class SignInViewModel(
    private val audience: SignInAudience,
    private val authRepository: AuthRepository = ServiceLocator.authRepository,
    private val adminRepository: AdminRepository = ServiceLocator.adminRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SignInUiState())
    val state: StateFlow<SignInUiState> = _state.asStateFlow()

    fun onEmail(value: String) = _state.update { it.copy(email = value.trim(), emailError = null, formError = null) }
    fun onPassword(value: String) = _state.update { it.copy(password = value, passwordError = null, formError = null) }

    fun signIn(onSuccess: (Profile) -> Unit) {
        val current = _state.value
        if (current.submitting) return
        val emailError = Validate.email(current.email)
        val passwordError = Validate.password(current.password)
        if (emailError != null || passwordError != null) {
            _state.update { it.copy(emailError = emailError, passwordError = passwordError) }
            return
        }

        _state.update { it.copy(submitting = true, formError = null) }
        viewModelScope.launch {
            try {
                val profile = authRepository.signIn(current.email, current.password)

                // A suspended account can still log in to Supabase but has no access
                // (admin_access.sql). Say so, rather than showing an empty app. If the
                // check itself fails, carry on: the database blocks the data anyway.
                val suspended = try {
                    adminRepository.isCurrentAccountSuspended()
                } catch (e: Exception) {
                    false
                }
                if (suspended) {
                    refuse("This account has been suspended. Contact your administrator.")
                    return@launch
                }

                val refusal = signInRefusal(audience, profile.role)
                if (refusal != null) {
                    refuse(refusal)
                } else {
                    _state.update { it.copy(submitting = false) }
                    onSuccess(profile)
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        submitting = false,
                        formError = e.friendlyMessage().lineSequence().firstOrNull { l -> l.isNotBlank() }?.trim()
                    )
                }
            }
        }
    }

    private suspend fun refuse(message: String) {
        authRepository.signOut()
        _state.update { it.copy(submitting = false, formError = message) }
    }
}

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

/**
 * Sign In (Deliverable 3, section 5.1). The role is never chosen here: it comes
 * from the account, and NavGraph opens that role's own screens.
 *
 * @param onRegister shown only on the supplier page, where a new supplier
 *        registers a company.
 */
@Composable
fun SignInScreen(
    audience: SignInAudience,
    onBack: () -> Unit,
    onSignedIn: (Profile) -> Unit,
    onForgotPassword: () -> Unit,
    onSwitchAudience: () -> Unit,
    onRegister: (() -> Unit)? = null
) {
    val viewModel: SignInViewModel = viewModel(
        key = "sign_in_${audience.name}",
        factory = viewModelFactory { SignInViewModel(audience) }
    )
    val state by viewModel.state.collectAsState()

    AppScaffold(title = audience.title, onBack = onBack) {
        ScreenHeading(
            eyebrow = audience.eyebrow,
            title = audience.heading,
            subtitle = audience.subtitle
        )

        AppTextField(
            label = "Email address",
            value = state.email,
            onValueChange = viewModel::onEmail,
            placeholder = if (audience == SignInAudience.GOVERNMENT) "you@department.gov.za" else "you@company.co.za",
            leadingIcon = Icons.Default.MailOutline,
            keyboardType = KeyboardType.Email,
            error = state.emailError,
            enabled = !state.submitting
        )
        AppTextField(
            label = "Password",
            value = state.password,
            onValueChange = viewModel::onPassword,
            placeholder = "Enter your password",
            leadingIcon = Icons.Default.Lock,
            keyboardType = KeyboardType.Password,
            isPassword = true,
            error = state.passwordError,
            enabled = !state.submitting
        )

        state.formError?.let { NoteBanner(text = it, tone = NoteTone.Danger, icon = Icons.Default.Warning) }

        PrimaryButton(
            text = "Sign in",
            icon = Icons.AutoMirrored.Filled.Login,
            loading = state.submitting,
            onClick = { viewModel.signIn(onSignedIn) }
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextAction(text = "Forgot password?", color = AppColor.InfoInk, onClick = onForgotPassword)
            onRegister?.let { TextAction(text = "Register an account", color = AppColor.InfoInk, onClick = it) }
        }
        TextAction(text = audience.switchLabel, color = AppColor.Muted, onClick = onSwitchAudience)

        NoteBanner(
            title = "Role is not selected here",
            text = "Your permissions come from the role assigned to your account and are applied to every " +
                "request by the database. Only an administrator can change a role.",
            tone = NoteTone.Neutral,
            icon = Icons.Default.Shield
        )

        if (ServiceLocator.usingSampleData) {
            Spacer(Modifier.height(Dimens.SpaceSm))
            NoteBanner(
                title = "Running on sample data",
                text = if (audience == SignInAudience.GOVERNMENT)
                    "Any password of 8 characters or more works. An email starting with \"admin\" opens the " +
                        "Administrator screens; any other email opens the Procurement Officer screens."
                else
                    "Any password of 8 characters or more works. An email starting with \"supplier\", or one " +
                        "you signed up with, opens the Supplier screens.",
                tone = NoteTone.Info,
                icon = Icons.Default.Info
            )
        }
    }
}
