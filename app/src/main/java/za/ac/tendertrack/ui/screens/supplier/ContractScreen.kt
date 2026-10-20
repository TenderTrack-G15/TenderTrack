package za.ac.tendertrack.ui.screens.supplier

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
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
import za.ac.tendertrack.core.ActionState
import za.ac.tendertrack.core.Format
import za.ac.tendertrack.core.UiState
import za.ac.tendertrack.data.model.*
import za.ac.tendertrack.data.repo.AwardRepository
import za.ac.tendertrack.data.repo.PhaseRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.screens.phases.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens
import za.ac.tendertrack.ui.viewModelFactory

/*
 * The supplier's contract: five set phases, each proved with a photo taken in
 * the app. Only the camera can be used: there is no upload or import.
 *
 * Only the current phase can be photographed. The next one opens when the
 * procurement officer has compared the supplier's photo with the auditor's
 * site photo and verified the phase. The database enforces both rules.
 */

/** An awarded contract and its phases. */
data class ContractData(val award: SupplierAward, val phases: List<ContractPhase>) {
    val verified: Int get() = phases.count { it.state.isDone }
    val paymentApproved: Int get() = phases.count { it.state == PhaseState.PAYMENT_APPROVED }
    val progress: Float get() = if (phases.isEmpty()) 0f else verified.toFloat() / phases.size

    /** The phase being worked on: the first one not yet verified. */
    val current: ContractPhase? get() = phases.filter { !it.state.isDone }.minByOrNull { it.phaseNo }
}

data class ContractUiState(
    val data: UiState<ContractData> = UiState.Loading,
    /** The photo just taken, shown for checking before it is sent. */
    val photo: PreparedPhoto? = null,
    val action: ActionState = ActionState.Idle
)

class ContractViewModel(
    private val tenderId: String,
    private val awards: AwardRepository = AwardRepository.instance,
    private val phases: PhaseRepository = PhaseRepository.instance
) : ViewModel() {

    private val _state = MutableStateFlow(ContractUiState())
    val state: StateFlow<ContractUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(data = UiState.Loading) }
        viewModelScope.launch { _state.update { it.copy(data = fetch()) } }
    }

    private suspend fun fetch(): UiState<ContractData> = try {
        val award = awards.awards().firstOrNull { it.tenderId == tenderId }
            ?: error("This tender was not awarded to your company.")
        UiState.Success(ContractData(award, phases.supplierPhases(tenderId)))
    } catch (e: Exception) {
        UiState.Error(e.phaseMessage())
    }

    fun onPhoto(photo: PreparedPhoto) = _state.update { it.copy(photo = photo, action = ActionState.Idle) }
    fun onCameraError(message: String) = _state.update { it.copy(action = ActionState.Failed(message)) }
    fun discardPhoto() = _state.update { it.copy(photo = null) }

    fun send(phase: ContractPhase) {
        val current = _state.value
        val photo = current.photo ?: return
        if (current.action is ActionState.Running) return
        _state.update { it.copy(action = ActionState.Running) }
        viewModelScope.launch {
            try {
                phases.submitSupplierPhoto(phase.id, photo.base64)
                val refreshed = fetch()
                _state.update {
                    it.copy(
                        data = refreshed, photo = null,
                        action = ActionState.Succeeded(
                            "Photo for \"${phase.phaseName}\" sent. An auditor will visit the site, then the " +
                                "procurement officer compares the two photos."
                        )
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(action = ActionState.Failed(e.phaseMessage())) }
            }
        }
    }
}

/**
 * Contract phases: once the award is claimed, the supplier photographs each
 * phase in turn and the department signs it off.
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

    AppScaffold(
        title = "Contract",
        onBack = onBack,
        actions = listOf(TopBarAction(Icons.Default.Refresh, "Refresh") { viewModel.load() })
    ) {
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
        title = "Contract phases",
        subtitle = "${award.title} · ${award.department}",
        trailing = { StatusBadge(award.claimStatus.displayName, award.claimStatus.tone()) }
    )

    if (!award.isClaimed) {
        NoteBanner(
            title = "Claim the award first",
            text = "Enter the award code from your email before you start work. Until the award is claimed, " +
                "no phase can be started.",
            tone = NoteTone.Warning,
            icon = Icons.Default.Lock
        )
        if (award.canEnterCode) {
            PrimaryButton(text = "Enter award code", icon = Icons.Default.Key, onClick = { onClaim(award.tenderId) })
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.GridGap)) {
        StatTile("Phases verified", "${data.verified} of ${data.phases.size}", Modifier.weight(1f), "Signed off by the department")
        StatTile("Approved for payment", "${data.paymentApproved}", Modifier.weight(1f), "Payment can be made")
    }
    ProgressBar(data.progress)
    Text(
        "Contract value ${award.awardedValue?.let { Format.money(it) } ?: "—"}" +
            (award.claimedAt?.let { " · claimed ${Format.date(it)}" } ?: ""),
        style = AppType.Meta
    )

    when (val action = state.action) {
        is ActionState.Succeeded -> NoteBanner(text = action.message, tone = NoteTone.Success, icon = Icons.Default.CheckCircle)
        is ActionState.Failed -> NoteBanner(text = action.message, tone = NoteTone.Danger, icon = Icons.Default.ErrorOutline)
        else -> Unit
    }

    val current = data.current
    when {
        data.phases.isEmpty() -> EmptyState(
            title = "No phases yet",
            message = "The contract's phases are created when the award is made. Tap refresh at the top to check again.",
            icon = Icons.Default.TaskAlt
        )
        current == null -> NoteBanner(
            title = "All phases verified",
            text = "Every phase of this contract has been verified by the procurement officer.",
            tone = NoteTone.Success,
            icon = Icons.Default.Verified
        )
        else -> CurrentPhaseCard(current, award.isClaimed, state, viewModel)
    }

    if (data.phases.isNotEmpty()) {
        SectionHeader("All phases")
        PhaseList(data.phases, forOfficer = false, selectedId = current?.id)
        NoteBanner(
            text = "Each phase opens only after the procurement officer has verified the one before it. Payment " +
                "for a phase is released after it is verified.",
            tone = NoteTone.Neutral,
            icon = Icons.Default.Info
        )
    }
}

@Composable
private fun CurrentPhaseCard(
    phase: ContractPhase,
    claimed: Boolean,
    state: ContractUiState,
    viewModel: ContractViewModel
) {
    val openCamera = rememberCameraCapture(onPhoto = viewModel::onPhoto, onError = viewModel::onCameraError)
    val sending = state.action is ActionState.Running

    AppCard(accent = AppColor.Ink) {
        CardHeader(
            title = phase.heading,
            subtitle = "${phase.valueLine()} · due ${Format.date(phase.targetDate)}",
            trailing = { StatusBadge(phase.state.supplierLabel, phase.state.tone(), showDot = false) }
        )
        Spacer(Modifier.height(Dimens.SpaceSm))
        Text("What to photograph", style = AppType.Label)
        Text(phase.guidance, style = AppType.Body.copy(color = AppColor.InkSoft))
    }

    if (phase.state == PhaseState.RETURNED && phase.returnedReason.isNotBlank()) {
        NoteBanner(
            title = "Returned by the procurement officer",
            text = phase.returnedReason,
            tone = NoteTone.Danger,
            icon = Icons.Default.Replay
        )
    }

    when {
        !claimed -> Unit
        phase.canTakePhoto -> {
            val photo = state.photo
            if (photo == null) {
                PrimaryButton(
                    text = if (phase.state == PhaseState.RETURNED) "Take a new photo" else "Take photo",
                    icon = Icons.Default.PhotoCamera,
                    onClick = openCamera
                )
                Text(
                    "Opens the camera. Photos can't be uploaded or chosen from the gallery: the photo must be " +
                        "taken now, on site.",
                    style = AppType.Tiny
                )
            } else {
                PhotoFrame(
                    label = "Your photo",
                    bitmap = photo.preview,
                    caption = "Check it shows the work clearly before sending.",
                    modifier = Modifier.fillMaxWidth(0.7f)
                )
                PrimaryButton(
                    text = "Send photo",
                    icon = Icons.AutoMirrored.Filled.Send,
                    loading = sending,
                    onClick = { viewModel.send(phase) }
                )
                SecondaryButton(
                    text = "Retake",
                    icon = Icons.Default.PhotoCamera,
                    enabled = !sending,
                    onClick = {
                        viewModel.discardPhoto()
                        openCamera()
                    }
                )
            }
        }
        phase.state == PhaseState.AWAITING_SITE_CHECK -> {
            NoteBanner(
                title = "Photo sent",
                text = "An auditor will visit the site and take their own photo of this phase. The procurement " +
                    "officer then compares the two.",
                tone = NoteTone.Info,
                icon = Icons.Default.HourglassTop
            )
            StoredPhotoFrame("Your photo", phase.supplierPhotoId, Modifier.fillMaxWidth(0.7f))
        }
        phase.state == PhaseState.AWAITING_OFFICER -> {
            NoteBanner(
                title = "Being checked",
                text = "The auditor has visited the site. The procurement officer is comparing your photo with " +
                    "the site photo. You can start the next phase once this one is verified.",
                tone = NoteTone.Info,
                icon = Icons.Default.HourglassTop
            )
            StoredPhotoFrame("Your photo", phase.supplierPhotoId, Modifier.fillMaxWidth(0.7f))
        }
    }
}
