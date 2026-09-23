package za.ac.tendertrack.ui.screens.account

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import za.ac.tendertrack.core.ActionState
import za.ac.tendertrack.core.Format
import za.ac.tendertrack.core.UiState
import za.ac.tendertrack.data.ServiceLocator
import za.ac.tendertrack.data.model.*
import za.ac.tendertrack.data.repo.SupplierAccountRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppType

/** Shown to the supplier as the expected decision time (D3 5.3). Change to your own service standard. */
const val EXPECTED_DECISION_WORKING_DAYS = 5

/** Wrapper so "no registration" can be a successful result. */
data class SupplierHome(val registration: SupplierRegistration?)

data class SupplierHomeUiState(
    val home: UiState<SupplierHome> = UiState.Loading,
    val editing: Boolean = false,
    val form: CompanyForm = CompanyForm(),
    val errors: Map<CompanyField, String> = emptyMap(),
    val formError: String? = null,
    val action: ActionState = ActionState.Idle
)

class SupplierHomeViewModel(
    private val repository: SupplierAccountRepository = ServiceLocator.supplierAccountRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SupplierHomeUiState())
    val state: StateFlow<SupplierHomeUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(home = UiState.Loading) }
        viewModelScope.launch {
            val result = try {
                UiState.Success(SupplierHome(repository.myRegistration()))
            } catch (e: Exception) {
                UiState.Error(e.accountMessage())
            }
            _state.update { it.copy(home = result) }
        }
    }

    // -- UPDATE: correct and resubmit (D3 "Registration not approved") --------------

    fun startEditing() {
        val registration = (_state.value.home as? UiState.Success)?.data?.registration ?: return
        _state.update { it.copy(editing = true, form = CompanyForm.from(registration), errors = emptyMap(), formError = null) }
    }

    fun cancelEditing() = _state.update { it.copy(editing = false, errors = emptyMap(), formError = null) }

    fun onForm(field: CompanyField?, form: CompanyForm) = _state.update {
        it.copy(form = form, errors = if (field == null) it.errors else it.errors - field, formError = null)
    }

    fun resubmit() {
        val s = _state.value
        if (s.action is ActionState.Running) return
        val errors = s.form.errors(includeMobile = true)
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors, formError = "Please correct the highlighted fields.") }
            return
        }
        _state.update { it.copy(action = ActionState.Running, formError = null) }
        viewModelScope.launch {
            try {
                val updated = repository.resubmit(s.form.toProfile())
                _state.update {
                    it.copy(home = UiState.Success(SupplierHome(updated)), editing = false,
                        action = ActionState.Succeeded("Registration resubmitted for review."))
                }
            } catch (e: Exception) {
                _state.update { it.copy(action = ActionState.Idle, formError = e.accountMessage()) }
            }
        }
    }

    fun clearAction() = _state.update { it.copy(action = ActionState.Idle) }
}

/**
 * Supplier Home — where a supplier lands after signing in. Shows the
 * registration's status as Deliverable 3, section 5.3 describes: "under
 * review" (reference, date, expected decision, where the outcome goes) or
 * "not approved" (the reason, numbered corrective steps, and a way to correct
 * and resubmit that keeps everything already submitted).
 */
@Composable
fun SupplierHomeScreen(
    supplierName: String?,
    onBrowseTenders: () -> Unit,
    onSignOut: () -> Unit,
    viewModel: SupplierHomeViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.action) {
        (state.action as? ActionState.Succeeded)?.let { snackbar.showSnackbar(it.message); viewModel.clearAction() }
    }

    AppScaffold(
        title = "TenderTrack",
        snackbarHostState = snackbar,
        actions = listOf(
            TopBarAction(Icons.Default.Refresh, "Refresh") { viewModel.load() },
            TopBarAction(Icons.AutoMirrored.Filled.Logout, "Sign out") { onSignOut() }
        )
    ) {
        val registration = ((state.home as? UiState.Success)?.data)?.registration
        ScreenHeading(
            eyebrow = "Supplier",
            title = registration?.companyName ?: "Welcome",
            subtitle = supplierName?.let { "Signed in as $it" }
        )

        when (val result = state.home) {
            is UiState.Loading -> LoadingState(message = "Loading your registration…")
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> {
                val reg = result.data.registration
                if (reg == null) {
                    EmptyState(
                        title = "No company profile",
                        message = "This account has no supplier registration. To register a company, sign out " +
                            "and use \"Supplier signs up here\".",
                        icon = Icons.Default.Business
                    )
                } else {
                    RegistrationStatus(reg)
                    when (reg.status) {
                        SupplierVerificationStatus.AWAITING_VERIFICATION -> NoteBanner(
                            title = "Under review",
                            text = "A procurement officer is checking your details against CIPC, CSD and SARS records.",
                            tone = NoteTone.Info,
                            icon = Icons.Default.HourglassTop
                        )
                        SupplierVerificationStatus.VERIFIED -> NoteBanner(
                            title = "Verified",
                            text = "Your company is verified on TenderTrack.",
                            tone = NoteTone.Info,
                            icon = Icons.Default.Verified
                        )
                        SupplierVerificationStatus.NOT_APPROVED -> NotApproved(reg, state, viewModel)
                    }
                    if (!state.editing) CompanyDetails(reg)
                }

                SecondaryButton(text = "Browse published tenders", icon = Icons.Default.Search, onClick = onBrowseTenders)
                FootNote("Still to come in the Supplier module: uploading compliance documents, following tenders and claiming an award code.")
            }
        }
    }
}

@Composable
private fun RegistrationStatus(reg: SupplierRegistration) {
    AppCard {
        CardHeader(
            title = "Registration",
            subtitle = "Reference ${reg.reference}",
            trailing = { StatusBadge(reg.status.supplierLabel(), reg.status.supplierTone()) }
        )
        Spacer(Modifier.height(10.dp))
        KeyValueRow("Submitted", Format.dateTime(reg.submittedAt))
        KeyValueRow("Documents", "${reg.documentsReceived} of ${reg.documentsRequired} received")
        if (reg.status == SupplierVerificationStatus.AWAITING_VERIFICATION) {
            KeyValueRow("Expected decision", "Within $EXPECTED_DECISION_WORKING_DAYS working days")
        }
        KeyValueRow("Outcome sent to", reg.contactEmail.ifBlank { "Your email" }, showDivider = false)
    }
}

@Composable
private fun NotApproved(reg: SupplierRegistration, state: SupplierHomeUiState, viewModel: SupplierHomeViewModel) {
    NoteBanner(
        title = "Not approved",
        text = reg.decisionReason ?: "The officer did not give a reason. Contact the department.",
        tone = NoteTone.Danger,
        icon = Icons.Default.ErrorOutline
    )
    SectionHeader("What to do next")
    AppCard {
        Text("1.  Read the reason above.", style = AppType.Body)
        Text("2.  Check your details against your CIPC registration and CSD profile.", style = AppType.Body)
        Text("3.  Correct them below and resubmit. Everything you submitted is kept.", style = AppType.Body)
    }

    if (!state.editing) {
        PrimaryButton(text = "Correct and resubmit", icon = Icons.Default.Edit, onClick = viewModel::startEditing)
        return
    }

    val busy = state.action is ActionState.Running
    SectionHeader("Correct your details")
    CompanyProfileFields(
        form = state.form,
        errors = state.errors,
        enabled = !busy,
        showMobile = true,
        onChange = viewModel::onForm
    )
    state.formError?.let { NoteBanner(text = it, tone = NoteTone.Danger, icon = Icons.Default.Warning) }
    PrimaryButton(text = "Resubmit for review", icon = Icons.Default.Check, loading = busy, onClick = viewModel::resubmit)
    SecondaryButton(text = "Cancel", enabled = !busy, onClick = viewModel::cancelEditing)
}
