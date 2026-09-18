package za.ac.tendertrack.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
import za.ac.tendertrack.data.model.PaymentDraft
import za.ac.tendertrack.data.model.Tender
import za.ac.tendertrack.data.model.TenderStatus
import za.ac.tendertrack.data.repo.PaymentRepository
import za.ac.tendertrack.data.repo.TenderRepository
import za.ac.tendertrack.data.sample.SampleData
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.viewModelFactory

data class RecordPaymentUiState(
    val payable: UiState<List<Tender>> = UiState.Loading,
    val tender: Tender? = null,
    val milestone: String? = null,
    val amount: String = "",
    val paidOn: String = "",
    val invoice: String = "",
    val errors: Map<String, String> = emptyMap(),
    val action: ActionState = ActionState.Idle
) {
    /** What the contract would stand at if this payment went through. */
    val projectedTotal: Double?
        get() {
            val t = tender ?: return null
            val entered = Validate.parseAmount(amount) ?: return null
            return t.paidToDate + entered
        }

    val wouldExceedAward: Boolean
        get() {
            val t = tender ?: return false
            val awarded = t.awardedValue ?: return false
            val projected = projectedTotal ?: return false
            return projected > awarded + 0.005
        }
}

class RecordPaymentViewModel(
    private val preselectedTenderId: String?,
    private val tenderRepository: TenderRepository = ServiceLocator.tenderRepository,
    private val paymentRepository: PaymentRepository = ServiceLocator.paymentRepository
) : ViewModel() {

    private val _state = MutableStateFlow(RecordPaymentUiState())
    val state: StateFlow<RecordPaymentUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(payable = UiState.Loading) }
        viewModelScope.launch {
            try {
                // A payment can only be recorded against a tender that has been awarded.
                val payable = tenderRepository.list().filter {
                    it.awardedValue != null &&
                        it.status in listOf(TenderStatus.AWARDED, TenderStatus.IN_PROGRESS, TenderStatus.COMPLETED)
                }
                _state.update {
                    it.copy(
                        payable = UiState.Success(payable),
                        tender = payable.firstOrNull { t -> t.id == preselectedTenderId } ?: it.tender
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(payable = UiState.Error(e.friendlyMessage())) }
            }
        }
    }

    fun onTender(tender: Tender) = _state.update { it.copy(tender = tender, errors = it.errors - "tender") }
    fun onMilestone(value: String) = _state.update { it.copy(milestone = value, errors = it.errors - "milestone") }
    fun onAmount(value: String) = _state.update { it.copy(amount = value, errors = it.errors - "amount") }
    fun onPaidOn(value: String) = _state.update { it.copy(paidOn = value, errors = it.errors - "paidOn") }
    fun onInvoice(value: String) = _state.update { it.copy(invoice = value, errors = it.errors - "invoice") }

    fun record(onRecorded: () -> Unit) {
        val current = _state.value
        val errors = buildMap {
            if (current.tender == null) put("tender", "Select the tender being paid")
            if (current.milestone.isNullOrBlank()) put("milestone", "Select the milestone")
            Validate.amount(current.amount, "Payment amount")?.let { put("amount", it) }
            Validate.date(current.paidOn, "Payment date")?.let { put("paidOn", it) }
            Validate.required(current.invoice, "Invoice number")?.let { put("invoice", it) }
            if (current.wouldExceedAward) {
                val awarded = current.tender?.awardedValue ?: 0.0
                put("amount",
                    "This would take total disbursement to ${Format.money(current.projectedTotal ?: 0.0)}, " +
                        "above the awarded value of ${Format.money(awarded)}.")
            }
        }
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors) }
            return
        }

        _state.update { it.copy(action = ActionState.Running) }
        viewModelScope.launch {
            try {
                paymentRepository.record(
                    PaymentDraft(
                        tenderId = current.tender!!.id,
                        milestone = current.milestone!!,
                        amount = Validate.parseAmount(current.amount)!!,
                        paidOn = Validate.isoDateOnly(current.paidOn),
                        invoiceNumber = current.invoice.trim()
                    )
                )
                _state.update {
                    it.copy(
                        amount = "", invoice = "", milestone = null,
                        action = ActionState.Succeeded("Payment recorded.")
                    )
                }
                load()
                onRecorded()
            } catch (e: Exception) {
                _state.update { it.copy(action = ActionState.Failed(e.friendlyMessage())) }
            }
        }
    }

    fun clearAction() = _state.update { it.copy(action = ActionState.Idle) }
}

/**
 * Record Payment — FR10 and FR12.
 *
 * The over-payment rule is shown before submission, blocks the button, and is
 * also enforced by the database, so it holds whatever the client does.
 */
@Composable
fun RecordPaymentScreen(
    tenderId: String?,
    onMenu: (() -> Unit)?,
    onBack: (() -> Unit)?,
    onRecorded: () -> Unit
) {
    val viewModel: RecordPaymentViewModel = viewModel(
        key = "payment_${tenderId ?: "any"}",
        factory = viewModelFactory { RecordPaymentViewModel(tenderId) }
    )
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.action) {
        when (val a = state.action) {
            is ActionState.Succeeded -> { snackbar.showSnackbar(a.message); viewModel.clearAction() }
            is ActionState.Failed -> { snackbar.showSnackbar(a.message); viewModel.clearAction() }
            else -> Unit
        }
    }

    AppScaffold(
        title = "Record Payment",
        onMenu = onMenu,
        onBack = onBack,
        snackbarHostState = snackbar
    ) {
        when (val result = state.payable) {
            is UiState.Loading -> LoadingState()
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> {
                if (result.data.isEmpty()) {
                    EmptyState(
                        title = "No awarded tenders",
                        message = "A payment can only be recorded against a tender that has been awarded.",
                        icon = Icons.Default.Payments
                    )
                    return@AppScaffold
                }

                AppDropdownField(
                    label = "Tender",
                    selected = state.tender,
                    options = result.data,
                    optionLabel = { "${it.referenceNumber} — ${it.title}" },
                    onSelect = viewModel::onTender,
                    placeholder = "Select an awarded tender",
                    error = state.errors["tender"]
                )

                state.tender?.let { tender ->
                    AppCard {
                        KeyValueRow("Supplier", tender.awardedSupplierName ?: "—")
                        KeyValueRow("Awarded value", Format.money(tender.awardedValue ?: 0.0))
                        KeyValueRow(
                            "Paid to date",
                            "${Format.money(tender.paidToDate)} (${Format.percent(tender.utilisation)})"
                        )
                        KeyValueRow(
                            "Remaining",
                            Format.money(tender.remainingValue),
                            valueColor = if (tender.remainingValue <= 0) AppColor.SuccessInk else AppColor.Ink,
                            showDivider = false
                        )
                        Spacer(Modifier.height(12.dp))
                        ProgressBar(
                            tender.utilisation,
                            color = if (tender.utilisation >= 1f) AppColor.SuccessBar else AppColor.WarnBar
                        )
                    }
                }

                AppDropdownField(
                    label = "Milestone",
                    selected = state.milestone,
                    options = SampleData.milestones,
                    optionLabel = { it },
                    onSelect = viewModel::onMilestone,
                    placeholder = "Select the milestone being paid",
                    error = state.errors["milestone"]
                )

                AppTextField(
                    label = "Payment amount (R)",
                    value = state.amount,
                    onValueChange = viewModel::onAmount,
                    placeholder = "0.00",
                    keyboardType = KeyboardType.Number,
                    error = state.errors["amount"]
                )

                AppTextField(
                    label = "Payment date",
                    value = state.paidOn,
                    onValueChange = viewModel::onPaidOn,
                    placeholder = "dd/mm/yyyy",
                    leadingIcon = Icons.Default.CalendarToday,
                    keyboardType = KeyboardType.Number,
                    error = state.errors["paidOn"]
                )

                AppTextField(
                    label = "Invoice / reference number",
                    value = state.invoice,
                    onValueChange = viewModel::onInvoice,
                    placeholder = "e.g. INV-2026-0441",
                    error = state.errors["invoice"]
                )

                // Live warning, shown before the user presses anything.
                if (state.wouldExceedAward) {
                    NoteBanner(
                        title = "This payment cannot be recorded",
                        text = "It would take total disbursement to " +
                            "${Format.money(state.projectedTotal ?: 0.0)}, above the awarded value of " +
                            "${Format.money(state.tender?.awardedValue ?: 0.0)}. Reduce the amount, or " +
                            "raise a variation order before paying more.",
                        tone = NoteTone.Danger,
                        icon = Icons.Default.Warning
                    )
                } else {
                    NoteBanner(
                        "A payment that takes total disbursement above the awarded value is blocked and " +
                            "raises a compliance flag automatically.",
                        NoteTone.Warning,
                        icon = Icons.Default.Warning
                    )
                }

                PrimaryButton(
                    text = "Record payment",
                    enabled = !state.wouldExceedAward,
                    loading = state.action is ActionState.Running,
                    onClick = { viewModel.record(onRecorded) }
                )

                Text(
                    "Recording a payment updates fund utilisation for the department and is written to " +
                        "the audit trail.",
                    style = AppType.Tiny
                )
            }
        }
    }
}
