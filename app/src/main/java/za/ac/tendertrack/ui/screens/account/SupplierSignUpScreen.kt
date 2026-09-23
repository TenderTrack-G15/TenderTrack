package za.ac.tendertrack.ui.screens.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import za.ac.tendertrack.core.Validate
import za.ac.tendertrack.data.ServiceLocator
import za.ac.tendertrack.data.model.*
import za.ac.tendertrack.data.repo.AuthRepository
import za.ac.tendertrack.data.repo.SupplierAccountRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType

// ---------------------------------------------------------------------------
// State + ViewModel
// ---------------------------------------------------------------------------

/** D3 5.3: "Create Account", then "Company Profile". CHECK_EMAIL only when Supabase requires confirmation. */
enum class SignUpStep { ACCOUNT, COMPANY, CHECK_EMAIL }

data class SupplierSignUpUiState(
    val step: SignUpStep = SignUpStep.ACCOUNT,
    val email: String = "",
    val password: String = "",
    val confirm: String = "",
    val emailError: String? = null,
    val passwordError: String? = null,
    val confirmError: String? = null,
    /** The mobile number is captured on step 1 but stored with the company profile. */
    val company: CompanyForm = CompanyForm(),
    val companyErrors: Map<CompanyField, String> = emptyMap(),
    val formError: String? = null,
    val submitting: Boolean = false
)

class SupplierSignUpViewModel(
    private val auth: AuthRepository = ServiceLocator.authRepository,
    private val suppliers: SupplierAccountRepository = ServiceLocator.supplierAccountRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SupplierSignUpUiState())
    val state: StateFlow<SupplierSignUpUiState> = _state.asStateFlow()

    fun onEmail(v: String) = _state.update { it.copy(email = v.trim(), emailError = null, formError = null) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, passwordError = null, formError = null) }
    fun onConfirm(v: String) = _state.update { it.copy(confirm = v, confirmError = null, formError = null) }

    fun onCompany(field: CompanyField?, form: CompanyForm) = _state.update {
        it.copy(company = form, companyErrors = if (field == null) it.companyErrors else it.companyErrors - field,
            formError = null)
    }

    // -- Step 1: account ------------------------------------------------------------

    fun continueToCompany() {
        val s = _state.value
        val emailError = Validate.email(s.email)
        val passwordError = Validate.password(s.password)
        val confirmError = confirmError(s.password, s.confirm)
        val mobileError = SupplierRules.mobile(s.company.mobileNumber)
        if (emailError != null || passwordError != null || confirmError != null || mobileError != null) {
            _state.update {
                it.copy(emailError = emailError, passwordError = passwordError, confirmError = confirmError,
                    companyErrors = mobileError?.let { m -> it.companyErrors + (CompanyField.MOBILE to m) }
                        ?: it.companyErrors)
            }
            return
        }
        _state.update { it.copy(step = SignUpStep.COMPANY, formError = null) }
    }

    /** Returns true if it went back a step, false if the screen should close. */
    fun back(): Boolean {
        if (_state.value.step != SignUpStep.COMPANY || _state.value.submitting) return false
        _state.update { it.copy(step = SignUpStep.ACCOUNT, formError = null) }
        return true
    }

    // -- Step 2: company profile, then create the account ------------------------------

    fun submit(onSignedIn: (Profile) -> Unit) {
        val s = _state.value
        if (s.submitting) return
        val errors = s.company.errors(includeMobile = false)
        if (errors.isNotEmpty()) {
            _state.update { it.copy(companyErrors = errors, formError = "Please correct the highlighted fields.") }
            return
        }
        val company = s.company.toProfile()

        _state.update { it.copy(submitting = true, formError = null) }
        viewModelScope.launch {
            try {
                // Catch a duplicate before Supabase does: its own error only says
                // "Database error saving new user". If the check itself fails, carry
                // on — the database still refuses a duplicate.
                val problem = try {
                    suppliers.registrationProblem(company.registrationNumber, company.csdNumber)
                } catch (e: Exception) {
                    null
                }
                if (problem != null) {
                    val field = if (problem.contains("CSD")) CompanyField.CSD_NUMBER else CompanyField.REGISTRATION_NUMBER
                    _state.update {
                        it.copy(submitting = false, companyErrors = it.companyErrors + (field to problem),
                            formError = problem)
                    }
                    return@launch
                }

                when (val result = auth.signUpSupplier(s.email, s.password, company)) {
                    is SignUpResult.SignedIn -> {
                        _state.update { it.copy(submitting = false) }
                        onSignedIn(result.profile)
                    }
                    SignUpResult.ConfirmEmail ->
                        _state.update { it.copy(submitting = false, step = SignUpStep.CHECK_EMAIL) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(submitting = false, formError = e.accountMessage()) }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

/**
 * Supplier Sign-up — Deliverable 3, section 5.3. The Supplier role is applied
 * automatically; the registration goes to the officers' Supplier
 * Registrations queue, status "awaiting verification".
 */
@Composable
fun SupplierSignUpScreen(
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onSignedIn: (Profile) -> Unit,
    viewModel: SupplierSignUpViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    // The phone's Back button goes from step 2 to step 1, not out of the form.
    BackHandler(enabled = state.step == SignUpStep.COMPANY) { viewModel.back() }

    AppScaffold(
        title = "Supplier sign-up",
        onBack = { if (!viewModel.back()) onBack() }
    ) {
        when (state.step) {
            SignUpStep.ACCOUNT -> AccountStep(state, viewModel, onSignIn)
            SignUpStep.COMPANY -> CompanyStep(state, viewModel, onSignedIn)
            SignUpStep.CHECK_EMAIL -> CheckEmailStep(state.email, onSignIn)
        }
    }
}

@Composable
private fun AccountStep(state: SupplierSignUpUiState, viewModel: SupplierSignUpViewModel, onSignIn: () -> Unit) {
    ScreenHeading(
        eyebrow = "Step 1 of 2",
        title = "Create your supplier account",
        subtitle = "The Supplier role is applied automatically."
    )
    AppTextField(
        label = "Email address",
        value = state.email,
        onValueChange = viewModel::onEmail,
        placeholder = "you@company.co.za",
        leadingIcon = Icons.Default.MailOutline,
        keyboardType = KeyboardType.Email,
        hint = "Your registration outcome is sent here.",
        error = state.emailError
    )
    MobileField(
        value = state.company.mobileNumber,
        error = state.companyErrors[CompanyField.MOBILE],
        enabled = true,
        onValueChange = { viewModel.onCompany(CompanyField.MOBILE, state.company.copy(mobileNumber = it)) }
    )
    AppTextField(
        label = "Password",
        value = state.password,
        onValueChange = viewModel::onPassword,
        placeholder = "At least 8 characters",
        leadingIcon = Icons.Default.Lock,
        keyboardType = KeyboardType.Password,
        isPassword = true,
        error = state.passwordError
    )
    AppTextField(
        label = "Confirm password",
        value = state.confirm,
        onValueChange = viewModel::onConfirm,
        placeholder = "Type it again",
        leadingIcon = Icons.Default.Lock,
        keyboardType = KeyboardType.Password,
        isPassword = true,
        error = state.confirmError
    )
    PrimaryButton(text = "Continue", onClick = viewModel::continueToCompany)
    TextAction(text = "Already registered? Sign in", color = AppColor.InfoInk, onClick = onSignIn)
}

@Composable
private fun CompanyStep(
    state: SupplierSignUpUiState,
    viewModel: SupplierSignUpViewModel,
    onSignedIn: (Profile) -> Unit
) {
    ScreenHeading(
        eyebrow = "Step 2 of 2",
        title = "Company profile",
        subtitle = "An officer checks these details against CIPC, CSD and SARS records."
    )
    CompanyProfileFields(
        form = state.company,
        errors = state.companyErrors,
        enabled = !state.submitting,
        showMobile = false,
        onChange = viewModel::onCompany
    )
    NoteBanner(
        title = "What happens next",
        text = "A procurement officer reviews your registration and you will see the outcome when you sign in. " +
            "Uploading compliance documents is not available in this version of the app yet.",
        tone = NoteTone.Info,
        icon = Icons.Default.Info
    )
    state.formError?.let { NoteBanner(text = it, tone = NoteTone.Danger, icon = Icons.Default.Warning) }
    PrimaryButton(
        text = "Create account",
        icon = Icons.Default.Check,
        loading = state.submitting,
        onClick = { viewModel.submit(onSignedIn) }
    )
    SecondaryButton(text = "Back", enabled = !state.submitting, onClick = { viewModel.back() })
}

@Composable
private fun CheckEmailStep(email: String, onSignIn: () -> Unit) {
    ScreenHeading(
        eyebrow = "Almost done",
        title = "Confirm your email",
        subtitle = "We sent a confirmation link to $email."
    )
    AppCard {
        Text(
            "Open the email and tap the link, then come back and sign in. Your registration has been " +
                "received and is already waiting for an officer.",
            style = AppType.Body
        )
        Spacer(Modifier.height(10.dp))
        Text("No email after a few minutes? Check your spam folder.", style = AppType.Meta)
    }
    PrimaryButton(text = "Go to sign in", icon = Icons.Default.Lock, onClick = onSignIn)
}
