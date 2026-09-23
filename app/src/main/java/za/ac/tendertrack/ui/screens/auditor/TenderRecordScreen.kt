package za.ac.tendertrack.ui.screens.auditor

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import za.ac.tendertrack.data.ServiceLocator
import za.ac.tendertrack.data.model.*
import za.ac.tendertrack.data.repo.AuditorRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens
import za.ac.tendertrack.ui.viewModelFactory

class TenderRecordViewModel(
    private val tenderId: String,
    private val repository: AuditorRepository = ServiceLocator.auditorRepository
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<TenderDossier>>(UiState.Loading)
    val state: StateFlow<UiState<TenderDossier>> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = try {
                UiState.Success(repository.dossier(tenderId))
            } catch (e: Exception) {
                UiState.Error(e.auditorMessage())
            }
        }
    }
}

/**
 * One tender, as the Auditor inspects it: the record itself, the bids
 * submitted, the evaluation and its scores, the award decision and who
 * approved it, the history of changes, and the compliance checks. Read-only
 * throughout.
 */
@Composable
fun TenderRecordScreen(
    tenderId: String,
    onBack: () -> Unit
) {
    val viewModel: TenderRecordViewModel = viewModel(
        key = "auditor_record_$tenderId",
        factory = viewModelFactory { TenderRecordViewModel(tenderId) }
    )
    val state by viewModel.state.collectAsState()

    AppScaffold(title = "Tender Record", onBack = onBack) {
        when (val result = state) {
            is UiState.Loading -> LoadingState(message = "Loading the record…")
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> DossierBody(result.data)
        }
    }
}

@Composable
private fun DossierBody(dossier: TenderDossier) {
    val record = dossier.record

    ScreenHeading(
        eyebrow = record.referenceNumber,
        title = record.title,
        subtitle = "${record.department} · ${record.category}",
        trailing = { StatusBadge(record.status.displayName, record.status.auditTone()) }
    )

    // -- 1. Tender record ---------------------------------------------------------
    SectionHeader("Tender record")
    AppCard {
        KeyValueRow("Tender number", record.referenceNumber)
        KeyValueRow("Publication date", record.publishedAt?.let { Format.date(it) } ?: "Not published")
        KeyValueRow("Closing date", Format.dateTime(record.closingDate))
        KeyValueRow("Award date", record.awardedAt?.let { Format.date(it) } ?: "Not awarded")
        KeyValueRow("Status", record.status.displayName)
        KeyValueRow("Estimated budget", Format.money(record.estimatedBudget))
        KeyValueRow("Awarded value", record.awardedValue?.let { Format.money(it) } ?: "—")
        KeyValueRow("Paid to date", Format.money(record.paidToDate), showDivider = false)
    }

    // -- 2. Bid submissions --------------------------------------------------------
    SectionHeader("Bid submissions")
    if (dossier.bids.isEmpty()) {
        AppCard { Text("No bids have been recorded for this tender.", style = AppType.Meta) }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.ListGap)) {
            dossier.bids.forEach { bid ->
                AppCard {
                    CardHeader(
                        title = bid.supplierName,
                        subtitle = "${bid.reference} · submitted ${Format.dateTime(bid.submittedAt)}",
                        trailing = { StatusBadge(bid.status.displayName, bid.status.tone()) }
                    )
                    Spacer(Modifier.height(10.dp))
                    KeyValueRow("Bid value", Format.money(bid.bidValue))
                    KeyValueRow(
                        "Documents",
                        "${bid.documentsReceived} of ${bid.documentsRequired} received",
                        valueColor = if (bid.documentsComplete) AppColor.Ink else AppColor.DangerInk,
                        showDivider = bid.disqualifiedReason != null
                    )
                    bid.disqualifiedReason?.let {
                        KeyValueRow("Disqualified because", it, valueColor = AppColor.DangerInk, showDivider = false)
                    }
                }
            }
        }
    }

    // -- 3. Evaluation results ------------------------------------------------------
    SectionHeader("Evaluation results")
    if (dossier.criteria.isEmpty()) {
        AppCard { Text("No evaluation criteria have been recorded yet.", style = AppType.Meta) }
    } else {
        AppCard {
            CardHeader(
                title = "Criteria used",
                subtitle = dossier.result?.committee ?: "Evaluation not completed yet"
            )
            Spacer(Modifier.height(Dimens.SpaceSm))
            dossier.criteria.forEachIndexed { index, criterion ->
                KeyValueRow(
                    key = "${criterion.name} (${criterion.weight}%)",
                    value = "",
                    showDivider = false
                )
                Text(criterion.description, style = AppType.Meta)
                if (index < dossier.criteria.lastIndex) Spacer(Modifier.height(Dimens.SpaceSm))
            }
        }

        dossier.scoredBids.filter { it.scores.isNotEmpty() }.forEach { scored ->
            AppCard(background = if (scored.recommended) AppColor.SurfaceMuted else AppColor.Surface) {
                CardHeader(
                    title = scored.bid.supplierName,
                    subtitle = scored.weightedTotal?.let { "Weighted score ${Format.percent((it / 100).toFloat(), 1)}" }
                        ?: "Not scored",
                    trailing = {
                        if (scored.recommended) StatusBadge("Recommended", BadgeTone.Success)
                        else StatusBadge(scored.bid.status.displayName, scored.bid.status.tone())
                    }
                )
                Spacer(Modifier.height(Dimens.SpaceSm))
                dossier.criteria.forEach { criterion ->
                    val score = scored.scoreFor(criterion)
                    KeyValueRow(
                        key = criterion.name,
                        value = score?.let { "${it.score.toInt()} / 100" } ?: "—",
                        showDivider = false
                    )
                    score?.comment?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = AppType.Meta)
                    }
                }
            }
        }

        dossier.result?.let { result ->
            AppCard {
                CardHeader(
                    title = "Recommendation",
                    subtitle = "Completed ${Format.dateTime(result.completedAt)}"
                )
                Spacer(Modifier.height(Dimens.SpaceSm))
                KeyValueRow("Recommended supplier", dossier.recommendedBid?.supplierName ?: "—", showDivider = false)
                Spacer(Modifier.height(Dimens.SpaceSm))
                Text(result.recommendationComment, style = AppType.Body.copy(color = AppColor.InkSoft))
            }
        }
    }

    // -- 4. Award decision -----------------------------------------------------------
    SectionHeader("Award decision")
    val approval = dossier.approval
    if (record.awardedAt == null) {
        AppCard { Text("This tender has not been awarded.", style = AppType.Meta) }
    } else {
        AppCard {
            CardHeader(
                title = record.awardedSupplierName ?: "Awarded",
                subtitle = "Awarded ${Format.date(record.awardedAt)}"
            )
            Spacer(Modifier.height(10.dp))
            KeyValueRow("Awarded value", record.awardedValue?.let { Format.money(it) } ?: "—")
            KeyValueRow("Approver", approval?.approverName ?: "Not recorded",
                valueColor = if (approval == null) AppColor.DangerInk else AppColor.Ink)
            KeyValueRow("Position", approval?.approverPosition ?: "—")
            KeyValueRow("Approved at", approval?.let { Format.dateTime(it.approvedAt) } ?: "—",
                showDivider = approval?.comments?.isNotBlank() == true)
            approval?.comments?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(Dimens.SpaceSm))
                Text(it, style = AppType.Body.copy(color = AppColor.InkSoft))
            }
        }
    }

    // -- 5. Actions recorded against this tender -----------------------------------------
    SectionHeader("Actions recorded")
    AppCard {
        if (dossier.auditEntries.isEmpty()) {
            Text("No actions have been recorded against this tender yet.", style = AppType.Meta)
        } else {
            dossier.auditEntries.forEachIndexed { index, entry ->
                KeyValueRow(
                    key = "${entry.action} · ${entry.actor}",
                    value = Format.dateTime(entry.createdAt),
                    valueColor = AppColor.Muted,
                    showDivider = false
                )
                if (entry.detail.isNotBlank()) Text(entry.detail, style = AppType.Meta)
                if (index < dossier.auditEntries.lastIndex) Spacer(Modifier.height(Dimens.SpaceSm))
            }
        }
    }

    // -- 6. Tender history ----------------------------------------------------------------
    SectionHeader("Tender history")
    AppCard {
        if (dossier.changes.isEmpty()) {
            Text("Nothing about this tender has been changed since it was created.", style = AppType.Meta)
        } else {
            dossier.changes.forEachIndexed { index, change ->
                Text(change.fieldLabel, style = AppType.Label)
                KeyValueRow("Original", change.oldValue ?: "—", showDivider = false)
                KeyValueRow("Changed to", change.newValue ?: "—", showDivider = false)
                KeyValueRow("Changed by", change.changedBy, showDivider = false)
                KeyValueRow("Change date", Format.dateTime(change.changedAt), valueColor = AppColor.Muted,
                    showDivider = index < dossier.changes.lastIndex)
                if (index < dossier.changes.lastIndex) Spacer(Modifier.height(Dimens.SpaceSm))
            }
        }
    }

    // -- 7. Compliance ------------------------------------------------------------------------
    SectionHeader("Compliance checks")
    ComplianceChecks(dossier.compliance)
}

/** The compliance checks for one tender, used here and on the reports screen. */
@Composable
fun ComplianceChecks(report: ComplianceReport) {
    AppCard {
        CardHeader(
            title = if (report.compliant) "No problems found" else "${report.failed} check(s) not met",
            subtitle = "${report.passed} met · ${report.pending} not yet due",
            trailing = {
                StatusBadge(
                    if (report.compliant) "Compliant" else "Attention",
                    if (report.compliant) BadgeTone.Success else BadgeTone.Danger
                )
            }
        )
        Spacer(Modifier.height(Dimens.SpaceSm))
        report.checks.forEachIndexed { index, check ->
            KeyValueRow(
                key = check.title,
                value = check.result.label(),
                valueColor = when (check.result) {
                    CheckResult.FAILED -> AppColor.DangerInk
                    CheckResult.PASSED -> AppColor.Ink
                    CheckResult.NOT_YET -> AppColor.Muted
                },
                showDivider = false
            )
            Text(check.detail, style = AppType.Meta)
            if (index < report.checks.lastIndex) Spacer(Modifier.height(Dimens.SpaceSm))
        }
    }
}
