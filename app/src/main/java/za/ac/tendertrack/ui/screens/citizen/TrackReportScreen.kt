package za.ac.tendertrack.ui.screens.citizen

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
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
import za.ac.tendertrack.data.ServiceLocator
import za.ac.tendertrack.data.model.CitizenReport
import za.ac.tendertrack.data.model.ReportStatus
import za.ac.tendertrack.data.repo.PublicRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens
import za.ac.tendertrack.ui.viewModelFactory

// ---------------------------------------------------------------------------
// State + ViewModel
// ---------------------------------------------------------------------------

data class TrackReportUiState(
    val referenceInput: String = "",
    val referenceError: String? = null,
    val searching: Boolean = false,
    /** True after a search that matched nothing. */
    val notFound: Boolean = false,
    /** A network or server failure during the search (not "no match"). */
    val lookupError: String? = null,
    val report: CitizenReport? = null,
    val information: String = "",
    val informationError: String? = null,
    val confirmWithdraw: Boolean = false,
    val action: ActionState = ActionState.Idle
)

class TrackReportViewModel(
    initialReference: String?,
    private val repository: PublicRepository = ServiceLocator.publicRepository
) : ViewModel() {

    private val _state = MutableStateFlow(TrackReportUiState(referenceInput = initialReference.orEmpty()))
    val state: StateFlow<TrackReportUiState> = _state.asStateFlow()

    init {
        // Arriving straight from the receipt: look the report up immediately.
        if (!initialReference.isNullOrBlank()) find()
    }

    fun onReference(value: String) = _state.update {
        it.copy(referenceInput = value.uppercase(), referenceError = null, notFound = false, lookupError = null)
    }

    fun onInformation(value: String) = _state.update {
        it.copy(information = value.take(MAX_INFORMATION), informationError = null)
    }

    // -- READ ------------------------------------------------------------------

    fun find() {
        val reference = normalise(_state.value.referenceInput)
        if (reference == null) {
            _state.update { it.copy(referenceError = "References look like CR-ABCDE-23456.") }
            return
        }
        _state.update {
            it.copy(referenceInput = reference, searching = true, notFound = false, lookupError = null, report = null)
        }
        viewModelScope.launch {
            try {
                val report = repository.findReport(reference)
                _state.update { it.copy(searching = false, report = report, notFound = report == null) }
            } catch (e: Exception) {
                _state.update { it.copy(searching = false, lookupError = e.citizenMessage()) }
            }
        }
    }

    // -- UPDATE ----------------------------------------------------------------

    fun addInformation() {
        val current = _state.value
        val report = current.report ?: return
        if (current.action is ActionState.Running) return

        val info = current.information.trim()
        val error = when {
            info.isBlank() -> "Type the information you want to add."
            info.length < MIN_INFORMATION -> "Add at least $MIN_INFORMATION characters (${info.length} so far)."
            else -> null
        }
        if (error != null) {
            _state.update { it.copy(informationError = error) }
            return
        }

        _state.update { it.copy(action = ActionState.Running) }
        viewModelScope.launch {
            try {
                val updated = repository.addInformation(report.reference, info)
                _state.update {
                    it.copy(
                        report = updated,
                        information = "",
                        action = ActionState.Succeeded("Your information was added to the report.")
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(action = ActionState.Failed(e.citizenMessage())) }
            }
        }
    }

    // -- DELETE (withdraw) -----------------------------------------------------

    fun requestWithdraw() = _state.update { it.copy(confirmWithdraw = true) }
    fun dismissWithdraw() = _state.update { it.copy(confirmWithdraw = false) }

    fun withdraw() {
        val report = _state.value.report ?: return
        _state.update { it.copy(confirmWithdraw = false, action = ActionState.Running) }
        viewModelScope.launch {
            try {
                val updated = repository.withdrawReport(report.reference)
                _state.update {
                    it.copy(
                        report = updated,
                        action = ActionState.Succeeded("Report withdrawn. Any contact details you gave were erased.")
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(action = ActionState.Failed(e.citizenMessage())) }
            }
        }
    }

    fun clearAction() = _state.update { it.copy(action = ActionState.Idle) }

    /**
     * Accepts "cr-abcde-23456", "CR ABCDE 23456" or "CRABCDE23456" and returns
     * CR-ABCDE-23456, or null if it cannot be a reference.
     */
    private fun normalise(raw: String): String? {
        val compact = raw.uppercase().filter { it.isLetterOrDigit() }
        if (compact.length != 12 || !compact.startsWith("CR")) return null
        return "CR-${compact.substring(2, 7)}-${compact.substring(7)}"
    }

    companion object {
        const val MIN_INFORMATION = 10
        const val MAX_INFORMATION = 1000
    }
}

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

/**
 * Track a Report — the citizen's own report, found by its reference.
 * READ: status and details. UPDATE: add information. DELETE: withdraw, which
 * also erases any contact details (POPIA). No account is involved.
 */
@Composable
fun TrackReportScreen(
    initialReference: String?,
    onBack: () -> Unit,
    onOpenTender: (String) -> Unit
) {
    val viewModel: TrackReportViewModel = viewModel(
        key = "public_track_${initialReference ?: "blank"}",
        factory = viewModelFactory { TrackReportViewModel(initialReference) }
    )
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    // Same success/failure feedback pattern as the officer screens.
    LaunchedEffect(state.action) {
        when (val a = state.action) {
            is ActionState.Succeeded -> { snackbar.showSnackbar(a.message); viewModel.clearAction() }
            is ActionState.Failed -> { snackbar.showSnackbar(a.message); viewModel.clearAction() }
            else -> Unit
        }
    }

    if (state.confirmWithdraw) {
        ConfirmDialog(
            title = "Withdraw this report?",
            message = "The reviewers will be told you withdrew it, and any contact details you gave " +
                "will be erased. You will not be able to add to it again.",
            confirmText = "Withdraw",
            danger = true,
            onConfirm = viewModel::withdraw,
            onDismiss = viewModel::dismissWithdraw
        )
    }

    AppScaffold(title = "Track a Report", onBack = onBack, snackbarHostState = snackbar) {
        ScreenHeading(
            eyebrow = "Public report",
            title = "Track a report",
            subtitle = "Enter the reference you were given when you submitted it."
        )

        AppTextField(
            label = "Report reference",
            value = state.referenceInput,
            onValueChange = viewModel::onReference,
            placeholder = "CR-XXXXX-XXXXX",
            leadingIcon = Icons.Default.FindInPage,
            error = state.referenceError
        )
        PrimaryButton(
            text = "Find report",
            icon = Icons.Default.Search,
            loading = state.searching,
            onClick = viewModel::find
        )

        when {
            state.lookupError != null -> ErrorState(state.lookupError!!, onRetry = viewModel::find)
            state.notFound -> NoteBanner(
                title = "No report found",
                text = "No report matches that reference. Check each character — references never " +
                    "use the letters O or I, or the digits 0 or 1.",
                tone = NoteTone.Warning,
                icon = Icons.Default.Warning
            )
            state.report != null -> ReportBody(state, state.report!!, viewModel, onOpenTender)
        }
    }
}

@Composable
private fun ReportBody(
    state: TrackReportUiState,
    report: CitizenReport,
    viewModel: TrackReportViewModel,
    onOpenTender: (String) -> Unit
) {
    val busy = state.action is ActionState.Running

    // -- Status ---------------------------------------------------------------
    AppCard {
        CardHeader(
            title = report.reference,
            subtitle = "${report.tenderReference} · ${report.tenderTitle}",
            trailing = { StatusBadge(report.status.displayName, report.status.tone()) }
        )
        Spacer(Modifier.height(10.dp))
        KeyValueRow("Type of concern", report.category.displayName)
        KeyValueRow("Submitted", Format.dateTime(report.submittedAt))
        KeyValueRow("Last updated", Format.dateTime(report.updatedAt))
        KeyValueRow("Contact details held", if (report.hasContact) "Yes" else "No")
        KeyValueRow(
            "Information added",
            "${report.additions} of ${CitizenReport.MAX_ADDITIONS}",
            showDivider = report.outcomeLabel != null
        )
        report.outcomeLabel?.let { outcome ->
            KeyValueRow("Outcome", outcome, valueColor = AppColor.SuccessInk, showDivider = false)
        }
    }

    NoteBanner(
        text = report.status.explanation,
        tone = when (report.status) {
            ReportStatus.RECEIVED -> NoteTone.Info
            ReportStatus.UNDER_REVIEW -> NoteTone.Warning
            ReportStatus.CLOSED -> NoteTone.Success
            ReportStatus.WITHDRAWN -> NoteTone.Neutral
        },
        icon = Icons.Default.Info
    )

    AppCard {
        CardHeader(title = "What you reported")
        Spacer(Modifier.height(Dimens.SpaceSm))
        Text(report.details, style = AppType.Body)
    }

    TextAction(
        text = "View the tender",
        color = AppColor.InfoInk,
        onClick = { onOpenTender(report.tenderId) }
    )

    if (!report.status.canChange) return

    // -- Update: add information ---------------------------------------------
    SectionHeader("Add information")
    if (report.additions >= CitizenReport.MAX_ADDITIONS) {
        NoteBanner(
            text = "This report already has the maximum of ${CitizenReport.MAX_ADDITIONS} additions.",
            tone = NoteTone.Neutral
        )
    } else {
        AppTextField(
            label = "Something you have noticed since",
            value = state.information,
            onValueChange = viewModel::onInformation,
            placeholder = "New dates, amounts or details",
            hint = "${state.information.trim().length} / ${TrackReportViewModel.MAX_INFORMATION} characters · " +
                "added to your report, not replacing it.",
            error = state.informationError,
            singleLine = false,
            minHeight = 110.dp,
            enabled = !busy
        )
        SecondaryButton(
            text = "Add to report",
            icon = Icons.Default.Add,
            enabled = !busy,
            onClick = viewModel::addInformation
        )
    }

    // -- Delete: withdraw ------------------------------------------------------
    SectionHeader("Withdraw")
    Text(
        "Changed your mind? Withdrawing tells the reviewers and erases any contact details you gave.",
        style = AppType.Meta
    )
    SecondaryButton(
        text = "Withdraw report",
        icon = Icons.Default.Delete,
        danger = true,
        enabled = !busy,
        onClick = viewModel::requestWithdraw
    )
}
