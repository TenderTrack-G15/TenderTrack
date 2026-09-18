package za.ac.tendertrack.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import za.ac.tendertrack.core.Format
import za.ac.tendertrack.core.UiState
import za.ac.tendertrack.core.friendlyMessage
import za.ac.tendertrack.data.ServiceLocator
import za.ac.tendertrack.data.model.ComplianceFlag
import za.ac.tendertrack.data.model.FlagSeverity
import za.ac.tendertrack.data.model.FlagStatus
import za.ac.tendertrack.data.repo.FlagRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens

data class FlagsUiState(
    val flags: UiState<List<ComplianceFlag>> = UiState.Loading,
    val filter: FlagStatus = FlagStatus.OPEN
) {
    val visible: List<ComplianceFlag>
        get() = (flags as? UiState.Success)?.data?.filter { it.status == filter } ?: emptyList()

    fun countOf(status: FlagStatus): Int =
        (flags as? UiState.Success)?.data?.count { it.status == status } ?: 0
}

class FlagsViewModel(
    private val repository: FlagRepository = ServiceLocator.flagRepository
) : ViewModel() {

    private val _state = MutableStateFlow(FlagsUiState())
    val state: StateFlow<FlagsUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(flags = UiState.Loading) }
        viewModelScope.launch {
            val result = try {
                UiState.Success(repository.list())
            } catch (e: Exception) {
                UiState.Error(e.friendlyMessage())
            }
            _state.update { it.copy(flags = result) }
        }
    }

    fun onFilter(status: FlagStatus) = _state.update { it.copy(filter = status) }
}

/** Flags & Compliance — FR6 to FR8. */
@Composable
fun FlagsScreen(
    onMenu: () -> Unit,
    onOpenFlag: (String) -> Unit,
    viewModel: FlagsViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    AppScaffold(
        title = "Flags & Compliance",
        onMenu = onMenu,
        actions = listOf(TopBarAction(Icons.Default.Refresh, "Refresh") { viewModel.load() })
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)
        ) {
            FlagStatus.entries.forEach { status ->
                FilterChip(
                    text = "${status.displayName} (${state.countOf(status)})",

                    selected = state.filter == status,
                    onClick = { viewModel.onFilter(status) }
                )
            }
        }

        when (val result = state.flags) {
            is UiState.Loading -> LoadingState()
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> {
                val visible = state.visible
                if (visible.isEmpty()) {
                    EmptyState(
                        title = "Nothing ${state.filter.displayName.lowercase()}",
                        message = "There are no compliance flags with this status.",
                        icon = Icons.Default.Flag
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.ListGap)) {
                        visible.forEach { flag -> FlagCard(flag) { onOpenFlag(flag.id) } }
                    }
                }
            }
        }
    }
}

@Composable
fun FlagCard(flag: ComplianceFlag, onClick: () -> Unit) {
    val ageDays = Format.daysSince(flag.raisedAt) ?: 0
    AppCard(onClick = onClick, accent = flag.severity.accent()) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)) {
            StatusBadge(flag.severity.displayName.uppercase(), flag.severity.tone(), showDot = false)
            Text(flag.reference, style = AppType.Tiny.copy(color = AppColor.Muted))
        }
        Spacer(Modifier.height(7.dp))
        Text(flag.title, style = AppType.CardTitle)
        Text("${flag.tenderReference} · ${flag.tenderTitle}", style = AppType.Meta)
        Spacer(Modifier.height(10.dp))
        KeyValueRow("Rule triggered", flag.ruleTriggered)
        KeyValueRow(
            "Raised",
            "${if (flag.raisedAutomatically) "Automatically" else "Manually"} · ${Format.date(flag.raisedAt)}"
        )
        KeyValueRow("Assigned to", flag.assignedTo ?: "Unassigned")
        KeyValueRow(
            "Status",
            if (flag.status == FlagStatus.RESOLVED) flag.status.displayName
            else "${flag.status.displayName} · $ageDays days",
            valueColor = when (flag.status) {
                FlagStatus.OPEN -> AppColor.DangerInk
                FlagStatus.UNDER_INVESTIGATION -> AppColor.InfoInk
                FlagStatus.RESOLVED -> AppColor.SuccessInk
            },
            showDivider = false
        )
    }
}

fun FlagSeverity.tone(): BadgeTone = when (this) {
    FlagSeverity.HIGH -> BadgeTone.Danger
    FlagSeverity.MEDIUM -> BadgeTone.Warning
    FlagSeverity.LOW -> BadgeTone.Neutral
}

fun FlagSeverity.accent(): androidx.compose.ui.graphics.Color = when (this) {
    FlagSeverity.HIGH -> AppColor.DangerBar
    FlagSeverity.MEDIUM -> AppColor.WarnBar
    FlagSeverity.LOW -> AppColor.LineStrong
}
