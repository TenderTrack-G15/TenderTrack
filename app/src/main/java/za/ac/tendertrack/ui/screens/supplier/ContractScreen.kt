package za.ac.tendertrack.ui.screens.supplier

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import za.ac.tendertrack.core.ActionState
import za.ac.tendertrack.core.Format
import za.ac.tendertrack.core.UiState
import za.ac.tendertrack.data.model.*
import za.ac.tendertrack.data.repo.AwardRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens
import za.ac.tendertrack.ui.viewModelFactory

/** An awarded contract and its delivery phases. */
data class ContractData(val award: SupplierAward, val phases: List<ContractDeliverable>) {
    val verified: Int get() = phases.count { it.status == ContractPhaseStatus.VERIFIED }
    val awaiting: Int get() = phases.count { it.status == ContractPhaseStatus.AWAITING_VERIFICATION }
    val progress: Float get() = if (phases.isEmpty()) 0f else verified.toFloat() / phases.size
}

data class ContractUiState(
    val data: UiState<ContractData> = UiState.Loading,
    /** The phase whose evidence form is open, if any. */
    val editing: String? = null,
    val form: EvidenceForm = EvidenceForm(),
    val errors: Map<String, String> = emptyMap(),
    val action: ActionState = ActionState.Idle
)

class ContractViewModel(
    private val tenderId: String,
    private val repository: AwardRepository = AwardRepository.instance
) : ViewModel() {

    private val _state = MutableStateFlow(ContractUiState())
    val state: StateFlow<ContractUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(data = UiState.Loading) }
        viewModelScope.launch { _state.update { it.copy(data = fetch()) } }
    }

    private suspend fun fetch(): UiState<ContractData> = try {
        val award = repository.awards().firstOrNull { it.tenderId == tenderId }
            ?: error("This tender was not awarded to your company.")
        UiState.Success(ContractData(award, repository.deliverables(tenderId)))
    } catch (e: Exception) {
        UiState.Error(e.supplierMessage())
    }

    fun startEvidence(phase: ContractDeliverable) = _state.update {
        // A returned phase starts with the earlier link, so the supplier can correct it.
        it.copy(editing = phase.id, form = EvidenceForm(link = phase.evidenceUrl.orEmpty()), errors = emptyMap(),
            action = ActionState.Idle)
    }

    fun cancel() = _state.update { it.copy(editing = null, errors = emptyMap()) }

    fun onLink(value: String) = _state.update { it.copy(form = it.form.copy(link = value), errors = it.errors - "link") }
    fun onNote(value: String) = _state.update { it.copy(form = it.form.copy(note = value), errors = it.errors - "note" - "link") }

    fun submit() {
        val current = _state.value
        val id = current.editing ?: return
        if (current.action is ActionState.Running) return
        val errors = current.form.errors()
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors) }
            return
        }
        _state.update { it.copy(action = ActionState.Running) }
        viewModelScope.launch {
            try {
                val saved = repository.submitEvidence(id, current.form)
                val refreshed = fetch()
                _state.update {
                    it.copy(
                        data = refreshed, editing = null, form = EvidenceForm(),
                        action = ActionState.Succeeded("Evidence for \"${saved.phaseName}\" submitted. The department will verify it.")
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(action = ActionState.Failed(e.supplierMessage())) }
            }
        }
    }
}

fun ContractPhaseStatus.tone(): BadgeTone = when (this) {
    ContractPhaseStatus.NOT_STARTED -> BadgeTone.Neutral
    ContractPhaseStatus.AWAITING_VERIFICATION -> BadgeTone.Warning
    ContractPhaseStatus.VERIFIED -> BadgeTone.Success
    ContractPhaseStatus.OVERDUE -> BadgeTone.Danger
}

/**
 * Contract deliverables: once the award is claimed, the supplier submits
 * evidence for each phase and the department verifies or returns it.
 */
@Composable
fun ContractScreen(
    tenderId: String,
    onBack: () -> Unit,
    onClaim: (String) -> Unit
) {
    val viewModel: ContractViewModel = viewModel(
        key = "supplier_contract_$tenderId",
        factory = viewModelFactory { ContractViewModel(tenderId) }
    )
    val state by viewModel.state.collectAsState()

    AppScaffold(title = "Contract", onBack = onBack) {
        when (val result = state.data) {
            is UiState.Loading -> LoadingState(message = "Loading the contract…")
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> ContractBody(result.data, state, viewModel, onClaim)
        }
    }
}

@Composable
private fun ContractBody(
    data: ContractData,
    state: ContractUiState,
    viewModel: ContractViewModel,
    onClaim: (String) -> Unit
) {
    val award = data.award
    ScreenHeading(
        eyebrow = award.referenceNumber,
        title = "Contract deliverables",
        subtitle = "${award.title} · ${award.department}",
        trailing = { StatusBadge(award.claimStatus.displayName, award.claimStatus.tone()) }
    )

    if (!award.isClaimed) {
        NoteBanner(
            title = "Claim the award first",
            text = "Enter the award code from your email before you start work. Until the award is claimed, " +
                "its deliverables cannot be updated.",
            tone = NoteTone.Warning,
            icon = Icons.Default.Lock
        )
        if (award.canEnterCode) {
            PrimaryButton(text = "Enter award code", icon = Icons.Default.Key, onClick = { onClaim(award.tenderId) })
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.GridGap)) {
        StatTile("Verified", "${data.verified} of ${data.phases.size}", Modifier.weight(1f), "Signed off by the department")
        StatTile(
            "Awaiting verification", "${data.awaiting}", Modifier.weight(1f), "Evidence submitted"
        )
    }
    ProgressBar(data.progress)
    Text(
        "Contract value ${award.awardedValue?.let { Format.money(it) } ?: "—"} · claimed ${Format.date(award.claimedAt)}",
        style = AppType.Meta
    )

    when (val action = state.action) {
        is ActionState.Succeeded -> NoteBanner(text = action.message, tone = NoteTone.Success, icon = Icons.Default.CheckCircle)
        is ActionState.Failed -> NoteBanner(text = action.message, tone = NoteTone.Danger, icon = Icons.Default.ErrorOutline)
        else -> Unit
    }

    SectionHeader("Phases")
    if (data.phases.isEmpty()) {
        EmptyState(
            title = "No phases yet",
            message = "The department has not set this contract's phases yet.",
            icon = Icons.Default.TaskAlt
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.ListGap)) {
            data.phases.forEach { phase ->
                PhaseCard(
                    phase = phase,
                    claimed = award.isClaimed,
                    editing = state.editing == phase.id,
                    state = state,
                    viewModel = viewModel
                )
            }
        }
    }
}

@Composable
private fun PhaseCard(
    phase: ContractDeliverable,
    claimed: Boolean,
    editing: Boolean,
    state: ContractUiState,
    viewModel: ContractViewModel
) {
    val context = LocalContext.current
    AppCard {
        CardHeader(
            title = phase.phaseName,
            subtitle = "Due ${Format.date(phase.targetDate)} · ${Format.money(phase.phaseValue)}",
            trailing = { StatusBadge(phase.status.displayName, phase.status.tone()) }
        )

        if (phase.wasReturned) {
            Spacer(Modifier.height(Dimens.SpaceSm))
            NoteBanner(
                title = "Returned by the department",
                text = phase.evidenceNote.orEmpty().removePrefix("Returned:").trim().lineSequence().first(),
                tone = NoteTone.Danger,
                icon = Icons.Default.ErrorOutline
            )
        } else if (phase.evidenceAt != null) {
            Spacer(Modifier.height(Dimens.SpaceSm))
            KeyValueRow("Evidence submitted", Format.dateTime(phase.evidenceAt), showDivider = false)
            phase.evidenceNote?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = AppType.Body.copy(color = AppColor.InkSoft))
            }
            phase.evidenceUrl?.let { url ->
                TextAction(text = "Open evidence link", color = AppColor.InfoInk, onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW).apply { data = Uri.parse(url) })
                    }.onFailure {
                        Toast.makeText(context, "Could not open that link.", Toast.LENGTH_SHORT).show()
                    }
                })
            }
        }

        Spacer(Modifier.height(Dimens.SpaceSm))
        when {
            phase.status == ContractPhaseStatus.VERIFIED ->
                Text("Verified on ${Format.date(phase.verifiedAt)}.", style = AppType.Meta)
            phase.status == ContractPhaseStatus.AWAITING_VERIFICATION ->
                Text("Waiting for the department to verify this evidence.", style = AppType.Meta)
            !claimed ->
                Text("Available once the award is claimed.", style = AppType.Meta)
            editing -> EvidenceFields(state, viewModel)
            phase.canSubmit -> SecondaryButton(
                text = if (phase.wasReturned) "Submit corrected evidence" else "Submit evidence",
                icon = Icons.Default.Upload,
                onClick = { viewModel.startEvidence(phase) }
            )
        }
    }
}

@Composable
private fun EvidenceFields(state: ContractUiState, viewModel: ContractViewModel) {
    val running = state.action is ActionState.Running
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMd)) {
        AppTextField(
            label = "Link to the evidence",
            value = state.form.link,
            onValueChange = viewModel::onLink,
            placeholder = "https://",
            hint = "A delivery note, photos or a signed report stored online (Google Drive, OneDrive).",
            error = state.errors["link"],
            leadingIcon = Icons.Default.Link,
            keyboardType = KeyboardType.Uri,
            enabled = !running
        )
        AppTextField(
            label = "What was delivered",
            value = state.form.note,
            onValueChange = viewModel::onNote,
            placeholder = "e.g. 42 switches delivered and signed for by the district IT manager",
            error = state.errors["note"],
            singleLine = false,
            minHeight = 110.dp,
            enabled = !running
        )
        ButtonRow(
            left = { SecondaryButton(text = "Cancel", modifier = Modifier.weight(1f), enabled = !running, onClick = viewModel::cancel) },
            right = {
                PrimaryButton(
                    text = "Submit",
                    modifier = Modifier.weight(1f),
                    loading = running,
                    onClick = viewModel::submit
                )
            }
        )
    }
}
