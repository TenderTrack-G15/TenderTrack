package za.ac.tendertrack.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.SnackbarHostState
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
import za.ac.tendertrack.core.*
import za.ac.tendertrack.data.ServiceLocator
import za.ac.tendertrack.data.model.AuditEntry
import za.ac.tendertrack.data.model.Tender
import za.ac.tendertrack.data.model.TenderStatus
import za.ac.tendertrack.data.repo.TenderRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.viewModelFactory

data class LifecycleUiState(
    val tender: UiState<Tender> = UiState.Loading,
    val audit: List<AuditEntry> = emptyList(),
    val reason: String = "",
    val reasonError: String? = null,
    val action: ActionState = ActionState.Idle
)

class LifecycleViewModel(
    private val tenderId: String,
    private val repository: TenderRepository = ServiceLocator.tenderRepository
) : ViewModel() {

    private val _state = MutableStateFlow(LifecycleUiState())
    val state: StateFlow<LifecycleUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(tender = UiState.Loading) }
        viewModelScope.launch {
            try {
                val tender = repository.byId(tenderId) ?: error("Tender not found.")
                _state.update {
                    it.copy(tender = UiState.Success(tender), audit = repository.auditTrail(tenderId))
                }
            } catch (e: Exception) {
                _state.update { it.copy(tender = UiState.Error(e.friendlyMessage())) }
            }
        }
    }

    fun onReasonChange(value: String) =
        _state.update { it.copy(reason = value, reasonError = null) }

    /** Moves the tender on one step. The next state is derived, never chosen. */
    fun advance() {
        val current = _state.value
        val tender = current.tender.dataOrNull() ?: return
        val next = tender.status.next() ?: return

        val reasonError = Validate.required(current.reason, "Reason for the change")
        if (reasonError != null) {
            _state.update { it.copy(reasonError = reasonError) }
            return
        }

        _state.update { it.copy(action = ActionState.Running) }
        viewModelScope.launch {
            try {
                repository.advanceStatus(tenderId, next, current.reason.trim())
                _state.update {
                    it.copy(
                        reason = "",
                        action = ActionState.Succeeded("Status updated to ${next.displayName}.")
                    )
                }
                load()
            } catch (e: Exception) {
                _state.update { it.copy(action = ActionState.Failed(e.friendlyMessage())) }
            }
        }
    }

    fun clearAction() = _state.update { it.copy(action = ActionState.Idle) }
}

/**
 * Update Lifecycle.
 *
 * The stepper shows the FR2 states in order. The dropdown has exactly one
 * option — the next state — which is what prevents a tender skipping evaluation
 * and jumping straight to awarded.
 */
@Composable
fun LifecycleScreen(
    tenderId: String,
    onBack: () -> Unit
) {
    val viewModel: LifecycleViewModel = viewModel(
        key = "lifecycle_$tenderId",
        factory = viewModelFactory { LifecycleViewModel(tenderId) }
    )
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.action) {
        when (val a = state.action) {
            is ActionState.Succeeded -> { snackbar.showSnackbar(a.message); viewModel.clearAction() }
            is ActionState.Failed -> { snackbar.showSnackbar(a.message); viewModel.clearAction() }
            else -> Unit
        }
    }

    AppScaffold(title = "Update Lifecycle", onBack = onBack, snackbarHostState = snackbar) {
        when (val result = state.tender) {
            is UiState.Loading -> LoadingState()
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> {
                val tender = result.data
                val next = tender.status.next()
                val currentIndex = TenderStatus.lifecycle.indexOf(tender.status)

                ScreenHeading(
                    title = tender.title,
                    eyebrow = tender.referenceNumber,
                    subtitle = "Current status: ${tender.status.displayName}",
                    small = true
                )

                AppCard {
                    Text("Tender status", style = AppType.H2)
                    Spacer(Modifier.height(14.dp))
                    LifecycleStepper(
                        TenderStatus.lifecycle.mapIndexed { index, status ->
                            StepItem(
                                title = status.displayName,
                                detail = auditDetailFor(status, state.audit, index, currentIndex),
                                state = when {
                                    index < currentIndex -> StepState.Done
                                    index == currentIndex -> StepState.Current
                                    else -> StepState.Pending
                                }
                            )
                        }
                    )
                }

                if (next == null) {
                    NoteBanner(
                        "This tender has reached the end of its lifecycle. No further status change is possible.",
                        NoteTone.Success
                    )
                } else {
                    AppDropdownField(
                        label = "Move to next status",
                        selected = next,
                        options = listOf(next),
                        optionLabel = { it.displayName },
                        onSelect = { },
                        hint = "Only the next state in the lifecycle can be selected. Every change is " +
                            "written to the audit trail with your name against it."
                    )

                    AppTextField(
                        label = "Reason for change",
                        value = state.reason,
                        onValueChange = viewModel::onReasonChange,
                        placeholder = "e.g. Contract signed and work commenced",
                        singleLine = false,
                        minHeight = 88.dp,
                        error = state.reasonError
                    )

                    PrimaryButton(
                        text = "Update status to ${next.displayName}",
                        loading = state.action is ActionState.Running,
                        onClick = viewModel::advance
                    )
                }

                NoteBanner(
                    "Supplier registrations use their own separate statuses — awaiting verification, " +
                        "verified and not approved — and are handled on the Supplier Registrations screen.",
                    NoteTone.Neutral,
                    icon = Icons.Default.Info
                )
            }
        }
    }
}

/** Picks the audit line that belongs to a lifecycle step, if there is one. */
private fun auditDetailFor(
    status: TenderStatus,
    audit: List<AuditEntry>,
    index: Int,
    currentIndex: Int
): String {
    val entry = audit.lastOrNull { it.detail.startsWith(status.displayName, ignoreCase = true) }
    return when {
        entry != null -> "${Format.date(entry.createdAt)} · ${entry.actor}"
        index < currentIndex -> "Completed"
        index == currentIndex -> "Current status"
        index == currentIndex + 1 -> "Next step"
        else -> "—"
    }
}
