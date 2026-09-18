package za.ac.tendertrack.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import za.ac.tendertrack.core.Format
import za.ac.tendertrack.core.Validate
import za.ac.tendertrack.core.friendlyMessage
import za.ac.tendertrack.data.ServiceLocator
import za.ac.tendertrack.data.model.TenderDraft
import za.ac.tendertrack.data.model.TenderStatus
import za.ac.tendertrack.data.repo.TenderRepository
import za.ac.tendertrack.data.sample.SampleData
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.viewModelFactory
import za.ac.tendertrack.ui.theme.Dimens

data class TenderFormUiState(
    val reference: String = "",
    val department: String? = null,
    val title: String = "",
    val description: String = "",
    val category: String? = null,
    val budget: String = "",
    val closingDate: String = "",
    val closingTime: String = "11:00",
    val contractMonths: String = "12",
    val errors: Map<String, String> = emptyMap(),
    val loading: Boolean = true,
    val submitting: Boolean = false,
    val formError: String? = null,
    val isEdit: Boolean = false
)

/**
 * Backs both Register Tender and Edit Tender — the same fields and the same
 * validation, so the two forms cannot drift apart.
 */
class TenderFormViewModel(
    private val tenderId: String?,
    private val repository: TenderRepository = ServiceLocator.tenderRepository
) : ViewModel() {

    private val _state = MutableStateFlow(TenderFormUiState(isEdit = tenderId != null))
    val state: StateFlow<TenderFormUiState> = _state.asStateFlow()

    init { load() }

    private fun load() {
        viewModelScope.launch {
            if (tenderId == null) {
                val department = SampleData.currentUser.department ?: SampleData.departments.first()
                _state.update {
                    it.copy(
                        loading = false,
                        department = department,
                        reference = SampleData.nextReference(department)
                    )
                }
                return@launch
            }
            try {
                val tender = repository.byId(tenderId) ?: error("Tender not found.")
                _state.update {
                    it.copy(
                        loading = false,
                        reference = tender.referenceNumber,
                        department = tender.department,
                        title = tender.title,
                        description = tender.description,
                        category = tender.category,
                        budget = tender.estimatedBudget.toLong().toString(),
                        closingDate = Format.isoDate(tender.closingDate).split("-").reversed().joinToString("/"),
                        closingTime = Format.dateTime(tender.closingDate).substringAfter("· ").trim(),
                        contractMonths = tender.contractPeriodMonths.toString()
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, formError = e.friendlyMessage()) }
            }
        }
    }

    fun update(transform: (TenderFormUiState) -> TenderFormUiState) =
        _state.update { transform(it).copy(formError = null) }

    private fun validate(s: TenderFormUiState): Map<String, String> = buildMap {
        Validate.required(s.reference, "Reference number")?.let { put("reference", it) }
        if (s.department.isNullOrBlank()) put("department", "Department is required")
        Validate.required(s.title, "Tender title")?.let { put("title", it) }
        Validate.required(s.description, "Description")?.let { put("description", it) }
        if (s.category.isNullOrBlank()) put("category", "Category is required")
        Validate.amount(s.budget, "Estimated budget")?.let { put("budget", it) }
        Validate.date(s.closingDate, "Closing date")?.let { put("closingDate", it) }
        Validate.time(s.closingTime, "Closing time")?.let { put("closingTime", it) }
        Validate.integer(s.contractMonths, "Contract period")?.let { put("contractMonths", it) }
    }

    fun save(onSaved: (String) -> Unit) {
        val current = _state.value
        val errors = validate(current)
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors) }
            return
        }
        _state.update { it.copy(submitting = true, errors = emptyMap()) }
        viewModelScope.launch {
            try {
                val draft = TenderDraft(
                    referenceNumber = current.reference.trim(),
                    title = current.title.trim(),
                    description = current.description.trim(),
                    department = current.department!!,
                    category = current.category!!,
                    estimatedBudget = Validate.parseAmount(current.budget)!!,
                    closingDate = Validate.toIso(current.closingDate, current.closingTime),
                    contractPeriodMonths = current.contractMonths.trim().toInt(),
                    status = TenderStatus.REGISTERED
                )
                val saved = if (tenderId == null) repository.create(draft)
                else repository.update(tenderId, draft)
                _state.update { it.copy(submitting = false) }
                onSaved(saved.id)
            } catch (e: Exception) {
                _state.update { it.copy(submitting = false, formError = e.friendlyMessage()) }
            }
        }
    }
}

@Composable
fun TenderFormScreen(
    tenderId: String?,
    onBack: () -> Unit,
    onSaved: (String) -> Unit
) {
    val viewModel: TenderFormViewModel = viewModel(
        key = "tender_form_${tenderId ?: "new"}",
        factory = viewModelFactory { TenderFormViewModel(tenderId) }
    )
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    AppScaffold(
        title = if (state.isEdit) "Edit Tender" else "Register Tender",
        onBack = onBack,
        snackbarHostState = snackbar
    ) {
        if (state.loading) {
            LoadingState()
            return@AppScaffold
        }

        AppTextField(
            label = "Tender reference number",
            value = state.reference,
            onValueChange = { v -> viewModel.update { it.copy(reference = v) } },
            placeholder = "GP/IT/2291",
            hint = if (state.isEdit) null else "Generated from your department's series. You can change it.",
            error = state.errors["reference"]
        )

        AppDropdownField(
            label = "Department",
            selected = state.department,
            options = SampleData.departments,
            optionLabel = { it },
            onSelect = { v -> viewModel.update { it.copy(department = v) } },
            placeholder = "Select a department",
            error = state.errors["department"]
        )

        AppTextField(
            label = "Tender title",
            value = state.title,
            onValueChange = { v -> viewModel.update { it.copy(title = v) } },
            placeholder = "e.g. Upgrade IT Infrastructure",
            error = state.errors["title"]
        )

        AppTextField(
            label = "Description",
            value = state.description,
            onValueChange = { v -> viewModel.update { it.copy(description = v) } },
            placeholder = "Provide the detailed scope of work…",
            singleLine = false,
            minHeight = 96.dp,
            error = state.errors["description"]
        )

        AppDropdownField(
            label = "Category",
            selected = state.category,
            options = SampleData.categories,
            optionLabel = { it },
            onSelect = { v -> viewModel.update { it.copy(category = v) } },
            placeholder = "Select a category",
            error = state.errors["category"]
        )

        AppTextField(
            label = "Estimated budget (R)",
            value = state.budget,
            onValueChange = { v -> viewModel.update { it.copy(budget = v) } },
            placeholder = "18 400 000",
            keyboardType = KeyboardType.Number,
            error = state.errors["budget"]
        )

        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMd)) {
            AppTextField(
                label = "Closing date",
                value = state.closingDate,
                onValueChange = { v -> viewModel.update { it.copy(closingDate = v) } },
                placeholder = "dd/mm/yyyy",
                leadingIcon = Icons.Default.CalendarToday,
                keyboardType = KeyboardType.Number,
                error = state.errors["closingDate"],
                modifier = Modifier.weight(1f)
            )
            AppTextField(
                label = "Closing time",
                value = state.closingTime,
                onValueChange = { v -> viewModel.update { it.copy(closingTime = v) } },
                placeholder = "11:00",
                leadingIcon = Icons.Default.Schedule,
                keyboardType = KeyboardType.Number,
                error = state.errors["closingTime"],
                modifier = Modifier.weight(1f)
            )
        }

        AppTextField(
            label = "Contract period (months)",
            value = state.contractMonths,
            onValueChange = { v -> viewModel.update { it.copy(contractMonths = v) } },
            placeholder = "24",
            keyboardType = KeyboardType.Number,
            error = state.errors["contractMonths"]
        )

        NoteBanner(
            text = if (state.isEdit)
                "Changes are recorded in the audit trail. A tender that is already open for bids " +
                    "keeps its closing date unless you change it here."
            else
                "Saving creates the tender in the Registered state. It becomes publicly visible only " +
                    "once you publish it from the tender's detail screen.",
            icon = Icons.Default.Info
        )

        if (state.formError != null) {
            NoteBanner(state.formError!!, NoteTone.Danger)
        }

        PrimaryButton(
            text = if (state.isEdit) "Save changes" else "Save tender",
            loading = state.submitting,
            onClick = { viewModel.save(onSaved) }
        )
        SecondaryButton("Cancel", onClick = onBack)
    }
}
