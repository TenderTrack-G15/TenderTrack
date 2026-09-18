package za.ac.tendertrack.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
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
import za.ac.tendertrack.core.friendlyMessage
import za.ac.tendertrack.data.ServiceLocator
import za.ac.tendertrack.data.model.FundSummary
import za.ac.tendertrack.data.model.Tender
import za.ac.tendertrack.data.repo.TenderRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens

data class FundsUiState(
    val summary: FundSummary? = null,
    val contracts: List<Tender> = emptyList()
)

class FundsViewModel(
    private val repository: TenderRepository = ServiceLocator.tenderRepository
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<FundsUiState>>(UiState.Loading)
    val state: StateFlow<UiState<FundsUiState>> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = try {
                UiState.Success(
                    FundsUiState(
                        summary = repository.fundSummary(),
                        contracts = repository.list()
                            .filter { it.awardedValue != null }
                            .sortedByDescending { it.awardedValue }
                    )
                )
            } catch (e: Exception) {
                UiState.Error(e.friendlyMessage())
            }
        }
    }
}

/** Fund Utilisation — FR11. The data behind the dashboard's budget tiles. */
@Composable
fun FundUtilisationScreen(
    onMenu: () -> Unit,
    onOpenTender: (String) -> Unit,
    viewModel: FundsViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    AppScaffold(
        title = "Fund Utilisation",
        onMenu = onMenu,
        actions = listOf(TopBarAction(Icons.Default.Refresh, "Refresh") { viewModel.load() })
    ) {
        when (val result = state) {
            is UiState.Loading -> LoadingState()
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> {
                val summary = result.data.summary ?: return@AppScaffold
                ScreenHeading(
                    title = "${summary.financialYear} financial year",
                    subtitle = "Updated ${Format.date(Format.nowIso())}",
                    small = true
                )

                StatGrid(
                    listOf<StatGridTile>(
                        { m -> StatTile("Allocated", Format.moneyCompact(summary.allocated), m, money = true) },
                        { m -> StatTile("Committed (awarded)", Format.moneyCompact(summary.committed), m, money = true) },
                        { m -> StatTile("Disbursed", Format.moneyCompact(summary.disbursed), m, money = true) },
                        { m -> StatTile("Uncommitted", Format.moneyCompact(summary.uncommitted), m, money = true) }
                    )
                )

                AppCard {
                    Text("Allocation breakdown", style = AppType.H2)
                    Spacer(Modifier.height(14.dp))
                    BreakdownRow(
                        "Disbursed", summary.disbursedFraction, AppColor.SuccessBar,
                        Format.money(summary.disbursed)
                    )
                    Spacer(Modifier.height(14.dp))
                    BreakdownRow(
                        "Committed, not yet paid", summary.committedFraction, AppColor.WarnBar,
                        Format.money(summary.committed - summary.disbursed)
                    )
                    Spacer(Modifier.height(14.dp))
                    BreakdownRow(
                        "Uncommitted", summary.uncommittedFraction, AppColor.MutedLight,
                        Format.money(summary.uncommitted)
                    )
                }

                SectionHeader("Spend by contract")
                if (result.data.contracts.isEmpty()) {
                    EmptyState(
                        "No awarded contracts",
                        "Once a tender is awarded its spend appears here.",
                        Icons.Default.Refresh
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.ListGap)) {
                        result.data.contracts.forEach { tender ->
                            AppCard(onClick = { onOpenTender(tender.id) }) {
                                CardHeader(
                                    title = tender.referenceNumber,
                                    subtitle = tender.title,
                                    trailing = {
                                        StatusBadge(
                                            Format.percent(tender.utilisation),
                                            if (tender.utilisation >= 1f) BadgeTone.Success else BadgeTone.Neutral,
                                            showDot = false
                                        )
                                    }
                                )
                                Spacer(Modifier.height(10.dp))
                                ProgressBar(
                                    tender.utilisation,
                                    color = if (tender.utilisation >= 1f) AppColor.SuccessBar else AppColor.WarnBar
                                )
                                Spacer(Modifier.height(7.dp))
                                Text(
                                    "${Format.money(tender.paidToDate)} paid of " +
                                        "${Format.money(tender.awardedValue ?: 0.0)} awarded",
                                    style = AppType.Tiny
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BreakdownRow(
    label: String,
    fraction: Float,
    color: androidx.compose.ui.graphics.Color,
    amount: String
) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = AppType.KvValue)
            Text(
                "$amount · ${Format.percent(fraction, 1)}",
                style = AppType.KvKey
            )
        }
        Spacer(Modifier.height(6.dp))
        ProgressBar(fraction, color = color)
    }
}
