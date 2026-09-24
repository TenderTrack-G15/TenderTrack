package za.ac.tendertrack.ui.screens.supplier

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Text
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
import za.ac.tendertrack.data.model.*
import za.ac.tendertrack.data.repo.SupplierPortalRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens
import za.ac.tendertrack.ui.viewModelFactory

class SupplierTenderViewModel(
    private val tenderId: String,
    private val repository: SupplierPortalRepository = ServiceLocator.supplierPortalRepository
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<TenderPack>>(UiState.Loading)
    val state: StateFlow<UiState<TenderPack>> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = try {
                UiState.Success(repository.pack(tenderId))
            } catch (e: Exception) {
                UiState.Error(e.supplierMessage())
            }
        }
    }
}

/**
 * One tender, as a bidder needs it: details, important dates, scope of work,
 * eligibility, documents, submission information, evaluation criteria, who to
 * ask, and any updates since publication.
 */
@Composable
fun SupplierTenderScreen(
    tenderId: String,
    onBack: () -> Unit
) {
    val viewModel: SupplierTenderViewModel = viewModel(
        key = "supplier_tender_$tenderId",
        factory = viewModelFactory { SupplierTenderViewModel(tenderId) }
    )
    val state by viewModel.state.collectAsState()

    AppScaffold(title = "Tender", onBack = onBack) {
        when (val result = state) {
            is UiState.Loading -> LoadingState(message = "Loading the tender…")
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> PackBody(result.data)
        }
    }
}

@Composable
private fun PackBody(pack: TenderPack) {
    val t = pack.tender
    val d = pack.details
    val context = LocalContext.current

    ScreenHeading(
        eyebrow = t.referenceNumber,
        title = t.title,
        subtitle = "${t.department} · ${t.category}",
        trailing = { StatusBadge(t.status.bidderLabel(), t.status.bidderTone()) }
    )

    // -- Tender details ----------------------------------------------------------
    SectionHeader("Tender details")
    AppCard {
        KeyValueRow("Tender number", t.referenceNumber)
        KeyValueRow("Category", t.category.ifBlank { "—" })
        KeyValueRow("Issuing department", t.department)
        KeyValueRow("Status", t.status.bidderLabel())
        KeyValueRow("Contract period", "${t.contractPeriodMonths} months", showDivider = t.description.isNotBlank())
        if (t.description.isNotBlank()) {
            Spacer(Modifier.height(Dimens.SpaceSm))
            Text(t.description, style = AppType.Body.copy(color = AppColor.InkSoft))
        }
    }

    // -- Important dates -----------------------------------------------------------
    SectionHeader("Important dates")
    AppCard {
        KeyValueRow("Published", t.publishedAt?.let { Format.date(it) } ?: "—")
        KeyValueRow("Closing", Format.dateTime(t.closingDate))
        KeyValueRow(
            "Briefing session",
            d?.briefingAt?.let {
                Format.dateTime(it) + if (d.briefingCompulsory) " · compulsory" else " · optional"
            } ?: "None"
        )
        KeyValueRow("Briefing venue", d?.briefingVenue?.ifBlank { null } ?: "—")
        KeyValueRow("Site visit", d?.siteVisitAt?.let { Format.dateTime(it) } ?: "None")
        KeyValueRow("Expected award", d?.expectedAwardDate?.let { Format.date(it) } ?: "—", showDivider = false)
    }

    // -- Scope of work --------------------------------------------------------------
    SectionHeader("Scope of work")
    AppCard {
        TextBlock("Project overview", d?.scopeOverview.orEmpty())
        Text("Deliverables required", style = AppType.Label)
        Spacer(Modifier.height(4.dp))
        BulletList(d?.deliverables.orEmpty())
        Spacer(Modifier.height(10.dp))
        Text("Technical specifications", style = AppType.Label)
        Spacer(Modifier.height(4.dp))
        BulletList(d?.technicalSpecs.orEmpty())
        Spacer(Modifier.height(10.dp))
        TextBlock("Quantity requirements", d?.quantityRequirements.orEmpty())
        TextBlock("Expected outcomes", d?.expectedOutcomes.orEmpty())
    }

    // -- Eligibility ------------------------------------------------------------------
    SectionHeader("Eligibility requirements")
    AppCard {
        if (pack.eligibility.isEmpty()) {
            Text("No eligibility requirements have been published.", style = AppType.Meta)
        } else {
            pack.eligibility.forEachIndexed { index, item ->
                KeyValueRow(
                    key = item.requirement,
                    value = if (item.mandatory) "Required" else "If applicable",
                    valueColor = if (item.mandatory) AppColor.Ink else AppColor.Muted,
                    showDivider = index < pack.eligibility.lastIndex
                )
            }
        }
    }

    // -- Documents ----------------------------------------------------------------------
    SectionHeader("Documents")
    AppCard {
        if (pack.documents.isEmpty()) {
            Text("No documents have been attached to this tender.", style = AppType.Meta)
        } else {
            pack.documents.forEachIndexed { index, doc ->
                KeyValueRow(
                    key = doc.name,
                    value = doc.docType.ifBlank { "Document" },
                    showDivider = index < pack.documents.lastIndex
                )
                doc.fileUrl?.let { url ->
                    TextAction(text = "Open", color = AppColor.InfoInk, onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW).apply { data = Uri.parse(url) })
                        }.onFailure {
                            Toast.makeText(context, "Could not open that link.", Toast.LENGTH_SHORT).show()
                        }
                    })
                }
            }
            Spacer(Modifier.height(Dimens.SpaceSm))
            Text(
                "Documents without a link are collected from the department at the address in the bid notice.",
                style = AppType.Tiny
            )
        }
    }

    // -- Submission -------------------------------------------------------------------------
    SectionHeader("Submission")
    AppCard {
        TextBlock("How to submit", d?.submissionMethod.orEmpty())
        KeyValueRow("Deadline", Format.dateTime(t.closingDate), showDivider = false)
        Spacer(Modifier.height(10.dp))
        TextBlock("Proposal format", d?.submissionFormat.orEmpty())
        Text("Documents to include", style = AppType.Label)
        Spacer(Modifier.height(4.dp))
        BulletList(d?.requiredDocuments.orEmpty())
    }

    // -- Evaluation ---------------------------------------------------------------------------
    SectionHeader("Evaluation criteria")
    AppCard {
        KeyValueRow("Technical evaluation", d?.technicalWeight?.let { "$it%" } ?: "—")
        KeyValueRow("Financial evaluation", d?.financialWeight?.let { "$it%" } ?: "—")
        KeyValueRow("Preference points", d?.preferencePoints?.ifBlank { null } ?: "—",
            showDivider = pack.criteria.isNotEmpty())
        pack.criteria.forEachIndexed { index, criterion ->
            KeyValueRow(
                key = "${criterion.name} (${criterion.weight}%)",
                value = "",
                showDivider = index < pack.criteria.lastIndex
            )
            if (criterion.description.isNotBlank()) Text(criterion.description, style = AppType.Meta)
        }
    }

    // -- Queries ------------------------------------------------------------------------------------
    SectionHeader("Contact for queries")
    AppCard {
        KeyValueRow("Contact person", d?.queryContactName?.ifBlank { null } ?: "—")
        KeyValueRow("Email", d?.queryContactEmail?.ifBlank { null } ?: "—")
        KeyValueRow("Telephone", d?.queryContactPhone?.ifBlank { null } ?: "—", showDivider = false)
    }

    // -- Updates -------------------------------------------------------------------------------------
    SectionHeader("Tender updates")
    if (pack.updates.isEmpty()) {
        AppCard { Text("No clarifications, amendments or extensions have been published.", style = AppType.Meta) }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.ListGap)) {
            pack.updates.forEach { update ->
                AppCard {
                    CardHeader(
                        title = update.title,
                        subtitle = Format.dateTime(update.publishedAt),
                        trailing = {
                            StatusBadge(
                                update.kind.displayName,
                                if (update.kind == TenderUpdateKind.EXTENSION) BadgeTone.Warning else BadgeTone.Info
                            )
                        }
                    )
                    if (update.body.isNotBlank()) {
                        Spacer(Modifier.height(Dimens.SpaceSm))
                        Text(update.body, style = AppType.Body.copy(color = AppColor.InkSoft))
                    }
                }
            }
        }
    }

    NoteBanner(
        title = "Submitting a bid",
        text = "Bids are submitted the way this notice describes, not yet inside the app. Submitting from " +
            "TenderTrack comes with the bidding module.",
        tone = NoteTone.Neutral,
        icon = Icons.Default.Info
    )
}
