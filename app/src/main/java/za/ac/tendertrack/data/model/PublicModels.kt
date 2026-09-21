package za.ac.tendertrack.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Models used by the Public / citizen screens.
 *
 * They live in their own file so the Procurement Officer models in Models.kt
 * are untouched. Field names match the Supabase columns exactly (see
 * supabase/schema.sql and supabase/public_access.sql).
 */

// ---------------------------------------------------------------------------
// Delivery phases (FR14: "overall deliverable completion")
// ---------------------------------------------------------------------------

@Serializable
enum class DeliverableStatus {
    @SerialName("not_started") NOT_STARTED,
    @SerialName("awaiting_verification") AWAITING_VERIFICATION,
    @SerialName("verified") VERIFIED,
    @SerialName("overdue") OVERDUE;

    /** Wording a member of the public understands, not internal jargon. */
    val displayName: String
        get() = when (this) {
            NOT_STARTED -> "Not started"
            AWAITING_VERIFICATION -> "Awaiting sign-off"
            VERIFIED -> "Delivered"
            OVERDUE -> "Overdue"
        }
}

/** One delivery phase of an awarded contract, from the `deliverables` table. */
@Serializable
data class Deliverable(
    val id: String,
    @SerialName("tender_id") val tenderId: String,
    @SerialName("phase_name") val phaseName: String,
    @SerialName("target_date") val targetDate: String,
    @SerialName("phase_value") val phaseValue: Double = 0.0,
    val status: DeliverableStatus,
    @SerialName("verified_at") val verifiedAt: String? = null
)

// ---------------------------------------------------------------------------
// Payments, as published (FR11)
// ---------------------------------------------------------------------------

/**
 * A payment from the `payments_public` view. Deliberately has no invoice number
 * and no official's name: the public sees how much was paid and when, which is
 * what FR11 asks for, and nothing that identifies a person (POPIA).
 */
@Serializable
data class PublicPayment(
    val id: String,
    @SerialName("tender_id") val tenderId: String,
    @SerialName("tender_reference") val tenderReference: String = "",
    val milestone: String,
    val amount: Double,
    @SerialName("paid_on") val paidOn: String
)

// ---------------------------------------------------------------------------
// Citizen reports (FR15)
// ---------------------------------------------------------------------------

@Serializable
enum class ReportCategory {
    @SerialName("irregular_award") IRREGULAR_AWARD,
    @SerialName("non_delivery") NON_DELIVERY,
    @SerialName("overpricing") OVERPRICING,
    @SerialName("conflict_of_interest") CONFLICT_OF_INTEREST,
    @SerialName("other") OTHER;

    /** The value the database function expects. */
    val apiValue: String
        get() = when (this) {
            IRREGULAR_AWARD -> "irregular_award"
            NON_DELIVERY -> "non_delivery"
            OVERPRICING -> "overpricing"
            CONFLICT_OF_INTEREST -> "conflict_of_interest"
            OTHER -> "other"
        }

    val displayName: String
        get() = when (this) {
            IRREGULAR_AWARD -> "Irregular award"
            NON_DELIVERY -> "Paid but not delivered"
            OVERPRICING -> "Overpricing"
            CONFLICT_OF_INTEREST -> "Conflict of interest"
            OTHER -> "Something else"
        }

    val explanation: String
        get() = when (this) {
            IRREGULAR_AWARD -> "The process or the winning company looks wrong."
            NON_DELIVERY -> "Money was paid but the work or goods did not arrive."
            OVERPRICING -> "The price is far above what the service should cost."
            CONFLICT_OF_INTEREST -> "An official appears to be linked to the supplier."
            OTHER -> "Any other irregularity you have noticed."
        }
}

@Serializable
enum class ReportStatus {
    @SerialName("received") RECEIVED,
    @SerialName("under_review") UNDER_REVIEW,
    @SerialName("closed") CLOSED,
    @SerialName("withdrawn") WITHDRAWN;

    val displayName: String
        get() = when (this) {
            RECEIVED -> "Received"
            UNDER_REVIEW -> "Under review"
            CLOSED -> "Closed"
            WITHDRAWN -> "Withdrawn"
        }

    val explanation: String
        get() = when (this) {
            RECEIVED -> "Your report has been logged and is waiting for a reviewer."
            UNDER_REVIEW -> "A procurement officer or auditor is investigating your report."
            CLOSED -> "The reviewers have finished with this report."
            WITHDRAWN -> "You withdrew this report. Any contact details you gave were erased."
        }

    /** Only open reports can be added to or withdrawn. */
    val canChange: Boolean get() = this == RECEIVED || this == UNDER_REVIEW
}

/**
 * What a citizen can see about their own report. Returned by the database
 * functions in public_access.sql. The contact email itself is never sent back
 * to the device, only whether one is held.
 */
@Serializable
data class CitizenReport(
    val found: Boolean = false,
    val reference: String = "",
    @SerialName("tender_id") val tenderId: String = "",
    @SerialName("tender_reference") val tenderReference: String = "",
    @SerialName("tender_title") val tenderTitle: String = "",
    val category: ReportCategory = ReportCategory.OTHER,
    val details: String = "",
    @SerialName("has_contact") val hasContact: Boolean = false,
    val additions: Int = 0,
    val status: ReportStatus = ReportStatus.RECEIVED,
    val outcome: String? = null,
    @SerialName("submitted_at") val submittedAt: String = "",
    @SerialName("updated_at") val updatedAt: String = ""
) {
    /** Plain-language result once the reviewers close the report. */
    val outcomeLabel: String?
        get() = when (outcome) {
            "cleared" -> "Reviewed — no irregularity found"
            "corrective_action" -> "Corrective action was taken"
            "escalated" -> "Escalated for further investigation"
            else -> null
        }

    companion object {
        /** The most additions a citizen may make, matching the database limit. */
        const val MAX_ADDITIONS = 5
    }
}

/** What the Flag for Review form sends. */
data class ReportDraft(
    val tenderId: String,
    val category: ReportCategory,
    val details: String,
    val contactEmail: String?
)

// ---------------------------------------------------------------------------
// Rollups calculated on the device from public data
// ---------------------------------------------------------------------------

/** Everything on the Public Dashboard (FR14). */
data class PublicDashboard(
    val counts: Map<TenderStatus, Int>,
    val closingWithin30Days: Int,
    val contractsAwarded: Int,
    val valueAwarded: Double,
    val paidToSuppliers: Double,
    val deliverablesCompleted: Int,
    val deliverablesTotal: Int,
    val openFlags: Int,
    val tendersWithFlags: Int,
    val totalTenders: Int
) {
    fun count(status: TenderStatus): Int = counts[status] ?: 0

    val paidFraction: Float
        get() = if (valueAwarded <= 0) 0f else (paidToSuppliers / valueAwarded).toFloat().coerceIn(0f, 1f)

    val deliveryFraction: Float
        get() = if (deliverablesTotal == 0) 0f
        else (deliverablesCompleted.toFloat() / deliverablesTotal).coerceIn(0f, 1f)
}

/** One department's row on the Spending screen (FR11). */
data class DepartmentSpend(
    val department: String,
    val tenders: Int,
    val estimatedBudget: Double,
    val awarded: Double,
    val paid: Double
) {
    val paidFraction: Float
        get() = if (awarded <= 0) 0f else (paid / awarded).toFloat().coerceIn(0f, 1f)
}
