package za.ac.tendertrack.ui.screens.citizen

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import za.ac.tendertrack.core.Format
import za.ac.tendertrack.data.model.Tender
import za.ac.tendertrack.data.model.TenderStatus
import za.ac.tendertrack.data.model.estimateIsPublic
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.screens.tone
import za.ac.tendertrack.ui.theme.AppColor

/**
 * One tender in the public search results.
 *
 * Same layout and components as the officer's TenderCard, with one difference
 * (design option 2): the department's estimate is only shown from award
 * onwards. Before that the card says the value is published after award.
 * Officers keep their own TenderCard, which always shows the estimate.
 */
@Composable
fun PublicTenderCard(tender: Tender, onClick: () -> Unit) {
    AppCard(onClick = onClick) {
        CardHeader(
            title = tender.referenceNumber,
            subtitle = "${tender.title} · ${tender.department}",
            trailing = { StatusBadge(tender.status.displayName, tender.status.tone()) }
        )
        Spacer(Modifier.height(10.dp))

        val awardedValue = tender.awardedValue
        if (tender.estimateIsPublic && awardedValue != null) {
            KeyValueRow("Department's estimate", Format.money(tender.estimatedBudget))
            KeyValueRow("Awarded value", Format.money(awardedValue))
            KeyValueRow(
                "Paid to date",
                "${Format.money(tender.paidToDate)} (${Format.percent(tender.utilisation)})"
            )
        } else {
            KeyValueRow("Contract value", "Published after award", valueColor = AppColor.Muted)
        }

        KeyValueRow(
            key = if (tender.status == TenderStatus.PUBLISHED) "Closing date" else "Closed",
            value = Format.dateTime(tender.closingDate),
            valueColor = if (tender.status == TenderStatus.PUBLISHED &&
                (Format.daysUntil(tender.closingDate) ?: 99) <= 14
            ) AppColor.WarnInk else AppColor.Ink,
            showDivider = tender.openFlagCount > 0
        )
        if (tender.openFlagCount > 0) {
            KeyValueRow(
                "Compliance",
                "${tender.openFlagCount} open flag${if (tender.openFlagCount == 1) "" else "s"}",
                valueColor = AppColor.DangerInk,
                showDivider = false
            )
        }
        if (awardedValue != null) {
            Spacer(Modifier.height(10.dp))
            ProgressBar(
                tender.utilisation,
                color = if (tender.utilisation >= 1f) AppColor.SuccessBar else AppColor.WarnBar
            )
        }
    }
}
