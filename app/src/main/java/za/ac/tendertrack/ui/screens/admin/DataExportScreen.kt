package za.ac.tendertrack.ui.screens.admin

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import za.ac.tendertrack.data.model.Payment
import za.ac.tendertrack.data.model.Tender
import za.ac.tendertrack.data.repo.AdminRepository
import za.ac.tendertrack.ui.components.*

/** Both datasets, loaded together. */
data class ExportData(val tenders: List<Tender>, val payments: List<Payment>)

class DataExportViewModel(
    private val repository: AdminRepository = ServiceLocator.adminRepository
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<ExportData>>(UiState.Loading)
    val state: StateFlow<UiState<ExportData>> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = try {
                UiState.Success(ExportData(repository.tendersForExport(), repository.paymentsForExport()))
            } catch (e: Exception) {
                UiState.Error(e.adminMessage())
            }
        }
    }

    fun tendersCsv(tenders: List<Tender>): String = buildString {
        appendLine(csvRow("reference", "title", "department", "category", "status", "estimated_budget",
            "awarded_supplier", "awarded_value", "paid_to_date", "closing_date", "open_flags"))
        tenders.forEach { t ->
            appendLine(csvRow(t.referenceNumber, t.title, t.department, t.category, t.status.displayName,
                t.estimatedBudget, t.awardedSupplierName, t.awardedValue, t.paidToDate, t.closingDate,
                t.openFlagCount))
        }
    }

    fun paymentsCsv(payments: List<Payment>): String = buildString {
        appendLine(csvRow("tender_reference", "milestone", "amount", "paid_on", "invoice_number", "recorded_by"))
        payments.forEach { p ->
            appendLine(csvRow(p.tenderReference, p.milestone, p.amount, p.paidOn, p.invoiceNumber, p.recordedBy))
        }
    }
}

/**
 * Export Data — FR17: "The system will allow Admin users to export tender and
 * payment data for audit or reporting purposes." Produces CSV files that open
 * in Excel, shared through the system share sheet (email, Drive, etc.).
 */
@Composable
fun DataExportScreen(
    onBack: () -> Unit,
    viewModel: DataExportViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun share(fileName: String, content: String) {
        if (!shareCsv(context, fileName, content)) {
            scope.launch { snackbar.showSnackbar("Could not open the share sheet.") }
        }
    }

    AppScaffold(title = "Export Data", onBack = onBack, snackbarHostState = snackbar) {
        ScreenHeading(
            eyebrow = "FR17",
            title = "Export tender and payment data",
            subtitle = "CSV files for audit and reporting. They open in Excel."
        )

        when (val result = state) {
            is UiState.Loading -> LoadingState(message = "Loading tenders and payments…")
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> {
                val data = result.data
                AppCard {
                    CardHeader(title = "Tenders", subtitle = "Every tender, at every stage, with its estimate")
                    Spacer(Modifier.height(10.dp))
                    KeyValueRow("Records", "${data.tenders.size}")
                    KeyValueRow("Awarded value", Format.money(data.tenders.sumOf { it.awardedValue ?: 0.0 }))
                    KeyValueRow("Paid to date", Format.money(data.tenders.sumOf { it.paidToDate }), showDivider = false)
                    Spacer(Modifier.height(10.dp))
                    PrimaryButton(
                        text = "Export tenders (CSV)",
                        icon = Icons.Default.Download,
                        enabled = data.tenders.isNotEmpty(),
                        onClick = { share("tendertrack-tenders.csv", viewModel.tendersCsv(data.tenders)) }
                    )
                }
                AppCard {
                    CardHeader(title = "Payments", subtitle = "Including invoice numbers and who recorded them")
                    Spacer(Modifier.height(10.dp))
                    KeyValueRow("Records", "${data.payments.size}")
                    KeyValueRow("Total paid", Format.money(data.payments.sumOf { it.amount }), showDivider = false)
                    Spacer(Modifier.height(10.dp))
                    PrimaryButton(
                        text = "Export payments (CSV)",
                        icon = Icons.Default.Download,
                        enabled = data.payments.isNotEmpty(),
                        onClick = { share("tendertrack-payments.csv", viewModel.paymentsCsv(data.payments)) }
                    )
                }
                NoteBanner(
                    title = "Handle with care",
                    text = "These files include figures and names that the public app does not show, such " +
                        "as estimates on open tenders and who recorded each payment. Share them only for " +
                        "audit or reporting.",
                    tone = NoteTone.Warning,
                    icon = Icons.Default.Shield
                )
            }
        }
    }
}
