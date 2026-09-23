package za.ac.tendertrack.ui.screens.auditor

import za.ac.tendertrack.core.friendlyMessage
import za.ac.tendertrack.data.model.*
import za.ac.tendertrack.ui.components.BadgeTone

/*
 * Pieces shared by the Auditor screens. Everything visual comes from the
 * existing components and the AppColor / AppType / Dimens tokens, so these
 * screens match the officer and public screens exactly.
 */

fun TenderStatus.auditTone(): BadgeTone = when (this) {
    TenderStatus.REGISTERED -> BadgeTone.Neutral
    TenderStatus.PUBLISHED -> BadgeTone.Info
    TenderStatus.UNDER_EVALUATION -> BadgeTone.Warning
    TenderStatus.AWARDED, TenderStatus.IN_PROGRESS -> BadgeTone.Success
    TenderStatus.COMPLETED -> BadgeTone.Success
}

fun BidStatus.tone(): BadgeTone = when (this) {
    BidStatus.AWARDED -> BadgeTone.Success
    BidStatus.SHORTLISTED -> BadgeTone.Info
    BidStatus.DISQUALIFIED -> BadgeTone.Danger
    BidStatus.WITHDRAWN -> BadgeTone.Neutral
    BidStatus.SUBMITTED -> BadgeTone.Warning
}

fun CheckResult.tone(): BadgeTone = when (this) {
    CheckResult.PASSED -> BadgeTone.Success
    CheckResult.FAILED -> BadgeTone.Danger
    CheckResult.NOT_YET -> BadgeTone.Neutral
}

fun CheckResult.label(): String = when (this) {
    CheckResult.PASSED -> "Met"
    CheckResult.FAILED -> "Not met"
    CheckResult.NOT_YET -> "Not yet due"
}

/** Friendlier names for the audit trail's record types. */
fun entityLabel(entityType: String): String = when (entityType) {
    "tender" -> "Tender"
    "supplier" -> "Supplier"
    "payment" -> "Payment"
    "flag" -> "Flag"
    "account" -> "Account"
    "invitation" -> "Invitation"
    else -> entityType.replaceFirstChar { it.uppercase() }
}

/** A server error can carry the URL on later lines; show only the first. */
fun Throwable.auditorMessage(): String =
    friendlyMessage().lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        ?: "Something went wrong. Please try again."
