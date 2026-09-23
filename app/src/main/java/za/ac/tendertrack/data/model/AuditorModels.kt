package za.ac.tendertrack.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Auditor role — record inspection.
 *
 * The Auditor observes and verifies: tender records, bid submissions,
 * evaluation results, award decisions, audit logs, compliance and the history
 * of changes. Nothing here is written by the app; the Auditor has no write
 * permission at all (supabase/auditor_records.sql).
 *
 * Field names match that migration.
 */

/** A tender as the audit record shows it. */
@Serializable
data class TenderRecord(
    val id: String,
    @SerialName("reference_number") val referenceNumber: String,
    val title: String,
    val department: String,
    val category: String = "",
    val status: TenderStatus,
    @SerialName("estimated_budget") val estimatedBudget: Double = 0.0,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("closing_date") val closingDate: String,
    @SerialName("awarded_at") val awardedAt: String? = null,
    @SerialName("awarded_supplier_name") val awardedSupplierName: String? = null,
    @SerialName("awarded_value") val awardedValue: Double? = null,
    @SerialName("paid_to_date") val paidToDate: Double = 0.0
)

@Serializable
enum class BidStatus {
    @SerialName("submitted") SUBMITTED,
    @SerialName("withdrawn") WITHDRAWN,
    @SerialName("disqualified") DISQUALIFIED,
    @SerialName("shortlisted") SHORTLISTED,
    @SerialName("awarded") AWARDED;

    val displayName: String
        get() = name.lowercase().replaceFirstChar { it.uppercase() }
}

/** One bid submission: who, when, and how much. */
@Serializable
data class Bid(
    val id: String,
    val reference: String,
    @SerialName("tender_id") val tenderId: String,
    @SerialName("supplier_name") val supplierName: String,
    @SerialName("bid_value") val bidValue: Double,
    @SerialName("submitted_at") val submittedAt: String,
    val status: BidStatus,
    @SerialName("documents_received") val documentsReceived: Int = 0,
    @SerialName("documents_required") val documentsRequired: Int = 4,
    @SerialName("disqualified_reason") val disqualifiedReason: String? = null
) {
    val documentsComplete: Boolean get() = documentsReceived >= documentsRequired
}

/** One criterion the bids were scored against. */
@Serializable
data class EvaluationCriterion(
    val id: String,
    @SerialName("tender_id") val tenderId: String,
    val name: String,
    val description: String = "",
    val weight: Int,
    val sequence: Int = 1
)

/** One score given to one bid for one criterion. */
@Serializable
data class EvaluationScore(
    val id: String,
    @SerialName("bid_id") val bidId: String,
    @SerialName("criterion_id") val criterionId: String,
    val score: Double,
    val comment: String = ""
)

/** The committee's conclusion for a tender. */
@Serializable
data class EvaluationResult(
    val id: String,
    @SerialName("tender_id") val tenderId: String,
    val committee: String,
    @SerialName("completed_at") val completedAt: String,
    @SerialName("recommended_bid_id") val recommendedBidId: String? = null,
    @SerialName("recommendation_comment") val recommendationComment: String = ""
)

/** Who approved an award, in what position, when, and why. */
@Serializable
data class AwardApproval(
    val id: String,
    @SerialName("tender_id") val tenderId: String,
    @SerialName("approver_name") val approverName: String,
    @SerialName("approver_position") val approverPosition: String,
    @SerialName("approved_at") val approvedAt: String,
    @SerialName("approved_value") val approvedValue: Double? = null,
    val comments: String = ""
)

/** One recorded change to a tender, written by a database trigger. */
@Serializable
data class TenderChange(
    val id: String,
    @SerialName("tender_id") val tenderId: String,
    @SerialName("field_label") val fieldLabel: String,
    @SerialName("old_value") val oldValue: String? = null,
    @SerialName("new_value") val newValue: String? = null,
    @SerialName("changed_by") val changedBy: String,
    @SerialName("changed_at") val changedAt: String
)

/** A bid with its scores worked out, for the evaluation table. */
data class ScoredBid(
    val bid: Bid,
    val scores: List<EvaluationScore>,
    val criteria: List<EvaluationCriterion>,
    val recommended: Boolean
) {
    /** Weighted total out of 100; null when this bid was not scored. */
    val weightedTotal: Double?
        get() {
            if (scores.isEmpty()) return null
            val totalWeight = criteria.filter { c -> scores.any { it.criterionId == c.id } }.sumOf { it.weight }
            if (totalWeight == 0) return null
            val earned = scores.sumOf { score ->
                val weight = criteria.firstOrNull { it.id == score.criterionId }?.weight ?: 0
                score.score * weight
            }
            return earned / totalWeight
        }

    fun scoreFor(criterion: EvaluationCriterion): EvaluationScore? = scores.firstOrNull { it.criterionId == criterion.id }
}

/** Everything the Auditor can see about one tender. */
data class TenderDossier(
    val record: TenderRecord,
    val bids: List<Bid>,
    val criteria: List<EvaluationCriterion>,
    val scores: List<EvaluationScore>,
    val result: EvaluationResult?,
    val approval: AwardApproval?,
    val changes: List<TenderChange>,
    val auditEntries: List<AuditEntry>
) {
    val recommendedBid: Bid? get() = bids.firstOrNull { it.id == result?.recommendedBidId }

    val scoredBids: List<ScoredBid>
        get() = bids.map { bid ->
            ScoredBid(
                bid = bid,
                scores = scores.filter { it.bidId == bid.id },
                criteria = criteria,
                recommended = bid.id == result?.recommendedBidId
            )
        }.sortedByDescending { it.weightedTotal ?: -1.0 }

    val compliance: ComplianceReport get() = ComplianceReport.of(this)
}

// ---------------------------------------------------------------------------
// Compliance (what the Auditor verifies)
// ---------------------------------------------------------------------------

enum class CheckResult { PASSED, FAILED, NOT_YET }

/** One thing the Auditor checks, and what the records say about it. */
data class ComplianceCheck(val title: String, val result: CheckResult, val detail: String)

/** The compliance picture for one tender. */
data class ComplianceReport(
    val tenderId: String,
    val reference: String,
    val title: String,
    val checks: List<ComplianceCheck>
) {
    val failed: Int get() = checks.count { it.result == CheckResult.FAILED }
    val passed: Int get() = checks.count { it.result == CheckResult.PASSED }
    val pending: Int get() = checks.count { it.result == CheckResult.NOT_YET }
    val compliant: Boolean get() = failed == 0

    companion object {
        /** Minimum advertising period for an open tender, in days. */
        const val MIN_ADVERTISING_DAYS = 21

        /** Below this many bids, competition is questionable. */
        const val MIN_BIDS = 3

        /** An award this far from the estimate needs explaining. */
        const val AWARD_VARIANCE = 0.10

        fun of(dossier: TenderDossier): ComplianceReport {
            val t = dossier.record
            val checks = mutableListOf<ComplianceCheck>()

            // 1. Advertised long enough
            val advertised = daysBetween(t.publishedAt, t.closingDate)
            checks += when {
                t.publishedAt == null -> ComplianceCheck("Advertising period", CheckResult.NOT_YET,
                    "Not published yet.")
                advertised == null -> ComplianceCheck("Advertising period", CheckResult.NOT_YET,
                    "Dates could not be read.")
                advertised >= MIN_ADVERTISING_DAYS -> ComplianceCheck("Advertising period", CheckResult.PASSED,
                    "Open for $advertised days (at least $MIN_ADVERTISING_DAYS required).")
                else -> ComplianceCheck("Advertising period", CheckResult.FAILED,
                    "Open for only $advertised days; $MIN_ADVERTISING_DAYS are required.")
            }

            // 2. Competition
            val counted = dossier.bids.count { it.status != BidStatus.WITHDRAWN }
            checks += when {
                t.status == TenderStatus.REGISTERED || t.status == TenderStatus.PUBLISHED ->
                    ComplianceCheck("Competitive bidding", CheckResult.NOT_YET,
                        "$counted bids so far; bidding is still open.")
                counted >= MIN_BIDS -> ComplianceCheck("Competitive bidding", CheckResult.PASSED,
                    "$counted bids received.")
                else -> ComplianceCheck("Competitive bidding", CheckResult.FAILED,
                    "Only $counted bids received; at least $MIN_BIDS are expected.")
            }

            // 3. Required documents
            val incomplete = dossier.bids.filter { !it.documentsComplete && it.status != BidStatus.WITHDRAWN }
            checks += when {
                dossier.bids.isEmpty() -> ComplianceCheck("Required documents", CheckResult.NOT_YET,
                    "No bids received yet.")
                incomplete.isEmpty() -> ComplianceCheck("Required documents", CheckResult.PASSED,
                    "Every bidder submitted all documents.")
                incomplete.all { it.status == BidStatus.DISQUALIFIED } ->
                    ComplianceCheck("Required documents", CheckResult.PASSED,
                        "${incomplete.size} incomplete bid(s), each disqualified as required.")
                else -> ComplianceCheck("Required documents", CheckResult.FAILED,
                    incomplete.filter { it.status != BidStatus.DISQUALIFIED }
                        .joinToString(", ") { "${it.supplierName} (${it.documentsReceived} of ${it.documentsRequired})" } +
                        " still in the process with documents outstanding.")
            }

            // 4. Evaluation before award
            checks += when {
                t.awardedAt == null -> ComplianceCheck("Evaluation before award", CheckResult.NOT_YET,
                    if (dossier.result == null) "Evaluation not completed yet." else "Not awarded yet.")
                dossier.result == null -> ComplianceCheck("Evaluation before award", CheckResult.FAILED,
                    "Awarded with no evaluation record.")
                (daysBetween(dossier.result.completedAt, t.awardedAt) ?: 0) >= 0 ->
                    ComplianceCheck("Evaluation before award", CheckResult.PASSED,
                        "Evaluation completed by ${dossier.result.committee}.")
                else -> ComplianceCheck("Evaluation before award", CheckResult.FAILED,
                    "The award is dated before the evaluation was completed.")
            }

            // 5. Award followed the recommendation
            val recommended = dossier.recommendedBid
            checks += when {
                t.awardedAt == null -> ComplianceCheck("Award follows recommendation", CheckResult.NOT_YET,
                    "Not awarded yet.")
                recommended == null -> ComplianceCheck("Award follows recommendation", CheckResult.NOT_YET,
                    "No recommendation recorded.")
                recommended.supplierName == t.awardedSupplierName ->
                    ComplianceCheck("Award follows recommendation", CheckResult.PASSED,
                        "Awarded to the recommended bidder, ${recommended.supplierName}.")
                else -> ComplianceCheck("Award follows recommendation", CheckResult.FAILED,
                    "Recommended ${recommended.supplierName}, awarded to ${t.awardedSupplierName}.")
            }

            // 6. Award value against the estimate
            val awarded = t.awardedValue
            checks += when {
                awarded == null || t.estimatedBudget <= 0.0 ->
                    ComplianceCheck("Award within estimate", CheckResult.NOT_YET, "Not awarded yet.")
                kotlin.math.abs(awarded - t.estimatedBudget) / t.estimatedBudget <= AWARD_VARIANCE ->
                    ComplianceCheck("Award within estimate", CheckResult.PASSED,
                        "Within ${(AWARD_VARIANCE * 100).toInt()}% of the published estimate.")
                else -> ComplianceCheck("Award within estimate", CheckResult.FAILED,
                    "Differs from the estimate by more than ${(AWARD_VARIANCE * 100).toInt()}%.")
            }

            // 7. Approval
            checks += when {
                t.awardedAt == null -> ComplianceCheck("Approval recorded", CheckResult.NOT_YET, "Not awarded yet.")
                dossier.approval == null -> ComplianceCheck("Approval recorded", CheckResult.FAILED,
                    "No approver is recorded for this award.")
                else -> ComplianceCheck("Approval recorded", CheckResult.PASSED,
                    "${dossier.approval.approverName}, ${dossier.approval.approverPosition}.")
            }

            // 8. Award only after closing
            checks += when {
                t.awardedAt == null -> ComplianceCheck("Closing date respected", CheckResult.NOT_YET,
                    "Not awarded yet.")
                (daysBetween(t.closingDate, t.awardedAt) ?: 0) >= 0 ->
                    ComplianceCheck("Closing date respected", CheckResult.PASSED,
                        "Awarded after bidding closed.")
                else -> ComplianceCheck("Closing date respected", CheckResult.FAILED,
                    "The award is dated before the closing date.")
            }

            return ComplianceReport(t.id, t.referenceNumber, t.title, checks)
        }
    }
}

/**
 * Whole days from one ISO date to another, or null if either cannot be read.
 * Worked out from the dates themselves, so a compliance check never depends on
 * the device's clock or time zone.
 */
fun daysBetween(startIso: String?, endIso: String?): Int? {
    val start = epochDay(startIso) ?: return null
    val end = epochDay(endIso) ?: return null
    return (end - start).toInt()
}

/** Days since 1970-01-01 for an ISO date such as "2026-08-12" or "2026-08-12T09:00:00Z". */
internal fun epochDay(iso: String?): Long? {
    val text = iso?.trim() ?: return null
    if (text.length < 10) return null
    val year = text.substring(0, 4).toLongOrNull() ?: return null
    val month = text.substring(5, 7).toLongOrNull() ?: return null
    val day = text.substring(8, 10).toLongOrNull() ?: return null
    if (month !in 1..12 || day !in 1..31) return null

    // Days from the civil calendar, shifting the year to start in March so leap
    // days fall at the end (Howard Hinnant's civil_from_days, in reverse).
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097 + doe - 719468
}
