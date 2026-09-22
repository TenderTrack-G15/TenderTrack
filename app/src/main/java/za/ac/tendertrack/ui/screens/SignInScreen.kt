package za.ac.tendertrack.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import za.ac.tendertrack.data.repo.canUseProcurementOfficerScreens
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens

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
    private val authRepository: za.ac.tendertrack.data.repo.AuthRepository = ServiceLocator.authRepository,
    private val adminRepository: za.ac.tendertrack.data.repo.AdminRepository = ServiceLocator.adminRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SignInUiState())
    val state: StateFlow<SignInUiState> = _state.asStateFlow()

    fun onEmailChange(value: String) =
        _state.update { it.copy(email = value, emailError = null, formError = null) }

    fun onPasswordChange(value: String) =
        _state.update { it.copy(password = value, passwordError = null, formError = null) }

    /**
     * Signs the user in, then reads the role from their profile. The role is not
     * sent up and is not selectable anywhere in the UI — this only checks that
     * the account that just authenticated is allowed on these screens.
     */
    fun signIn(onSuccess: (Profile) -> Unit) {
        val current = _state.value
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
                // check itself fails (e.g. the migration is not run yet), carry on:
                // the database still blocks a suspended user's data either way.
                val suspended = try {
                    adminRepository.isCurrentAccountSuspended()
                } catch (e: Exception) {
                    false
                }
                if (suspended) {
                    authRepository.signOut()
                    _state.update {
                        it.copy(
                            submitting = false,
                            formError = "This account has been suspended. Contact your administrator."
                        )
                    }
                    return@launch
                }

                // Staff sign-in is for procurement officers and administrators;
                // NavGraph sends each to their own screens.
                if (!profile.canUseProcurementOfficerScreens()) {
                    authRepository.signOut()
                    _state.update {
                        it.copy(
                            submitting = false,
                            formError = "This account is registered as ${profile.role.displayName}. " +
                                "Staff sign-in is for procurement officers and administrators. Contact " +
                                "your administrator if your role is wrong."
                        )
                    }
                    return@launch
                }
                _state.update { it.copy(submitting = false) }
                onSuccess(profile)
            } catch (e: Exception) {
                _state.update { it.copy(submitting = false, formError = e.friendlyMessage()) }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

@Composable
fun SignInScreen(
    onSignedIn: (Profile) -> Unit,
    /** Guest entry to the public screens — no account needed (Deliverable 3, 5.1). */
    onContinueAsPublic: () -> Unit,
    viewModel: SignInViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .background(AppColor.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))

        Box(
            Modifier
                .size(74.dp)
                .clip(RoundedCornerShape(18.dp))
                .border(2.dp, AppColor.Ink, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center
        ) {
            AppIcon(Icons.Default.BusinessCenter, tint = AppColor.Ink, size = 36.dp)
        }
        Spacer(Modifier.height(14.dp))
        Text("TenderTrack", style = AppType.H1.copy(fontSize = 30.sp))
        Spacer(Modifier.height(6.dp))
        Text(
            "IT tender lifecycle tracking for South African public procurement",
            style = AppType.Meta,
            textAlign = TextAlign.Center
        )

        // -- Public / citizen entry first: most people using TenderTrack are
        //    members of the public (FR13-FR15, no login required) ----------------
        Spacer(Modifier.height(30.dp))
        PrimaryButton(
            text = "Continue as a member of the public",
            icon = Icons.Default.Public,
            onClick = onContinueAsPublic
        )
        Spacer(Modifier.height(Dimens.SpaceSm))
        Text(
            "View published tenders, spending and delivery progress, or flag a tender for review. " +
                "No account needed.",
            style = AppType.Tiny,
            textAlign = TextAlign.Center
        )

        // -- Staff sign-in below ------------------------------------------------------
        Spacer(Modifier.height(Dimens.SpaceXxl))
        AppCard {
            CardHeader(
                title = "Government staff",
                subtitle = "Procurement officers and administrators sign in here"
            )
            Spacer(Modifier.height(Dimens.SpaceLg))

            AppTextField(
                label = "Email address",
                value = state.email,
                onValueChange = viewModel::onEmailChange,
                placeholder = "you@department.gov.za",
                leadingIcon = Icons.Default.MailOutline,
                keyboardType = KeyboardType.Email,
                error = state.emailError
            )
            Spacer(Modifier.height(Dimens.SpaceLg))
            AppTextField(
                label = "Password",
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                placeholder = "Enter your password",
                leadingIcon = Icons.Default.Lock,
                keyboardType = KeyboardType.Password,
                isPassword = true,
                error = state.passwordError
            )

            if (state.formError != null) {
                Spacer(Modifier.height(Dimens.SpaceLg))
                NoteBanner(
                    text = state.formError!!,
                    tone = NoteTone.Danger,
                    icon = Icons.Default.Warning
                )
            }

            Spacer(Modifier.height(Dimens.SpaceXl))
            // A secondary button, so the public entry above stays the one primary
            // action on the page. It still shows progress while signing in.
            SecondaryButton(
                text = if (state.submitting) "Signing in…" else "Sign in",
                icon = Icons.Default.Lock,
                enabled = !state.submitting,
                onClick = { viewModel.signIn(onSignedIn) }
            )
        }

        Spacer(Modifier.height(Dimens.SpaceXl))
        NoteBanner(
            title = "Role is not selected here",
            text = "Your permissions come from the role assigned to your account and are applied to " +
                "every request by the database. Only an administrator can change a role.",
            tone = NoteTone.Neutral,
            icon = Icons.Default.Shield
        )

        if (za.ac.tendertrack.data.ServiceLocator.usingSampleData) {
            Spacer(Modifier.height(Dimens.SpaceMd))
            NoteBanner(
                title = "Running on sample data",
                text = "No Supabase project is configured, so the app is using the bundled demo data. " +
                    "Sign in with any email address and a password of 8 characters or more. " +
                    "An email starting with \"admin\" opens the Administrator screens.",
                tone = NoteTone.Info,
                icon = Icons.Default.Info
            )
        }
    }
}
