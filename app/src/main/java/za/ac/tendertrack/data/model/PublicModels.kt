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
// When the department's estimate becomes public (design option 2)
// ---------------------------------------------------------------------------

/**
 * The department's estimated budget is withheld while a tender is open for
 * bids or under evaluation, so bids reflect real cost rather than clustering
 * just under the budget. From award onwards it is published next to the
 * awarded value. The database enforces the same rule (tenders_public view).
 */
val Tender.estimateIsPublic: Boolean
    get() = status == TenderStatus.AWARDED ||
        status == TenderStatus.IN_PROGRESS ||
        status == TenderStatus.COMPLETED

/**
 * A copy safe to show the public: the estimate is zeroed while it is withheld,
 * so no screen can display it by accident. Screens check [estimateIsPublic]
 * before showing the figure at all.
 */
fun Tender.withEstimateWithheld(): Tender = if (estimateIsPublic) this else copy(estimatedBudget = 0.0)

/**
 * How far the award landed from the department's estimate, as a fraction:
 * -0.02 means 2% below, 0.15 means 15% above. Null when there is no award yet.
 */
val Tender.awardVsEstimate: Double?
    get() {
        val awarded = awardedValue ?: return null
        if (!estimateIsPublic || estimatedBudget <= 0.0) return null
        return (awarded - estimatedBudget) / estimatedBudget
    }

/**
 * One row of the tenders_public view. Identical to [Tender] except that
 * estimated_budget is NULL until award, which the officer's Tender model
 * (a non-null Double) cannot hold.
 */
@Serializable
data class PublicTenderRow(
    val id: String,
    @SerialName("reference_number") val referenceNumber: String,
    val title: String,
    val description: String = "",
    val department: String,
    val category: String,
    @SerialName("estimated_budget") val estimatedBudget: Double? = null,
    @SerialName("closing_date") val closingDate: String,
    @SerialName("contract_period_months") val contractPeriodMonths: Int = 12,
    val status: TenderStatus,
    @SerialName("awarded_supplier_id") val awardedSupplierId: String? = null,
    @SerialName("awarded_supplier_name") val awardedSupplierName: String? = null,
    @SerialName("awarded_value") val awardedValue: Double? = null,
    @SerialName("awarded_at") val awardedAt: String? = null,
    @SerialName("paid_to_date") val paidToDate: Double = 0.0,
    @SerialName("open_flag_count") val openFlagCount: Int = 0,
    @SerialName("created_at") val createdAt: String? = null
) {
    /** Converts to the app's normal [Tender]; a withheld estimate becomes 0. */
    fun toTender(): Tender = Tender(
        id = id,
        referenceNumber = referenceNumber,
        title = title,
        description = description,
        department = department,
        category = category,
        estimatedBudget = estimatedBudget ?: 0.0,
        closingDate = closingDate,
        contractPeriodMonths = contractPeriodMonths,
        status = status,
        awardedSupplierId = awardedSupplierId,
        awardedSupplierName = awardedSupplierName,
        awardedValue = awardedValue,
        awardedAt = awardedAt,
        paidToDate = paidToDate,
        openFlagCount = openFlagCount,
        createdAt = createdAt
    )
}

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
    /** Sum of estimates that are public — i.e. of awarded tenders only. */
    val estimatedBudget: Double,
    val awarded: Double,
    val paid: Double
) {
    val paidFraction: Float
        get() = if (awarded <= 0) 0f else (paid / awarded).toFloat().coerceIn(0f, 1f)
}
