package za.ac.tendertrack.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
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
import kotlinx.coroutines.launch
import za.ac.tendertrack.core.Format
import za.ac.tendertrack.core.UiState
import za.ac.tendertrack.core.friendlyMessage
import za.ac.tendertrack.data.ServiceLocator
import za.ac.tendertrack.data.model.DashboardSummary
import za.ac.tendertrack.data.model.Profile
import za.ac.tendertrack.data.model.TenderStatus
import za.ac.tendertrack.data.repo.TenderRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppType

class DashboardViewModel(
    private val tenderRepository: TenderRepository = ServiceLocator.tenderRepository
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<DashboardSummary>>(UiState.Loading)
    val state: StateFlow<UiState<DashboardSummary>> = _state.asStateFlow()

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
    }
}

/**
 * Procurement Officer dashboard.
 *
 * Counts use the tender lifecycle vocabulary only, and the screen carries the
 * budget, fund-utilisation and flagged-compliance tiles the review asked for.
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
    viewModel: DashboardViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

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

        when (val s = state) {
            is UiState.Loading -> LoadingState()
            is UiState.Error -> ErrorState(s.message, onRetry = viewModel::load)
            is UiState.Success -> DashboardContent(
                summary = s.data,
                onOpenFlags = onOpenFlags,
                onOpenSuppliers = onOpenSuppliers,
                onOpenTenders = onOpenTenders,
                onOpenFunds = onOpenFunds
            )
        }
    }
}

@Composable
private fun ColumnScope.DashboardContent(
    summary: DashboardSummary,
    onOpenFlags: () -> Unit,
    onOpenSuppliers: () -> Unit,
    onOpenTenders: () -> Unit,
    onOpenFunds: () -> Unit
) {
    Column {
        Eyebrow("Tender lifecycle")
        Spacer(Modifier.height(10.dp))
        StatGrid(
            listOf<StatGridTile>(
                { m ->
                    StatTile("Registered", "${summary.count(TenderStatus.REGISTERED)}",
                        m, "Not yet published", onClick = onOpenTenders)
                },
                { m ->
                    StatTile("Open for bids", "${summary.count(TenderStatus.PUBLISHED)}",
                        m, "${summary.closingWithin30Days} close in 30 days", onClick = onOpenTenders)
                },
                { m ->
                    StatTile("Under evaluation", "${summary.count(TenderStatus.UNDER_EVALUATION)}",
                        m, if (summary.overdueEvaluations > 0) "${summary.overdueEvaluations} overdue" else "On schedule",
                        onClick = onOpenTenders)
                },
                { m ->
                    StatTile("Awarded", "${summary.count(TenderStatus.AWARDED)}",
                        m, "${summary.unclaimedAwardCodes} awaiting claim", onClick = onOpenTenders)
                },
                { m ->
                    StatTile("In progress", "${summary.count(TenderStatus.IN_PROGRESS)}",
                        m, "Active contracts", onClick = onOpenTenders)
                },
                { m ->
                    StatTile("Completed", "${summary.count(TenderStatus.COMPLETED)}",
                        m, "This financial year", onClick = onOpenTenders)
                }
            )
        )
    }

    Column {
        Eyebrow("Budget and oversight")
        Spacer(Modifier.height(10.dp))
        StatGrid(
            listOf<StatGridTile>(
                { m ->
                    StatTile("Budget allocated", Format.moneyCompact(summary.funds.allocated),
                        m, "${summary.funds.financialYear} financial year", money = true, onClick = onOpenFunds)
                },
                { m ->
                    StatTile("Funds utilised", Format.moneyCompact(summary.funds.disbursed),
                        m, "${Format.percent(summary.funds.disbursedFraction, 1)} of allocation",
                        money = true, onClick = onOpenFunds)
                },
                { m ->
                    StatTile("Flagged for compliance", "${summary.openFlags}",
                        m, if (summary.openFlags > 0) "Action required" else "Nothing open",
                        alert = summary.openFlags > 0, onClick = onOpenFlags)
                },
                { m ->
                    StatTile("Registrations to verify", "${summary.registrationsToVerify}",
                        m,
                        if (summary.registrationsToVerify > 0) "Oldest ${summary.oldestRegistrationDays} days"
                        else "Queue is clear",
                        onClick = onOpenSuppliers)
                }
            )
        )
    }

    UtilisationCard(
        title = "Fund utilisation",
        caption = "${Format.money(summary.funds.disbursed)} of ${Format.money(summary.funds.allocated)} " +
            "allocated has been disbursed",
        fraction = summary.funds.disbursedFraction
    )

    Text(
        "Tender counts use the lifecycle states defined in FR2. Supplier verification has its own " +
            "separate statuses on the Supplier Registrations screen.",
        style = AppType.Tiny
    )
}
