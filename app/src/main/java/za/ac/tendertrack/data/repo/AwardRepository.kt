package za.ac.tendertrack.data.repo

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import za.ac.tendertrack.core.Format
import za.ac.tendertrack.data.SupabaseModule
import za.ac.tendertrack.data.model.*

/**
 * A supplier's awards: claiming one with the emailed award code
 * (supabase/etender_awards.sql). The contract's phases and their photo proof
 * are in PhaseRepository.
 *
 * Every rule is enforced by the database, not by this class: only the awarded
 * company can claim, a wrong code counts as an attempt, five wrong codes lock
 * it, and no phase can be started before the award is claimed.
 */
interface AwardRepository {
    /** Tenders awarded to the signed-in supplier's company, newest first. */
    suspend fun awards(): List<SupplierAward>

    /** Sends the code to the database, which compares it with its hashed copy. */
    suspend fun claim(tenderId: String, code: String): ClaimResult

    companion object {
        /** The real database when Supabase is configured, sample data otherwise. */
        val instance: AwardRepository by lazy {
            SupabaseModule.client?.let { SupabaseAwardRepository(it) } ?: SampleAwardRepository()
        }
    }
}

/** Database names used here. Kept with the repository so no other file needs editing. */
private object AwardApi {
    const val SUPPLIER_AWARDS = "supplier_awards"
    const val CLAIM_AWARD = "claim_award"
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

    /** Used by the sample phases: no phase can start before the award is claimed. */
    fun isClaimed(tenderId: String): Boolean = tenderId != award.tenderId || award.isClaimed

    override suspend fun awards(): List<SupplierAward> = listOf(award.withPhases())

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

    private suspend fun SupplierAward.withPhases(): SupplierAward {
        if (!isClaimed) return this
        val phases = PhaseRepository.instance.supplierPhases(tenderId)
        val current = phases.filter { !it.state.isDone }.minByOrNull { it.phaseNo }
        return copy(
            deliverablesTotal = phases.size,
            deliverablesVerified = phases.count { it.state.isDone },
            deliverablesAwaiting = phases.count {
                it.state == PhaseState.AWAITING_SITE_CHECK || it.state == PhaseState.AWAITING_OFFICER
            },
            currentPhaseNo = current?.phaseNo,
            currentPhaseName = current?.phaseName,
            currentPhaseState = current?.state,
            phasesPaymentApproved = phases.count { it.paymentApprovedAt != null }
        )
    }

    companion object {
        const val SAMPLE_CODE = "1234567890"
    }
}
