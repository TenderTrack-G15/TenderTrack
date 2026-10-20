package za.ac.tendertrack.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
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
import kotlinx.coroutines.launch
import za.ac.tendertrack.core.Format
import za.ac.tendertrack.core.UiState
import za.ac.tendertrack.core.friendlyMessage
import za.ac.tendertrack.data.ServiceLocator
import za.ac.tendertrack.data.model.DashboardSummary
import za.ac.tendertrack.data.model.OfficerAlerts
import za.ac.tendertrack.data.model.Profile
import za.ac.tendertrack.data.model.TenderStatus
import za.ac.tendertrack.data.repo.OfficerAlertsStore
import za.ac.tendertrack.data.repo.TenderRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens

class DashboardViewModel(
    private val tenderRepository: TenderRepository = ServiceLocator.tenderRepository
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<DashboardSummary>>(UiState.Loading)
    val state: StateFlow<UiState<DashboardSummary>> = _state.asStateFlow()

    /** Alerts shown on the tiles (phase checks, payments, new documents…). Null until loaded. */
    val alerts: StateFlow<OfficerAlerts?> = OfficerAlertsStore.alerts

    init { load() }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = try {
                UiState.Success(tenderRepository.dashboard())
            } catch (e: Exception) {
                UiState.Error(e.friendlyMessage())
            }
        }
        viewModelScope.launch { OfficerAlertsStore.refresh() }
    }
}

/**
 * Procurement Officer dashboard.
 *
 * Counts use the tender lifecycle vocabulary only, and the screen carries the
 * budget, fund-utilisation and flagged-compliance tiles the review asked for.
 *
 * Notices about tenders appear on the tiles, not under the bell: the Open for
 * bids tile counts bids submitted or changed on the eTender portal (with their
 * documents) and tenders past their closing date, the In progress tile counts
 * phases waiting for the officer (photos to compare, payments to approve), and
 * Registrations to verify counts companies that sent new documents. Each
 * tender's own alerts are on its card in All Tenders. The bell keeps
 * everything else.
 */
@Composable
fun DashboardScreen(
    profile: Profile?,
    onMenu: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenFlags: () -> Unit,
    onOpenSuppliers: () -> Unit,
    onOpenTenders: () -> Unit,
    onOpenFunds: () -> Unit,
    onOpenBudget: () -> Unit,
    /** Opens Phase checks. Without it, the In progress tile opens All Tenders. */
    onOpenPhaseChecks: (() -> Unit)? = null,
    viewModel: DashboardViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val alerts by viewModel.alerts.collectAsState()

    AppScaffold(
        title = "TenderTrack",
        onMenu = onMenu,
        actions = listOf(
            TopBarAction(Icons.Default.Refresh, "Refresh") { viewModel.load() },
            TopBarAction(
                Icons.Default.Notifications,
                "Notifications",
                badgeCount = (state as? UiState.Success)?.data?.unreadNotifications ?: 0
            ) { onOpenNotifications() }
        )
    ) {
        ScreenHeading(
            title = "Dashboard",
            subtitle = listOfNotNull(profile?.department, profile?.fullName).joinToString(" · ")
        )
        AnnouncementsBanner(AnnouncementViewer.STAFF)

        when (val s = state) {
            is UiState.Loading -> LoadingState()
            is UiState.Error -> ErrorState(s.message, onRetry = viewModel::load)
            is UiState.Success -> DashboardContent(
                summary = s.data,
                alerts = alerts,
                onOpenFlags = onOpenFlags,
                onOpenSuppliers = onOpenSuppliers,
                onOpenTenders = onOpenTenders,
                onOpenFunds = onOpenFunds,
                onOpenBudget = onOpenBudget,
                onOpenPhaseChecks = onOpenPhaseChecks ?: onOpenTenders
            )
        }
    }
}

@Composable
private fun ColumnScope.DashboardContent(
    summary: DashboardSummary,
    alerts: OfficerAlerts?,
    onOpenFlags: () -> Unit,
    onOpenSuppliers: () -> Unit,
    onOpenTenders: () -> Unit,
    onOpenFunds: () -> Unit,
    onOpenBudget: () -> Unit,
    onOpenPhaseChecks: () -> Unit
) {
    val totals = alerts?.totals
    val phaseActions = alerts?.officerActions ?: 0
    val closingPassed = totals?.closingPassed ?: 0
    val newBids = totals?.newBids ?: 0
    val newDocuments = totals?.newDocuments ?: 0

    // The tiles are laid out as plain rows of two. Your project's Kotlin and Compose
    // versions reject tiles passed to StatGrid as a list of lambdas
    // ("Argument type mismatch ... ComposableFunction1"), as in the Officer fix.
    Column {
        Eyebrow("Tender lifecycle")
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.GridGap)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.GridGap)) {
                StatTile(
                    "Registered", "${summary.count(TenderStatus.REGISTERED)}", Modifier.weight(1f),
                    "Not yet published", onClick = onOpenTenders
                )
                AlertTile(
                    "Open for bids", "${summary.count(TenderStatus.PUBLISHED)}", Modifier.weight(1f),
                    openForBidsFootnote(newBids, closingPassed, summary.closingWithin30Days),
                    alertCount = newBids + closingPassed, onClick = onOpenTenders
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.GridGap)) {
                StatTile(
                    "Under evaluation", "${summary.count(TenderStatus.UNDER_EVALUATION)}", Modifier.weight(1f),
                    if (summary.overdueEvaluations > 0) "${summary.overdueEvaluations} overdue" else "On schedule",
                    onClick = onOpenTenders
                )
                StatTile(
                    "Awarded", "${summary.count(TenderStatus.AWARDED)}", Modifier.weight(1f),
                    "${summary.unclaimedAwardCodes} awaiting claim", onClick = onOpenTenders
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.GridGap)) {
                AlertTile(
                    "In progress", "${summary.count(TenderStatus.IN_PROGRESS)}", Modifier.weight(1f),
                    phaseFootnote(alerts), alertCount = phaseActions,
                    onClick = if (phaseActions > 0 || (totals?.siteChecksWaiting ?: 0) > 0) onOpenPhaseChecks else onOpenTenders
                )
                StatTile(
                    "Completed", "${summary.count(TenderStatus.COMPLETED)}", Modifier.weight(1f),
                    "This financial year", onClick = onOpenTenders
                )
            }
        }
    }

    Column {
        Eyebrow("Budget and oversight")
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.GridGap)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.GridGap)) {
                StatTile(
                    "Budget allocated", Format.moneyCompact(summary.funds.allocated), Modifier.weight(1f),
                    "${summary.funds.financialYear} financial year", money = true, onClick = onOpenBudget
                )
                StatTile(
                    "Funds utilised", Format.moneyCompact(summary.funds.disbursed), Modifier.weight(1f),
                    "${Format.percent(summary.funds.disbursedFraction, 1)} of allocation",
                    money = true, onClick = onOpenFunds
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.GridGap)) {
                StatTile(
                    "Flagged for compliance", "${summary.openFlags}", Modifier.weight(1f),
                    if (summary.openFlags > 0) "Action required" else "Nothing open",
                    alert = summary.openFlags > 0, onClick = onOpenFlags
                )
                AlertTile(
                    "Registrations to verify", "${summary.registrationsToVerify}", Modifier.weight(1f),
                    when {
                        newDocuments > 0 -> "$newDocuments new ${if (newDocuments == 1) "document" else "documents"} to check"
                        summary.registrationsToVerify > 0 -> "Oldest ${summary.oldestRegistrationDays} days"
                        else -> "Queue is clear"
                    },
                    alertCount = totals?.companiesWithNewDocuments ?: 0,
                    onClick = onOpenSuppliers
                )
            }
        }
    }

    UtilisationCard(
        title = "Fund utilisation",
        caption = "${Format.money(summary.funds.disbursed)} of ${Format.money(summary.funds.allocated)} " +
            "allocated has been disbursed",
        fraction = summary.funds.disbursedFraction
    )

    Text(
        "Tender counts use the lifecycle states defined in FR2. Supplier verification has its own " +
            "separate statuses on the Supplier Registrations screen. A red number on a tile is how many " +
            "items are waiting for you there.",
        style = AppType.Tiny
    )
}

/** "1 new bid to check · 1 past closing", or how many close soon. */
private fun openForBidsFootnote(newBids: Int, closingPassed: Int, closingSoon: Int): String {
    val parts = buildList {
        if (newBids > 0) add("$newBids new ${if (newBids == 1) "bid" else "bids"} to check")
        if (closingPassed > 0) add("$closingPassed past closing")
    }
    return if (parts.isNotEmpty()) parts.joinToString(" · ") else "$closingSoon close in 30 days"
}

/** "2 to compare · 1 to pay", or what the auditor is still doing. */
private fun phaseFootnote(alerts: OfficerAlerts?): String {
    val totals = alerts?.totals ?: return "Active contracts"
    val parts = buildList {
        if (totals.phaseChecks > 0) add("${totals.phaseChecks} to compare")
        if (totals.paymentsToApprove > 0) add("${totals.paymentsToApprove} to approve for payment")
    }
    return when {
        parts.isNotEmpty() -> parts.joinToString(" · ")
        totals.siteChecksWaiting > 0 -> "${totals.siteChecksWaiting} waiting for a site check"
        else -> "Active contracts"
    }
}

/**
 * A stat tile with a count of items waiting for the officer in its top-right
 * corner. Built from the same AppCard and type styles as StatTile, so it looks
 * the same apart from the count.
 */
@Composable
private fun AlertTile(
    label: String,
    value: String,
    modifier: Modifier,
    footnote: String,
    alertCount: Int,
    onClick: () -> Unit
) {
    AppCard(modifier = modifier, onClick = onClick) {
        Row(verticalAlignment = Alignment.Top) {
            Text(label, style = AppType.StatLabel, minLines = 2, maxLines = 2, modifier = Modifier.weight(1f))
            if (alertCount > 0) {
                Spacer(Modifier.width(6.dp))
                CountPill(alertCount, BadgeTone.Danger)
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(value, style = AppType.StatValue.copy(color = AppColor.Ink), maxLines = 1)
        Spacer(Modifier.height(3.dp))
        Text(
            footnote,
            style = AppType.StatFoot.copy(color = if (alertCount > 0) AppColor.DangerInk else AppColor.MutedLight),
            maxLines = 2
        )
    }
}
