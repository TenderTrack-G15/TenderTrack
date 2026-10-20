package za.ac.tendertrack.ui.screens.phases

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import za.ac.tendertrack.core.Format
import za.ac.tendertrack.data.model.BidDocumentsEntry
import za.ac.tendertrack.data.model.TenderBidDocuments
import za.ac.tendertrack.data.repo.OfficerAlertsStore
import za.ac.tendertrack.data.repo.PhaseRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens

/*
 * "New bid documents" on a tender card, tapped: the bids suppliers submitted
 * (or changed) on the eTender portal and the documents each one confirmed.
 * "Mark as checked" clears the alert on the tender's tile. Prices stay sealed
 * while bidding is open, as on the portal's Department console.
 */

private sealed interface BidDocsState {
    data object Loading : BidDocsState
    data class Error(val message: String) : BidDocsState
    data class Ready(val data: TenderBidDocuments) : BidDocsState
}

@Composable
fun BidDocumentsDialog(
    tenderId: String,
    onDismiss: () -> Unit,
    repository: PhaseRepository = PhaseRepository.instance
) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<BidDocsState>(BidDocsState.Loading) }
    var marking by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var reloads by remember { mutableStateOf(0) }

    LaunchedEffect(tenderId, reloads) {
        state = BidDocsState.Loading
        state = try {
            BidDocsState.Ready(repository.bidDocuments(tenderId))
        } catch (e: Exception) {
            BidDocsState.Error(e.bidDocumentsMessage())
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(AppColor.Background)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(Dimens.TopBarHeight)
                    .background(AppColor.Surface)
                    .padding(horizontal = Dimens.ScreenPadding),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Bid documents", style = AppType.TopBarTitle, modifier = Modifier.weight(1f))
                Box(Modifier.clickable { onDismiss() }.padding(6.dp)) {
                    AppIcon(Icons.Default.Close, tint = AppColor.Ink, size = Dimens.IconLg, contentDescription = "Close")
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(Dimens.ScreenPadding),
                verticalArrangement = Arrangement.spacedBy(Dimens.SectionGap)
            ) {
                when (val s = state) {
                    is BidDocsState.Loading -> LoadingState()
                    is BidDocsState.Error -> ErrorState(s.message, onRetry = { reloads++ })
                    is BidDocsState.Ready -> {
                        val data = s.data
                        ScreenHeading(
                            eyebrow = "From the eTender portal",
                            title = data.referenceNumber.ifBlank { "Bids" },
                            subtitle = data.title.ifBlank { null }
                        )
                        NoteBanner(
                            text = if (data.sealed) {
                                "Suppliers confirm these documents when they submit or change a bid on the eTender " +
                                    "portal. Prices stay sealed until bidding closes on ${Format.dateTime(data.closingDate)}."
                            } else {
                                "Suppliers confirmed these documents when they submitted their bids on the eTender portal."
                            },
                            tone = NoteTone.Info,
                            icon = Icons.Default.Info
                        )
                        if (data.bids.isEmpty()) {
                            EmptyState(
                                title = "No bids yet",
                                message = "Bids submitted on the eTender portal appear here.",
                                icon = Icons.Default.Description
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(Dimens.ListGap)) {
                            data.bids.forEach { bid -> BidDocumentsCard(bid, data.requiredDocuments, data.sealed) }
                        }
                        problem?.let { NoteBanner(text = it, tone = NoteTone.Danger, icon = Icons.Default.Info) }
                        if (data.newBids > 0) {
                            PrimaryButton(
                                text = "Mark as checked",
                                icon = Icons.Default.Check,
                                loading = marking,
                                onClick = {
                                    marking = true
                                    problem = null
                                    scope.launch {
                                        try {
                                            repository.markBidDocumentsChecked(tenderId)
                                            OfficerAlertsStore.refresh(repository)
                                            onDismiss()
                                        } catch (e: Exception) {
                                            problem = e.bidDocumentsMessage()
                                        } finally {
                                            marking = false
                                        }
                                    }
                                }
                            )
                            Text(
                                "This removes \"New bid documents\" from the tender's card. It does not accept or reject a bid.",
                                style = AppType.Tiny
                            )
                        }
                        TextAction(text = "Close", color = AppColor.Muted, onClick = onDismiss)
                    }
                }
            }
        }
    }
}

@Composable
private fun BidDocumentsCard(bid: BidDocumentsEntry, required: List<String>, sealed: Boolean) {
    val expected = required.ifEmpty { bid.documents }
    val newBadge: (@Composable () -> Unit)? = if (bid.isNew) { { StatusBadge("New", BadgeTone.Warning) } } else null
    AppCard(accent = if (bid.isNew) AppColor.WarnBar else null) {
        CardHeader(
            title = bid.supplierName,
            subtitle = listOfNotNull(bid.reference.ifBlank { null }, bid.csdNumber).joinToString(" · "),
            trailing = newBadge
        )
        Spacer(Modifier.height(10.dp))
        KeyValueRow("Submitted", Format.dateTime(bid.submittedAt))
        KeyValueRow("Total bid price", if (sealed || bid.bidValue == null) "Sealed until bidding closes" else Format.money(bid.bidValue))
        KeyValueRow(
            "Documents confirmed",
            "${bid.documentsReceived} of ${bid.documentsRequired}",
            valueColor = if (bid.documentsReceived < bid.documentsRequired) AppColor.DangerInk else AppColor.Ink,
            showDivider = expected.isNotEmpty()
        )
        if (expected.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                expected.forEach { name ->
                    val confirmed = name in bid.documents
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppIcon(
                            if (confirmed) Icons.Default.CheckCircle else Icons.Default.Close,
                            tint = if (confirmed) AppColor.SuccessInk else AppColor.DangerInk,
                            size = Dimens.IconSm
                        )
                        Text(
                            if (confirmed) name else "$name (not confirmed)",
                            style = AppType.Meta.copy(color = if (confirmed) AppColor.InkSoft else AppColor.DangerInk),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/** A problem loading or marking bid documents, in words an officer can act on. */
private fun Throwable.bidDocumentsMessage(): String {
    val raw = message.orEmpty()
    return when {
        raw.contains("tender_bid_documents", true) || raw.contains("mark_bid_documents_seen", true) ||
            raw.contains("PGRST202") -> "The database has not been updated for bid documents yet. Run supabase/portal_fixes.sql in Supabase."
        else -> phaseMessage()
    }
}
