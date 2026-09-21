package za.ac.tendertrack.ui.screens.citizen

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import za.ac.tendertrack.core.Format
import za.ac.tendertrack.core.friendlyMessage
import za.ac.tendertrack.data.model.DeliverableStatus
import za.ac.tendertrack.data.model.ReportStatus
import za.ac.tendertrack.data.model.Tender
import za.ac.tendertrack.ui.components.BadgeTone
import za.ac.tendertrack.ui.components.TextAction
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType

/*
 * Small pieces shared by the Public / citizen screens.
 *
 * Everything visual still comes from the existing components and the AppColor,
 * AppType and Dimens tokens, so these screens look exactly like the officer
 * screens.
 */

// ---------------------------------------------------------------------------
// Status colours — one mapping each, like TenderStatus.tone() in TenderListScreen
// ---------------------------------------------------------------------------

fun DeliverableStatus.tone(): BadgeTone = when (this) {
    DeliverableStatus.VERIFIED -> BadgeTone.Success
    DeliverableStatus.AWAITING_VERIFICATION -> BadgeTone.Warning
    DeliverableStatus.OVERDUE -> BadgeTone.Danger
    DeliverableStatus.NOT_STARTED -> BadgeTone.Neutral
}

fun ReportStatus.tone(): BadgeTone = when (this) {
    ReportStatus.RECEIVED -> BadgeTone.Info
    ReportStatus.UNDER_REVIEW -> BadgeTone.Warning
    ReportStatus.CLOSED -> BadgeTone.Success
    ReportStatus.WITHDRAWN -> BadgeTone.Neutral
}

// ---------------------------------------------------------------------------
// Search filters (FR13: department, status, budget range and date)
// ---------------------------------------------------------------------------

/** Budget range filter. Bands chosen to split the sample tenders sensibly. */
enum class BudgetBand(val label: String) {
    ANY("Any budget"),
    UNDER_5M("Under R 5 m"),
    FROM_5M_TO_20M("R 5 m – R 20 m"),
    OVER_20M("Over R 20 m");

    fun matches(budget: Double): Boolean = when (this) {
        ANY -> true
        UNDER_5M -> budget < 5_000_000
        FROM_5M_TO_20M -> budget >= 5_000_000 && budget <= 20_000_000
        OVER_20M -> budget > 20_000_000
    }
}

/** Closing date filter. */
enum class DateWindow(val label: String) {
    ANY("Any closing date"),
    NEXT_30("Closes within 30 days"),
    NEXT_90("Closes within 90 days"),
    CLOSED("Already closed");

    fun matches(tender: Tender): Boolean {
        val days = Format.daysUntil(tender.closingDate)
        return when (this) {
            ANY -> true
            NEXT_30 -> days != null && days in 0..30
            NEXT_90 -> days != null && days in 0..90
            CLOSED -> days != null && days < 0
        }
    }
}

/** Label for the "no department filter" option. */
const val ALL_DEPARTMENTS = "All departments"

// ---------------------------------------------------------------------------
// Feedback
// ---------------------------------------------------------------------------

/**
 * A server error can carry the request URL and headers on later lines. Only
 * the first line is meant for a person, so that is all the citizen sees.
 */
fun Throwable.citizenMessage(): String =
    friendlyMessage().lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        ?: "Something went wrong. Please try again."

/**
 * Confirmation before an action that cannot be undone. Styled with the app's
 * own colours and type rather than stock Material.
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppColor.Surface,
        title = { Text(title, style = AppType.H2) },
        text = { Text(message, style = AppType.Body.copy(color = AppColor.InkSoft)) },
        confirmButton = {
            TextAction(
                text = confirmText,
                color = if (danger) AppColor.DangerInk else AppColor.Ink,
                onClick = onConfirm
            )
        },
        dismissButton = {
            TextAction(text = "Cancel", color = AppColor.Muted, onClick = onDismiss)
        }
    )
}
