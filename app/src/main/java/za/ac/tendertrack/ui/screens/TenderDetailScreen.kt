package za.ac.tendertrack.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import za.ac.tendertrack.data.model.*
import za.ac.tendertrack.data.repo.PaymentRepository
import za.ac.tendertrack.data.repo.TenderRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens
import za.ac.tendertrack.ui.viewModelFactory

data class TenderDetailUiState(
    val tender: UiState<Tender> = UiState.Loading,
    val payments: List<Payment> = emptyList(),
    val audit: List<AuditEntry> = emptyList(),
    val action: ActionState = ActionState.Idle
)

class TenderDetailViewModel(
    private val tenderId: String,
    private val repository: TenderRepository = ServiceLocator.tenderRepository,
    private val paymentRepository: PaymentRepository = ServiceLocator.paymentRepository
) : ViewModel() {

    private val _state = MutableStateFlow(TenderDetailUiState())
    val state: StateFlow<TenderDetailUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(tender = UiState.Loading) }
        viewModelScope.launch {
            try {
                val tender = repository.byId(tenderId) ?: error("Tender not found.")
                _state.update {
                    it.copy(
                        tender = UiState.Success(tender),
                        payments = paymentRepository.forTender(tenderId),
                        audit = repository.auditTrail(tenderId)
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(tender = UiState.Error(e.friendlyMessage())) }
            }
        }
    }

    /**
     * Publish and Close are the same operation as any other lifecycle move, so
     * they go through the one guarded transition rather than setting the status
     * directly.
     */
    fun advance(to: TenderStatus, reason: String) {
        _state.update { it.copy(action = ActionState.Running) }
        viewModelScope.launch {
            try {
                val updated = repository.advanceStatus(tenderId, to, reason)
                _state.update {
                    it.copy(
                        tender = UiState.Success(updated),
                        action = ActionState.Succeeded("Tender moved to ${to.displayName}.")
                    )
                }
                load()
            } catch (e: Exception) {
                _state.update { it.copy(action = ActionState.Failed(e.friendlyMessage())) }
            }
        }
    }

    fun clearAction() = _state.update { it.copy(action = ActionState.Idle) }
}

/**
 * A single tender in full, and the place the officer acts on it: publish, close
 * (move to evaluation), award, record a payment or edit.
 */
@Composable
fun TenderDetailScreen(
    tenderId: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onLifecycle: (String) -> Unit,
    onAward: (String) -> Unit,
    onRecordPayment: (String) -> Unit
) {
    val viewModel: TenderDetailViewModel = viewModel(
        key = "tender_detail_$tenderId",
        factory = viewModelFactory { TenderDetailViewModel(tenderId) }
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

    AppScaffold(title = "Tender Detail", onBack = onBack, snackbarHostState = snackbar) {
        when (val result = state.tender) {
            is UiState.Loading -> LoadingState()
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> {
                val tender = result.data
                val busy = state.action is ActionState.Running

                ScreenHeading(
                    title = tender.title,
                    eyebrow = tender.referenceNumber,
                    subtitle = tender.department,
                    small = true,
                    trailing = { StatusBadge(tender.status.displayName, tender.status.tone()) }
                )

                AppCard {
                    KeyValueRow("Category", tender.category)
                    KeyValueRow("Estimated budget", Format.money(tender.estimatedBudget))
                    if (tender.awardedValue != null) {
                        KeyValueRow("Awarded value", Format.money(tender.awardedValue))
                        KeyValueRow("Awarded supplier", tender.awardedSupplierName ?: "—")
                        KeyValueRow("Awarded on", Format.date(tender.awardedAt))
                    }
                    KeyValueRow("Closing", Format.dateTime(tender.closingDate))
                    KeyValueRow("Contract period", "${tender.contractPeriodMonths} months", showDivider = false)
                }

                if (tender.description.isNotBlank()) {
                    AppCard {
                        Text("Scope of work", style = AppType.H2)
                        Spacer(Modifier.height(8.dp))
                        Text(tender.description, style = AppType.Body)
                    }
                }

                if (tender.awardedValue != null) {
                    UtilisationCard(
                        title = "Fund utilisation",
                        caption = "${Format.money(tender.paidToDate)} of " +
                            "${Format.money(tender.awardedValue)} disbursed · " +
                            "${state.payments.size} payment${if (state.payments.size == 1) "" else "s"} recorded",
                        fraction = tender.utilisation,
                        barColor = if (tender.utilisation >= 1f) AppColor.SuccessBar else AppColor.WarnBar
                    )
                }

                if (tender.openFlagCount > 0) {
                    NoteBanner(
                        title = "${tender.openFlagCount} compliance flag open",
                        text = "This tender has an unresolved flag. See Flags & Compliance for the detail.",
                        tone = NoteTone.Danger,
                        icon = Icons.Default.Flag
                    )
                }

                // --- Actions available in this state ---------------------------
                SectionHeader("Actions")
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMd)) {
                    when (tender.status) {
                        TenderStatus.REGISTERED -> {
                            PrimaryButton(
                                "Publish tender",
                                icon = Icons.Default.Public,
                                enabled = !busy,
                                onClick = {
                                    viewModel.advance(TenderStatus.PUBLISHED, "Published for public bidding")
                                }
                            )
                            SecondaryButton("Edit tender", icon = Icons.Default.Edit) { onEdit(tender.id) }
                        }
                        TenderStatus.PUBLISHED -> {
                            PrimaryButton(
                                "Close tender and begin evaluation",
                                icon = Icons.Default.Lock,
                                enabled = !busy,
                                onClick = {
                                    viewModel.advance(
                                        TenderStatus.UNDER_EVALUATION,
                                        "Closed for bids and handed to the evaluation committee"
                                    )
                                }
                            )
                            SecondaryButton("Edit tender", icon = Icons.Default.Edit) { onEdit(tender.id) }
                        }
                        TenderStatus.UNDER_EVALUATION -> {
                            PrimaryButton(
                                "Award tender",
                                icon = Icons.Default.EmojiEvents,
                                enabled = !busy,
                                onClick = { onAward(tender.id) }
                            )
                        }
                        TenderStatus.AWARDED, TenderStatus.IN_PROGRESS -> {
                            PrimaryButton(
                                "Record a payment",
                                icon = Icons.Default.Payments,
                                enabled = !busy,
                                onClick = { onRecordPayment(tender.id) }
                            )
                            SecondaryButton("Update lifecycle status", icon = Icons.Default.Refresh) {
                                onLifecycle(tender.id)
                            }
                        }
                        TenderStatus.COMPLETED -> {
                            NoteBanner(
                                "This tender is complete and fully disbursed. It is read-only.",
                                NoteTone.Success,
                                icon = Icons.Default.CheckCircle
                            )
                        }
                    }
                    if (tender.status != TenderStatus.COMPLETED &&
                        tender.status != TenderStatus.AWARDED &&
                        tender.status != TenderStatus.IN_PROGRESS
                    ) {
                        SecondaryButton("View lifecycle", icon = Icons.Default.Timeline) {
                            onLifecycle(tender.id)
                        }
                    }
                }

                // --- Payments --------------------------------------------------
                if (state.payments.isNotEmpty()) {
                    SectionHeader("Payments recorded")
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.ListGap)) {
                        state.payments.forEach { payment ->
                            AppCard {
                                CardHeader(
                                    title = payment.milestone,
                                    subtitle = "${payment.invoiceNumber} · ${Format.date(payment.paidOn)}",
                                    trailing = {
                                        Text(Format.money(payment.amount), style = AppType.KvValue)
                                    }
                                )
                                if (payment.recordedBy.isNotBlank()) {
                                    Spacer(Modifier.height(6.dp))
                                    Text("Recorded by ${payment.recordedBy}", style = AppType.Tiny)
                                }
                            }
                        }
                    }
                }

                // --- Audit trail -----------------------------------------------
                if (state.audit.isNotEmpty()) {
                    SectionHeader("Audit trail")
                    AppCard {
                        LifecycleStepper(
                            state.audit.mapIndexed { index, entry ->
                                StepItem(
                                    title = entry.action,
                                    detail = "${entry.detail} · ${Format.dateTime(entry.createdAt)} · ${entry.actor}",
                                    state = if (index == state.audit.lastIndex) StepState.Current else StepState.Done
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}
