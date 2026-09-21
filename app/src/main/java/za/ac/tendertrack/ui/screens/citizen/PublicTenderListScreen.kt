package za.ac.tendertrack.ui.screens.citizen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import za.ac.tendertrack.core.UiState
import za.ac.tendertrack.data.ServiceLocator
import za.ac.tendertrack.data.model.Tender
import za.ac.tendertrack.data.model.TenderStatus
import za.ac.tendertrack.data.repo.PublicRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens
import za.ac.tendertrack.ui.viewModelFactory

// ---------------------------------------------------------------------------
// State + ViewModel
// ---------------------------------------------------------------------------

data class PublicTenderListUiState(
    val tenders: UiState<List<Tender>> = UiState.Loading,
    val query: String = "",
    val status: TenderStatus? = null,
    val department: String = ALL_DEPARTMENTS,
    val budget: BudgetBand = BudgetBand.ANY,
    val dateWindow: DateWindow = DateWindow.ANY
) {
    /** Departments that actually have published tenders, for the dropdown. */
    val departments: List<String>
        get() = listOf(ALL_DEPARTMENTS) +
            ((tenders as? UiState.Success)?.data?.map { it.department }?.distinct()?.sorted() ?: emptyList())

    val filtersActive: Boolean
        get() = status != null || department != ALL_DEPARTMENTS ||
            budget != BudgetBand.ANY || dateWindow != DateWindow.ANY

    /** Search and all four filters applied together, so the count is always right. */
    val visible: List<Tender>
        get() {
            val all = (tenders as? UiState.Success)?.data ?: return emptyList()
            return all.filter { tender ->
                val matchesQuery = query.isBlank() ||
                    tender.referenceNumber.contains(query, true) ||
                    tender.title.contains(query, true) ||
                    tender.department.contains(query, true) ||
                    tender.category.contains(query, true) ||
                    (tender.awardedSupplierName?.contains(query, true) ?: false)
                matchesQuery &&
                    (status == null || tender.status == status) &&
                    (department == ALL_DEPARTMENTS || tender.department == department) &&
                    budget.matches(tender) &&
                    dateWindow.matches(tender)
            }
        }
}

class PublicTenderListViewModel(
    initialStatus: TenderStatus?,
    private val repository: PublicRepository = ServiceLocator.publicRepository
) : ViewModel() {

    private val _state = MutableStateFlow(PublicTenderListUiState(status = initialStatus))
    val state: StateFlow<PublicTenderListUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(tenders = UiState.Loading) }
        viewModelScope.launch {
            val result = try {
                UiState.Success(repository.tenders())
            } catch (e: Exception) {
                UiState.Error(e.citizenMessage())
            }
            _state.update { it.copy(tenders = result) }
        }
    }

    fun onQuery(value: String) = _state.update { it.copy(query = value) }
    fun onStatus(value: TenderStatus?) = _state.update { it.copy(status = value) }
    fun onDepartment(value: String) = _state.update { it.copy(department = value) }
    fun onBudget(value: BudgetBand) = _state.update { it.copy(budget = value) }
    fun onDateWindow(value: DateWindow) = _state.update { it.copy(dateWindow = value) }

    fun clearFilters() = _state.update {
        it.copy(status = null, department = ALL_DEPARTMENTS, budget = BudgetBand.ANY, dateWindow = DateWindow.ANY)
    }
}

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

/**
 * Search Tenders — FR13. Keyword search plus filters for status, department,
 * budget range and closing date, with no sign-in. There is deliberately no
 * "Apply" button: TenderTrack tracks tenders, it does not accept bids.
 */
@Composable
fun PublicTenderListScreen(
    initialStatus: TenderStatus?,
    onBack: () -> Unit,
    onOpenTender: (String) -> Unit
) {
    val viewModel: PublicTenderListViewModel = viewModel(
        key = "public_tenders_${initialStatus?.name ?: "all"}",
        factory = viewModelFactory { PublicTenderListViewModel(initialStatus) }
    )
    val state by viewModel.state.collectAsState()

    AppScaffold(title = "Search Tenders", onBack = onBack) {
        SearchField(
            value = state.query,
            onValueChange = viewModel::onQuery,
            placeholder = "Search reference, title, department or supplier"
        )

        // Status — the registered stage is never shown to the public.
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)
        ) {
            // onClick is passed by name: in FilterChip it is not the last parameter,
            // so a trailing lambda would be bound to `modifier` instead.
            FilterChip(text = "All", selected = state.status == null, onClick = { viewModel.onStatus(null) })
            TenderStatus.lifecycle.filter { it != TenderStatus.REGISTERED }.forEach { status ->
                FilterChip(
                    text = status.displayName,
                    selected = state.status == status,
                    onClick = { viewModel.onStatus(status) }
                )
            }
        }

        AppDropdownField(
            label = "Department",
            selected = state.department,
            options = state.departments,
            optionLabel = { it },
            onSelect = viewModel::onDepartment
        )
        AppDropdownField(
            label = "Contract value",
            selected = state.budget,
            options = BudgetBand.entries.toList(),
            optionLabel = { it.label },
            onSelect = viewModel::onBudget,
            hint = "Values are published once a tender is awarded."
        )
        AppDropdownField(
            label = "Closing date",
            selected = state.dateWindow,
            options = DateWindow.entries.toList(),
            optionLabel = { it.label },
            onSelect = viewModel::onDateWindow
        )

        when (val result = state.tenders) {
            is UiState.Loading -> LoadingState(message = "Loading published tenders…")
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> {
                val visible = state.visible
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${visible.size} of ${result.data.size} published tenders", style = AppType.Meta)
                    if (state.filtersActive) {
                        TextAction("Clear filters", color = AppColor.InfoInk, onClick = viewModel::clearFilters)
                    }
                }

                if (visible.isEmpty()) {
                    EmptyState(
                        title = "No tenders match",
                        message = "Try a different search term, or clear the filters.",
                        icon = Icons.Default.Description
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.ListGap)) {
                        visible.forEach { tender ->
                            // Public version of the officer's card: the estimate
                            // is only shown once the tender is awarded.
                            PublicTenderCard(tender, onClick = { onOpenTender(tender.id) })
                        }
                    }
                }
            }
        }
    }
}
