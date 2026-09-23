package za.ac.tendertrack.data.repo

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import za.ac.tendertrack.data.SupabaseModule
import za.ac.tendertrack.data.model.*
import za.ac.tendertrack.data.sample.SampleData

/**
 * Everything the Auditor can see. Reads only: there is no function here that
 * creates, changes or deletes anything, and the database gives an auditor no
 * write permission on any of these tables (supabase/auditor_records.sql).
 */
interface AuditorRepository {
    /** Tender records: number, title, publication, closing, award date, status. */
    suspend fun tenderRecords(): List<TenderRecord>

    /** Everything about one tender: bids, evaluation, award, history, audit entries. */
    suspend fun dossier(tenderId: String): TenderDossier

    /** Every action the system recorded, newest first. */
    suspend fun auditLogs(): List<AuditEntry>

    /** A compliance report for every tender. */
    suspend fun complianceReports(): List<ComplianceReport>
}

// ---------------------------------------------------------------------------
// Supabase
// ---------------------------------------------------------------------------

class SupabaseAuditorRepository(private val client: SupabaseClient) : AuditorRepository {

    override suspend fun tenderRecords(): List<TenderRecord> = withContext(Dispatchers.IO) {
        client.from(SupabaseModule.Table.TENDERS)
            .select { order("closing_date", Order.DESCENDING) }
            .decodeList()
    }

    override suspend fun auditLogs(): List<AuditEntry> = withContext(Dispatchers.IO) {
        client.from(SupabaseModule.Table.AUDIT)
            .select { order("created_at", Order.DESCENDING) }
            .decodeList()
    }

    override suspend fun dossier(tenderId: String): TenderDossier = withContext(Dispatchers.IO) {
        val record = client.from(SupabaseModule.Table.TENDERS)
            .select {
                filter { eq("id", tenderId) }
                limit(1)
            }
            .decodeSingleOrNull<TenderRecord>() ?: error("That tender record no longer exists.")

        val bids = client.from(SupabaseModule.Table.BIDS)
            .select {
                filter { eq("tender_id", tenderId) }
                order("submitted_at", Order.ASCENDING)
            }
            .decodeList<Bid>()

        val criteria = client.from(SupabaseModule.Table.EVALUATION_CRITERIA)
            .select {
                filter { eq("tender_id", tenderId) }
                order("sequence", Order.ASCENDING)
            }
            .decodeList<EvaluationCriterion>()

        // Scores belong to bids, so they are filtered here rather than in the query.
        val bidIds = bids.map { it.id }.toSet()
        val scores = if (bidIds.isEmpty()) emptyList() else {
            client.from(SupabaseModule.Table.EVALUATION_SCORES)
                .select()
                .decodeList<EvaluationScore>()
                .filter { it.bidId in bidIds }
        }

        val result = client.from(SupabaseModule.Table.EVALUATION_RESULTS)
            .select {
                filter { eq("tender_id", tenderId) }
                limit(1)
            }
            .decodeSingleOrNull<EvaluationResult>()

        val approval = client.from(SupabaseModule.Table.AWARD_APPROVALS)
            .select {
                filter { eq("tender_id", tenderId) }
                limit(1)
            }
            .decodeSingleOrNull<AwardApproval>()

        val changes = client.from(SupabaseModule.Table.TENDER_CHANGES)
            .select {
                filter { eq("tender_id", tenderId) }
                order("changed_at", Order.DESCENDING)
            }
            .decodeList<TenderChange>()

        val audit = client.from(SupabaseModule.Table.AUDIT)
            .select {
                filter { eq("entity_id", tenderId) }
                order("created_at", Order.DESCENDING)
            }
            .decodeList<AuditEntry>()

        TenderDossier(record, bids, criteria, scores, result, approval, changes, audit)
    }

    override suspend fun complianceReports(): List<ComplianceReport> = withContext(Dispatchers.IO) {
        // Read each table once, then build every report in memory: one request
        // per table instead of one per tender.
        val records = tenderRecords()
        val bids = client.from(SupabaseModule.Table.BIDS).select().decodeList<Bid>()
        val criteria = client.from(SupabaseModule.Table.EVALUATION_CRITERIA).select().decodeList<EvaluationCriterion>()
        val scores = client.from(SupabaseModule.Table.EVALUATION_SCORES).select().decodeList<EvaluationScore>()
        val results = client.from(SupabaseModule.Table.EVALUATION_RESULTS).select().decodeList<EvaluationResult>()
        val approvals = client.from(SupabaseModule.Table.AWARD_APPROVALS).select().decodeList<AwardApproval>()

        records.map { record ->
            TenderDossier(
                record = record,
                bids = bids.filter { it.tenderId == record.id },
                criteria = criteria.filter { it.tenderId == record.id },
                scores = scores,
                result = results.firstOrNull { it.tenderId == record.id },
                approval = approvals.firstOrNull { it.tenderId == record.id },
                changes = emptyList(),
                auditEntries = emptyList()
            ).compliance
        }
    }
}

// ---------------------------------------------------------------------------
// Sample data (no Supabase configured)
// ---------------------------------------------------------------------------

/** The sample auditor. Sign in with any email starting with "auditor". */
object SampleAuditor {
    val profile = Profile(
        id = "u-aud-001",
        email = "auditor@agsa.co.za",
        fullName = "S. van Wyk",
        role = UserRole.AUDITOR,
        department = null
    )
}

/**
 * Offline stand-in. Mirrors the demo records created by
 * supabase/auditor_records.sql, so the screens look the same either way.
 */
class SampleAuditorRepository : AuditorRepository {

    private val records: List<TenderRecord> = SampleData.tenders.map { t ->
        TenderRecord(
            id = t.id,
            referenceNumber = t.referenceNumber,
            title = t.title,
            department = t.department,
            category = t.category,
            status = t.status,
            estimatedBudget = t.estimatedBudget,
            // Everything past registration was published; the sample data keeps
            // no separate date, so the tender's own start is used.
            publishedAt = if (t.status == TenderStatus.REGISTERED) null else publicationOf(t.referenceNumber),
            closingDate = t.closingDate,
            awardedAt = t.awardedAt,
            awardedSupplierName = t.awardedSupplierName,
            awardedValue = t.awardedValue,
            paidToDate = t.paidToDate
        )
    }

    private fun publicationOf(reference: String): String = when (reference) {
        "GP/IT/2290" -> "2026-06-02T09:00:00Z"
        "GP/CLD/1187" -> "2026-04-01T09:00:00Z"
        "NAT/SEC/492" -> "2026-06-20T09:00:00Z"
        "WC/SF/1042" -> "2026-07-14T09:00:00Z"
        "KZN/HW/9923" -> "2026-07-28T09:00:00Z"
        "EC/WEB/004" -> "2025-12-05T09:00:00Z"
        else -> "2026-09-01T09:00:00Z"
    }

    private val gpIt get() = records.first { it.referenceNumber == "GP/IT/2290" }.id
    private val nat get() = records.first { it.referenceNumber == "NAT/SEC/492" }.id
    private val cld get() = records.first { it.referenceNumber == "GP/CLD/1187" }.id

    private val bids: List<Bid> by lazy {
        listOf(
            Bid("b-2290-1", "BID-2290-01", gpIt, "Infratech Solutions (Pty) Ltd", 17_950_000.0,
                "2026-06-28T10:14:00Z", BidStatus.AWARDED, 4, 4),
            Bid("b-2290-2", "BID-2290-02", gpIt, "Vuka IT Consulting", 19_200_000.0,
                "2026-06-29T15:42:00Z", BidStatus.SHORTLISTED, 4, 4),
            Bid("b-2290-3", "BID-2290-03", gpIt, "Lethabo Digital CC", 15_100_000.0,
                "2026-06-30T16:55:00Z", BidStatus.DISQUALIFIED, 2, 4,
                "Tax clearance certificate and B-BBEE affidavit not submitted."),
            Bid("b-0492-1", "BID-0492-01", nat, "Siyakhula Technologies", 4_610_000.0,
                "2026-08-01T11:05:00Z", BidStatus.SUBMITTED, 4, 4),
            Bid("b-0492-2", "BID-0492-02", nat, "Vuka IT Consulting", 4_880_000.0,
                "2026-08-02T09:48:00Z", BidStatus.SUBMITTED, 3, 4),
            Bid("b-1187-1", "BID-1187-01", cld, "Siyakhula Technologies", 9_450_000.0,
                "2026-05-14T13:20:00Z", BidStatus.AWARDED, 4, 4)
        )
    }

    private val criteria: List<EvaluationCriterion> by lazy {
        listOf(
            EvaluationCriterion("c-2290-1", gpIt, "Price", "Scored against the lowest acceptable bid (PPPFA 80/20).", 80, 1),
            EvaluationCriterion("c-2290-2", gpIt, "Technical capability", "Team, method and comparable past projects.", 15, 2),
            EvaluationCriterion("c-2290-3", gpIt, "B-BBEE", "Specific goals claimed and verified.", 5, 3),
            EvaluationCriterion("c-0492-1", nat, "Price", "Scored against the lowest acceptable bid (PPPFA 80/20).", 80, 1),
            EvaluationCriterion("c-0492-2", nat, "Security accreditation", "Valid accreditation for classified environments.", 20, 2)
        )
    }

    private val scores: List<EvaluationScore> by lazy {
        listOf(
            EvaluationScore("s-1", "b-2290-1", "c-2290-1", 92.0, "Within 2% of the lowest compliant bid."),
            EvaluationScore("s-2", "b-2290-1", "c-2290-2", 88.0, "Three comparable provincial rollouts evidenced."),
            EvaluationScore("s-3", "b-2290-1", "c-2290-3", 100.0, "Level 2 contributor, verified certificate."),
            EvaluationScore("s-4", "b-2290-2", "c-2290-1", 78.0, "Higher than the lowest compliant bid."),
            EvaluationScore("s-5", "b-2290-2", "c-2290-2", 84.0, "Strong method, fewer comparable projects."),
            EvaluationScore("s-6", "b-2290-2", "c-2290-3", 80.0, "Level 4 contributor.")
        )
    }

    private val results: List<EvaluationResult> by lazy {
        listOf(
            EvaluationResult("r-2290", gpIt, "Bid Evaluation Committee — Gauteng Dept of e-Government",
                "2026-08-05T14:30:00Z", "b-2290-1",
                "Infratech scored highest overall after the disqualification of Lethabo Digital for incomplete documents.")
        )
    }

    private val approvals: List<AwardApproval> by lazy {
        listOf(
            AwardApproval("ap-2290", gpIt, "M. Dlamini", "Chief Financial Officer",
                "2026-08-12T08:15:00Z", 17_950_000.0,
                "Recommendation accepted. Award within the approved budget and the delegated authority."),
            AwardApproval("ap-1187", cld, "P. Naicker", "Head of Supply Chain Management",
                "2026-06-30T10:40:00Z", 9_450_000.0,
                "Single compliant bid received. Market price tested against the transversal contract.")
        )
    }

    private val changes: List<TenderChange> by lazy {
        listOf(
            TenderChange("ch-1", cld, "Closing date", "08 May 2026", "15 May 2026", "T. Mokoena", "2026-05-02T09:15:00Z"),
            TenderChange("ch-2", cld, "Status", "published", "under evaluation", "T. Mokoena", "2026-05-16T08:05:00Z"),
            TenderChange("ch-3", cld, "Status", "under evaluation", "awarded", "T. Mokoena", "2026-06-30T10:45:00Z"),
            TenderChange("ch-4", cld, "Status", "awarded", "in progress", "T. Mokoena", "2026-07-02T07:50:00Z")
        )
    }

    override suspend fun tenderRecords(): List<TenderRecord> = records.sortedByDescending { it.closingDate }

    override suspend fun auditLogs(): List<AuditEntry> = SampleData.auditTrail.sortedByDescending { it.createdAt }

    override suspend fun dossier(tenderId: String): TenderDossier {
        val record = records.firstOrNull { it.id == tenderId } ?: error("That tender record no longer exists.")
        return TenderDossier(
            record = record,
            bids = bids.filter { it.tenderId == tenderId }.sortedBy { it.submittedAt },
            criteria = criteria.filter { it.tenderId == tenderId }.sortedBy { it.sequence },
            scores = scores,
            result = results.firstOrNull { it.tenderId == tenderId },
            approval = approvals.firstOrNull { it.tenderId == tenderId },
            changes = changes.filter { it.tenderId == tenderId }.sortedByDescending { it.changedAt },
            auditEntries = SampleData.auditTrail.filter { it.entityId == tenderId }.sortedByDescending { it.createdAt }
        )
    }

    override suspend fun complianceReports(): List<ComplianceReport> = records.map { record ->
        TenderDossier(
            record = record,
            bids = bids.filter { it.tenderId == record.id },
            criteria = criteria.filter { it.tenderId == record.id },
            scores = scores,
            result = results.firstOrNull { it.tenderId == record.id },
            approval = approvals.firstOrNull { it.tenderId == record.id },
            changes = emptyList(),
            auditEntries = emptyList()
        ).compliance
    }
}
