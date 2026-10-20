package za.ac.tendertrack.ui.screens.auditor

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
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
import za.ac.tendertrack.data.model.SiteCheckQueue
import za.ac.tendertrack.data.repo.PhaseRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.screens.phases.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens
import za.ac.tendertrack.ui.viewModelFactory

/*
 * Site checks: the auditor confirms a supplier's photo proof by visiting the
 * site and taking their own photo of the same phase (camera only). The
 * procurement officer then compares the two photos and verifies the phase.
 *
 * Adding a site photo (and a note) is the only thing an auditor can add in
 * TenderTrack; everything else stays read-only.
 */

class SiteChecksViewModel(
    private val repository: PhaseRepository = PhaseRepository.instance
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<SiteCheckQueue>>(UiState.Loading)
    val state: StateFlow<UiState<SiteCheckQueue>> = _state.asStateFlow()

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = try {
                UiState.Success(repository.siteCheckQueue())
            } catch (e: Exception) {
                UiState.Error(e.phaseMessage())
            }
        }
    }
}

/** The auditor's list: phases waiting for a site visit, then the latest site checks. */
@Composable
fun SiteChecksScreen(
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    onMenu: (() -> Unit)? = null,
    viewModel: SiteChecksViewModel = viewModel(key = "auditor_site_checks")
) {
    val state by viewModel.state.collectAsState()

    // Back from a site check shows the list as it now is.
    LaunchedEffect(Unit) { viewModel.load() }

    AppScaffold(
        title = "Site checks",
        onMenu = onMenu,
        onBack = if (onMenu == null) onBack else null,
        actions = listOf(TopBarAction(Icons.Default.Refresh, "Refresh") { viewModel.load() })
    ) {
        ScreenHeading(
            eyebrow = "Auditor",
            title = "Site checks",
            subtitle = "Confirm a supplier's photo by visiting the site and taking your own"
        )

        when (val result = state) {
            is UiState.Loading -> LoadingState(message = "Loading site checks…")
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> {
                val queue = result.data
                SectionHeader("Waiting for a site visit", trailing = { CountPill(queue.waiting.size, BadgeTone.Warning) })
                if (queue.waiting.isEmpty()) {
                    EmptyState(
                        title = "Nothing waiting",
                        message = "When a supplier sends a photo of a contract phase, it appears here for a site check.",
                        icon = Icons.Default.TaskAlt
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.ListGap)) {
                        queue.waiting.forEach { phase -> SiteCheckCard(phase) { onOpen(phase.id) } }
                    }
                }

                if (queue.done.isNotEmpty()) {
                    SectionHeader("Recent site checks")
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.ListGap)) {
                        queue.done.forEach { phase -> SiteCheckCard(phase) { onOpen(phase.id) } }
                    }
                }

                NoteBanner(
                    title = "How a site check works",
                    text = "Visit the site, look at the work the supplier photographed, and take your own photo of " +
                        "the same thing with this phone's camera. Add a note if something differs. The procurement " +
                        "officer compares the two photos before verifying the phase or approving any payment.",
                    tone = NoteTone.Neutral,
                    icon = Icons.Default.Info
                )
            }
        }
    }
}

@Composable
private fun SiteCheckCard(phase: ContractPhase, onClick: () -> Unit) {
    AppCard(onClick = onClick) {
        CardHeader(
            title = phase.referenceNumber,
            subtitle = "${phase.title} · ${phase.supplierName}",
            trailing = { StatusBadge(phase.state.officerLabel, phase.state.tone(), showDot = false) }
        )
        Spacer(Modifier.height(10.dp))
        KeyValueRow("Phase", phase.heading)
        KeyValueRow("Supplier's photo", Format.dateTime(phase.supplierPhotoAt))
        KeyValueRow(
            "Your site photo",
            if (phase.siteCheckedAt != null) Format.dateTime(phase.siteCheckedAt) else "Not taken yet",
            valueColor = if (phase.siteCheckedAt != null) AppColor.SuccessInk else AppColor.WarnInk,
            showDivider = false
        )
    }
}

// ---------------------------------------------------------------------------
// One site check
// ---------------------------------------------------------------------------

data class SiteCheckUiState(
    val phase: UiState<ContractPhase> = UiState.Loading,
    val photo: PreparedPhoto? = null,
    val note: String = "",
    val noteError: String? = null,
    val action: ActionState = ActionState.Idle
)

class SiteCheckViewModel(
    private val deliverableId: String,
    private val repository: PhaseRepository = PhaseRepository.instance
) : ViewModel() {

    private val _state = MutableStateFlow(SiteCheckUiState())
    val state: StateFlow<SiteCheckUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(phase = UiState.Loading) }
        viewModelScope.launch {
            val result = try {
                val queue = repository.siteCheckQueue()
                val phase = (queue.waiting + queue.done).firstOrNull { it.id == deliverableId }
                    ?: error("This phase is no longer waiting for a site check.")
                UiState.Success(phase)
            } catch (e: Exception) {
                UiState.Error(e.phaseMessage())
            }
            _state.update { it.copy(phase = result) }
        }
    }

    fun onPhoto(photo: PreparedPhoto) = _state.update { it.copy(photo = photo, action = ActionState.Idle) }
    fun onCameraError(message: String) = _state.update { it.copy(action = ActionState.Failed(message)) }
    fun discardPhoto() = _state.update { it.copy(photo = null) }
    fun onNote(value: String) = _state.update { it.copy(note = value.take(500), noteError = null) }

    fun send() {
        val current = _state.value
        val photo = current.photo ?: return
        if (current.action is ActionState.Running) return
        if (current.note.length > 500) {
            _state.update { it.copy(noteError = "Keep the note under 500 characters.") }
            return
        }
        _state.update { it.copy(action = ActionState.Running) }
        viewModelScope.launch {
            try {
                val updated = repository.submitSiteCheck(deliverableId, photo.base64, current.note)
                _state.update {
                    it.copy(
                        phase = UiState.Success(updated), photo = null,
                        action = ActionState.Succeeded("Site photo sent. The procurement officer can now compare the two photos.")
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(action = ActionState.Failed(e.phaseMessage())) }
            }
        }
    }
}

@Composable
fun SiteCheckScreen(
    deliverableId: String,
    onBack: () -> Unit
) {
    val viewModel: SiteCheckViewModel = viewModel(
        key = "auditor_site_check_$deliverableId",
        factory = viewModelFactory { SiteCheckViewModel(deliverableId) }
    )
    val state by viewModel.state.collectAsState()
    val openCamera = rememberCameraCapture(onPhoto = viewModel::onPhoto, onError = viewModel::onCameraError)

    AppScaffold(title = "Site check", onBack = onBack) {
        when (val result = state.phase) {
            is UiState.Loading -> LoadingState()
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> {
                val phase = result.data
                val sending = state.action is ActionState.Running
                ScreenHeading(
                    eyebrow = phase.referenceNumber,
                    title = phase.heading,
                    subtitle = "${phase.title} · ${phase.supplierName}",
                    small = true
                )

                AppCard {
                    KeyValueRow("Department", phase.department.ifBlank { "—" })
                    KeyValueRow("Phase value", phase.valueLine())
                    KeyValueRow("Supplier's photo taken", Format.dateTime(phase.supplierPhotoAt), showDivider = false)
                    Spacer(Modifier.height(Dimens.SpaceSm))
                    Text("What the supplier was asked to photograph", style = AppType.Label)
                    Text(phase.guidance, style = AppType.Body.copy(color = AppColor.InkSoft))
                }

                when (val action = state.action) {
                    is ActionState.Succeeded -> NoteBanner(text = action.message, tone = NoteTone.Success, icon = Icons.Default.CheckCircle)
                    is ActionState.Failed -> NoteBanner(text = action.message, tone = NoteTone.Danger, icon = Icons.Default.ErrorOutline)
                    else -> Unit
                }

                val photo = state.photo
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.GridGap), modifier = Modifier.fillMaxWidth()) {
                    StoredPhotoFrame("Supplier's photo", phase.supplierPhotoId, Modifier.weight(1f))
                    when {
                        phase.sitePhotoId != null ->
                            StoredPhotoFrame("Your site photo", phase.sitePhotoId, Modifier.weight(1f))
                        photo != null ->
                            PhotoFrame("Your site photo", photo.preview, Modifier.weight(1f), caption = "Not sent yet")
                        else ->
                            PhotoFrame("Your site photo", null, Modifier.weight(1f), emptyText = "Take it on site")
                    }
                }

                if (phase.sitePhotoId != null) {
                    NoteBanner(
                        title = "Site check done",
                        text = listOfNotNull(
                            "Taken ${Format.dateTime(phase.siteCheckedAt)}" +
                                (phase.siteCheckedByName?.let { " by $it" } ?: "") + ".",
                            phase.siteNote.ifBlank { null }?.let { "Note: $it" },
                            when (phase.state) {
                                PhaseState.AWAITING_OFFICER -> "The procurement officer is comparing the photos."
                                PhaseState.VERIFIED, PhaseState.PAYMENT_APPROVED -> "The procurement officer verified this phase."
                                else -> null
                            }
                        ).joinToString(" "),
                        tone = NoteTone.Success,
                        icon = Icons.Default.Verified
                    )
                } else {
                    AppTextField(
                        label = "Note (optional)",
                        value = state.note,
                        onValueChange = viewModel::onNote,
                        placeholder = "e.g. Equipment installed as shown; serial numbers match.",
                        hint = "Say what you saw, especially anything that differs from the supplier's photo.",
                        singleLine = false,
                        minHeight = 96.dp,
                        error = state.noteError,
                        enabled = !sending
                    )
                    if (photo == null) {
                        PrimaryButton(text = "Take site photo", icon = Icons.Default.PhotoCamera, onClick = openCamera)
                        Text(
                            "Opens the camera. Take the photo now, on site: photos can't be uploaded or chosen " +
                                "from the gallery.",
                            style = AppType.Tiny
                        )
                    } else {
                        PrimaryButton(text = "Send site photo", icon = Icons.AutoMirrored.Filled.Send, loading = sending, onClick = viewModel::send)
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
            }
        }
    }
}
