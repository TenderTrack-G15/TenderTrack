package za.ac.tendertrack.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import za.ac.tendertrack.core.*
import za.ac.tendertrack.data.ServiceLocator
import za.ac.tendertrack.data.model.Supplier
import za.ac.tendertrack.data.model.Tender
import za.ac.tendertrack.data.repo.SupplierRepository
import za.ac.tendertrack.data.repo.TenderRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.viewModelFactory

data class AwardUiState(
    val tender: UiState<Tender> = UiState.Loading,
    val verifiedSuppliers: List<Supplier> = emptyList(),
    val supplier: Supplier? = null,
    val value: String = "",
    val awardDate: String = "",
    val errors: Map<String, String> = emptyMap(),
    val action: ActionState = ActionState.Idle
)

class AwardViewModel(
    private val tenderId: String,
    private val tenderRepository: TenderRepository = ServiceLocator.tenderRepository,
    private val supplierRepository: SupplierRepository = ServiceLocator.supplierRepository
) : ViewModel() {

    private val _state = MutableStateFlow(AwardUiState())
    val state: StateFlow<AwardUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(tender = UiState.Loading) }
        viewModelScope.launch {
            try {
                val tender = tenderRepository.byId(tenderId) ?: error("Tender not found.")
                _state.update {
                    it.copy(
                        tender = UiState.Success(tender),
                        verifiedSuppliers = supplierRepository.verified(),
                        value = tender.estimatedBudget.toLong().toString()
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(tender = UiState.Error(e.friendlyMessage())) }
            }
        }
    }

    fun onSupplier(supplier: Supplier) =
        _state.update { it.copy(supplier = supplier, errors = it.errors - "supplier") }

    fun onValue(value: String) = _state.update { it.copy(value = value, errors = it.errors - "value") }
    fun onDate(value: String) = _state.update { it.copy(awardDate = value, errors = it.errors - "date") }

    /**
     * Confirms the award. The 10-digit code is generated and delivered
     * server-side and is deliberately not returned here, so it never appears on
     * the officer's device.
     */
    fun confirm(onAwarded: () -> Unit) {
        val current = _state.value
        val errors = buildMap {
            if (current.supplier == null) put("supplier", "Select the awarded supplier")
            Validate.amount(current.value, "Awarded value")?.let { put("value", it) }
            Validate.date(current.awardDate, "Award date")?.let { put("date", it) }
        }
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors) }
            return
        }

        _state.update { it.copy(action = ActionState.Running) }
        viewModelScope.launch {
            try {
                tenderRepository.award(
                    id = tenderId,
                    supplierId = current.supplier!!.id,
                    supplierName = current.supplier.companyName,
                    value = Validate.parseAmount(current.value)!!,
                    awardDate = Validate.toIso(current.awardDate)
                )
                _state.update {
                    it.copy(action = ActionState.Succeeded(
                        "Tender awarded. The award code has been sent to the supplier."
                    ))
                }
                onAwarded()
            } catch (e: Exception) {
                _state.update { it.copy(action = ActionState.Failed(e.friendlyMessage())) }
            }
        }
    }

    fun clearAction() = _state.update { it.copy(action = ActionState.Idle) }
}

/** Award Tender — the point at which the award code is issued (FR4). */
@Composable
fun AwardTenderScreen(
    tenderId: String,
    onBack: () -> Unit,
    onAwarded: () -> Unit
) {
    val viewModel: AwardViewModel = viewModel(
        key = "award_$tenderId",
        factory = viewModelFactory { AwardViewModel(tenderId) }
    )
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.action) {
        val a = state.action
        if (a is ActionState.Failed) { snackbar.showSnackbar(a.message); viewModel.clearAction() }
    }

    AppScaffold(title = "Award Tender", onBack = onBack, snackbarHostState = snackbar) {
        when (val result = state.tender) {
            is UiState.Loading -> LoadingState()
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> {
                val tender = result.data

                ScreenHeading(
                    title = tender.title,
                    eyebrow = tender.referenceNumber,
                    subtitle = "Estimated budget ${Format.money(tender.estimatedBudget)}",
                    small = true
                )

                AppDropdownField(
                    label = "Awarded supplier",
                    selected = state.supplier,
                    options = state.verifiedSuppliers,
                    optionLabel = { it.companyName },
                    onSelect = viewModel::onSupplier,
                    placeholder = "Select a verified supplier",
                    hint = "Only suppliers whose registration has been verified appear in this list.",
                    error = state.errors["supplier"]
                )

                AppTextField(
                    label = "Awarded value (R)",
                    value = state.value,
                    onValueChange = viewModel::onValue,
                    placeholder = "17 950 000",
                    keyboardType = KeyboardType.Number,
                    hint = "A value more than 10% away from the estimate raises a compliance flag " +
                        "automatically.",
                    error = state.errors["value"]
                )

                AppTextField(
                    label = "Award date",
                    value = state.awardDate,
                    onValueChange = viewModel::onDate,
                    placeholder = "dd/mm/yyyy",
                    leadingIcon = Icons.Default.CalendarToday,
                    keyboardType = KeyboardType.Number,
                    error = state.errors["date"]
                )

                NoteBanner(
                    title = "A 10-digit award code will be issued",
                    text = "On confirming this award the system generates a single-use code and sends it " +
                        "to the supplier by email and SMS. The supplier enters it to claim the award. " +
                        "The code is not shown on this screen and cannot be retrieved from this device.",
                    tone = NoteTone.Info,
                    icon = Icons.Default.VpnKey
                )

                PrimaryButton(
                    text = "Confirm award and issue code",
                    loading = state.action is ActionState.Running,
                    onClick = { viewModel.confirm(onAwarded) }
                )

                Text(
                    "This moves the tender to Awarded, notifies unsuccessful bidders and is recorded " +
                        "in the audit trail.",
                    style = AppType.Tiny
                )
            }
        }
    }
}
