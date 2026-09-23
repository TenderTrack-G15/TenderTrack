package za.ac.tendertrack.ui.screens.auditor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import za.ac.tendertrack.core.UiState
import za.ac.tendertrack.data.ServiceLocator
import za.ac.tendertrack.data.model.ComplianceReport
import za.ac.tendertrack.data.repo.AuditorRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens

data class ComplianceUiState(
    val reports: UiState<List<ComplianceReport>> = UiState.Loading,
    val onlyProblems: Boolean = false
) {
    val visible: List<ComplianceReport>
        get() = ((reports as? UiState.Success)?.data ?: emptyList())
            .filter { !onlyProblems || !it.compliant }
            .sortedByDescending { it.failed }
}

class ComplianceReportsViewModel(
    private val repository: AuditorRepository = ServiceLocator.auditorRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ComplianceUiState())
    val state: StateFlow<ComplianceUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(reports = UiState.Loading) }
        viewModelScope.launch {
            val result = try {
                UiState.Success(repository.complianceReports())
            } catch (e: Exception) {
                UiState.Error(e.auditorMessage())
            }
            _state.update { it.copy(reports = result) }
        }
    }

    fun onOnlyProblems(v: Boolean) = _state.update { it.copy(onlyProblems = v) }
}

/**
 * Compliance Reports — for every tender: were the required documents
 * submitted, were the procurement rules followed, were the deadlines
 * respected, and were the proper approvals obtained.
 */
@Composable
fun ComplianceReportsScreen(
    onBack: () -> Unit,
    onOpenRecord: (String) -> Unit,
    viewModel: ComplianceReportsViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    AppScaffold(title = "Compliance Reports", onBack = onBack) {
        ScreenHeading(
            eyebrow = "Auditor",
            title = "Compliance",
            subtitle = "Checked against the records themselves, not against anyone's word for it."
        )

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)
        ) {
            FilterChip(text = "All tenders", selected = !state.onlyProblems, onClick = { viewModel.onOnlyProblems(false) })
            FilterChip(text = "Needs attention", selected = state.onlyProblems, onClick = { viewModel.onOnlyProblems(true) })
        }

        when (val result = state.reports) {
            is UiState.Loading -> LoadingState(message = "Checking the records…")
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> {
                val visible = state.visible
                Text("${visible.size} of ${result.data.size} tenders", style = AppType.Meta)
                if (visible.isEmpty()) {
                    EmptyState(
                        title = "Nothing to show",
                        message = "No tender has a failed check.",
                        icon = Icons.Default.FactCheck
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.ListGap)) {
                        visible.forEach { report ->
                            AppCard(onClick = { onOpenRecord(report.tenderId) }) {
                                CardHeader(
                                    title = report.reference,
                                    subtitle = report.title,
                                    trailing = {
                                        StatusBadge(
                                            if (report.compliant) "Compliant" else "${report.failed} not met",
                                            if (report.compliant) BadgeTone.Success else BadgeTone.Danger
                                        )
                                    }
                                )
                                Spacer(Modifier.height(Dimens.SpaceSm))
                                Text(
                                    report.checks.filter { it.result == za.ac.tendertrack.data.model.CheckResult.FAILED }
                                        .joinToString("; ") { it.title }
                                        .ifBlank { "${report.passed} checks met, ${report.pending} not yet due." },
                                    style = AppType.Meta
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
