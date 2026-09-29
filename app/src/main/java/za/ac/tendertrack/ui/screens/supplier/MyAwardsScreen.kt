package za.ac.tendertrack.ui.screens.supplier

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
import kotlinx.coroutines.launch
import za.ac.tendertrack.core.Format
import za.ac.tendertrack.core.UiState
import za.ac.tendertrack.data.model.*
import za.ac.tendertrack.data.repo.AwardRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens

class MyAwardsViewModel(
    private val repository: AwardRepository = AwardRepository.instance
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<SupplierAward>>>(UiState.Loading)
    val state: StateFlow<UiState<List<SupplierAward>>> = _state.asStateFlow()

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = try {
                UiState.Success(repository.awards())
            } catch (e: Exception) {
                UiState.Error(e.supplierMessage())
            }
        }
    }
}

fun AwardClaimStatus.tone(): BadgeTone = when (this) {
    AwardClaimStatus.PENDING -> BadgeTone.Warning
    AwardClaimStatus.CLAIMED -> BadgeTone.Success
    AwardClaimStatus.EXPIRED, AwardClaimStatus.LOCKED -> BadgeTone.Danger
    AwardClaimStatus.NO_CODE -> BadgeTone.Neutral
}

/**
 * Tenders awarded to the supplier's company. An award becomes a contract only
 * once the company enters the 10-digit code it was emailed (Claim award).
 */
@Composable
fun MyAwardsScreen(
    onBack: () -> Unit,
    onClaim: (String) -> Unit,
    onOpenContract: (String) -> Unit,
    viewModel: MyAwardsViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    // Reloads when the screen comes back into view, e.g. after claiming.
    LaunchedEffect(Unit) { viewModel.load() }

    AppScaffold(title = "Awards", onBack = onBack) {
        when (val result = state) {
            is UiState.Loading -> LoadingState(message = "Loading your awards…")
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> {
                val awards = result.data
                val waiting = awards.count { it.canEnterCode }
                val active = awards.count { it.isClaimed && it.status != TenderStatus.COMPLETED }

                ScreenHeading(
                    eyebrow = "Supplier",
                    title = "My awards",
                    subtitle = "Tenders awarded to your company"
                )

                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.GridGap)) {
                    StatTile(
                        "Awaiting your code", "$waiting", Modifier.weight(1f), "Enter it to claim",
                        alert = waiting > 0
                    )
                    StatTile("Active contracts", "$active", Modifier.weight(1f), "Claimed awards")
                }

                NoteBanner(
                    title = "How claiming works",
                    text = "When a tender is awarded to your company, a 10-digit award code is emailed to your " +
                        "company's contact address. Enter it here to claim the contract. Until you do, you " +
                        "cannot start work or update deliverables.",
                    tone = NoteTone.Info,
                    icon = Icons.Default.MarkEmailRead
                )

                if (awards.isEmpty()) {
                    EmptyState(
                        title = "No awards yet",
                        message = "When a department awards your company a tender on the eTender portal, it appears here.",
                        icon = Icons.Default.EmojiEvents
                    )
                } else {
                    SectionHeader("Awarded tenders")
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.ListGap)) {
                        awards.forEach { award -> AwardCard(award, onClaim, onOpenContract) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AwardCard(award: SupplierAward, onClaim: (String) -> Unit, onOpenContract: (String) -> Unit) {
    AppCard(
        onClick = when {
            award.isClaimed -> ({ onOpenContract(award.tenderId) })
            award.canEnterCode -> ({ onClaim(award.tenderId) })
            else -> null
        }
    ) {
        CardHeader(
            title = award.referenceNumber,
            subtitle = award.title,
            trailing = { StatusBadge(award.claimStatus.displayName, award.claimStatus.tone()) }
        )
        Spacer(Modifier.height(10.dp))
        KeyValueRow("Department", award.department)
        KeyValueRow("Awarded value", award.awardedValue?.let { Format.money(it) } ?: "—")
        KeyValueRow("Award date", Format.date(award.awardedAt), showDivider = false)
        Spacer(Modifier.height(Dimens.SpaceSm))

        when (award.claimStatus) {
            AwardClaimStatus.PENDING -> {
                Text(
                    "Code sent to ${award.sentTo}. Enter it by ${Format.date(award.expiresAt)} · " +
                        "${award.attemptsLeft} ${if (award.attemptsLeft == 1) "attempt" else "attempts"} left.",
                    style = AppType.Meta
                )
                Spacer(Modifier.height(Dimens.SpaceMd))
                PrimaryButton(text = "Enter award code", icon = Icons.Default.Key, onClick = { onClaim(award.tenderId) })
            }
            AwardClaimStatus.CLAIMED -> {
                Text(
                    "Claimed on ${Format.date(award.claimedAt)} · ${award.deliverablesVerified} of " +
                        "${award.deliverablesTotal} phases verified",
                    style = AppType.Meta
                )
                Spacer(Modifier.height(Dimens.SpaceSm))
                ProgressBar(award.progress)
                Spacer(Modifier.height(Dimens.SpaceMd))
                SecondaryButton(
                    text = "Update deliverables",
                    icon = Icons.Default.TaskAlt,
                    onClick = { onOpenContract(award.tenderId) }
                )
            }
            AwardClaimStatus.EXPIRED, AwardClaimStatus.LOCKED -> NoteBanner(
                text = if (award.claimStatus == AwardClaimStatus.LOCKED)
                    "Five wrong codes were entered, so the code is locked. Ask the department to send a new one."
                else
                    "The code expired on ${Format.date(award.expiresAt)}. Ask the department to send a new one.",
                tone = NoteTone.Danger,
                icon = Icons.Default.Lock
            )
            AwardClaimStatus.NO_CODE -> NoteBanner(
                text = "The department has not sent the award code yet. It will arrive by email.",
                tone = NoteTone.Neutral,
                icon = Icons.Default.Schedule
            )
        }
    }
}
