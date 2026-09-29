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

/**
 * A supplier's awards: claiming one with the emailed award code, then keeping
 * the contract's deliverables up to date (supabase/etender_awards.sql).
 *
 * Every rule is enforced by the database, not by this class: only the awarded
 * company can claim, a wrong code counts as an attempt, five wrong codes lock
 * it, and deliverables cannot be updated before the award is claimed.
 */
interface AwardRepository {
    /** Tenders awarded to the signed-in supplier's company, newest first. */
    suspend fun awards(): List<SupplierAward>

    /** Sends the code to the database, which compares it with its hashed copy. */
    suspend fun claim(tenderId: String, code: String): ClaimResult

    /** The contract's phases, in delivery order. */
    suspend fun deliverables(tenderId: String): List<ContractDeliverable>

    /** Evidence for one phase; the department then verifies or returns it. */
    suspend fun submitEvidence(deliverableId: String, form: EvidenceForm): ContractDeliverable

    companion object {
        /** The real database when Supabase is configured, sample data otherwise. */
        val instance: AwardRepository by lazy {
            SupabaseModule.client?.let { SupabaseAwardRepository(it) } ?: SampleAwardRepository()
        }
    }
}

/** Database names used here. Kept with the repository so no other file needs editing. */
private object AwardApi {
    const val DELIVERABLES = "deliverables"
    const val SUPPLIER_AWARDS = "supplier_awards"
    const val CLAIM_AWARD = "claim_award"
    const val SUBMIT_DELIVERABLE_EVIDENCE = "submit_deliverable_evidence"
}

// ---------------------------------------------------------------------------
// Supabase
// ---------------------------------------------------------------------------

class SupabaseAwardRepository(private val client: SupabaseClient) : AwardRepository {

    override suspend fun awards(): List<SupplierAward> = withContext(Dispatchers.IO) {
        client.postgrest.rpc(AwardApi.SUPPLIER_AWARDS, buildJsonObject { }).decodeAs<List<SupplierAward>>()
    }

    override suspend fun claim(tenderId: String, code: String): ClaimResult = withContext(Dispatchers.IO) {
        client.postgrest.rpc(
            AwardApi.CLAIM_AWARD,
            buildJsonObject {
                put("p_tender_id", tenderId)
                put("p_code", AwardCodeInput.clean(code))
            }
        ).decodeAs<ClaimResult>()
    }

    override suspend fun deliverables(tenderId: String): List<ContractDeliverable> = withContext(Dispatchers.IO) {
        client.from(AwardApi.DELIVERABLES)
            .select {
                filter { eq("tender_id", tenderId) }
                order("target_date", Order.ASCENDING)
            }
            .decodeList<ContractDeliverable>()
    }

    override suspend fun submitEvidence(deliverableId: String, form: EvidenceForm): ContractDeliverable =
        withContext(Dispatchers.IO) {
            client.postgrest.rpc(
                AwardApi.SUBMIT_DELIVERABLE_EVIDENCE,
                buildJsonObject {
                    put("p_deliverable_id", deliverableId)
                    put("p_evidence_url", form.link.trim().ifBlank { null })
                    put("p_note", form.note.trim())
                }
            ).decodeAs<ContractDeliverable>()
        }
}

// ---------------------------------------------------------------------------
// Sample data (no Supabase configured)
// ---------------------------------------------------------------------------

/**
 * Offline stand-in with the same rules as the database. The sample award code
 * is 12345 67890 (the claim screen says so when sample data is in use).
 */
class SampleAwardRepository : AwardRepository {

    private val code = SAMPLE_CODE
    private var attempts = 0

    private var award = SupplierAward(
        tenderId = "t-001", referenceNumber = "GP/IT/2290", title = "Network Hardware Supply",
        department = "Gauteng Dept of e-Government", status = TenderStatus.AWARDED,
        awardedValue = 17_950_000.0, awardedAt = "2026-08-12T14:00:00Z",
        claimStatus = AwardClaimStatus.PENDING, expiresAt = "2026-10-13T10:00:00Z",
        attemptsLeft = 5, sentTo = "o•••s@infratech.co.za"
    )

    private val phases = mutableListOf(
        ContractDeliverable("p-1", "t-001", "Core switching delivered", "2026-11-30", 5_000_000.0),
        ContractDeliverable("p-2", "t-001", "Site installation at 18 offices", "2027-02-28", 8_000_000.0),
        ContractDeliverable("p-3", "t-001", "Handover and as-built documentation", "2027-04-30", 4_950_000.0)
    )

    override suspend fun awards(): List<SupplierAward> = listOf(award.withCounts())

    override suspend fun claim(tenderId: String, code: String): ClaimResult {
        require(tenderId == award.tenderId) { "This tender was not awarded to your company." }
        return when {
            award.isClaimed -> ClaimResult(true, "You have already claimed this award.")
            award.claimStatus == AwardClaimStatus.LOCKED -> ClaimResult(
                false, "This code is locked after 5 wrong attempts. Ask the department to send a new code.", locked = true
            )
            AwardCodeInput.clean(code).length != AwardCodeInput.LENGTH ->
                ClaimResult(false, "Enter all 10 digits of the code from the email.", 5 - attempts)
            AwardCodeInput.clean(code) != this.code -> {
                attempts += 1
                val left = 5 - attempts
                if (left <= 0) {
                    award = award.copy(claimStatus = AwardClaimStatus.LOCKED, attemptsLeft = 0)
                    ClaimResult(false, "That code is not right, and the code is now locked. Ask the department to send a new code.", 0, locked = true)
                } else {
                    award = award.copy(attemptsLeft = left)
                    ClaimResult(false, "That code is not right. $left ${if (left == 1) "attempt" else "attempts"} left.", left)
                }
            }
            else -> {
                award = award.copy(
                    claimStatus = AwardClaimStatus.CLAIMED, claimedAt = Format.nowIso(),
                    status = TenderStatus.IN_PROGRESS, attemptsLeft = 0
                )
                ClaimResult(true, "Award claimed. ${award.referenceNumber} is now your contract.")
            }
        }
    }

    override suspend fun deliverables(tenderId: String): List<ContractDeliverable> =
        phases.filter { it.tenderId == tenderId }

    override suspend fun submitEvidence(deliverableId: String, form: EvidenceForm): ContractDeliverable {
        check(award.isClaimed) { "Claim this award with the code from your email before updating its deliverables." }
        val errors = form.errors()
        require(errors.isEmpty()) { errors.values.first() }
        val index = phases.indexOfFirst { it.id == deliverableId }
        require(index >= 0) { "That deliverable no longer exists." }
        check(phases[index].status != ContractPhaseStatus.VERIFIED) { "This deliverable has already been verified." }
        val updated = phases[index].copy(
            status = ContractPhaseStatus.AWAITING_VERIFICATION,
            evidenceUrl = form.link.trim().ifBlank { null },
            evidenceNote = form.note.trim(),
            evidenceAt = Format.nowIso()
        )
        phases[index] = updated
        return updated
    }

    private fun SupplierAward.withCounts() = copy(
        deliverablesTotal = if (isClaimed) phases.size else 0,
        deliverablesVerified = phases.count { it.status == ContractPhaseStatus.VERIFIED },
        deliverablesAwaiting = phases.count { it.status == ContractPhaseStatus.AWAITING_VERIFICATION }
    )

    companion object {
        const val SAMPLE_CODE = "1234567890"
    }
}
