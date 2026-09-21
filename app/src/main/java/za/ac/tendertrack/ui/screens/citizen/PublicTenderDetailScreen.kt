package za.ac.tendertrack.ui.screens.citizen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
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
import za.ac.tendertrack.data.ServiceLocator
import za.ac.tendertrack.data.model.*
import za.ac.tendertrack.data.repo.PublicRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.screens.tone
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens
import za.ac.tendertrack.ui.viewModelFactory

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

/** Everything the detail screen shows, loaded together. */
data class PublicTenderDetail(
    val tender: Tender,
    val deliverables: List<Deliverable>,
    val payments: List<PublicPayment>
)

class PublicTenderDetailViewModel(
    private val tenderId: String,
    private val repository: PublicRepository = ServiceLocator.publicRepository
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<PublicTenderDetail>>(UiState.Loading)
    val state: StateFlow<UiState<PublicTenderDetail>> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = try {
                val tender = repository.tender(tenderId)
                if (tender == null) {
                    UiState.Error("This tender is not published, or it no longer exists.")
                } else {
                    UiState.Success(
                        PublicTenderDetail(
                            tender = tender,
                            deliverables = repository.deliverables(tenderId),
                            payments = repository.payments(tenderId)
                        )
                    )
                }
            } catch (e: Exception) {
                UiState.Error(e.citizenMessage())
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

/**
 * Tender Detail — one tender in full: dated lifecycle timeline, awarded value
 * against amount paid (FR11), delivery phases (FR14), open compliance flags,
 * and the entry point to flag it for review (FR15).
 */
@Composable
fun PublicTenderDetailScreen(
    tenderId: String,
    onBack: () -> Unit,
    onFlag: (String) -> Unit
) {
    val viewModel: PublicTenderDetailViewModel = viewModel(
        key = "public_tender_$tenderId",
        factory = viewModelFactory { PublicTenderDetailViewModel(tenderId) }
    )
    val state by viewModel.state.collectAsState()

    AppScaffold(
        title = "Tender Detail",
        onBack = onBack,
        actions = listOf(TopBarAction(Icons.Default.Refresh, "Refresh") { viewModel.load() })
    ) {
        when (val result = state) {
            is UiState.Loading -> LoadingState()
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> DetailBody(result.data, onFlag = { onFlag(result.data.tender.id) })
        }
    }
}

@Composable
private fun DetailBody(detail: PublicTenderDetail, onFlag: () -> Unit) {
    val tender = detail.tender

    ScreenHeading(
        eyebrow = tender.category,
        title = tender.title,
        subtitle = "${tender.referenceNumber} · ${tender.department}",
        trailing = { StatusBadge(tender.status.displayName, tender.status.tone()) }
    )

    if (tender.openFlagCount > 0) {
        NoteBanner(
            title = "Under compliance review",
            text = "This tender has ${tender.openFlagCount} open compliance " +
                "flag${if (tender.openFlagCount == 1) "" else "s"}. Reviewers are looking into it.",
            tone = NoteTone.Danger,
            icon = Icons.Default.Flag
        )
    }

    // -- Overview --------------------------------------------------------------
    AppCard {
        CardHeader(title = "Overview")
        if (tender.description.isNotBlank()) {
            Spacer(Modifier.height(Dimens.SpaceSm))
            Text(tender.description, style = AppType.Body)
        }
        Spacer(Modifier.height(10.dp))
        KeyValueRow("Department", tender.department)
        KeyValueRow("Category", tender.category)
        if (!tender.estimateIsPublic) {
            // Design option 2: withheld while bidding and evaluation are under way.
            KeyValueRow("Department's estimate", "Published after award", valueColor = AppColor.Muted)
        }
        KeyValueRow("Contract period", "${tender.contractPeriodMonths} months")
        KeyValueRow(
            key = if (tender.status == TenderStatus.PUBLISHED) "Closing date" else "Closed",
            value = Format.dateTime(tender.closingDate),
            valueColor = if (tender.status == TenderStatus.PUBLISHED &&
                (Format.daysUntil(tender.closingDate) ?: 99) <= 14
            ) AppColor.WarnInk else AppColor.Ink,
            showDivider = false
        )
    }

    // -- Award and spending (FR11) -------------------------------------------
    val awardedValue = tender.awardedValue
    if (awardedValue != null) {
        AppCard {
            CardHeader(
                title = "Award and spending",
                subtitle = "How much of the contract has been paid"
            )
            Spacer(Modifier.height(10.dp))
            KeyValueRow("Awarded to", tender.awardedSupplierName ?: "—")
            KeyValueRow("Awarded on", Format.date(tender.awardedAt))
            KeyValueRow("Department's estimate", Format.money(tender.estimatedBudget))
            KeyValueRow("Awarded value", Format.money(awardedValue))
            tender.awardVsEstimate?.let { variance ->
                KeyValueRow(
                    "Award vs estimate",
                    varianceLabel(variance),
                    // More than 10% over the estimate is the same threshold the
                    // officer's "Award value variance" compliance rule uses.
                    valueColor = if (variance > 0.10) AppColor.DangerInk else AppColor.Ink
                )
            }
            KeyValueRow("Paid to date", Format.money(tender.paidToDate))
            KeyValueRow("Still to be paid", Format.money(tender.remainingValue), showDivider = false)
            Spacer(Modifier.height(10.dp))
            ProgressBar(
                tender.utilisation,
                color = if (tender.utilisation >= 1f) AppColor.SuccessBar else AppColor.WarnBar
            )
            Spacer(Modifier.height(Dimens.SpaceXs))
            Text("${Format.percent(tender.utilisation)} of the awarded value paid", style = AppType.Meta)
        }
    }

    if (!tender.estimateIsPublic) {
        Text(
            "The department's estimate is published once the tender is awarded, so bids " +
                "reflect real cost rather than the budget. You can then compare it with the award.",
            style = AppType.Meta
        )
    }

    // -- Lifecycle timeline (FR2 made public) --------------------------------
    AppCard {
        CardHeader(title = "Lifecycle", subtitle = "Where this tender is now")
        Spacer(Modifier.height(10.dp))
        LifecycleStepper(steps = lifecycleSteps(tender))
    }

    // -- Delivery phases (FR14) ----------------------------------------------
    if (detail.deliverables.isNotEmpty()) {
        val delivered = detail.deliverables.count { it.status == DeliverableStatus.VERIFIED }
        AppCard {
            CardHeader(
                title = "Delivery progress",
                subtitle = "$delivered of ${detail.deliverables.size} phases delivered"
            )
            Spacer(Modifier.height(10.dp))
            ProgressBar(delivered.toFloat() / detail.deliverables.size)
            Spacer(Modifier.height(Dimens.SpaceMd))
            detail.deliverables.forEachIndexed { index, phase ->
                PhaseRow(phase, showDivider = index < detail.deliverables.lastIndex)
            }
        }
    }

    // -- Payments made (FR11) --------------------------------------------------
    if (detail.payments.isNotEmpty()) {
        AppCard {
            CardHeader(
                title = "Payments made",
                subtitle = "${detail.payments.size} payment${if (detail.payments.size == 1) "" else "s"} · " +
                    Format.money(detail.payments.sumOf { it.amount })
            )
            Spacer(Modifier.height(10.dp))
            detail.payments.forEachIndexed { index, payment ->
                KeyValueRow(
                    key = "${payment.milestone} · ${Format.date(payment.paidOn)}",
                    value = Format.money(payment.amount),
                    showDivider = index < detail.payments.lastIndex
                )
            }
        }
    }

    // -- Flag for review (FR15) ----------------------------------------------
    PrimaryButton(text = "Flag this tender for review", icon = Icons.Default.Flag, onClick = onFlag)
    Text(
        "Reports go to procurement officers and auditors. You do not need an account, " +
            "and you do not have to give your name.",
        style = AppType.Tiny,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

/** "4% below the estimate", "12% above the estimate" or "Matches the estimate". */
private fun varianceLabel(variance: Double): String {
    val percent = Format.percent(kotlin.math.abs(variance).toFloat())
    return when {
        kotlin.math.abs(variance) < 0.005 -> "Matches the estimate"
        variance < 0 -> "$percent below the estimate"
        else -> "$percent above the estimate"
    }
}

/** One delivery phase: name and date on the left, status on the right. */
@Composable
private fun PhaseRow(phase: Deliverable, showDivider: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.SpaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(phase.phaseName, style = AppType.KvValue)
            Text("Due ${Format.date(phase.targetDate)}", style = AppType.Meta)
        }
        Spacer(Modifier.width(Dimens.SpaceSm))
        StatusBadge(phase.status.displayName, phase.status.tone())
    }
    if (showDivider) {
        // Same 1 dp divider KeyValueRow uses, so rows line up visually.
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(AppColor.SurfaceMuted)
        )
    }
}

/**
 * Builds the public lifecycle timeline from the tender's own dates. Each stage
 * before the current one is Done, the current one is Current, the rest Pending.
 */
private fun lifecycleSteps(tender: Tender): List<StepItem> {
    val stages = TenderStatus.lifecycle
    val currentIndex = stages.indexOf(tender.status)
    return stages.mapIndexed { index, stage ->
        val stepState = when {
            index < currentIndex -> StepState.Done
            index == currentIndex -> StepState.Current
            else -> StepState.Pending
        }
        val detail = when (stage) {
            TenderStatus.REGISTERED -> tender.createdAt?.let { "Captured ${Format.date(it)}" } ?: "Captured by the department"
            TenderStatus.PUBLISHED -> "Closing date ${Format.dateTime(tender.closingDate)}"
            TenderStatus.UNDER_EVALUATION ->
                if (index <= currentIndex) "Bids assessed by the evaluation committee" else "After bidding closes"
            TenderStatus.AWARDED ->
                tender.awardedAt?.let { "Awarded ${Format.date(it)} to ${tender.awardedSupplierName ?: "the supplier"}" }
                    ?: "Supplier not yet chosen"
            TenderStatus.IN_PROGRESS ->
                if (index <= currentIndex) "Paid ${Format.money(tender.paidToDate)} so far" else "Delivery not started"
            TenderStatus.COMPLETED -> if (index <= currentIndex) "Fully delivered" else "Not yet complete"
        }
        StepItem(title = stage.displayName, detail = detail, state = stepState)
    }
}
