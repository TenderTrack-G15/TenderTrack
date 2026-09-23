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
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens

/** The counts on the Auditor's home screen. */
data class AuditorSummary(
    val records: List<TenderRecord>,
    val reports: List<ComplianceReport>,
    val logEntries: Int
) {
    val awarded: Int get() = records.count { it.awardedAt != null }
    val open: Int get() = records.count { it.status == TenderStatus.PUBLISHED }
    val underEvaluation: Int get() = records.count { it.status == TenderStatus.UNDER_EVALUATION }
    val notCompliant: Int get() = reports.count { !it.compliant }
    val checksFailed: Int get() = reports.sumOf { it.failed }
}

class AuditorHomeViewModel(
    private val repository: AuditorRepository = ServiceLocator.auditorRepository
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
                        logEntries = repository.auditLogs().size
                    )
                )
            } catch (e: Exception) {
                UiState.Error(e.auditorMessage())
            }
        }
    }
}

/**
 * The Auditor's home screen. The Auditor observes and verifies: every screen
 * below is read-only, and the account has no permission to create, change or
 * delete any record.
 */
@Composable
fun AuditorHomeScreen(
    auditorName: String,
    onOpenRecords: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenCompliance: () -> Unit,
    onSignOut: () -> Unit,
    viewModel: AuditorHomeViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) { viewModel.load() }

    AppScaffold(
        title = "TenderTrack",
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

        when (val result = state) {
            is UiState.Loading -> LoadingState(message = "Loading records…")
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> {
                val summary = result.data

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
                        "records. The database gives this account no write permission at all.",
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
