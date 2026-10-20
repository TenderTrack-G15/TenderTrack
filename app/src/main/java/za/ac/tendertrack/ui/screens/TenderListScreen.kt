package za.ac.tendertrack.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
import za.ac.tendertrack.core.friendlyMessage
import za.ac.tendertrack.data.ServiceLocator
import za.ac.tendertrack.data.model.OfficerAlerts
import za.ac.tendertrack.data.model.Tender
import za.ac.tendertrack.data.model.TenderAlert
import za.ac.tendertrack.data.model.TenderStatus
import za.ac.tendertrack.data.repo.OfficerAlertsStore
import za.ac.tendertrack.data.repo.TenderRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.screens.phases.badgeTone
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens

data class TenderListUiState(
    val tenders: UiState<List<Tender>> = UiState.Loading,
    val query: String = "",
    val statusFilter: TenderStatus? = null
) {
    /** Search and filter applied together, so the count on screen is always right. */
    val visible: List<Tender>
        get() {
            val all = tenders.let { (it as? UiState.Success)?.data } ?: return emptyList()
            return all.filter { tender ->
                val matchesQuery = query.isBlank() ||
                    tender.referenceNumber.contains(query, true) ||
                    tender.title.contains(query, true) ||
                    tender.department.contains(query, true) ||
                    tender.category.contains(query, true)
                val matchesStatus = statusFilter == null || tender.status == statusFilter
                matchesQuery && matchesStatus
            }
        }
}

class TenderListViewModel(
    private val repository: TenderRepository = ServiceLocator.tenderRepository
) : ViewModel() {

    private val _state = MutableStateFlow(TenderListUiState())
    val state: StateFlow<TenderListUiState> = _state.asStateFlow()

    /** Each tender's alerts, shown on its card (the bell no longer carries tender notices). */
    val alerts: StateFlow<OfficerAlerts?> = OfficerAlertsStore.alerts

    init { load() }

    fun load() {
        _state.update { it.copy(tenders = UiState.Loading) }
        viewModelScope.launch {
            val result = try {
                UiState.Success(repository.list())
            } catch (e: Exception) {
                UiState.Error(e.friendlyMessage())
            }
            _state.update { it.copy(tenders = result) }
        }
        viewModelScope.launch { OfficerAlertsStore.refresh() }
    }

    fun onQueryChange(value: String) = _state.update { it.copy(query = value) }
    fun onStatusFilter(status: TenderStatus?) = _state.update { it.copy(statusFilter = status) }
}

/**
 * All tenders, with search and status filters, and the entry point to Register.
 * Each card shows that tender's alerts; a phase alert opens its Phase checks.
 */
@Composable
fun TenderListScreen(
    onMenu: () -> Unit,
    onOpenTender: (String) -> Unit,
    onRegisterTender: () -> Unit,
    /** Opens Phase checks for one tender. Without it, a phase alert opens the tender. */
    onOpenPhaseChecks: ((String) -> Unit)? = null,
    viewModel: TenderListViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val alerts by viewModel.alerts.collectAsState()

    AppScaffold(
        title = "All Tenders",
        onMenu = onMenu,
        actions = listOf(TopBarAction(Icons.Default.Add, "Register tender") { onRegisterTender() })
    ) {
        SearchField(
            value = state.query,
            onValueChange = viewModel::onQueryChange,
            placeholder = "Search by reference, title or department"
        )

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)
        ) {
            FilterChip(text = "All", selected = state.statusFilter == null, onClick = { viewModel.onStatusFilter(null) })
            TenderStatus.lifecycle.forEach { status ->
                FilterChip(
                    text = status.displayName,
                    selected = state.statusFilter == status,
                    onClick = { viewModel.onStatusFilter(status) }
                )
            }
        }

        when (val result = state.tenders) {
            is UiState.Loading -> LoadingState()
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> {
                val visible = state.visible
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${visible.size} of ${result.data.size} tenders", style = AppType.Meta)
                }

                if (visible.isEmpty()) {
                    EmptyState(
                        title = "No tenders match",
                        message = "Try a different search term, or clear the status filter.",
                        icon = Icons.Default.Description
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.ListGap)) {
                        visible.forEach { tender ->
                            TenderCard(
                                tender = tender,
                                alerts = alerts?.forTender(tender.id).orEmpty(),
                                onAlert = { alert ->
                                    if (alert.isPhaseAlert && onOpenPhaseChecks != null) onOpenPhaseChecks(tender.id)
                                    else onOpenTender(tender.id)
                                },
                                onOpenPhases = onOpenPhaseChecks?.let { open -> { open(tender.id) } }
                            ) { onOpenTender(tender.id) }
                        }
                    }
                }

                Spacer(Modifier.height(Dimens.SpaceXs))
                PrimaryButton("Register a new tender", icon = Icons.Default.Add, onClick = onRegisterTender)
            }
        }
    }
}

/**
 * One tender in a list. Used on this screen and on Record Payment. [alerts] are
 * the tender's notices (photos to compare, payment to approve, closing date
 * passed…), shown at the top of the card; tapping one calls [onAlert].
 * [onOpenPhases] adds "Contract phases and photos" to an awarded contract.
 */
@Composable
fun TenderCard(
    tender: Tender,
    alerts: List<TenderAlert> = emptyList(),
    onAlert: ((TenderAlert) -> Unit)? = null,
    onOpenPhases: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    AppCard(onClick = onClick, accent = if (alerts.any { it.tone == "danger" }) AppColor.DangerBar else null) {
        CardHeader(
            title = tender.referenceNumber,
            subtitle = "${tender.title} · ${tender.department}",
            trailing = { StatusBadge(tender.status.displayName, tender.status.tone()) }
        )
        if (alerts.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                alerts.forEach { alert ->
                    StatusBadge(
                        text = alert.label,
                        tone = alert.badgeTone(),
                        modifier = if (onAlert != null) Modifier.clickable { onAlert(alert) } else Modifier
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        KeyValueRow("Estimated budget", Format.money(tender.estimatedBudget))
        if (tender.awardedValue != null) {
            KeyValueRow("Awarded value", Format.money(tender.awardedValue))
            KeyValueRow(
                "Paid to date",
                "${Format.money(tender.paidToDate)} (${Format.percent(tender.utilisation)})"
            )
        }
        KeyValueRow(
            key = if (tender.status == TenderStatus.PUBLISHED) "Closing date" else "Closed",
            value = Format.dateTime(tender.closingDate),
            valueColor = if (tender.status == TenderStatus.PUBLISHED &&
                (Format.daysUntil(tender.closingDate) ?: 99) <= 14
            ) AppColor.WarnInk else AppColor.Ink,
            showDivider = tender.openFlagCount > 0
        )
        if (tender.openFlagCount > 0) {
            KeyValueRow(
                "Compliance",
                "${tender.openFlagCount} open flag",
                valueColor = AppColor.DangerInk,
                showDivider = false
            )
        }
        if (tender.awardedValue != null) {
            Spacer(Modifier.height(10.dp))
            ProgressBar(
                tender.utilisation,
                color = if (tender.utilisation >= 1f) AppColor.SuccessBar else AppColor.WarnBar
            )
        }
        val hasContract = tender.status == TenderStatus.AWARDED || tender.status == TenderStatus.IN_PROGRESS ||
            tender.status == TenderStatus.COMPLETED
        if (onOpenPhases != null && hasContract) {
            Spacer(Modifier.height(4.dp))
            TextAction(text = "Contract phases and photos", color = AppColor.InfoInk, onClick = onOpenPhases)
        }
    }
}

/** Maps a lifecycle state onto a badge treatment, in one place. */
fun TenderStatus.tone(): BadgeTone = when (this) {
    TenderStatus.REGISTERED -> BadgeTone.Neutral
    TenderStatus.PUBLISHED -> BadgeTone.Success
    TenderStatus.UNDER_EVALUATION -> BadgeTone.Warning
    TenderStatus.AWARDED -> BadgeTone.Success
    TenderStatus.IN_PROGRESS -> BadgeTone.Info
    TenderStatus.COMPLETED -> BadgeTone.Neutral
}
