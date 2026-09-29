package za.ac.tendertrack.ui.screens.supplier

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
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
import za.ac.tendertrack.data.repo.SampleAwardRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens
import za.ac.tendertrack.ui.viewModelFactory

data class ClaimAwardUiState(
    val award: UiState<SupplierAward> = UiState.Loading,
    val code: String = "",
    val codeError: String? = null,
    val action: ActionState = ActionState.Idle
)

class ClaimAwardViewModel(
    private val tenderId: String,
    private val repository: AwardRepository = AwardRepository.instance
) : ViewModel() {

    private val _state = MutableStateFlow(ClaimAwardUiState())
    val state: StateFlow<ClaimAwardUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(award = UiState.Loading) }
        viewModelScope.launch {
            val result = try {
                val award = repository.awards().firstOrNull { it.tenderId == tenderId }
                    ?: error("This tender was not awarded to your company.")
                UiState.Success(award)
            } catch (e: Exception) {
                UiState.Error(e.supplierMessage())
            }
            _state.update { it.copy(award = result) }
        }
    }

    /** Digits only, at most 10; any message clears as the supplier types. */
    fun onCode(value: String) = _state.update {
        it.copy(code = AwardCodeInput.clean(value), codeError = null, action = ActionState.Idle)
    }

    fun claim() {
        val current = _state.value
        if (current.action is ActionState.Running) return
        AwardCodeInput.error(current.code)?.let { message ->
            _state.update { it.copy(codeError = message) }
            return
        }
        _state.update { it.copy(action = ActionState.Running, codeError = null) }
        viewModelScope.launch {
            try {
                val result = repository.claim(tenderId, current.code)
                if (result.ok) {
                    _state.update { it.copy(code = "", action = ActionState.Succeeded(result.message)) }
                } else {
                    // A wrong code counts as an attempt: show what the database said and how many are left.
                    _state.update { it.copy(code = "", codeError = result.message, action = ActionState.Idle) }
                }
                refreshAward()
            } catch (e: Exception) {
                _state.update { it.copy(action = ActionState.Failed(e.supplierMessage())) }
            }
        }
    }

    private suspend fun refreshAward() {
        runCatching { repository.awards().firstOrNull { it.tenderId == tenderId } }
            .getOrNull()?.let { fresh -> _state.update { it.copy(award = UiState.Success(fresh)) } }
    }
}

/**
 * Claim award: where the supplier types the 10-digit code from the award
 * email. The database compares it with its hashed copy; a correct code makes
 * the contract the supplier's and unlocks its deliverables.
 */
@Composable
fun ClaimAwardScreen(
    tenderId: String,
    onBack: () -> Unit,
    onOpenContract: (String) -> Unit
) {
    val viewModel: ClaimAwardViewModel = viewModel(
        key = "supplier_claim_$tenderId",
        factory = viewModelFactory { ClaimAwardViewModel(tenderId) }
    )
    val state by viewModel.state.collectAsState()

    AppScaffold(title = "Claim award", onBack = onBack) {
        when (val result = state.award) {
            is UiState.Loading -> LoadingState(message = "Loading the award…")
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> ClaimBody(result.data, state, viewModel, onOpenContract)
        }
    }
}

@Composable
private fun ClaimBody(
    award: SupplierAward,
    state: ClaimAwardUiState,
    viewModel: ClaimAwardViewModel,
    onOpenContract: (String) -> Unit
) {
    ScreenHeading(
        eyebrow = award.referenceNumber,
        title = "Claim award",
        subtitle = award.title,
        trailing = { StatusBadge(award.claimStatus.displayName, award.claimStatus.tone()) }
    )

    AppCard {
        KeyValueRow("Department", award.department)
        KeyValueRow("Awarded value", award.awardedValue?.let { Format.money(it) } ?: "—")
        KeyValueRow("Award date", Format.date(award.awardedAt))
        KeyValueRow("Code sent to", award.sentTo.ifBlank { "—" })
        KeyValueRow("Code expires", Format.date(award.expiresAt), showDivider = false)
    }

    when {
        award.isClaimed -> {
            val just = state.action as? ActionState.Succeeded
            NoteBanner(
                title = if (just != null) "Award claimed" else "Already claimed",
                text = just?.message ?: "You claimed this award on ${Format.dateTime(award.claimedAt)}.",
                tone = NoteTone.Success,
                icon = Icons.Default.CheckCircle
            )
            Text(
                "The contract is yours. Record your progress by submitting evidence for each phase; " +
                    "the department verifies it and the public can follow the progress in TenderTrack.",
                style = AppType.Body.copy(color = AppColor.InkSoft)
            )
            PrimaryButton(
                text = "Open contract deliverables",
                icon = Icons.Default.TaskAlt,
                onClick = { onOpenContract(award.tenderId) }
            )
        }

        award.canEnterCode -> {
            SectionHeader("Enter your award code")
            Text(
                "Open the award email sent to ${award.sentTo.ifBlank { "your company" }} and type the " +
                    "10-digit code. You have ${award.attemptsLeft} " +
                    "${if (award.attemptsLeft == 1) "attempt" else "attempts"} left.",
                style = AppType.Body.copy(color = AppColor.InkSoft)
            )
            AppTextField(
                label = "Award code",
                value = state.code,
                onValueChange = viewModel::onCode,
                placeholder = "10 digits",
                hint = if (state.code.isEmpty()) "Spaces are ignored." else "${state.code.length} of 10 digits",
                error = state.codeError,
                leadingIcon = Icons.Default.Key,
                keyboardType = KeyboardType.NumberPassword,
                enabled = state.action !is ActionState.Running
            )
            PrimaryButton(
                text = "Claim award",
                icon = Icons.Default.Verified,
                loading = state.action is ActionState.Running,
                enabled = state.code.length == AwardCodeInput.LENGTH,
                onClick = viewModel::claim
            )
            (state.action as? ActionState.Failed)?.let {
                NoteBanner(text = it.message, tone = NoteTone.Danger, icon = Icons.Default.ErrorOutline)
            }
            NoteBanner(
                text = "Never share this code. The department cannot see it, and nobody from the department " +
                    "or TenderTrack will ask you for it.",
                tone = NoteTone.Neutral,
                icon = Icons.Default.Lock
            )
            if (AwardRepository.instance is SampleAwardRepository) {
                NoteBanner(
                    title = "Sample data",
                    text = "No Supabase project is configured, so this is the sample award. Its code is " +
                        AwardCodeInput.pretty(SampleAwardRepository.SAMPLE_CODE) + ".",
                    tone = NoteTone.Info
                )
            }
        }

        award.claimStatus == AwardClaimStatus.EXPIRED || award.claimStatus == AwardClaimStatus.LOCKED -> {
            // Also reached straight after the fifth wrong code.
            NoteBanner(
                title = if (award.claimStatus == AwardClaimStatus.LOCKED) "Code locked" else "Code expired",
                text = (state.codeError?.let { "$it " } ?: "") +
                    "The department can send a new code from the eTender portal; it arrives by email.",
                tone = NoteTone.Danger,
                icon = Icons.Default.Lock
            )
        }

        else -> NoteBanner(
            title = "No code yet",
            text = "The department has not sent the award code yet. When it arrives by email, enter it here.",
            tone = NoteTone.Neutral,
            icon = Icons.Default.Schedule
        )
    }
    Spacer(Modifier.height(Dimens.SpaceSm))
}
