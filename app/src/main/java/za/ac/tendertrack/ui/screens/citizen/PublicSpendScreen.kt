package za.ac.tendertrack.ui.screens.citizen

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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
import za.ac.tendertrack.data.model.DepartmentSpend
import za.ac.tendertrack.data.repo.PublicRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

class PublicSpendViewModel(
    private val repository: PublicRepository = ServiceLocator.publicRepository
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<DepartmentSpend>>>(UiState.Loading)
    val state: StateFlow<UiState<List<DepartmentSpend>>> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = try {
                UiState.Success(repository.spendByDepartment())
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
 * Spending by Department — FR11 fund transparency. For each department: how
 * many published tenders it has, what was awarded, and what has actually been
 * paid out. Figures are totals of the published tenders themselves.
 */
@Composable
fun PublicSpendScreen(
    onBack: () -> Unit,
    onOpenTenders: () -> Unit,
    viewModel: PublicSpendViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    AppScaffold(title = "Spending", onBack = onBack) {
        ScreenHeading(
            eyebrow = "Fund utilisation",
            title = "Where the money goes",
            subtitle = "Value awarded and paid to suppliers, per department."
        )

        when (val result = state) {
            is UiState.Loading -> LoadingState()
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> {
                val departments = result.data
                if (departments.isEmpty()) {
                    EmptyState(
                        title = "Nothing published yet",
                        message = "Spending appears here once departments publish tenders.",
                        icon = Icons.Default.AccountBalance
                    )
                } else {
                    val awarded = departments.sumOf { it.awarded }
                    val paid = departments.sumOf { it.paid }

                    UtilisationCard(
                        title = "All departments",
                        caption = "${Format.money(paid)} paid of ${Format.money(awarded)} awarded",
                        fraction = if (awarded <= 0) 0f else (paid / awarded).toFloat().coerceIn(0f, 1f)
                    )

                    SectionHeader("By department")
                    departments.forEach { spend -> DepartmentCard(spend) }

                    SecondaryButton(
                        text = "Search these tenders",
                        icon = Icons.Default.Search,
                        onClick = onOpenTenders
                    )
                    Text(
                        "Only published tenders are included. Payments are shown as amounts and dates; " +
                            "invoice numbers and officials' names are not published.",
                        style = AppType.Tiny,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun DepartmentCard(spend: DepartmentSpend) {
    AppCard {
        CardHeader(
            title = spend.department,
            subtitle = "${spend.tenders} published tender${if (spend.tenders == 1) "" else "s"}"
        )
        Spacer(Modifier.height(10.dp))
        KeyValueRow("Estimated budgets", Format.money(spend.estimatedBudget))
        KeyValueRow("Awarded", Format.money(spend.awarded))
        KeyValueRow("Paid to suppliers", Format.money(spend.paid), showDivider = false)
        if (spend.awarded > 0) {
            Spacer(Modifier.height(10.dp))
            ProgressBar(
                spend.paidFraction,
                color = if (spend.paidFraction >= 1f) AppColor.SuccessBar else AppColor.WarnBar
            )
            Spacer(Modifier.height(4.dp))
            Text("${Format.percent(spend.paidFraction)} of awarded value paid", style = AppType.Meta)
        } else {
            Spacer(Modifier.height(4.dp))
            Text("No contracts awarded yet", style = AppType.Meta)
        }
    }
}
