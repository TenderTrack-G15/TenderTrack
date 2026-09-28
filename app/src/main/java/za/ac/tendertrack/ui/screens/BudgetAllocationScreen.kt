package za.ac.tendertrack.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountBalance
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
import za.ac.tendertrack.data.model.DepartmentBudget
import za.ac.tendertrack.data.repo.BudgetRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens

class BudgetAllocationViewModel(
    private val repository: BudgetRepository = ServiceLocator.budgetRepository
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<DepartmentBudget>>>(UiState.Loading)
    val state: StateFlow<UiState<List<DepartmentBudget>>> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = try {
                val all = repository.departmentBudgets()
                // Only the latest financial year: "2026/27" sorts after "2025/26".
                val latest = all.maxOfOrNull { it.financialYear }
                UiState.Success(all.filter { it.financialYear == latest }.sortedByDescending { it.allocated })
            } catch (e: Exception) {
                UiState.Error(e.friendlyMessage())
            }
        }
    }
}

/**
 * Budget Allocation — FR11. Opened from the dashboard's "Budget allocated"
 * tile. It shows how the year's budget is shared between departments and how
 * much of each allocation is already committed to contracts. How committed
 * money is being spent is on Fund Utilisation, opened from "Funds utilised".
 *
 * @param ownDepartment the signed-in officer's department, highlighted.
 */
@Composable
fun BudgetAllocationScreen(
    ownDepartment: String?,
    onBack: () -> Unit,
    onOpenUtilisation: () -> Unit,
    viewModel: BudgetAllocationViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    AppScaffold(
        title = "Budget Allocation",
        onBack = onBack,
        actions = listOf(TopBarAction(Icons.Default.Refresh, "Refresh") { viewModel.load() })
    ) {
        when (val result = state) {
            is UiState.Loading -> LoadingState()
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> {
                val budgets = result.data
                if (budgets.isEmpty()) {
                    EmptyState(
                        "No budgets captured",
                        "Department budgets for the financial year appear here once they are captured.",
                        Icons.Default.AccountBalance
                    )
                    return@AppScaffold
                }

                val totalAllocated = budgets.sumOf { it.allocated }
                val totalCommitted = budgets.sumOf { it.committed }
                val own = budgets.firstOrNull { it.department == ownDepartment }

                ScreenHeading(
                    title = "${budgets.first().financialYear} financial year",
                    subtitle = "Budget allocated to each department",
                    small = true
                )

                // Two rows of two tiles. Built as plain Rows rather than StatGrid(listOf { … }),
                // which this project's Kotlin/Compose versions reject with
                // "Argument type mismatch … ComposableFunction1".
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.GridGap)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.GridGap)) {
                        StatTile(
                            "Total allocated", Format.moneyCompact(totalAllocated), Modifier.weight(1f),
                            "${budgets.size} departments", money = true
                        )
                        if (own != null) {
                            StatTile(
                                "Your department", Format.moneyCompact(own.allocated), Modifier.weight(1f),
                                "${Format.percent(share(own.allocated, totalAllocated), 1)} of the total",
                                money = true
                            )
                        } else {
                            StatTile(
                                "Largest allocation", Format.moneyCompact(budgets.first().allocated), Modifier.weight(1f),
                                budgets.first().department, money = true
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.GridGap)) {
                        StatTile(
                            "Committed", Format.moneyCompact(totalCommitted), Modifier.weight(1f),
                            "${Format.percent(share(totalCommitted, totalAllocated), 1)} of the total",
                            money = true
                        )
                        StatTile(
                            "Uncommitted", Format.moneyCompact(budgets.sumOf { it.uncommitted }), Modifier.weight(1f),
                            "Still available to award", money = true
                        )
                    }
                }

                SectionHeader("By department")
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.ListGap)) {
                    budgets.forEach { budget ->
                        DepartmentBudgetCard(
                            budget = budget,
                            shareOfTotal = share(budget.allocated, totalAllocated),
                            isOwn = budget.department == ownDepartment
                        )
                    }
                }

                SecondaryButton(
                    text = "See how funds are being spent",
                    icon = Icons.AutoMirrored.Filled.TrendingUp,
                    onClick = onOpenUtilisation
                )

                Text(
                    "Committed means awarded to a contract. Payments made against those contracts are on " +
                            "Fund Utilisation.",
                    style = AppType.Tiny
                )
            }
        }
    }
}

@Composable
private fun DepartmentBudgetCard(budget: DepartmentBudget, shareOfTotal: Float, isOwn: Boolean) {
    AppCard(
        border = if (isOwn) AppColor.InfoBorder else AppColor.Line
    ) {
        val badge: (@Composable () -> Unit)? =
            if (isOwn) { { StatusBadge("Your department", BadgeTone.Info, showDot = false) } } else null
        CardHeader(
            title = budget.department,
            subtitle = "${Format.money(budget.allocated)} allocated · ${Format.percent(shareOfTotal, 1)} of the total",
            trailing = badge
        )
        Spacer(Modifier.height(10.dp))
        ProgressBar(
            budget.committedFraction,
            color = if (budget.committedFraction >= 1f) AppColor.SuccessBar else AppColor.WarnBar
        )
        Spacer(Modifier.height(7.dp))
        Text(
            "${Format.money(budget.committed)} committed · ${Format.money(budget.uncommitted)} still available",
            style = AppType.Tiny
        )
    }
}

private fun share(part: Double, total: Double): Float = if (total <= 0) 0f else (part / total).toFloat()