package za.ac.tendertrack.ui.screens.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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
import za.ac.tendertrack.data.model.*
import za.ac.tendertrack.data.repo.AuthRepository
import za.ac.tendertrack.data.repo.RegistrationRepository
import za.ac.tendertrack.data.repo.SampleRegistrationRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens

/*
 * Supplier registration for TenderTrack.
 *
 * A company registers on BOTH systems, on purpose:
 *   1. on the eTender portal (the government's record): company, contact,
 *      compliance, banking, capabilities and documents;
 *   2. here, for TenderTrack: the same email and password, the CSD and company
 *      registration numbers from the portal registration, and a 6-digit code
 *      emailed to the company's contact address.
 * A stolen password alone is therefore not enough to get a company into
 * TenderTrack, and the database refuses awards until both are done.
 */

enum class SignUpStep { DETAILS, CODE, DONE }

data class SupplierSignUpUiState(
    /** True while checking whether someone is already signed in. */
    val checking: Boolean = true,
    val step: SignUpStep = SignUpStep.DETAILS,
    /** Set when a supplier is already signed in (sent here from the home screen). */
    val signedInAs: Profile? = null,
    val codePending: Boolean = false,
    val email: String = "",
    val password: String = "",
    val csdNumber: String = "",
    val registrationNumber: String = "",
    val errors: Map<String, String> = emptyMap(),
    val sentTo: String = "",
    val code: String = "",
    val codeError: String? = null,
    val needNewCode: Boolean = false,
    val formError: String? = null,
    val notice: String? = null,
    val doneMessage: String = "",
    val submitting: Boolean = false
)

class SupplierSignUpViewModel(
    private val auth: AuthRepository = ServiceLocator.authRepository,
    private val registration: RegistrationRepository = RegistrationRepository.instance
) : ViewModel() {

    private val _state = MutableStateFlow(SupplierSignUpUiState())
    val state: StateFlow<SupplierSignUpUiState> = _state.asStateFlow()

    /** True when this screen signed the person in, so leaving it half-way signs them out again. */
    private var signedInHere = false

    init {
        viewModelScope.launch {
            val profile = runCatching { auth.currentProfile() }.getOrNull()
            if (profile?.role != UserRole.SUPPLIER) {
                _state.update { it.copy(checking = false) }
                return@launch
            }
            val status = runCatching { registration.status() }.getOrNull()
            _state.update {
                it.copy(
                    checking = false,
                    signedInAs = profile,
                    codePending = status?.codePending == true,
                    sentTo = status?.sentTo.orEmpty(),
                    step = if (status?.appRegistered == true) SignUpStep.DONE else it.step,
                    doneMessage = if (status?.appRegistered == true) "You are already registered for TenderTrack." else ""
                )
            }
        }
    }

    fun onEmail(v: String) = _state.update { it.copy(email = v.trim(), errors = it.errors - "email", formError = null) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, errors = it.errors - "password", formError = null) }
    fun onCsd(v: String) = _state.update { it.copy(csdNumber = v.uppercase(), errors = it.errors - "csd", formError = null) }
    fun onRegistrationNumber(v: String) =
        _state.update { it.copy(registrationNumber = v, errors = it.errors - "registration", formError = null) }
    fun onCode(v: String) = _state.update { it.copy(code = RegistrationInput.cleanCode(v), codeError = null, notice = null) }

    // -- Step 1: find the portal registration and send the code ------------------------

    fun sendCode() {
        val s = _state.value
        if (s.submitting) return
        val errors = buildMap {
            if (s.signedInAs == null) {
                Validate.email(s.email)?.let { put("email", it) }
                Validate.password(s.password)?.let { put("password", it) }
            }
            RegistrationInput.csd(s.csdNumber)?.let { put("csd", it) }
            RegistrationInput.registrationNumber(s.registrationNumber)?.let { put("registration", it) }
        }
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors) }
            return
        }
        _state.update { it.copy(submitting = true, formError = null) }
        viewModelScope.launch {
            try {
                if (_state.value.signedInAs == null) signInWithPortalAccount(s.email, s.password) ?: return@launch
                val sent = registration.start(s.csdNumber, s.registrationNumber)
                _state.update {
                    if (sent.already) it.copy(submitting = false, step = SignUpStep.DONE,
                        doneMessage = "You are already registered for TenderTrack.")
                    else it.copy(submitting = false, step = SignUpStep.CODE, sentTo = sent.sentTo, code = "",
                        codeError = null, needNewCode = false, notice = "A 6-digit code was sent to ${sent.sentTo}.")
                }
            } catch (e: Exception) {
                _state.update { it.copy(submitting = false, formError = e.shortMessage()) }
            }
        }
    }

    /** Signs in with the portal's email and password. Returns null (with a message shown) if it fails. */
    private suspend fun signInWithPortalAccount(email: String, password: String): Profile? {
        val profile = try {
            auth.signIn(email, password)
        } catch (e: Exception) {
            val raw = e.message.orEmpty()
            val message = when {
                raw.contains("Invalid login credentials", true) ->
                    "No eTender portal account has this email and password. Register your company on the " +
                        "eTender portal first, then come back."
                raw.contains("banned", true) -> "This account has been suspended. Contact the department."
                else -> e.shortMessage()
            }
            _state.update { it.copy(submitting = false, formError = message) }
            return null
        }
        if (profile.role != UserRole.SUPPLIER) {
            runCatching { auth.signOut() }
            _state.update {
                it.copy(submitting = false, formError = "This is not a supplier account. Government officials use " +
                    "the Government Official login.")
            }
            return null
        }
        signedInHere = true
        _state.update { it.copy(signedInAs = profile) }
        return profile
    }

    /** "I already have a code": straight to step 2. */
    fun useExistingCode() = _state.update { it.copy(step = SignUpStep.CODE, formError = null, notice = null) }

    // -- Step 2: the code from the email ---------------------------------------------------

    fun verify() {
        val s = _state.value
        if (s.submitting) return
        RegistrationInput.code(s.code)?.let { message ->
            _state.update { it.copy(codeError = message) }
            return
        }
        _state.update { it.copy(submitting = true, codeError = null, notice = null) }
        viewModelScope.launch {
            try {
                val result = registration.complete(s.code)
                _state.update {
                    if (result.ok) it.copy(submitting = false, step = SignUpStep.DONE, doneMessage = result.message)
                    else it.copy(submitting = false, code = "", codeError = result.message, needNewCode = result.needNewCode)
                }
            } catch (e: Exception) {
                _state.update { it.copy(submitting = false, codeError = e.shortMessage()) }
            }
        }
    }

    fun resend() {
        val s = _state.value
        if (s.submitting) return
        if (RegistrationInput.csd(s.csdNumber) != null || RegistrationInput.registrationNumber(s.registrationNumber) != null) {
            // Came straight to step 2 without typing the numbers: they are needed for a new code.
            _state.update { it.copy(step = SignUpStep.DETAILS, formError = "Enter your CSD and registration numbers to get a new code.") }
            return
        }
        _state.update { it.copy(submitting = true, codeError = null, notice = null) }
        viewModelScope.launch {
            try {
                val sent = registration.start(s.csdNumber, s.registrationNumber)
                _state.update {
                    it.copy(submitting = false, sentTo = sent.sentTo, needNewCode = false, code = "",
                        notice = "A new code was sent to ${sent.sentTo}. Earlier codes no longer work.")
                }
            } catch (e: Exception) {
                _state.update { it.copy(submitting = false, codeError = e.shortMessage()) }
            }
        }
    }

    // -- Leaving -------------------------------------------------------------------------------

    /** Returns true if it went back a step, false if the screen should close. */
    fun back(): Boolean {
        if (_state.value.step != SignUpStep.CODE || _state.value.submitting) return false
        _state.update { it.copy(step = SignUpStep.DETAILS, codeError = null, notice = null) }
        return true
    }

    /** Leaving before the end: an account this screen signed in is signed out again. */
    fun leave(then: () -> Unit) {
        if (signedInHere && _state.value.step != SignUpStep.DONE) {
            viewModelScope.launch {
                runCatching { auth.signOut() }
                then()
            }
        } else then()
    }

    fun finish(onSignedIn: (Profile) -> Unit) {
        viewModelScope.launch {
            val profile = runCatching { auth.currentProfile() }.getOrNull() ?: _state.value.signedInAs
            if (profile != null) onSignedIn(profile)
        }
    }

    private fun Throwable.shortMessage(): String =
        friendlyMessage().lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "Something went wrong. Please try again."
}

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

/**
 * Supplier registration for TenderTrack (Deliverable 3, section 5.3), opened
 * from "Register an account" on the Supplier login, or from the supplier's home
 * screen when the company has not finished registering here.
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
    BackHandler(enabled = state.step == SignUpStep.CODE) { viewModel.back() }

    AppScaffold(
        title = "Supplier registration",
        onBack = { if (!viewModel.back()) viewModel.leave(onBack) }
    ) {
        when {
            state.checking -> LoadingState(message = "One moment…")
            state.step == SignUpStep.DETAILS -> DetailsStep(state, viewModel) { viewModel.leave(onSignIn) }
            state.step == SignUpStep.CODE -> CodeStep(state, viewModel)
            else -> DoneStep(state) { viewModel.finish(onSignedIn) }
        }
    }
}

@Composable
private fun DetailsStep(state: SupplierSignUpUiState, viewModel: SupplierSignUpViewModel, onSignIn: () -> Unit) {
    ScreenHeading(
        eyebrow = "Step 1 of 2",
        title = "Register for TenderTrack",
        subtitle = "For companies already registered on the eTender portal"
    )
    NoteBanner(
        title = "Two registrations, for security",
        text = "First register your company on the eTender portal. Then register here with the same email and " +
            "password and the numbers from that registration. A 6-digit code is emailed to the company's " +
            "contact address to prove it is you. Both registrations are needed to claim an award.",
        tone = NoteTone.Info,
        icon = Icons.Default.Shield
    )

    val signedIn = state.signedInAs
    if (signedIn != null) {
        AppCard {
            KeyValueRow("Signed in as", signedIn.email, showDivider = false)
        }
    } else {
        AppTextField(
            label = "Email address",
            value = state.email,
            onValueChange = viewModel::onEmail,
            placeholder = "you@company.co.za",
            leadingIcon = Icons.Default.MailOutline,
            keyboardType = KeyboardType.Email,
            hint = "The sign-in email of your eTender portal registration.",
            error = state.errors["email"],
            enabled = !state.submitting
        )
        AppTextField(
            label = "Password",
            value = state.password,
            onValueChange = viewModel::onPassword,
            placeholder = "Your eTender portal password",
            leadingIcon = Icons.Default.Lock,
            keyboardType = KeyboardType.Password,
            isPassword = true,
            error = state.errors["password"],
            enabled = !state.submitting
        )
    }
    AppTextField(
        label = "CSD supplier number",
        value = state.csdNumber,
        onValueChange = viewModel::onCsd,
        placeholder = "MAAA0451236",
        leadingIcon = Icons.Default.Badge,
        error = state.errors["csd"],
        enabled = !state.submitting
    )
    AppTextField(
        label = "Company registration number",
        value = state.registrationNumber,
        onValueChange = viewModel::onRegistrationNumber,
        placeholder = "2019/451236/07",
        leadingIcon = Icons.Default.Business,
        error = state.errors["registration"],
        enabled = !state.submitting
    )

    state.formError?.let { NoteBanner(text = it, tone = NoteTone.Danger, icon = Icons.Default.Warning) }

    PrimaryButton(
        text = "Send verification code",
        icon = Icons.Default.MarkEmailRead,
        loading = state.submitting,
        onClick = viewModel::sendCode
    )
    if (state.codePending) {
        TextAction(text = "I already have a code", color = AppColor.InfoInk, onClick = viewModel::useExistingCode)
    }
    if (signedIn == null) {
        TextAction(text = "Already registered for TenderTrack? Sign in", color = AppColor.InfoInk, onClick = onSignIn)
    }
    if (RegistrationRepository.instance is SampleRegistrationRepository) {
        NoteBanner(
            title = "Sample data",
            text = "No Supabase project is configured. Sign in with any email starting with \"supplier\", and use " +
                "CSD ${SampleRegistrationRepository.SAMPLE_CSD}, registration number " +
                "${SampleRegistrationRepository.SAMPLE_REGISTRATION} and code ${SampleRegistrationRepository.SAMPLE_CODE}.",
            tone = NoteTone.Neutral
        )
    }
}

@Composable
private fun CodeStep(state: SupplierSignUpUiState, viewModel: SupplierSignUpViewModel) {
    ScreenHeading(
        eyebrow = "Step 2 of 2",
        title = "Enter the code",
        subtitle = if (state.sentTo.isNotBlank()) "Sent to ${state.sentTo}" else "Sent to the company's contact email"
    )
    Text(
        "Open the email \"Your TenderTrack verification code\" from the eTender portal (or the portal's Demo " +
            "mailbox) and type the 6 digits. The code works for 15 minutes.",
        style = AppType.Body.copy(color = AppColor.InkSoft)
    )
    state.notice?.let { NoteBanner(text = it, tone = NoteTone.Success, icon = Icons.Default.CheckCircle) }
    AppTextField(
        label = "Verification code",
        value = state.code,
        onValueChange = viewModel::onCode,
        placeholder = "6 digits",
        leadingIcon = Icons.Default.Key,
        keyboardType = KeyboardType.NumberPassword,
        hint = if (state.code.isEmpty()) null else "${state.code.length} of 6 digits",
        error = state.codeError,
        enabled = !state.submitting && !state.needNewCode
    )
    PrimaryButton(
        text = "Finish registering",
        icon = Icons.Default.Check,
        loading = state.submitting,
        enabled = state.code.length == RegistrationInput.CODE_LENGTH && !state.needNewCode,
        onClick = viewModel::verify
    )
    SecondaryButton(
        text = "Send a new code",
        icon = Icons.Default.Refresh,
        enabled = !state.submitting,
        onClick = viewModel::resend
    )
    TextAction(text = "Change the numbers", color = AppColor.InfoInk, onClick = { viewModel.back() })
    Spacer(Modifier.height(Dimens.SpaceSm))
}

@Composable
private fun DoneStep(state: SupplierSignUpUiState, onContinue: () -> Unit) {
    ScreenHeading(
        eyebrow = "Registered",
        title = "Welcome to TenderTrack",
        subtitle = state.signedInAs?.fullName
    )
    NoteBanner(
        title = "Registration complete",
        text = state.doneMessage.ifBlank { "Your company is registered for TenderTrack." },
        tone = NoteTone.Success,
        icon = Icons.Default.CheckCircle
    )
    Text(
        "Your company is now registered on the eTender portal and in TenderTrack. Follow tenders here, and if " +
            "you are awarded one, claim it under Awards with the code you are emailed. A procurement officer " +
            "still verifies your company's details.",
        style = AppType.Body.copy(color = AppColor.InkSoft)
    )
    PrimaryButton(text = "Continue to TenderTrack", icon = Icons.AutoMirrored.Filled.ArrowForward, onClick = onContinue)
}
