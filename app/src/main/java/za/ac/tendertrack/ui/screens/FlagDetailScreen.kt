package za.ac.tendertrack.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
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
import za.ac.tendertrack.data.model.ComplianceFlag
import za.ac.tendertrack.data.model.FlagOutcome
import za.ac.tendertrack.data.model.FlagStatus
import za.ac.tendertrack.data.repo.FlagRepository
import za.ac.tendertrack.data.sample.SampleData
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.viewModelFactory

data class FlagDetailUiState(
    val flag: UiState<ComplianceFlag> = UiState.Loading,
    val note: String = "",
    val outcome: FlagOutcome? = null,
    val errors: Map<String, String> = emptyMap(),
    val action: ActionState = ActionState.Idle
)

class FlagDetailViewModel(
    private val flagId: String,
    private val repository: FlagRepository = ServiceLocator.flagRepository
) : ViewModel() {

    private val author = SampleData.currentUser.fullName

    private val _state = MutableStateFlow(FlagDetailUiState())
    val state: StateFlow<FlagDetailUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(flag = UiState.Loading) }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    flag = try {
                        UiState.Success(repository.byId(flagId) ?: error("Flag not found."))
                    } catch (e: Exception) {
                        UiState.Error(e.friendlyMessage())
                    }
                )
            }
        }
    }

    fun onNote(value: String) = _state.update { it.copy(note = value, errors = it.errors - "note") }
    fun onOutcome(value: FlagOutcome) = _state.update { it.copy(outcome = value, errors = it.errors - "outcome") }

    fun assignToMe() = perform("Flag assigned to you.") { repository.assignToMe(flagId, author) }

    fun saveNote() {
        val text = _state.value.note.trim()
        val noteError = Validate.required(text, "Investigation note")
        if (noteError != null) {
            _state.update { it.copy(errors = mapOf("note" to noteError)) }
            return
        }
        // Capture the text first: the field is cleared as soon as the note saves.
        perform("Note saved.") {
            repository.addNote(flagId, text, author).also {
                _state.update { s -> s.copy(note = "") }
            }
        }
    }

    /** Resolving requires both an outcome and a note — the audit trail needs both. */
    fun resolve(onResolved: () -> Unit) {
        val current = _state.value
        val errors = buildMap {
            if (current.outcome == null) put("outcome", "Select an outcome")
            Validate.required(current.note, "Investigation note")?.let { put("note", it) }
        }
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors) }
            return
        }
        _state.update { it.copy(action = ActionState.Running) }
        viewModelScope.launch {
            try {
                val updated = repository.resolve(flagId, current.outcome!!, current.note.trim(), author)
                _state.update {
                    it.copy(
                        flag = UiState.Success(updated),
                        note = "",
                        action = ActionState.Succeeded("Flag resolved — ${current.outcome.displayName}.")
                    )
                }
                onResolved()
            } catch (e: Exception) {
                _state.update { it.copy(action = ActionState.Failed(e.friendlyMessage())) }
            }
        }
    }

    private fun perform(message: String, block: suspend () -> ComplianceFlag) {
        _state.update { it.copy(action = ActionState.Running) }
        viewModelScope.launch {
            try {
                val updated = block()
                _state.update {
                    it.copy(flag = UiState.Success(updated), action = ActionState.Succeeded(message))
                }
            } catch (e: Exception) {
                _state.update { it.copy(action = ActionState.Failed(e.friendlyMessage())) }
            }
        }
    }

    fun clearAction() = _state.update { it.copy(action = ActionState.Idle) }
}

/** One compliance flag investigated end to end — FR7 and FR8. */
@Composable
fun FlagDetailScreen(
    flagId: String,
    onBack: () -> Unit,
    onOpenTender: (String) -> Unit
) {
    val viewModel: FlagDetailViewModel = viewModel(
        key = "flag_$flagId",
        factory = viewModelFactory { FlagDetailViewModel(flagId) }
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

    AppScaffold(title = "Flag Investigation", onBack = onBack, snackbarHostState = snackbar) {
        when (val result = state.flag) {
            is UiState.Loading -> LoadingState()
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> {
                val flag = result.data
                val busy = state.action is ActionState.Running
                val resolved = flag.status == FlagStatus.RESOLVED

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusBadge(flag.severity.displayName.uppercase(), flag.severity.tone(), showDot = false)
                    StatusBadge(flag.status.displayName, BadgeTone.Neutral, showDot = false)
                }

                ScreenHeading(
                    title = flag.title,
                    subtitle = "${flag.tenderReference} · ${flag.tenderTitle}",
                    eyebrow = flag.reference,
                    small = true
                )

                NoteBanner(
                    title = "Why this was flagged",
                    text = flag.description,
                    tone = NoteTone.Danger,
                    icon = Icons.Default.Warning
                )

                AppCard {
                    KeyValueRow("Rule triggered", flag.ruleTriggered)
                    KeyValueRow("Raised", Format.dateTime(flag.raisedAt))
                    KeyValueRow(
                        "Raised by",
                        if (flag.raisedAutomatically) "System rule" else "Manual entry"
                    )
                    KeyValueRow("Assigned to", flag.assignedTo ?: "Unassigned", showDivider = false)
                }

                SecondaryButton("Open tender ${flag.tenderReference}") { onOpenTender(flag.tenderId) }

                if (flag.notes.isNotEmpty()) {
                    AppCard {
                        Text("Audit trail", style = AppType.H2)
                        Spacer(Modifier.height(14.dp))
                        LifecycleStepper(
                            flag.notes.mapIndexed { index, note ->
                                StepItem(
                                    title = note.author,
                                    detail = "${note.text} · ${Format.dateTime(note.createdAt)}",
                                    state = if (index == flag.notes.lastIndex && !resolved) StepState.Current
                                    else StepState.Done
                                )
                            }
                        )
                    }
                }

                if (resolved) {
                    NoteBanner(
                        "This flag has been resolved and is now read-only.",
                        NoteTone.Success,
                        icon = Icons.Default.Check
                    )
                } else {
                    if (flag.assignedTo == null) {
                        SecondaryButton("Assign to me", icon = Icons.Default.Assignment, enabled = !busy) {
                            viewModel.assignToMe()
                        }
                    }

                    AppTextField(
                        label = "Investigation note",
                        value = state.note,
                        onValueChange = viewModel::onNote,
                        placeholder = "Record the findings and the evidence reviewed…",
                        singleLine = false,
                        minHeight = 96.dp,
                        error = state.errors["note"]
                    )

                    AppDropdownField(
                        label = "Outcome",
                        selected = state.outcome,
                        options = FlagOutcome.entries.toList(),
                        optionLabel = { it.displayName },
                        onSelect = viewModel::onOutcome,
                        placeholder = "Select an outcome",
                        hint = "Required before a flag can be resolved.",
                        error = state.errors["outcome"]
                    )

                    SecondaryButton("Save note", enabled = !busy, onClick = viewModel::saveNote)
                    PrimaryButton(
                        "Resolve flag",
                        icon = Icons.Default.Check,
                        loading = busy,
                        onClick = { viewModel.resolve(onBack) }
                    )
                }
            }
        }
    }
}
