package za.ac.tendertrack.data.repo

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import za.ac.tendertrack.core.Format
import za.ac.tendertrack.data.SupabaseModule
import za.ac.tendertrack.data.model.*
import za.ac.tendertrack.data.sample.SampleData
import java.security.SecureRandom

/**
 * Everything a member of the public can see or do (FR11, FR13, FR14, FR15).
 *
 * No sign-in is involved. On Supabase every call runs as the anonymous role, so
 * row-level security decides what comes back: tenders still in 'registered' are
 * never returned, payments come from the redacted payments_public view, and
 * citizen reports are only reachable through the four database functions in
 * supabase/public_access.sql.
 */
interface PublicRepository {
    /** Every tender the public may see, newest first. Never 'registered' ones. */
    suspend fun tenders(): List<Tender>
    suspend fun tender(id: String): Tender?
    suspend fun deliverables(tenderId: String): List<Deliverable>
    suspend fun payments(tenderId: String): List<PublicPayment>
    suspend fun dashboard(): PublicDashboard
    suspend fun spendByDepartment(): List<DepartmentSpend>

    // Citizen reports — create, read, update, delete (withdraw)
    suspend fun submitReport(draft: ReportDraft): CitizenReport
    /** Returns null when no report has that reference. */
    suspend fun findReport(reference: String): CitizenReport?
    suspend fun addInformation(reference: String, information: String): CitizenReport
    suspend fun withdrawReport(reference: String): CitizenReport
}

// ---------------------------------------------------------------------------
// Supabase
// ---------------------------------------------------------------------------

class SupabasePublicRepository(private val client: SupabaseClient) : PublicRepository {

    override suspend fun tenders(): List<Tender> = withContext(Dispatchers.IO) {
        // The tenders_public view, not the tenders table: it withholds the
        // estimate until award and leaves out unpublished tenders.
        client.from(SupabaseModule.Table.TENDERS_PUBLIC)
            .select { order("created_at", Order.DESCENDING) }
            .decodeList<PublicTenderRow>()
            .map { it.toTender() }
            .filter { it.status != TenderStatus.REGISTERED }
    }

    override suspend fun tender(id: String): Tender? = withContext(Dispatchers.IO) {
        client.from(SupabaseModule.Table.TENDERS_PUBLIC)
            .select { filter { eq("id", id) } }
            .decodeSingleOrNull<PublicTenderRow>()
            ?.toTender()
            ?.takeIf { it.status != TenderStatus.REGISTERED }
    }

    override suspend fun deliverables(tenderId: String): List<Deliverable> = withContext(Dispatchers.IO) {
        client.from(SupabaseModule.Table.DELIVERABLES)
            .select {
                filter { eq("tender_id", tenderId) }
                order("target_date", Order.ASCENDING)
            }
            .decodeList<Deliverable>()
    }

    override suspend fun payments(tenderId: String): List<PublicPayment> = withContext(Dispatchers.IO) {
        client.from(SupabaseModule.Table.PAYMENTS_PUBLIC)
            .select {
                filter { eq("tender_id", tenderId) }
                order("paid_on", Order.ASCENDING)
            }
            .decodeList<PublicPayment>()
    }

    override suspend fun dashboard(): PublicDashboard {
        val visible = tenders()
        val allDeliverables = withContext(Dispatchers.IO) {
            client.from(SupabaseModule.Table.DELIVERABLES)
                .select()
                .decodeList<Deliverable>()
        }
        return buildPublicDashboard(visible, allDeliverables)
    }

    override suspend fun spendByDepartment(): List<DepartmentSpend> = buildDepartmentSpend(tenders())

    override suspend fun submitReport(draft: ReportDraft): CitizenReport = withContext(Dispatchers.IO) {
        client.postgrest.rpc(
            SupabaseModule.Rpc.SUBMIT_CITIZEN_REPORT,
            buildJsonObject {
                put("p_tender_id", draft.tenderId)
                put("p_category", draft.category.apiValue)
                put("p_details", draft.details)
                put("p_contact_email", draft.contactEmail)
            }
        ).decodeAs<CitizenReport>()
    }

    override suspend fun findReport(reference: String): CitizenReport? = withContext(Dispatchers.IO) {
        client.postgrest.rpc(
            SupabaseModule.Rpc.CITIZEN_REPORT_STATUS,
            buildJsonObject { put("p_reference", reference) }
        ).decodeAs<CitizenReport>().takeIf { it.found }
    }

    override suspend fun addInformation(reference: String, information: String): CitizenReport =
        withContext(Dispatchers.IO) {
            client.postgrest.rpc(
                SupabaseModule.Rpc.ADD_CITIZEN_REPORT_INFORMATION,
                buildJsonObject {
                    put("p_reference", reference)
                    put("p_information", information)
                }
            ).decodeAs<CitizenReport>()
        }

    override suspend fun withdrawReport(reference: String): CitizenReport = withContext(Dispatchers.IO) {
        client.postgrest.rpc(
            SupabaseModule.Rpc.WITHDRAW_CITIZEN_REPORT,
            buildJsonObject { put("p_reference", reference) }
        ).decodeAs<CitizenReport>()
    }
}

// ---------------------------------------------------------------------------
// Sample data (no Supabase configured)
// ---------------------------------------------------------------------------

/**
 * Offline stand-in. Uses the same [SampleData] tenders as the officer screens,
 * applies the same validation rules as the database functions, and writes a
 * citizen report into the sample flags and notifications so the officer
 * screens show it too.
 */
class SamplePublicRepository : PublicRepository {

    /** Mirrors the deliverables seeded in supabase/seed.sql for GP/CLD/1187. */
    private val sampleDeliverables = listOf(
        Deliverable("d-001", "t-006", "Deposit / mobilisation", "2026-07-05", 2_400_000.0, DeliverableStatus.VERIFIED, "2026-07-08T09:00:00Z"),
        Deliverable("d-002", "t-006", "Delivery milestone 1", "2026-08-10", 1_700_000.0, DeliverableStatus.VERIFIED, "2026-08-13T09:00:00Z"),
        Deliverable("d-003", "t-006", "Delivery milestone 2", "2026-10-15", 2_400_000.0, DeliverableStatus.AWAITING_VERIFICATION),
        Deliverable("d-004", "t-006", "Acceptance testing", "2026-12-01", 1_900_000.0, DeliverableStatus.NOT_STARTED),
        Deliverable("d-005", "t-006", "Final payment / retention", "2027-01-20", 1_050_000.0, DeliverableStatus.NOT_STARTED)
    )

    /** In-memory reports, keyed by reference. Also holds the one private field. */
    private data class StoredReport(val report: CitizenReport, val flagId: String, val contactEmail: String?)

    private val reports = mutableMapOf<String, StoredReport>()
    private val random = SecureRandom()

    /** Same rule as the tenders_public view: no unpublished tenders, no early estimates. */
    override suspend fun tenders(): List<Tender> =
        SampleData.tenders
            .filter { it.status != TenderStatus.REGISTERED }
            .map { it.withEstimateWithheld() }

    override suspend fun tender(id: String): Tender? = tenders().firstOrNull { it.id == id }

    override suspend fun deliverables(tenderId: String): List<Deliverable> =
        sampleDeliverables.filter { it.tenderId == tenderId }

    override suspend fun payments(tenderId: String): List<PublicPayment> =
        SampleData.payments
            .filter { it.tenderId == tenderId }
            .map { PublicPayment(it.id, it.tenderId, it.tenderReference, it.milestone, it.amount, it.paidOn) }
            .sortedBy { it.paidOn }

    override suspend fun dashboard(): PublicDashboard = buildPublicDashboard(tenders(), sampleDeliverables)

    override suspend fun spendByDepartment(): List<DepartmentSpend> = buildDepartmentSpend(tenders())

    override suspend fun submitReport(draft: ReportDraft): CitizenReport {
        val tender = tender(draft.tenderId) ?: error("This tender is not open to public review.")
        val details = draft.details.trim()
        val contact = draft.contactEmail?.trim()?.lowercase()?.ifBlank { null }

        // Same rules as submit_citizen_report() in public_access.sql.
        require(details.length >= 20) { "Describe the concern in at least 20 characters." }
        require(details.length <= 1000) { "Keep the description under 1000 characters." }
        require(reports.values.none {
            it.report.tenderId == tender.id && it.report.details.equals(details, ignoreCase = true)
        }) { "An identical report on this tender was already received today." }

        val now = Format.nowIso()
        val reference = newReference()
        val flagId = "f-citizen-${System.currentTimeMillis()}"
        val flagReference = "FLG-2026-" + (SampleData.flags.size + 100).toString().padStart(4, '0')

        // Raise the flag the officer reviews (FR15: "reviewed by authorised users").
        SampleData.flags.add(
            0,
            ComplianceFlag(
                id = flagId,
                reference = flagReference,
                tenderId = tender.id,
                tenderReference = tender.referenceNumber,
                tenderTitle = tender.title,
                title = "Citizen report: ${draft.category.displayName}",
                description = details,
                ruleTriggered = "Public report (FR15)",
                severity = if (draft.category == ReportCategory.IRREGULAR_AWARD ||
                    draft.category == ReportCategory.CONFLICT_OF_INTEREST
                ) FlagSeverity.MEDIUM else FlagSeverity.LOW,
                status = FlagStatus.OPEN,
                raisedAt = now,
                raisedAutomatically = false
            )
        )
        // Update the real sample record, not the public copy: the public copy
        // has its estimate withheld, and saving it would wipe the estimate
        // from the officer screens too.
        val index = SampleData.tenders.indexOfFirst { it.id == tender.id }
        if (index >= 0) {
            val original = SampleData.tenders[index]
            SampleData.tenders[index] = original.copy(openFlagCount = original.openFlagCount + 1)
        }
        SampleData.notifications.add(
            0,
            AppNotification(
                id = "n-citizen-${System.currentTimeMillis()}",
                kind = NotificationKind.FLAG,
                title = "Citizen report on ${tender.referenceNumber}",
                body = "${draft.category.displayName} reported by a member of the public. See $flagReference.",
                createdAt = now
            )
        )

        val report = CitizenReport(
            found = true,
            reference = reference,
            tenderId = tender.id,
            tenderReference = tender.referenceNumber,
            tenderTitle = tender.title,
            category = draft.category,
            details = details,
            hasContact = contact != null,
            status = ReportStatus.RECEIVED,
            submittedAt = now,
            updatedAt = now
        )
        reports[reference] = StoredReport(report, flagId, contact)
        return report
    }

    override suspend fun findReport(reference: String): CitizenReport? =
        reports[normalise(reference)]?.let { stored -> withFlagStatus(stored) }

    override suspend fun addInformation(reference: String, information: String): CitizenReport {
        val stored = reports[normalise(reference)] ?: error("No report was found with that reference.")
        val current = withFlagStatus(stored)
        val info = information.trim()
        require(current.status.canChange) {
            if (current.status == ReportStatus.WITHDRAWN) "This report was withdrawn and can no longer be changed."
            else "This report has been closed by the reviewers and can no longer be changed."
        }
        require(info.length >= 10) { "Add at least 10 characters of new information." }
        require(info.length <= 1000) { "Keep the new information under 1000 characters." }
        require(current.additions < CitizenReport.MAX_ADDITIONS) {
            "This report already has the maximum of ${CitizenReport.MAX_ADDITIONS} additions."
        }

        addFlagNote(stored.flagId, "Additional information: $info")
        val updated = stored.report.copy(additions = stored.report.additions + 1, updatedAt = Format.nowIso())
        reports[updated.reference] = stored.copy(report = updated)
        return withFlagStatus(reports.getValue(updated.reference))
    }

    override suspend fun withdrawReport(reference: String): CitizenReport {
        val stored = reports[normalise(reference)] ?: error("No report was found with that reference.")
        if (stored.report.status != ReportStatus.WITHDRAWN) {
            addFlagNote(
                stored.flagId,
                "The member of the public withdrew this report. Any contact details they gave have been erased."
            )
            val updated = stored.report.copy(
                status = ReportStatus.WITHDRAWN,
                hasContact = false,
                updatedAt = Format.nowIso()
            )
            // POPIA: the contact email is erased, not just hidden.
            reports[updated.reference] = stored.copy(report = updated, contactEmail = null)
        }
        return withFlagStatus(reports.getValue(stored.report.reference))
    }

    // -- helpers --------------------------------------------------------------

    /** Reports follow their flag: an officer resolving the flag closes the report. */
    private fun withFlagStatus(stored: StoredReport): CitizenReport {
        if (stored.report.status == ReportStatus.WITHDRAWN) return stored.report
        val flag = SampleData.flags.firstOrNull { it.id == stored.flagId } ?: return stored.report
        return when (flag.status) {
            FlagStatus.RESOLVED -> stored.report.copy(status = ReportStatus.CLOSED)
            FlagStatus.UNDER_INVESTIGATION -> stored.report.copy(status = ReportStatus.UNDER_REVIEW)
            FlagStatus.OPEN -> stored.report.copy(status = ReportStatus.RECEIVED)
        }
    }

    private fun addFlagNote(flagId: String, text: String) {
        val index = SampleData.flags.indexOfFirst { it.id == flagId }
        if (index < 0) return
        val flag = SampleData.flags[index]
        val note = FlagNote("n-citizen-${System.currentTimeMillis()}", "Member of the public", text, Format.nowIso())
        SampleData.flags[index] = flag.copy(notes = flag.notes + note)
    }

    private fun normalise(reference: String) = reference.trim().uppercase()

    /** CR-XXXXX-XXXXX, same alphabet as the database (no 0/O or 1/I). */
    private fun newReference(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        var reference: String
        do {
            val chars = (0 until 10).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
            reference = "CR-${chars.substring(0, 5)}-${chars.substring(5)}"
        } while (reference in reports)
        return reference
    }
}

// ---------------------------------------------------------------------------
// Rollups shared by both implementations, so sample and live numbers are
// always calculated the same way.
// ---------------------------------------------------------------------------

internal fun buildPublicDashboard(tenders: List<Tender>, deliverables: List<Deliverable>): PublicDashboard {
    val visibleIds = tenders.map { it.id }.toSet()
    val phases = deliverables.filter { it.tenderId in visibleIds }
    val awarded = tenders.filter { it.awardedValue != null }

    return PublicDashboard(
        counts = tenders.groupingBy { it.status }.eachCount(),
        closingWithin30Days = tenders.count {
            it.status == TenderStatus.PUBLISHED && (Format.daysUntil(it.closingDate) ?: -1) in 0..30
        },
        contractsAwarded = awarded.size,
        valueAwarded = awarded.sumOf { it.awardedValue ?: 0.0 },
        paidToSuppliers = tenders.sumOf { it.paidToDate },
        deliverablesCompleted = phases.count { it.status == DeliverableStatus.VERIFIED },
        deliverablesTotal = phases.size,
        openFlags = tenders.sumOf { it.openFlagCount },
        tendersWithFlags = tenders.count { it.openFlagCount > 0 },
        totalTenders = tenders.size
    )
}

internal fun buildDepartmentSpend(tenders: List<Tender>): List<DepartmentSpend> =
    tenders.groupBy { it.department }
        .map { (department, list) ->
            DepartmentSpend(
                department = department,
                tenders = list.size,
                // Only estimates that are already public (design option 2).
                estimatedBudget = list.filter { it.estimateIsPublic }.sumOf { it.estimatedBudget },
                awarded = list.sumOf { it.awardedValue ?: 0.0 },
                paid = list.sumOf { it.paidToDate }
            )
        }
        .sortedByDescending { it.awarded }
