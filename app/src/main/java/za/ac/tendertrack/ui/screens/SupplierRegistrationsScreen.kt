package za.ac.tendertrack.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import za.ac.tendertrack.core.Format
import za.ac.tendertrack.core.UiState
import za.ac.tendertrack.core.friendlyMessage
import za.ac.tendertrack.data.ServiceLocator
import za.ac.tendertrack.data.model.Supplier
import za.ac.tendertrack.data.model.SupplierVerificationStatus
import za.ac.tendertrack.data.repo.SupplierRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens

data class SupplierListUiState(
    val suppliers: UiState<List<Supplier>> = UiState.Loading,
    val filter: SupplierVerificationStatus = SupplierVerificationStatus.AWAITING_VERIFICATION
) {
    val visible: List<Supplier>
        get() = (suppliers as? UiState.Success)?.data?.filter { it.status == filter } ?: emptyList()

    fun countOf(status: SupplierVerificationStatus): Int =
        (suppliers as? UiState.Success)?.data?.count { it.status == status } ?: 0
}

class SupplierListViewModel(
    private val repository: SupplierRepository = ServiceLocator.supplierRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SupplierListUiState())
    val state: StateFlow<SupplierListUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(suppliers = UiState.Loading) }
        viewModelScope.launch {
            val result = try {
                UiState.Success(repository.list())
            } catch (e: Exception) {
                UiState.Error(e.friendlyMessage())
            }
            _state.update { it.copy(suppliers = result) }
        }
    }

    fun onFilter(status: SupplierVerificationStatus) = _state.update { it.copy(filter = status) }
}

/**
 * Supplier Registrations — company verification only.
 *
 * Renamed from the original "Tracking Tender Status" screen, and its statuses
 * describe registrations rather than tenders.
 */
@Composable
fun SupplierRegistrationsScreen(
    onMenu: () -> Unit,
    onOpenSupplier: (String) -> Unit,
    viewModel: SupplierListViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    AppScaffold(
        title = "Supplier Registrations",
        onMenu = onMenu,
        actions = listOf(TopBarAction(Icons.Default.Refresh, "Refresh") { viewModel.load() })
    ) {
        ScreenHeading(
            title = "Registrations to verify",
            subtitle = "Company verification only. This queue does not evaluate bids.",
            small = true
        )

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)
        ) {
            SupplierVerificationStatus.entries.forEach { status ->
                FilterChip(
                    text = "${status.displayName} (${state.countOf(status)})",
                    selected = state.filter == status,
                    onClick = { viewModel.onFilter(status) }
                )
            }
        }

        when (val result = state.suppliers) {
            is UiState.Loading -> LoadingState()
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> {
                val visible = state.visible
                if (visible.isEmpty()) {
                    EmptyState(
                        title = "Nothing here",
                        message = "There are no registrations with the status ${state.filter.displayName.lowercase()}.",
                        icon = Icons.Default.Groups
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.ListGap)) {
                        visible.forEach { supplier ->
                            SupplierCard(supplier) { onOpenSupplier(supplier.id) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SupplierCard(supplier: Supplier, onClick: () -> Unit) {
    val taxExpiryDays = Format.daysUntil(supplier.taxClearanceExpiry)
    AppCard(onClick = onClick) {
        CardHeader(
            title = supplier.companyName,
            subtitle = "CSD ${supplier.csdNumber} · submitted ${Format.date(supplier.submittedAt)}",
            trailing = { StatusBadge(supplier.status.displayName, supplier.status.tone()) }
        )
        Spacer(Modifier.height(10.dp))
        KeyValueRow(
            "Documents",
            "${supplier.documentsReceived} of ${supplier.documentsRequired} received",
            valueColor = if (supplier.documentsComplete) AppColor.SuccessInk else AppColor.WarnInk
        )
        KeyValueRow(
            "Tax clearance",
            when {
                supplier.taxClearanceExpiry == null -> "Not uploaded"
                taxExpiryDays != null && taxExpiryDays < 0 ->
                    "Expired ${Format.date(supplier.taxClearanceExpiry)}"
                else -> "Valid to ${Format.date(supplier.taxClearanceExpiry)}"
            },
            valueColor = when {
                supplier.taxClearanceExpiry == null -> AppColor.WarnInk
                taxExpiryDays != null && taxExpiryDays < 0 -> AppColor.DangerInk
                else -> AppColor.SuccessInk
            }
        )
        KeyValueRow(
            "B-BBEE",
            supplier.bbbeeLevel?.let { "Level $it" } ?: "Not provided",
            showDivider = false
        )
        if (supplier.decisionReason != null) {
            Spacer(Modifier.height(10.dp))
            Text(supplier.decisionReason, style = AppType.Tiny.copy(color = AppColor.DangerInk))
        }
    }
}

fun SupplierVerificationStatus.tone(): BadgeTone = when (this) {
    SupplierVerificationStatus.AWAITING_VERIFICATION -> BadgeTone.Warning
    SupplierVerificationStatus.VERIFIED -> BadgeTone.Success
    SupplierVerificationStatus.NOT_APPROVED -> BadgeTone.Danger
}
