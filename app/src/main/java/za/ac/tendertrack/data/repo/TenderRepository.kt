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

/**
 * Tenders: the list, the lifecycle transitions, and awarding.
 *
 * Awarding goes through a Postgres function rather than a plain update, because
 * the 10-digit award code is generated and delivered server-side and must never
 * reach the device that issued it.
 */
interface TenderRepository {
    suspend fun list(): List<Tender>
    suspend fun byId(id: String): Tender?
    suspend fun create(draft: TenderDraft): Tender
    suspend fun update(id: String, draft: TenderDraft): Tender
    suspend fun advanceStatus(id: String, to: TenderStatus, reason: String): Tender
    suspend fun award(id: String, supplierId: String, supplierName: String, value: Double, awardDate: String): Tender
    suspend fun auditTrail(tenderId: String): List<AuditEntry>
    suspend fun dashboard(): DashboardSummary
    suspend fun fundSummary(): FundSummary
}

class SupabaseTenderRepository(
    private val client: SupabaseClient,
    private val supplierRepository: SupplierRepository,
    private val flagRepository: FlagRepository,
    private val notificationRepository: NotificationRepository
) : TenderRepository {

    override suspend fun list(): List<Tender> = withContext(Dispatchers.IO) {
        client.from(SupabaseModule.Table.TENDERS)
            .select { order("created_at", Order.DESCENDING) }
            .decodeList()
    }

    override suspend fun byId(id: String): Tender? = withContext(Dispatchers.IO) {
        client.from(SupabaseModule.Table.TENDERS)
            .select {
                filter { eq("id", id) }
                limit(1)
            }
            .decodeSingleOrNull()
    }

    override suspend fun create(draft: TenderDraft): Tender = withContext(Dispatchers.IO) {
        client.from(SupabaseModule.Table.TENDERS)
            .insert(draft) { select() }
            .decodeSingle()
    }

    override suspend fun update(id: String, draft: TenderDraft): Tender = withContext(Dispatchers.IO) {
        client.from(SupabaseModule.Table.TENDERS)
            .update(draft) {
                filter { eq("id", id) }
                select()
            }
            .decodeSingle()
    }

    override suspend fun advanceStatus(id: String, to: TenderStatus, reason: String): Tender =
        withContext(Dispatchers.IO) {
            client.postgrest.rpc(
                SupabaseModule.Rpc.ADVANCE_TENDER_STATUS,
                buildJsonObject {
                    put("p_tender_id", id)
                    put("p_new_status", to.serialName())
                    put("p_reason", reason)
                }
            )
            byId(id) ?: error("Tender not found after the status change.")
        }

    override suspend fun award(
        id: String,
        supplierId: String,
        supplierName: String,
        value: Double,
        awardDate: String
    ): Tender = withContext(Dispatchers.IO) {
        // The function writes the award, generates the single-use code, queues
        // the email and SMS, and writes the audit entry — all server-side.
        client.postgrest.rpc(
            SupabaseModule.Rpc.AWARD_TENDER,
            buildJsonObject {
                put("p_tender_id", id)
                put("p_supplier_id", supplierId)
                put("p_awarded_value", value)
                put("p_awarded_at", awardDate)
            }
        )
        byId(id) ?: error("Tender not found after the award.")
    }

    override suspend fun auditTrail(tenderId: String): List<AuditEntry> = withContext(Dispatchers.IO) {
        client.from(SupabaseModule.Table.AUDIT)
            .select {
                filter {
                    eq("entity_type", "tender")
                    eq("entity_id", tenderId)
                }
                order("created_at", Order.ASCENDING)
            }
            .decodeList()
    }

    override suspend fun fundSummary(): FundSummary = withContext(Dispatchers.IO) {
        client.from(SupabaseModule.Table.BUDGETS)
            .select {
                order("financial_year", Order.DESCENDING)
                limit(1)
            }
            .decodeSingleOrNull() ?: FundSummary("—", 0.0, 0.0, 0.0)
    }

    override suspend fun dashboard(): DashboardSummary =
        buildDashboard(list(), supplierRepository.list(), flagRepository.list(),
            notificationRepository.list(), fundSummary())
}

/** Offline stand-in backed by [SampleData]. Mutations update the in-memory list. */
class SampleTenderRepository : TenderRepository {

    override suspend fun list(): List<Tender> = SampleData.tenders.toList()

    override suspend fun byId(id: String): Tender? = SampleData.tenders.firstOrNull { it.id == id }

    override suspend fun create(draft: TenderDraft): Tender {
        val tender = Tender(
            id = "t-${System.currentTimeMillis()}",
            referenceNumber = draft.referenceNumber,
            title = draft.title,
            description = draft.description,
            department = draft.department,
            category = draft.category,
            estimatedBudget = draft.estimatedBudget,
            closingDate = draft.closingDate,
            contractPeriodMonths = draft.contractPeriodMonths,
            status = draft.status,
            createdAt = Format.nowIso()
        )
        SampleData.tenders.add(0, tender)
        return tender
    }

    override suspend fun update(id: String, draft: TenderDraft): Tender {
        val index = SampleData.tenders.indexOfFirst { it.id == id }
        require(index >= 0) { "Tender not found." }
        val updated = SampleData.tenders[index].copy(
            referenceNumber = draft.referenceNumber,
            title = draft.title,
            description = draft.description,
            department = draft.department,
            category = draft.category,
            estimatedBudget = draft.estimatedBudget,
            closingDate = draft.closingDate,
            contractPeriodMonths = draft.contractPeriodMonths
        )
        SampleData.tenders[index] = updated
        return updated
    }

    override suspend fun advanceStatus(id: String, to: TenderStatus, reason: String): Tender {
        val index = SampleData.tenders.indexOfFirst { it.id == id }
        require(index >= 0) { "Tender not found." }
        val current = SampleData.tenders[index]
        require(current.status.next() == to) {
            "A tender can only move to ${current.status.next()?.displayName ?: "no further status"}."
        }
        val updated = current.copy(status = to)
        SampleData.tenders[index] = updated
        SampleData.auditTrail.add(
            AuditEntry(
                id = "a-${System.currentTimeMillis()}",
                entityType = "tender",
                entityId = id,
                action = "Status changed",
                detail = "${to.displayName} — $reason",
                actor = SampleData.currentUser.fullName,
                createdAt = Format.nowIso()
            )
        )
        return updated
    }

    override suspend fun award(
        id: String,
        supplierId: String,
        supplierName: String,
        value: Double,
        awardDate: String
    ): Tender {
        val index = SampleData.tenders.indexOfFirst { it.id == id }
        require(index >= 0) { "Tender not found." }
        val updated = SampleData.tenders[index].copy(
            status = TenderStatus.AWARDED,
            awardedSupplierId = supplierId,
            awardedSupplierName = supplierName,
            awardedValue = value,
            awardedAt = awardDate
        )
        SampleData.tenders[index] = updated
        SampleData.auditTrail.add(
            AuditEntry(
                id = "a-${System.currentTimeMillis()}",
                entityType = "tender",
                entityId = id,
                action = "Tender awarded",
                detail = "$supplierName · ${Format.money(value)}. Award code issued and sent to the supplier.",
                actor = SampleData.currentUser.fullName,
                createdAt = Format.nowIso()
            )
        )
        return updated
    }

    override suspend fun auditTrail(tenderId: String): List<AuditEntry> =
        SampleData.auditTrail.filter { it.entityType == "tender" && it.entityId == tenderId }
            .sortedBy { it.createdAt }

    override suspend fun fundSummary(): FundSummary {
        val awarded = SampleData.tenders.mapNotNull { it.awardedValue }.sum()
        val paid = SampleData.tenders.sumOf { it.paidToDate }
        return SampleData.fundSummary.copy(
            committed = maxOf(SampleData.fundSummary.committed, awarded),
            disbursed = maxOf(SampleData.fundSummary.disbursed, paid)
        )
    }

    override suspend fun dashboard(): DashboardSummary = buildDashboard(
        SampleData.tenders, SampleData.suppliers, SampleData.flags,
        SampleData.notifications, fundSummary()
    )
}

// ---------------------------------------------------------------------------
// Shared rollup so both implementations compute the dashboard identically.
// ---------------------------------------------------------------------------

internal fun buildDashboard(
    tenders: List<Tender>,
    suppliers: List<Supplier>,
    flags: List<ComplianceFlag>,
    notifications: List<AppNotification>,
    funds: FundSummary
): DashboardSummary {
    val awaiting = suppliers.filter { it.status == SupplierVerificationStatus.AWAITING_VERIFICATION }
    return DashboardSummary(
        countsByStatus = TenderStatus.entries.associateWith { status ->
            tenders.count { it.status == status }
        },
        funds = funds,
        openFlags = flags.count { it.status != FlagStatus.RESOLVED },
        registrationsToVerify = awaiting.size,
        unreadNotifications = notifications.count { !it.read },
        closingWithin30Days = tenders.count {
            it.status == TenderStatus.PUBLISHED &&
                (Format.daysUntil(it.closingDate) ?: Int.MAX_VALUE) in 0..30
        },
        overdueEvaluations = tenders.count {
            it.status == TenderStatus.UNDER_EVALUATION &&
                (Format.daysSince(it.closingDate) ?: 0) > 30
        },
        unclaimedAwardCodes = tenders.count { it.status == TenderStatus.AWARDED },
        oldestRegistrationDays = awaiting.mapNotNull { Format.daysSince(it.submittedAt) }.maxOrNull() ?: 0
    )
}

/** The database spelling of a status, matching the @SerialName annotations. */
internal fun TenderStatus.serialName(): String = when (this) {
    TenderStatus.REGISTERED -> "registered"
    TenderStatus.PUBLISHED -> "published"
    TenderStatus.UNDER_EVALUATION -> "under_evaluation"
    TenderStatus.AWARDED -> "awarded"
    TenderStatus.IN_PROGRESS -> "in_progress"
    TenderStatus.COMPLETED -> "completed"
}

