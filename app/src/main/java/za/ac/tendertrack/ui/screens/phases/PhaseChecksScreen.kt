package za.ac.tendertrack.ui.screens.phases

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import za.ac.tendertrack.core.Format
import za.ac.tendertrack.core.UiState
import za.ac.tendertrack.data.model.ContractPhase
import za.ac.tendertrack.data.model.PhaseState
import za.ac.tendertrack.data.repo.OfficerAlertsStore
import za.ac.tendertrack.data.repo.PhaseRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens
import za.ac.tendertrack.ui.viewModelFactory

/*
 * Phase checks, for the procurement officer: every contract phase where the
 * supplier has sent a photo, grouped by what happens next. Opened from the
 * drawer, from the dashboard's In progress tile, or from an alert on a tender
 * card (then showing only that contract).
 */

enum class PhaseFilter(val label: String) {
    COMPARE("To compare"),
    PAYMENT("Approve payment"),
    AUDITOR("Waiting for auditor");

    fun matches(phase: ContractPhase): Boolean = when (this) {
        COMPARE -> phase.state == PhaseState.AWAITING_OFFICER
        PAYMENT -> phase.state == PhaseState.VERIFIED
        AUDITOR -> phase.state == PhaseState.AWAITING_SITE_CHECK
    }
}

data class PhaseChecksUiState(
    val phases: UiState<List<ContractPhase>> = UiState.Loading,
    val filter: PhaseFilter = PhaseFilter.COMPARE
) {
    private val all: List<ContractPhase> get() = (phases as? UiState.Success)?.data ?: emptyList()
    val visible: List<ContractPhase> get() = all.filter { filter.matches(it) }
    fun countOf(f: PhaseFilter): Int = all.count { f.matches(it) }
}

class PhaseChecksViewModel(
    private val tenderId: String?,
    private val repository: PhaseRepository = PhaseRepository.instance
) : ViewModel() {

    private val _state = MutableStateFlow(PhaseChecksUiState())
    val state: StateFlow<PhaseChecksUiState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            val result = try {
                UiState.Success(repository.officerQueue(tenderId))
            } catch (e: Exception) {
                UiState.Error(e.phaseMessage())
            }
            _state.update { current ->
                // Open on the first group that has something in it.
                val loaded = current.copy(phases = result)
                if (current.phases is UiState.Loading && loaded.countOf(current.filter) == 0) {
                    loaded.copy(filter = PhaseFilter.entries.firstOrNull { loaded.countOf(it) > 0 } ?: current.filter)
                } else loaded
            }
            OfficerAlertsStore.refresh(repository)
        }
    }

    fun onFilter(filter: PhaseFilter) = _state.update { it.copy(filter = filter) }
}

/**
 * The officer's phase checks. With [tenderId], every phase of that one contract;
 * without, the phases waiting for someone, across all contracts.
 */
@Composable
fun PhaseChecksScreen(
    tenderId: String?,
    onOpen: (ContractPhase) -> Unit,
    onBack: () -> Unit,
    onMenu: (() -> Unit)? = null
) {
    val viewModel: PhaseChecksViewModel = viewModel(
        key = "phase_checks_${tenderId ?: "all"}",
        factory = viewModelFactory { PhaseChecksViewModel(tenderId) }
    )
    val state by viewModel.state.collectAsState()

    // Coming back from a phase shows the list as it now is.
    LaunchedEffect(Unit) { viewModel.load() }

    AppScaffold(
        title = "Phase checks",
        onMenu = onMenu,
        onBack = if (onMenu == null) onBack else null,
        actions = listOf(TopBarAction(Icons.Default.Refresh, "Refresh") { viewModel.load() })
    ) {
        when (val result = state.phases) {
            is UiState.Loading -> LoadingState(message = "Loading phase checks…")
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> if (tenderId != null) {
                ContractPhases(result.data, onOpen)
            } else {
                Queue(state, viewModel, onOpen)
            }
        }
    }
}

@Composable
private fun ContractPhases(phases: List<ContractPhase>, onOpen: (ContractPhase) -> Unit) {
    val first = phases.firstOrNull()
    ScreenHeading(
        eyebrow = first?.referenceNumber ?: "Contract",
        title = "Contract phases",
        subtitle = first?.let { "${it.title} · ${it.supplierName}" }
    )
    if (phases.isEmpty()) {
        EmptyState(
            title = "No phases",
            message = "This tender has no contract phases. They are created when it is awarded.",
            icon = Icons.Default.TaskAlt
        )
        return
    }
    PhaseList(phases, forOfficer = true, onOpen = onOpen)
    Text("Tap a phase to see its photos.", style = AppType.Tiny)
}

@Composable
private fun Queue(state: PhaseChecksUiState, viewModel: PhaseChecksViewModel, onOpen: (ContractPhase) -> Unit) {
    ScreenHeading(
        title = "Phase checks",
        subtitle = "Compare each supplier's photo with the auditor's site photo, then approve payment"
    )
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)
    ) {
        PhaseFilter.entries.forEach { f ->
            FilterChip(
                text = "${f.label} (${state.countOf(f)})",
                selected = state.filter == f,
                onClick = { viewModel.onFilter(f) }
            )
        }
    }
    val visible = state.visible
    if (visible.isEmpty()) {
        EmptyState(
            title = "Nothing here",
            message = when (state.filter) {
                PhaseFilter.COMPARE -> "No phase has both a supplier photo and an auditor's site photo waiting for you."
                PhaseFilter.PAYMENT -> "No verified phase is waiting for \"Payment can be made\"."
                PhaseFilter.AUDITOR -> "No supplier photo is waiting for an auditor's site visit."
            },
            icon = Icons.Default.TaskAlt
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.ListGap)) {
            visible.forEach { phase -> PhaseQueueCard(phase) { onOpen(phase) } }
        }
    }
    NoteBanner(
        text = "A phase is verified only when the two photos show the same completed work. Verifying opens the next " +
            "phase for the supplier. Payment can then be approved for the verified phase.",
        tone = NoteTone.Neutral,
        icon = Icons.Default.Info
    )
}

@Composable
private fun PhaseQueueCard(phase: ContractPhase, onClick: () -> Unit) {
    AppCard(onClick = onClick) {
        CardHeader(
            title = phase.referenceNumber,
            subtitle = "${phase.heading} · ${phase.supplierName}",
            trailing = { StatusBadge(phase.state.officerLabel, phase.state.tone(), showDot = false) }
        )
        Spacer(Modifier.height(10.dp))
        KeyValueRow("Phase value", phase.valueLine())
        KeyValueRow("Supplier's photo", Format.dateTime(phase.supplierPhotoAt))
        KeyValueRow(
            "Auditor's site photo",
            if (phase.siteCheckedAt != null) Format.dateTime(phase.siteCheckedAt) else "Waiting for the site visit",
            valueColor = if (phase.siteCheckedAt != null) AppColor.Ink else AppColor.WarnInk,
            showDivider = phase.verifiedAt != null
        )
        if (phase.verifiedAt != null) {
            KeyValueRow("Verified", Format.dateTime(phase.verifiedAt), showDivider = false)
        }
    }
}
