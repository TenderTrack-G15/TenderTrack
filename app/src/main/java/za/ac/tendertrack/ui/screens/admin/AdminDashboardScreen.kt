package za.ac.tendertrack.ui.screens.admin

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import za.ac.tendertrack.core.Format
import za.ac.tendertrack.core.UiState
import za.ac.tendertrack.data.ServiceLocator
import za.ac.tendertrack.data.model.AdminOverview
import za.ac.tendertrack.data.model.AuditEntry
import za.ac.tendertrack.data.repo.AdminRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

class AdminDashboardViewModel(
    private val repository: AdminRepository = ServiceLocator.adminRepository
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<AdminOverview>>(UiState.Loading)
    val state: StateFlow<UiState<AdminOverview>> = _state.asStateFlow()

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = try {
                UiState.Success(repository.overview())
            } catch (e: Exception) {
                UiState.Error(e.adminMessage())
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

/**
 * Admin Dashboard — Deliverable 3, section 5.7. Tiles for the four
 * administrative duties (user accounts, roles and permissions, application
 * activity, audit logs), plus read-only oversight of tenders. There are
 * deliberately no tender actions here.
 */
@Composable
fun AdminDashboardScreen(
    adminName: String,
    onOpenAccounts: (AccountFilter) -> Unit,
    onOpenInvitations: () -> Unit,
    onOpenRoles: () -> Unit,
    onOpenAudit: () -> Unit,
    onOpenExport: () -> Unit,
    onSignOut: () -> Unit,
    viewModel: AdminDashboardViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    // Reloads whenever the dashboard comes back into view, so counts reflect
    // any change just made on another admin screen.
    LaunchedEffect(Unit) { viewModel.load() }

    AppScaffold(
        title = "TenderTrack",
        actions = listOf(
            TopBarAction(Icons.Default.Refresh, "Refresh") { viewModel.load() },
            TopBarAction(Icons.AutoMirrored.Filled.Logout, "Sign out") { onSignOut() }
        )
    ) {
        ScreenHeading(
            eyebrow = "Administrator",
            title = "Admin dashboard",
            subtitle = "Signed in as $adminName · accounts, roles, activity and audit logs"
        )

        when (val result = state) {
            is UiState.Loading -> LoadingState(message = "Loading accounts and activity…")
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> DashboardBody(
                overview = result.data,
                onOpenAccounts = onOpenAccounts,
                onOpenInvitations = onOpenInvitations,
                onOpenRoles = onOpenRoles,
                onOpenAudit = onOpenAudit,
                onOpenExport = onOpenExport
            )
        }
    }
}

@Composable
private fun DashboardBody(
    overview: AdminOverview,
    onOpenAccounts: (AccountFilter) -> Unit,
    onOpenInvitations: () -> Unit,
    onOpenRoles: () -> Unit,
    onOpenAudit: () -> Unit,
    onOpenExport: () -> Unit
) {
    // Tiles are laid out in rows of two directly (not through StatGrid), the
    // same way as the public dashboard.

    // -- 1. User accounts ------------------------------------------------------
    SectionHeader("User accounts")
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.GridGap)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.GridGap)) {
            StatTile(
                "Active accounts", "${overview.activeCount}",
                Modifier.weight(1f), "Across ${overview.rolesInUse} roles",
                onClick = { onOpenAccounts(AccountFilter.ACTIVE) }
            )
            StatTile(
                "Suspended", "${overview.suspendedCount}",
                Modifier.weight(1f), if (overview.suspendedCount == 0) "None" else "No access",
                alert = overview.suspendedCount > 0,
                onClick = { onOpenAccounts(AccountFilter.SUSPENDED) }
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.GridGap)) {
            StatTile(
                "Pending invitations", "${overview.pendingInvitations}",
                Modifier.weight(1f), "Awaiting a login",
                onClick = onOpenInvitations
            )
            StatTile(
                "Administrators", "${overview.activeAdministrators}",
                Modifier.weight(1f), "Active",
                onClick = { onOpenAccounts(AccountFilter.ADMINISTRATORS) }
            )
        }
    }

    // -- 2. Roles and permissions ---------------------------------------------
    SectionHeader("Roles and permissions")
    AppCard(onClick = onOpenRoles) {
        CardHeader(
            title = "Role and permission summary",
            subtitle = "What each of the seven roles may and may not do"
        )
        Spacer(Modifier.height(Dimens.SpaceSm))
        Text(
            "Roles are assigned per account and enforced by the database on every request (FR16).",
            style = AppType.Meta
        )
    }

    // -- 3. Application activity ----------------------------------------------
    SectionHeader("Application activity")
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.GridGap)) {
        StatTile(
            "Events today", "${overview.eventsToday}",
            Modifier.weight(1f), "Recorded in the audit trail",
            onClick = onOpenAudit
        )
        StatTile(
            "Last 7 days", "${overview.eventsThisWeek}",
            Modifier.weight(1f), "Status, payment and account changes",
            onClick = onOpenAudit
        )
    }
    AppCard {
        CardHeader(title = "Recent activity")
        Spacer(Modifier.height(Dimens.SpaceSm))
        if (overview.recentActivity.isEmpty()) {
            Text("Nothing recorded yet.", style = AppType.Meta)
        } else {
            overview.recentActivity.forEachIndexed { index, entry ->
                ActivityRow(entry, showDivider = index < overview.recentActivity.lastIndex)
            }
        }
        Spacer(Modifier.height(Dimens.SpaceSm))
        TextAction("Open the audit log", color = AppColor.InfoInk, onClick = onOpenAudit)
    }

    // -- 4. Oversight (read-only) ----------------------------------------------
    SectionHeader("Tender oversight")
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.GridGap)) {
        StatTile(
            "Open compliance flags", "${overview.openFlags}",
            Modifier.weight(1f), "Across ${overview.totalTenders} tenders",
            alert = overview.openFlags > 0
        )
        StatTile(
            "Award anomalies", "${overview.awardVarianceCount}",
            Modifier.weight(1f), "Award >10% from estimate",
            alert = overview.awardVarianceCount > 0
        )
    }

    // -- 5. Audit logs and data export -----------------------------------------
    SectionHeader("Audit logs and data")
    SecondaryButton(text = "Audit log", icon = Icons.Default.History, onClick = onOpenAudit)
    SecondaryButton(text = "Export tender and payment data", icon = Icons.Default.Download, onClick = onOpenExport)

    NoteBanner(
        title = "Backups",
        text = "Database backups and restores are run by Supabase on the server, not from this app.",
        tone = NoteTone.Neutral,
        icon = Icons.Default.Info
    )

    Text(
        "Administrators manage the platform, not procurement: there are no tender actions here.",
        style = AppType.Tiny,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

/** One audit entry: what happened, who did it, when. */
@Composable
fun ActivityRow(entry: AuditEntry, showDivider: Boolean) {
    KeyValueRow(
        key = "${entry.action} · ${entry.actor}",
        value = Format.dateTime(entry.createdAt),
        valueColor = AppColor.Muted,
        showDivider = showDivider
    )
}
