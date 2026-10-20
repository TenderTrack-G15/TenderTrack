package za.ac.tendertrack.ui.screens.phases

import androidx.compose.foundation.layout.*
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
import za.ac.tendertrack.core.ActionState
import za.ac.tendertrack.core.Format
import za.ac.tendertrack.core.UiState
import za.ac.tendertrack.data.model.ContractPhase
import za.ac.tendertrack.data.model.PhaseState
import za.ac.tendertrack.data.repo.OfficerAlertsStore
import za.ac.tendertrack.data.repo.PhaseRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens
import za.ac.tendertrack.ui.viewModelFactory

/*
 * One contract phase, for the procurement officer: the supplier's photo next to
 * the auditor's site photo.
 *   - Photos match    -> "Verify phase": the next phase opens for the supplier.
 *   - Photos differ   -> "Return to supplier", with the reason they will see.
 *   - Once verified   -> "Payment can be made" releases the phase for payment.
 * The database checks each step again: a phase cannot be verified before the
 * auditor's photo is in, nor approved for payment before it is verified.
 */

data class PhaseCheckUiState(
    val phases: UiState<List<ContractPhase>> = UiState.Loading,
    val returning: Boolean = false,
    val reason: String = "",
    val reasonError: String? = null,
    val confirmingPayment: Boolean = false,
    val action: ActionState = ActionState.Idle
)

class PhaseCheckViewModel(
    private val tenderId: String,
    private val repository: PhaseRepository = PhaseRepository.instance
) : ViewModel() {

    private val _state = MutableStateFlow(PhaseCheckUiState())
    val state: StateFlow<PhaseCheckUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(phases = UiState.Loading) }
        viewModelScope.launch {
            val result = try {
                UiState.Success(repository.officerQueue(tenderId))
            } catch (e: Exception) {
                UiState.Error(e.phaseMessage())
            }
            _state.update { it.copy(phases = result) }
        }
    }

    fun startReturn() = _state.update { it.copy(returning = true, confirmingPayment = false, action = ActionState.Idle) }
    fun cancelReturn() = _state.update { it.copy(returning = false, reason = "", reasonError = null) }
    fun onReason(value: String) = _state.update { it.copy(reason = value.take(500), reasonError = null) }
    fun askPayment() = _state.update { it.copy(confirmingPayment = true, action = ActionState.Idle) }
    fun cancelPayment() = _state.update { it.copy(confirmingPayment = false) }

    fun verify(phase: ContractPhase, onChanged: () -> Unit) = perform(onChanged, "${phase.phaseName} verified. The supplier can now start the next phase.") {
        repository.verify(phase.id, photosMatch = true, comment = "")
    }

    fun returnPhase(phase: ContractPhase, onChanged: () -> Unit) {
        val reason = _state.value.reason.trim()
        if (reason.length < 5) {
            _state.update { it.copy(reasonError = "Say what does not match (at least 5 characters). The supplier sees this.") }
            return
        }
        perform(onChanged, "${phase.phaseName} returned to the supplier for a new photo.") {
            repository.verify(phase.id, photosMatch = false, comment = reason)
        }
    }

    fun approvePayment(phase: ContractPhase, onChanged: () -> Unit) =
        perform(onChanged, "Payment can be made for ${phase.phaseName} (${Format.money(phase.phaseValue)}).") {
            repository.approvePayment(phase.id)
        }

    private fun perform(onChanged: () -> Unit, message: String, block: suspend () -> ContractPhase) {
        if (_state.value.action is ActionState.Running) return
        _state.update { it.copy(action = ActionState.Running) }
        viewModelScope.launch {
            try {
                block()
                val refreshed = repository.officerQueue(tenderId)
                _state.update {
                    it.copy(
                        phases = UiState.Success(refreshed), returning = false, reason = "",
                        confirmingPayment = false, action = ActionState.Succeeded(message)
                    )
                }
                OfficerAlertsStore.refresh(repository)
                onChanged()
            } catch (e: Exception) {
                _state.update { it.copy(action = ActionState.Failed(e.phaseMessage())) }
            }
        }
    }
}

@Composable
fun PhaseCheckScreen(
    tenderId: String,
    deliverableId: String,
    onBack: () -> Unit,
    onOpenPhase: (ContractPhase) -> Unit,
    onRecordPayment: (String) -> Unit,
    /** Called after a change, so the dashboard counts follow. */
    onChanged: () -> Unit = {}
) {
    val viewModel: PhaseCheckViewModel = viewModel(
        key = "phase_check_$tenderId",
        factory = viewModelFactory { PhaseCheckViewModel(tenderId) }
    )
    val state by viewModel.state.collectAsState()

    AppScaffold(title = "Phase check", onBack = onBack) {
        when (val result = state.phases) {
            is UiState.Loading -> LoadingState()
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> {
                val phases = result.data
                val phase = phases.firstOrNull { it.id == deliverableId }
                if (phase == null) {
                    ErrorState("This phase could not be found.", onRetry = viewModel::load)
                } else {
                    PhaseCheckBody(phase, phases, state, viewModel, onOpenPhase, onRecordPayment, onChanged)
                }
            }
        }
    }
}

@Composable
private fun PhaseCheckBody(
    phase: ContractPhase,
    phases: List<ContractPhase>,
    state: PhaseCheckUiState,
    viewModel: PhaseCheckViewModel,
    onOpenPhase: (ContractPhase) -> Unit,
    onRecordPayment: (String) -> Unit,
    onChanged: () -> Unit
) {
    val busy = state.action is ActionState.Running

    ScreenHeading(
        eyebrow = phase.referenceNumber,
        title = phase.heading,
        subtitle = "${phase.title} · ${phase.supplierName}",
        small = true,
        trailing = { StatusBadge(phase.state.officerLabel, phase.state.tone(), showDot = false) }
    )

    AppCard {
        KeyValueRow("Department", phase.department.ifBlank { "—" })
        KeyValueRow("Phase value", phase.valueLine())
        KeyValueRow("Due", Format.date(phase.targetDate))
        KeyValueRow("Supplier's photo", Format.dateTime(phase.supplierPhotoAt))
        KeyValueRow(
            "Auditor's site photo",
            if (phase.siteCheckedAt != null)
                Format.dateTime(phase.siteCheckedAt) + (phase.siteCheckedByName?.let { " · $it" } ?: "")
            else "Not taken yet",
            showDivider = false
        )
        Spacer(Modifier.height(Dimens.SpaceSm))
        Text("What the supplier was asked to photograph", style = AppType.Label)
        Text(phase.guidance, style = AppType.Body.copy(color = AppColor.InkSoft))
    }

    PhotoPair(phase)
    Text("Tap a photo to see it full screen.", style = AppType.Tiny)

    if (phase.siteNote.isNotBlank()) {
        NoteBanner(title = "Auditor's note", text = phase.siteNote, tone = NoteTone.Info, icon = Icons.Default.Info)
    }

    when (val action = state.action) {
        is ActionState.Succeeded -> NoteBanner(text = action.message, tone = NoteTone.Success, icon = Icons.Default.CheckCircle)
        is ActionState.Failed -> NoteBanner(text = action.message, tone = NoteTone.Danger, icon = Icons.Default.ErrorOutline)
        else -> Unit
    }

    when (phase.state) {
        PhaseState.AWAITING_OFFICER -> {
            Text("Do both photos show the same, completed work for this phase?", style = AppType.H2)
            if (state.returning) {
                AppTextField(
                    label = "What does not match?",
                    value = state.reason,
                    onValueChange = viewModel::onReason,
                    placeholder = "e.g. The site photo shows no equipment installed yet.",
                    hint = "The supplier sees this and must take a new photo. The auditor then visits again.",
                    singleLine = false,
                    minHeight = 96.dp,
                    error = state.reasonError,
                    enabled = !busy
                )
                PrimaryButton(
                    text = "Return to supplier",
                    icon = Icons.Default.Replay,
                    loading = busy,
                    onClick = { viewModel.returnPhase(phase, onChanged) }
                )
                SecondaryButton(text = "Cancel", enabled = !busy, onClick = viewModel::cancelReturn)
            } else {
                PrimaryButton(
                    text = "Photos match: verify phase",
                    icon = Icons.Default.Verified,
                    loading = busy,
                    onClick = { viewModel.verify(phase, onChanged) }
                )
                SecondaryButton(
                    text = "Photos don't match: return",
                    icon = Icons.Default.Close,
                    danger = true,
                    enabled = !busy,
                    onClick = viewModel::startReturn
                )
            }
        }
        PhaseState.AWAITING_SITE_CHECK -> NoteBanner(
            title = "Waiting for the auditor",
            text = "An auditor has to visit the site and take their own photo before you can compare the two and " +
                "verify this phase.",
            tone = NoteTone.Warning,
            icon = Icons.Default.HourglassTop
        )
        PhaseState.TO_DO, PhaseState.RETURNED -> NoteBanner(
            title = if (phase.locked) "Locked" else "Waiting for the supplier's photo",
            text = when {
                phase.locked -> "This phase opens for the supplier once the phase before it is verified."
                phase.state == PhaseState.RETURNED -> "Returned to the supplier: ${phase.returnedReason}"
                else -> "The supplier has not photographed this phase yet."
            },
            tone = NoteTone.Neutral,
            icon = if (phase.locked) Icons.Default.Lock else Icons.Default.PhotoCamera
        )
        PhaseState.VERIFIED -> {
            NoteBanner(
                title = "Verified",
                text = "Verified on ${Format.dateTime(phase.verifiedAt)}. The next phase is open for the supplier. " +
                    "Confirm below that payment can be made for this phase.",
                tone = NoteTone.Success,
                icon = Icons.Default.Verified
            )
            if (state.confirmingPayment) {
                AppCard(accent = AppColor.Ink) {
                    Text("Payment can be made?", style = AppType.H2)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${phase.heading} · ${Format.money(phase.phaseValue)}. The payment can then be recorded " +
                            "against this phase. This is written to the audit trail and cannot be undone.",
                        style = AppType.Meta
                    )
                }
                PrimaryButton(
                    text = "Yes, payment can be made",
                    icon = Icons.Default.Payments,
                    loading = busy,
                    onClick = { viewModel.approvePayment(phase, onChanged) }
                )
                SecondaryButton(text = "Cancel", enabled = !busy, onClick = viewModel::cancelPayment)
            } else {
                PrimaryButton(text = "Payment can be made", icon = Icons.Default.Payments, enabled = !busy, onClick = viewModel::askPayment)
            }
        }
        PhaseState.PAYMENT_APPROVED -> {
            NoteBanner(
                title = "Payment can be made",
                text = "Approved for payment on ${Format.dateTime(phase.paymentApprovedAt)}. " +
                    "${Format.money(phase.phaseValue)} can now be recorded against this phase.",
                tone = NoteTone.Success,
                icon = Icons.Default.Payments
            )
            SecondaryButton(text = "Record payment", icon = Icons.Default.Payments, onClick = { onRecordPayment(phase.tenderId) })
        }
    }

    SectionHeader("All phases of this contract")
    PhaseList(phases, forOfficer = true, selectedId = phase.id, onOpen = { if (it.id != phase.id) onOpenPhase(it) })
}
