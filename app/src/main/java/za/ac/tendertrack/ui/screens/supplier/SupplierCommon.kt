package za.ac.tendertrack.ui.screens.supplier

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import za.ac.tendertrack.core.friendlyMessage
import za.ac.tendertrack.data.model.*
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType

/*
 * Pieces shared by the Supplier screens, using the existing components and the
 * AppColor / AppType / Dimens tokens.
 */

fun SupplierVerificationStatus.supplierLabel(): String = when (this) {
    SupplierVerificationStatus.AWAITING_VERIFICATION -> "Under review"
    SupplierVerificationStatus.VERIFIED -> "Verified"
    SupplierVerificationStatus.NOT_APPROVED -> "Not approved"
}

fun SupplierVerificationStatus.supplierTone(): BadgeTone = when (this) {
    SupplierVerificationStatus.AWAITING_VERIFICATION -> BadgeTone.Warning
    SupplierVerificationStatus.VERIFIED -> BadgeTone.Success
    SupplierVerificationStatus.NOT_APPROVED -> BadgeTone.Danger
}

fun DocumentStatus.tone(): BadgeTone = when (this) {
    DocumentStatus.VERIFIED -> BadgeTone.Success
    DocumentStatus.SUBMITTED -> BadgeTone.Info
    DocumentStatus.EXPIRED -> BadgeTone.Danger
    DocumentStatus.NOT_SUBMITTED -> BadgeTone.Neutral
}

fun TenderStatus.bidderTone(): BadgeTone = when (this) {
    TenderStatus.PUBLISHED -> BadgeTone.Success
    TenderStatus.UNDER_EVALUATION -> BadgeTone.Warning
    TenderStatus.AWARDED, TenderStatus.IN_PROGRESS, TenderStatus.COMPLETED -> BadgeTone.Neutral
    TenderStatus.REGISTERED -> BadgeTone.Neutral
}

/** What a bidder calls each stage. */
fun TenderStatus.bidderLabel(): String = when (this) {
    TenderStatus.PUBLISHED -> "Open for bids"
    TenderStatus.UNDER_EVALUATION -> "Closed · under evaluation"
    TenderStatus.AWARDED, TenderStatus.IN_PROGRESS, TenderStatus.COMPLETED -> "Awarded"
    TenderStatus.REGISTERED -> "Not published"
}

/** A server error can carry the URL on later lines; show only the first. */
fun Throwable.supplierMessage(): String =
    friendlyMessage().lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        ?: "Something went wrong. Please try again."

/** Multi-line text stored as one field, shown as a list. */
@Composable
fun BulletList(text: String, empty: String = "Not stated.") {
    val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty()) {
        Text(empty, style = AppType.Meta)
    } else {
        lines.forEach { line ->
            Text("•  $line", style = AppType.Body.copy(color = AppColor.InkSoft))
        }
    }
}

/** A labelled block of free text, used throughout the tender pack. */
@Composable
fun TextBlock(label: String, value: String, empty: String = "Not stated.") {
    Text(label, style = AppType.Label)
    Spacer(Modifier.height(4.dp))
    if (value.isBlank()) Text(empty, style = AppType.Meta)
    else Text(value, style = AppType.Body.copy(color = AppColor.InkSoft))
    Spacer(Modifier.height(10.dp))
}
