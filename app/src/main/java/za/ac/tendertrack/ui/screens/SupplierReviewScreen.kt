package za.ac.tendertrack.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.SnackbarHostState
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import za.ac.tendertrack.core.*
import za.ac.tendertrack.data.ServiceLocator
import za.ac.tendertrack.data.model.Supplier
import za.ac.tendertrack.data.model.SupplierVerificationStatus
import za.ac.tendertrack.data.repo.SupplierRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.viewModelFactory

data class SupplierReviewUiState(
    val supplier: UiState<Supplier> = UiState.Loading,
    val rejecting: Boolean = false,
    val reason: String = "",
    val reasonError: String? = null,
    val action: ActionState = ActionState.Idle
)

class SupplierReviewViewModel(
    private val supplierId: String,
    private val repository: SupplierRepository = ServiceLocator.supplierRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SupplierReviewUiState())
    val state: StateFlow<SupplierReviewUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(supplier = UiState.Loading) }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    supplier = try {
                        UiState.Success(repository.byId(supplierId) ?: error("Registration not found."))
                    } catch (e: Exception) {
                        UiState.Error(e.friendlyMessage())
                    }
                )
            }
        }
    }

    fun startReject() = _state.update { it.copy(rejecting = true) }
    fun cancelReject() = _state.update { it.copy(rejecting = false, reason = "", reasonError = null) }
    fun onReason(value: String) = _state.update { it.copy(reason = value, reasonError = null) }

    fun approve(onDone: () -> Unit) = perform("Registration verified.", onDone) {
        repository.approve(supplierId)
    }

    /**
     * Rejection always carries a reason. It is shown to the supplier verbatim on
     * their "Registration not approved" screen, which is why it cannot be blank.
     */
    fun reject(onDone: () -> Unit) {
        val reasonError = Validate.required(_state.value.reason, "Reason")
        if (reasonError != null) {
            _state.update { it.copy(reasonError = reasonError) }
            return
        }
        perform("Registration marked as not approved.", onDone) {
            repository.reject(supplierId, _state.value.reason.trim())
        }
    }

    private fun perform(message: String, onDone: () -> Unit, block: suspend () -> Supplier) {
        _state.update { it.copy(action = ActionState.Running) }
        viewModelScope.launch {
            try {
                val updated = block()
                _state.update {
                    it.copy(
                        supplier = UiState.Success(updated),
                        rejecting = false,
                        action = ActionState.Succeeded(message)
                    )
                }
                onDone()
            } catch (e: Exception) {
                _state.update { it.copy(action = ActionState.Failed(e.friendlyMessage())) }
            }
        }
    }

    fun clearAction() = _state.update { it.copy(action = ActionState.Idle) }
}

/** Review one supplier registration and verify or decline it. */
@Composable
fun SupplierReviewScreen(
    supplierId: String,
    onBack: () -> Unit
) {
    val viewModel: SupplierReviewViewModel = viewModel(
        key = "supplier_$supplierId",
        factory = viewModelFactory { SupplierReviewViewModel(supplierId) }
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

    AppScaffold(title = "Review Registration", onBack = onBack, snackbarHostState = snackbar) {
        when (val result = state.supplier) {
            is UiState.Loading -> LoadingState()
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> {
                val supplier = result.data
                val busy = state.action is ActionState.Running
                val taxDays = Format.daysUntil(supplier.taxClearanceExpiry)

                ScreenHeading(
                    title = supplier.companyName,
                    subtitle = "Submitted ${Format.date(supplier.submittedAt)}",
                    small = true,
                    trailing = { StatusBadge(supplier.status.displayName, supplier.status.tone()) }
                )

                AppCard {
                    Text("Company details", style = AppType.H2)
                    Spacer(Modifier.height(10.dp))
                    KeyValueRow("Registration number", supplier.registrationNumber)
                    KeyValueRow("CSD supplier number", supplier.csdNumber)
                    KeyValueRow("Business type", supplier.businessType.ifBlank { "—" })
                    KeyValueRow("B-BBEE level", supplier.bbbeeLevel?.let { "Level $it" } ?: "Not provided")
                    KeyValueRow("Contact email", supplier.contactEmail.ifBlank { "—" })
                    KeyValueRow("Address", supplier.physicalAddress.ifBlank { "—" }, showDivider = false)
                }

                AppCard {
                    Text("Verification checks", style = AppType.H2)
                    Spacer(Modifier.height(10.dp))
                    KeyValueRow(
                        "Documents received",
                        "${supplier.documentsReceived} of ${supplier.documentsRequired}",
                        valueColor = if (supplier.documentsComplete) AppColor.SuccessInk else AppColor.WarnInk
                    )
                    KeyValueRow(
                        "Tax clearance",
                        when {
                            supplier.taxClearanceExpiry == null -> "Not uploaded"
                            taxDays != null && taxDays < 0 -> "Expired ${Format.date(supplier.taxClearanceExpiry)}"
                            else -> "Valid to ${Format.date(supplier.taxClearanceExpiry)}"
                        },
                        valueColor = when {
                            supplier.taxClearanceExpiry == null -> AppColor.WarnInk
                            taxDays != null && taxDays < 0 -> AppColor.DangerInk
                            else -> AppColor.SuccessInk
                        },
                        showDivider = false
                    )
                }

                if (supplier.decisionReason != null) {
                    NoteBanner(
                        title = "Reason given to the supplier",
                        text = supplier.decisionReason,
                        tone = NoteTone.Danger
                    )
                }

                when {
                    supplier.status != SupplierVerificationStatus.AWAITING_VERIFICATION -> {
                        NoteBanner(
                            "This registration has already been decided. Reopen it from the " +
                                "Procurement Office if the decision needs to change.",
                            NoteTone.Neutral,
                            icon = Icons.Default.Info
                        )
                    }
                    state.rejecting -> {
                        AppTextField(
                            label = "Why is it not approved?",
                            value = state.reason,
                            onValueChange = viewModel::onReason,
                            placeholder = "e.g. The Tax Clearance Certificate expired on 30 June 2026.",
                            hint = "The supplier sees this wording, so write it in plain language and " +
                                "say what they need to do next.",
                            singleLine = false,
                            minHeight = 96.dp,
                            error = state.reasonError
                        )
                        PrimaryButton(
                            "Confirm — not approved",
                            icon = Icons.Default.Close,
                            loading = busy,
                            onClick = { viewModel.reject(onBack) }
                        )
                        SecondaryButton("Cancel", enabled = !busy, onClick = viewModel::cancelReject)
                    }
                    else -> {
                        PrimaryButton(
                            "Verify this supplier",
                            icon = Icons.Default.Check,
                            enabled = !busy,
                            onClick = { viewModel.approve(onBack) }
                        )
                        SecondaryButton(
                            "Do not approve",
                            icon = Icons.Default.Close,
                            danger = true,
                            enabled = !busy,
                            onClick = viewModel::startReject
                        )
                        NoteBanner(
                            "Only a verified supplier can be selected when awarding a tender.",
                            NoteTone.Neutral,
                            icon = Icons.Default.Info
                        )
                    }
                }
            }
        }
    }
}
