package za.ac.tendertrack.ui.screens.auditor

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
import za.ac.tendertrack.data.ServiceLocator
import za.ac.tendertrack.data.model.TenderRecord
import za.ac.tendertrack.data.model.TenderStatus
import za.ac.tendertrack.data.repo.AuditorRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens

data class TenderRecordsUiState(
    val records: UiState<List<TenderRecord>> = UiState.Loading,
    val query: String = "",
    val status: TenderStatus? = null
) {
    val visible: List<TenderRecord>
        get() = ((records as? UiState.Success)?.data ?: emptyList()).filter { r ->
            val matchesQuery = query.isBlank() ||
                r.referenceNumber.contains(query, true) || r.title.contains(query, true) ||
                r.department.contains(query, true) ||
                (r.awardedSupplierName?.contains(query, true) ?: false)
            matchesQuery && (status == null || r.status == status)
        }
}

class TenderRecordsViewModel(
    private val repository: AuditorRepository = ServiceLocator.auditorRepository
) : ViewModel() {

    private val _state = MutableStateFlow(TenderRecordsUiState())
    val state: StateFlow<TenderRecordsUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(records = UiState.Loading) }
        viewModelScope.launch {
            val result = try {
                UiState.Success(repository.tenderRecords())
            } catch (e: Exception) {
                UiState.Error(e.auditorMessage())
            }
            _state.update { it.copy(records = result) }
        }
    }

    fun onQuery(v: String) = _state.update { it.copy(query = v) }
    fun onStatus(v: TenderStatus?) = _state.update { it.copy(status = v) }
}

/**
 * Tender Records — the number, title, publication date, closing date, award
 * date and status of every tender. Read-only.
 */
@Composable
fun TenderRecordsScreen(
    onBack: () -> Unit,
    onOpenRecord: (String) -> Unit,
    viewModel: TenderRecordsViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    AppScaffold(title = "Tender Records", onBack = onBack) {
        SearchField(
            value = state.query,
            onValueChange = viewModel::onQuery,
            placeholder = "Search number, title, department or supplier"
        )

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)
        ) {
            // onClick by name: it is not FilterChip's last parameter.
            FilterChip(text = "All", selected = state.status == null, onClick = { viewModel.onStatus(null) })
            TenderStatus.entries.forEach { status ->
                FilterChip(
                    text = status.displayName,
                    selected = state.status == status,
                    onClick = { viewModel.onStatus(status) }
                )
            }
        }

        when (val result = state.records) {
            is UiState.Loading -> LoadingState(message = "Loading tender records…")
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> {
                val visible = state.visible
                Text("${visible.size} of ${result.data.size} tenders", style = AppType.Meta)
                if (visible.isEmpty()) {
                    EmptyState(
                        title = "No records match",
                        message = "Try a different search or status.",
                        icon = Icons.Default.Description
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.ListGap)) {
                        visible.forEach { record -> RecordCard(record) { onOpenRecord(record.id) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordCard(record: TenderRecord, onClick: () -> Unit) {
    AppCard(onClick = onClick) {
        CardHeader(
            title = record.referenceNumber,
            subtitle = record.title,
            trailing = { StatusBadge(record.status.displayName, record.status.auditTone()) }
        )
        Spacer(Modifier.height(10.dp))
        KeyValueRow("Department", record.department)
        KeyValueRow("Published", record.publishedAt?.let { Format.date(it) } ?: "Not published")
        KeyValueRow("Closing", Format.date(record.closingDate))
        KeyValueRow(
            "Awarded",
            record.awardedAt?.let { "${Format.date(it)} · ${record.awardedSupplierName ?: "—"}" } ?: "Not awarded",
            showDivider = false
        )
    }
}
