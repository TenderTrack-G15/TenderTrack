package za.ac.tendertrack.ui.screens.admin

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import za.ac.tendertrack.data.model.AuditEntry
import za.ac.tendertrack.data.repo.AdminRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens

private const val ALL_TYPES = "All record types"

/** Long logs are shown in pages of this size; the export always includes everything filtered. */
private const val PAGE = 50

data class AuditLogUiState(
    val entries: UiState<List<AuditEntry>> = UiState.Loading,
    val query: String = "",
    val type: String = ALL_TYPES,
    val shown: Int = PAGE
) {
    val types: List<String>
        get() = listOf(ALL_TYPES) +
            ((entries as? UiState.Success)?.data?.map { entityLabel(it.entityType) }?.distinct()?.sorted()
                ?: emptyList())

    val filtered: List<AuditEntry>
        get() = ((entries as? UiState.Success)?.data ?: emptyList()).filter { e ->
            (type == ALL_TYPES || entityLabel(e.entityType) == type) &&
                (query.isBlank() || e.action.contains(query, true) || e.detail.contains(query, true) ||
                    e.actor.contains(query, true))
        }
}

class AuditLogViewModel(
    private val repository: AdminRepository = ServiceLocator.adminRepository
) : ViewModel() {

    private val _state = MutableStateFlow(AuditLogUiState())
    val state: StateFlow<AuditLogUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(entries = UiState.Loading) }
        viewModelScope.launch {
            val result = try {
                UiState.Success(repository.auditLog())
            } catch (e: Exception) {
                UiState.Error(e.adminMessage())
            }
            _state.update { it.copy(entries = result, shown = PAGE) }
        }
    }

    fun onQuery(value: String) = _state.update { it.copy(query = value, shown = PAGE) }
    fun onType(value: String) = _state.update { it.copy(type = value, shown = PAGE) }
    fun showMore() = _state.update { it.copy(shown = it.shown + PAGE) }

    /** The filtered log as CSV, newest first. */
    fun csvOf(entries: List<AuditEntry>): String = buildString {
        appendLine(csvRow("date_time", "record_type", "action", "detail", "actor"))
        entries.forEach { appendLine(csvRow(it.createdAt, it.entityType, it.action, it.detail, it.actor)) }
    }
}

/**
 * Audit Log — the append-only record of every status change, payment,
 * verification and account change (FR3), searchable, filterable and
 * exportable (Deliverable 3, section 5.7). Read-only: nothing here can alter
 * or delete an entry.
 */
@Composable
fun AuditLogScreen(
    onBack: () -> Unit,
    viewModel: AuditLogViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun export() {
        val entries = state.filtered
        val ok = shareCsv(context, "tendertrack-audit-log.csv", viewModel.csvOf(entries))
        if (!ok) scope.launch { snackbar.showSnackbar("Could not open the share sheet.") }
    }

    AppScaffold(
        title = "Audit Log",
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = listOf(TopBarAction(Icons.Default.Download, "Export CSV") { export() })
    ) {
        SearchField(
            value = state.query,
            onValueChange = viewModel::onQuery,
            placeholder = "Search action, detail or person"
        )
        AppDropdownField(
            label = "Record type",
            selected = state.type,
            options = state.types,
            optionLabel = { it },
            onSelect = viewModel::onType
        )

        when (val result = state.entries) {
            is UiState.Loading -> LoadingState(message = "Loading the audit trail…")
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> {
                val filtered = state.filtered
                Text("${filtered.size} of ${result.data.size} entries · append-only", style = AppType.Meta)
                if (filtered.isEmpty()) {
                    EmptyState(
                        title = "No entries match",
                        message = "Try a different search or record type.",
                        icon = Icons.Default.History
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.ListGap)) {
                        filtered.take(state.shown).forEach { entry -> AuditCard(entry) }
                    }
                    if (filtered.size > state.shown) {
                        SecondaryButton(
                            text = "Show ${minOf(PAGE, filtered.size - state.shown)} more",
                            onClick = viewModel::showMore
                        )
                    }
                    SecondaryButton(
                        text = "Export ${filtered.size} entries (CSV)",
                        icon = Icons.Default.Download,
                        onClick = { export() }
                    )
                }
            }
        }
    }
}

@Composable
private fun AuditCard(entry: AuditEntry) {
    AppCard {
        CardHeader(
            title = entry.action,
            subtitle = "${entry.actor} · ${Format.dateTime(entry.createdAt)}",
            trailing = { StatusBadge(entityLabel(entry.entityType), BadgeTone.Neutral, showDot = false) }
        )
        if (entry.detail.isNotBlank()) {
            Spacer(Modifier.height(Dimens.SpaceSm))
            Text(entry.detail, style = AppType.Body.copy(color = AppColor.InkSoft))
        }
    }
}
