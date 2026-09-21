package za.ac.tendertrack.ui.screens.citizen

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
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
import za.ac.tendertrack.core.UiState
import za.ac.tendertrack.core.Validate
import za.ac.tendertrack.data.ServiceLocator
import za.ac.tendertrack.data.model.CitizenReport
import za.ac.tendertrack.data.model.ReportCategory
import za.ac.tendertrack.data.model.ReportDraft
import za.ac.tendertrack.data.model.Tender
import za.ac.tendertrack.data.repo.PublicRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens
import za.ac.tendertrack.ui.viewModelFactory

// ---------------------------------------------------------------------------
// State + ViewModel
// ---------------------------------------------------------------------------

data class ReportConcernUiState(
    val tenders: UiState<List<Tender>> = UiState.Loading,
    val tender: Tender? = null,
    val category: ReportCategory? = null,
    val details: String = "",
    val contactEmail: String = "",
    val tenderError: String? = null,
    val categoryError: String? = null,
    val detailsError: String? = null,
    val contactError: String? = null,
    val formError: String? = null,
    val submitting: Boolean = false,
    /** Set once the report is accepted; the screen then shows the receipt. */
    val receipt: CitizenReport? = null
)

class ReportConcernViewModel(
    private val preselectedTenderId: String?,
    private val repository: PublicRepository = ServiceLocator.publicRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ReportConcernUiState())
    val state: StateFlow<ReportConcernUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(tenders = UiState.Loading) }
        viewModelScope.launch {
            try {
                // Completed tenders are included: a citizen may report non-delivery after the fact.
                val tenders = repository.tenders()
                _state.update {
                    it.copy(
                        tenders = UiState.Success(tenders),
                        tender = it.tender ?: tenders.firstOrNull { t -> t.id == preselectedTenderId }
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(tenders = UiState.Error(e.citizenMessage())) }
            }
        }
    }

    fun onTender(value: Tender) = _state.update { it.copy(tender = value, tenderError = null, formError = null) }
    fun onCategory(value: ReportCategory) = _state.update { it.copy(category = value, categoryError = null, formError = null) }
    fun onDetails(value: String) = _state.update {
        // Hard stop at the database limit so the counter never goes red silently.
        it.copy(details = value.take(MAX_DETAILS), detailsError = null, formError = null)
    }
    fun onContact(value: String) = _state.update { it.copy(contactEmail = value.trim(), contactError = null, formError = null) }

    /**
     * Validates on the device first (instant feedback), then submits. The
     * database function checks the same rules again, so a modified app cannot
     * bypass them.
     */
    fun submit() {
        val current = _state.value
        if (current.submitting) return

        val details = current.details.trim()
        val tenderError = if (current.tender == null) "Choose the tender you are reporting." else null
        val categoryError = if (current.category == null) "Choose what kind of concern this is." else null
        val detailsError = when {
            details.isBlank() -> "Describe the concern."
            details.length < MIN_DETAILS -> "Describe the concern in at least $MIN_DETAILS characters " +
                "(${details.length} so far)."
            else -> null
        }
        // The email is optional; only check it if one was typed.
        val contactError = if (current.contactEmail.isBlank()) null else Validate.email(current.contactEmail)

        if (tenderError != null || categoryError != null || detailsError != null || contactError != null) {
            _state.update {
                it.copy(
                    tenderError = tenderError,
                    categoryError = categoryError,
                    detailsError = detailsError,
                    contactError = contactError,
                    formError = "Please correct the highlighted fields."
                )
            }
            return
        }

        _state.update { it.copy(submitting = true, formError = null) }
        viewModelScope.launch {
            try {
                val receipt = repository.submitReport(
                    ReportDraft(
                        tenderId = current.tender!!.id,
                        category = current.category!!,
                        details = details,
                        contactEmail = current.contactEmail.ifBlank { null }
                    )
                )
                _state.update { it.copy(submitting = false, receipt = receipt) }
            } catch (e: Exception) {
                _state.update { it.copy(submitting = false, formError = e.citizenMessage()) }
            }
        }
    }

    companion object {
        const val MIN_DETAILS = 20
        const val MAX_DETAILS = 1000
    }
}

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

/**
 * Flag for Review — FR15. The citizen selects a tender, says what kind of
 * concern it is and describes it. Contact details are optional (POPIA). On
 * success the screen shows the reference the citizen needs to follow up.
 */
@Composable
fun ReportConcernScreen(
    tenderId: String?,
    onBack: () -> Unit,
    onTrack: (String) -> Unit
) {
    val viewModel: ReportConcernViewModel = viewModel(
        key = "public_report_${tenderId ?: "pick"}",
        factory = viewModelFactory { ReportConcernViewModel(tenderId) }
    )
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    AppScaffold(
        title = if (state.receipt == null) "Flag for Review" else "Report Received",
        onBack = onBack,
        snackbarHostState = snackbar
    ) {
        val receipt = state.receipt
        if (receipt != null) {
            ReceiptBody(receipt, snackbar, onTrack = { onTrack(receipt.reference) }, onDone = onBack)
        } else {
            when (val tenders = state.tenders) {
                is UiState.Loading -> LoadingState()
                is UiState.Error -> ErrorState(tenders.message, onRetry = viewModel::load)
                is UiState.Success -> FormBody(state, tenders.data, viewModel)
            }
        }
    }
}

@Composable
private fun FormBody(
    state: ReportConcernUiState,
    tenders: List<Tender>,
    viewModel: ReportConcernViewModel
) {
    ScreenHeading(
        eyebrow = "Public report",
        title = "Flag a tender for review",
        subtitle = "Tell the reviewers what looks wrong. You do not need an account."
    )

    // 1. Which tender ("The public can select a tender")
    AppDropdownField(
        label = "Tender",
        selected = state.tender,
        options = tenders,
        optionLabel = { "${it.referenceNumber} · ${it.title}" },
        onSelect = viewModel::onTender,
        placeholder = "Choose a published tender",
        error = state.tenderError
    )
    state.tender?.let { tender ->
        AppCard(background = AppColor.SurfaceMuted, border = AppColor.Line) {
            KeyValueRow("Department", tender.department)
            KeyValueRow("Status", tender.status.displayName)
            KeyValueRow(
                "Supplier",
                tender.awardedSupplierName ?: "Not yet awarded",
                showDivider = false
            )
        }
    }

    // 2. What kind of concern
    AppDropdownField(
        label = "Type of concern",
        selected = state.category,
        options = ReportCategory.entries.toList(),
        optionLabel = { it.displayName },
        onSelect = viewModel::onCategory,
        placeholder = "Choose one",
        hint = state.category?.explanation,
        error = state.categoryError
    )

    // 3. The description
    AppTextField(
        label = "What did you notice?",
        value = state.details,
        onValueChange = viewModel::onDetails,
        placeholder = "Dates, amounts and company names help the reviewers most.",
        hint = "${state.details.trim().length} / ${ReportConcernViewModel.MAX_DETAILS} characters · " +
            "minimum ${ReportConcernViewModel.MIN_DETAILS}. Please do not include your own personal details here.",
        error = state.detailsError,
        singleLine = false,
        minHeight = 140.dp
    )

    // 4. Optional contact (POPIA: only what is necessary)
    AppTextField(
        label = "Email address (optional)",
        value = state.contactEmail,
        onValueChange = viewModel::onContact,
        placeholder = "you@example.com",
        hint = "Only reviewers can see this. It is erased if you withdraw the report.",
        error = state.contactError,
        leadingIcon = Icons.Default.MailOutline,
        keyboardType = KeyboardType.Email
    )

    NoteBanner(
        title = "Your privacy",
        text = "Your report is not published. Only procurement officers and auditors can read it. " +
            "You will get a reference to check progress, add information or withdraw the report.",
        tone = NoteTone.Neutral,
        icon = Icons.Default.Shield
    )

    state.formError?.let { message ->
        NoteBanner(text = message, tone = NoteTone.Danger, icon = Icons.Default.Warning)
    }

    PrimaryButton(
        text = "Submit report",
        icon = Icons.Default.Flag,
        loading = state.submitting,
        onClick = viewModel::submit
    )
}

@Composable
private fun ReceiptBody(
    receipt: CitizenReport,
    snackbar: SnackbarHostState,
    onTrack: () -> Unit,
    onDone: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    NoteBanner(
        title = "Report received",
        text = "Thank you. Your report on ${receipt.tenderReference} has been sent to the reviewers.",
        tone = NoteTone.Success,
        icon = Icons.Default.CheckCircle
    )

    AppCard {
        Eyebrow("Your reference")
        Spacer(Modifier.height(Dimens.SpaceSm))
        Text(receipt.reference, style = AppType.H1.copy(fontSize = 26.sp, letterSpacing = 1.sp))
        Spacer(Modifier.height(Dimens.SpaceMd))
        KeyValueRow("Tender", "${receipt.tenderReference} · ${receipt.tenderTitle}")
        KeyValueRow("Type of concern", receipt.category.displayName)
        KeyValueRow("Status", receipt.status.displayName)
        KeyValueRow("Contact details held", if (receipt.hasContact) "Yes" else "No", showDivider = false)
    }

    NoteBanner(
        title = "Keep this reference private",
        text = "Write it down or copy it now. It is the only way to check progress, add information " +
            "or withdraw the report, and anyone who has it can do the same.",
        tone = NoteTone.Warning,
        icon = Icons.Default.Lock
    )

    SecondaryButton(
        text = "Copy reference",
        icon = Icons.Default.ContentCopy,
        onClick = {
            clipboard.setText(AnnotatedString(receipt.reference))
            scope.launch { snackbar.showSnackbar("Reference copied") }
        }
    )
    PrimaryButton(text = "Track this report", icon = Icons.Default.FindInPage, onClick = onTrack)
    TextAction(text = "Done", color = AppColor.Muted, onClick = onDone, modifier = Modifier.fillMaxWidth())
}
