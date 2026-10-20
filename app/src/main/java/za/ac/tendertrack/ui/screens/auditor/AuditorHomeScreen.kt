package za.ac.tendertrack.ui.screens.auditor

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
import za.ac.tendertrack.core.UiState
import za.ac.tendertrack.data.ServiceLocator
import za.ac.tendertrack.data.model.*
import za.ac.tendertrack.data.repo.AuditorRepository
import za.ac.tendertrack.data.repo.PhaseRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens

/** The counts on the Auditor's home screen. */
data class AuditorSummary(
    val records: List<TenderRecord>,
    val reports: List<ComplianceReport>,
    val logEntries: Int,
    /** Contract phases waiting for the auditor's site photo (null if they could not be read). */
    val siteChecksWaiting: Int? = null
) {
    val awarded: Int get() = records.count { it.awardedAt != null }
    val open: Int get() = records.count { it.status == TenderStatus.PUBLISHED }
    val underEvaluation: Int get() = records.count { it.status == TenderStatus.UNDER_EVALUATION }
    val notCompliant: Int get() = reports.count { !it.compliant }
    val checksFailed: Int get() = reports.sumOf { it.failed }
}

class AuditorHomeViewModel(
    private val repository: AuditorRepository = ServiceLocator.auditorRepository,
    private val phases: PhaseRepository = PhaseRepository.instance
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<AuditorSummary>>(UiState.Loading)
    val state: StateFlow<UiState<AuditorSummary>> = _state.asStateFlow()

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = try {
                UiState.Success(
                    AuditorSummary(
                        records = repository.tenderRecords(),
                        reports = repository.complianceReports(),
                        logEntries = repository.auditLogs().size,
                        siteChecksWaiting = runCatching { phases.siteCheckQueue().waiting.size }.getOrNull()
                    )
                )
            } catch (e: Exception) {
                UiState.Error(e.auditorMessage())
            }
        }
    }
}

/**
 * The Auditor's home screen. The Auditor observes and verifies: every record is
 * read-only. The one thing an auditor adds is a site photo, confirming the
 * photo a supplier took of a contract phase (Site checks).
 */
@Composable
fun AuditorHomeScreen(
    auditorName: String,
    onOpenRecords: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenCompliance: () -> Unit,
    onSignOut: () -> Unit,
    /** Opens the auditor's navigation panel; the menu button shows when it is given. */
    onMenu: (() -> Unit)? = null,
    /** Opens Site checks: contract phases waiting for the auditor's own photo. */
    onOpenSiteChecks: (() -> Unit)? = null,
    viewModel: AuditorHomeViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) { viewModel.load() }

    AppScaffold(
        title = "TenderTrack",
        onMenu = onMenu,
        actions = listOf(
            TopBarAction(Icons.Default.Refresh, "Refresh") { viewModel.load() },
            TopBarAction(Icons.AutoMirrored.Filled.Logout, "Sign out") { onSignOut() }
        )
    ) {
        ScreenHeading(
            eyebrow = "Auditor",
            title = "Audit and verification",
            subtitle = "Signed in as $auditorName · read-only across every record"
        )
        AnnouncementsBanner(AnnouncementViewer.STAFF)

        when (val result = state) {
            is UiState.Loading -> LoadingState(message = "Loading records…")
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> {
                val summary = result.data

                if (onOpenSiteChecks != null && summary.siteChecksWaiting != null) {
                    SectionHeader("Site checks")
                    val waiting = summary.siteChecksWaiting
                    StatTile(
                        "Phases waiting for your site photo", "$waiting",
                        Modifier.fillMaxWidth(),
                        if (waiting > 0) "Visit the site and take your own photo" else "Nothing waiting",
                        alert = waiting > 0, onClick = onOpenSiteChecks
                    )
                    SecondaryButton(text = "Open site checks", icon = Icons.Default.PhotoCamera, onClick = onOpenSiteChecks)
                }

                SectionHeader("Tender records")
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.GridGap)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.GridGap)) {
                        StatTile("Tenders on record", "${summary.records.size}",
                            Modifier.weight(1f), "Every stage", onClick = onOpenRecords)
                        StatTile("Awarded", "${summary.awarded}",
                            Modifier.weight(1f), "With an award decision", onClick = onOpenRecords)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.GridGap)) {
                        StatTile("Open for bids", "${summary.open}",
                            Modifier.weight(1f), "Bidding still open", onClick = onOpenRecords)
                        StatTile("Under evaluation", "${summary.underEvaluation}",
                            Modifier.weight(1f), "Being scored", onClick = onOpenRecords)
                    }
                }
                SecondaryButton(text = "Open tender records", icon = Icons.Default.Description, onClick = onOpenRecords)

                SectionHeader("Compliance")
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.GridGap)) {
                    StatTile("Tenders with a problem", "${summary.notCompliant}",
                        Modifier.weight(1f), "At least one check not met",
                        alert = summary.notCompliant > 0, onClick = onOpenCompliance)
                    StatTile("Checks not met", "${summary.checksFailed}",
                        Modifier.weight(1f), "Across all tenders",
                        alert = summary.checksFailed > 0, onClick = onOpenCompliance)
                }
                SecondaryButton(text = "Open compliance reports", icon = Icons.Default.FactCheck, onClick = onOpenCompliance)

                SectionHeader("Audit logs")
                AppCard(onClick = onOpenLogs) {
                    CardHeader(
                        title = "Every recorded action",
                        subtitle = "${summary.logEntries} entries"
                    )
                    Spacer(Modifier.height(Dimens.SpaceSm))
                    Text(
                        "Status changes, awards, payments, verifications and account changes, each with who " +
                            "did it and when. Entries can be read but never edited or removed.",
                        style = AppType.Meta
                    )
                }
                SecondaryButton(text = "Open audit logs", icon = Icons.Default.History, onClick = onOpenLogs)

                NoteBanner(
                    title = "What an auditor cannot do",
                    text = "Create or edit tenders, submit bids, award contracts, approve payments or delete " +
                        "records. The only thing this account can add is a site photo with a note, under Site checks.",
                    tone = NoteTone.Neutral,
                    icon = Icons.Default.Shield
                )
                Text(
                    "Observe and verify.",
                    style = AppType.Tiny,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
