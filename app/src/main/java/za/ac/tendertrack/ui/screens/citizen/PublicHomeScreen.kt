package za.ac.tendertrack.ui.screens.citizen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import za.ac.tendertrack.data.model.PublicDashboard
import za.ac.tendertrack.data.model.TenderStatus
import za.ac.tendertrack.data.repo.PublicRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

class PublicHomeViewModel(
    private val repository: PublicRepository = ServiceLocator.publicRepository
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<PublicDashboard>>(UiState.Loading)
    val state: StateFlow<UiState<PublicDashboard>> = _state.asStateFlow()

    /** When the figures were last fetched, shown under the heading. */
    var updatedAt: String = Format.nowIso()
        private set

    init { load() }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = try {
                UiState.Success(repository.dashboard()).also { updatedAt = Format.nowIso() }
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
 * Public Dashboard — FR14 and FR11. Live counts by lifecycle state, value
 * awarded against value paid, and delivery progress, all without an account.
 * Every tender tile opens the search pre-filtered to what it counts; the money
 * tile opens Spending by department.
 */
@Composable
fun PublicHomeScreen(
    onBack: () -> Unit,
    onOpenTenders: (TenderStatus?) -> Unit,
    onOpenSpend: () -> Unit,
    onFlagTender: () -> Unit,
    onTrackReport: () -> Unit,
    /** Opens the public navigation panel; the menu button shows instead of Back when given. */
    onMenu: (() -> Unit)? = null,
    viewModel: PublicHomeViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    AppScaffold(
        title = "TenderTrack",
        onBack = onBack,
        onMenu = onMenu,
        actions = listOf(
            TopBarAction(Icons.Default.Search, "Search tenders") { onOpenTenders(null) },
            TopBarAction(Icons.Default.Refresh, "Refresh figures") { viewModel.load() }
        )
    ) {
        ScreenHeading(
            eyebrow = "Public view",
            title = "Public dashboard",
            subtitle = "Published IT tenders · updated ${Format.dateTime(viewModel.updatedAt)}"
        )

        when (val result = state) {
            is UiState.Loading -> LoadingState(message = "Loading published tenders…")
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> DashboardBody(
                summary = result.data,
                onOpenTenders = onOpenTenders,
                onOpenSpend = onOpenSpend,
                onFlagTender = onFlagTender,
                onTrackReport = onTrackReport
            )
        }
    }
}

@Composable
private fun DashboardBody(
    summary: PublicDashboard,
    onOpenTenders: (TenderStatus?) -> Unit,
    onOpenSpend: () -> Unit,
    onFlagTender: () -> Unit,
    onTrackReport: () -> Unit
) {
    PrimaryButton(
        text = "Search published tenders",
        icon = Icons.Default.Search,
        onClick = { onOpenTenders(null) }
    )

    // The tiles are laid out directly in rows of two instead of through
    // StatGrid, which takes a list of composable lambdas. The Compose compiler
    // could not type that list here, so this avoids it entirely. The layout is
    // exactly what StatGrid produces: two tiles per row, equal widths, GridGap.
    val flagsCaption = if (summary.tendersWithFlags == 1) "On 1 tender" else "On ${summary.tendersWithFlags} tenders"
    val contractsCaption = if (summary.contractsAwarded == 1) "Across 1 contract"
    else "Across ${summary.contractsAwarded} contracts"
    val paidCaption = "${Format.percent(summary.paidFraction)} of awarded value"

    // -- Tenders by lifecycle state ------------------------------------------
    SectionHeader("Tenders")
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.GridGap)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.GridGap)) {
            StatTile(
                "Open for bids", "${summary.count(TenderStatus.PUBLISHED)}",
                Modifier.weight(1f), "${summary.closingWithin30Days} close within 30 days",
                onClick = { onOpenTenders(TenderStatus.PUBLISHED) }
            )
            StatTile(
                "Under evaluation", "${summary.count(TenderStatus.UNDER_EVALUATION)}",
                Modifier.weight(1f), "Decision pending",
                onClick = { onOpenTenders(TenderStatus.UNDER_EVALUATION) }
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.GridGap)) {
            StatTile(
                "Awarded", "${summary.count(TenderStatus.AWARDED)}",
                Modifier.weight(1f), "Supplier published",
                onClick = { onOpenTenders(TenderStatus.AWARDED) }
            )
            StatTile(
                "In progress", "${summary.count(TenderStatus.IN_PROGRESS)}",
                Modifier.weight(1f), "Being delivered",
                onClick = { onOpenTenders(TenderStatus.IN_PROGRESS) }
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.GridGap)) {
            StatTile(
                "Completed", "${summary.count(TenderStatus.COMPLETED)}",
                Modifier.weight(1f), "Fully delivered",
                onClick = { onOpenTenders(TenderStatus.COMPLETED) }
            )
            StatTile(
                "Open compliance flags", "${summary.openFlags}",
                Modifier.weight(1f), flagsCaption,
                alert = summary.openFlags > 0,
                onClick = { onOpenTenders(null) }
            )
        }
    }

    // -- Money and delivery (FR11) -------------------------------------------
    SectionHeader("Money and delivery")
    // One tile for both figures: they open the same page (Spending by department).
    MoneyTile(
        valueAwarded = Format.moneyCompact(summary.valueAwarded),
        awardedCaption = contractsCaption,
        paidToSuppliers = Format.moneyCompact(summary.paidToSuppliers),
        paidCaption = paidCaption,
        onClick = onOpenSpend
    )

    UtilisationCard(
        title = "Deliverables completed",
        caption = if (summary.deliverablesTotal == 0) "No delivery phases have been published yet"
        else "${summary.deliverablesCompleted} of ${summary.deliverablesTotal} delivery phases signed off",
        fraction = summary.deliveryFraction
    )

    // -- Citizen participation (FR15) ----------------------------------------
    SectionHeader("Spotted something wrong?")
    NoteBanner(
        text = "Any member of the public can flag a published tender for review. " +
            "Reports go to procurement officers and auditors. You do not need an account.",
        tone = NoteTone.Info,
        icon = Icons.Default.Flag
    )
    PrimaryButton(text = "Flag a tender for review", icon = Icons.Default.Flag, onClick = onFlagTender)
    SecondaryButton(text = "Track a report I made", icon = Icons.Default.FindInPage, onClick = onTrackReport)

    Spacer(Modifier.height(Dimens.SpaceSm))
    Text(
        "Figures come directly from the procurement records. A tender appears here once the " +
            "department publishes it.",
        style = AppType.Tiny,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * Value awarded and paid to suppliers in one tile, since both open Spending by
 * department. Same label, figure and footnote styles as StatTile.
 */
@Composable
private fun MoneyTile(
    valueAwarded: String,
    awardedCaption: String,
    paidToSuppliers: String,
    paidCaption: String,
    onClick: () -> Unit
) {
    AppCard(onClick = onClick) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.GridGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MoneyFigure("Value awarded", valueAwarded, awardedCaption, Modifier.weight(1f))
            Box(
                Modifier
                    .width(1.dp)
                    .height(56.dp)
                    .background(AppColor.Line)
            )
            MoneyFigure("Paid to suppliers", paidToSuppliers, paidCaption, Modifier.weight(1f))
            AppIcon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                tint = AppColor.MutedLight,
                contentDescription = "Open spending by department"
            )
        }
    }
}

@Composable
private fun MoneyFigure(label: String, value: String, footnote: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = AppType.StatLabel, maxLines = 2)
        Spacer(Modifier.height(2.dp))
        Text(value, style = AppType.StatValueMoney.copy(color = AppColor.Ink), maxLines = 1)
        Spacer(Modifier.height(3.dp))
        Text(footnote, style = AppType.StatFoot.copy(color = AppColor.MutedLight), maxLines = 2)
    }
}
