package za.ac.tendertrack.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Awards and contract delivery for the Supplier (supabase/etender_awards.sql).
 *
 * A tender is awarded on the eTender portal. The database then creates a
 * one-time 10-digit award code, keeps only a scrambled (hashed) copy, and the
 * portal emails the code to the company. The company claims the award by
 * entering the code in TenderTrack. Until then it cannot start the contract
 * or photograph its phases — the database refuses both.
 *
 * The contract's five phases and their photo proof are in PhaseModels.kt.
 */

/** Where the award code is, as supplier_awards() reports it. */
@Serializable
enum class AwardClaimStatus {
    @SerialName("no_code") NO_CODE,
    @SerialName("pending") PENDING,
    @SerialName("claimed") CLAIMED,
    @SerialName("expired") EXPIRED,
    @SerialName("locked") LOCKED;

    val displayName: String get() = when (this) {
        NO_CODE -> "Code not sent yet"
        PENDING -> "Awaiting your code"
        CLAIMED -> "Claimed"
        EXPIRED -> "Code expired"
        LOCKED -> "Code locked"
    }
}

/** One tender awarded to the signed-in supplier's company. */
@Serializable
data class SupplierAward(
    @SerialName("tender_id") val tenderId: String,
    @SerialName("reference_number") val referenceNumber: String,
    val title: String,
    val department: String,
    val status: TenderStatus,
    @SerialName("awarded_value") val awardedValue: Double? = null,
    @SerialName("awarded_at") val awardedAt: String? = null,
    @SerialName("claim_status") val claimStatus: AwardClaimStatus = AwardClaimStatus.NO_CODE,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("claimed_at") val claimedAt: String? = null,
    @SerialName("attempts_left") val attemptsLeft: Int = 0,
    /** Masked by the database, e.g. "t•••s@ubuntunet.co.za". */
    @SerialName("sent_to") val sentTo: String = "",
    @SerialName("deliverables_total") val deliverablesTotal: Int = 0,
    @SerialName("deliverables_verified") val deliverablesVerified: Int = 0,
    @SerialName("deliverables_awaiting") val deliverablesAwaiting: Int = 0,
    /** The phase being worked on now: the first one not yet verified. Null when all are done. */
    @SerialName("current_phase_no") val currentPhaseNo: Int? = null,
    @SerialName("current_phase_name") val currentPhaseName: String? = null,
    @SerialName("current_phase_state") val currentPhaseState: PhaseState? = null,
    @SerialName("phases_payment_approved") val phasesPaymentApproved: Int = 0
) {
    val isClaimed: Boolean get() = claimStatus == AwardClaimStatus.CLAIMED
    val canEnterCode: Boolean get() = claimStatus == AwardClaimStatus.PENDING

    /** Share of the contract's phases the department has verified, 0..1. */
    val progress: Float
        get() = if (deliverablesTotal == 0) 0f else deliverablesVerified.toFloat() / deliverablesTotal

    /** "Phase 2 of 5 · Execution: photo needed", or that every phase is verified. */
    val currentPhaseLine: String
        get() = when {
            deliverablesTotal == 0 -> "The contract's phases are being set up."
            currentPhaseNo == null -> "All $deliverablesTotal phases verified"
            else -> "Phase $currentPhaseNo of $deliverablesTotal · $currentPhaseName" +
                (currentPhaseState?.let { ": ${it.supplierLabel.replaceFirstChar { c -> c.lowercase() }}" } ?: "")
        }
}

/**
 * The answer from claim_award(). A wrong code is an answer, not an error, so
 * the database can count the attempt.
 */
@Serializable
data class ClaimResult(
    val ok: Boolean,
    val message: String,
    @SerialName("attempts_left") val attemptsLeft: Int = 0,
    val locked: Boolean = false,
    val expired: Boolean = false
)

/** The award code as the supplier types it. The database checks it again. */
object AwardCodeInput {
    const val LENGTH = 10

    /** Keeps digits only, at most 10: "12345 67890" and "12345-67890" both work. */
    fun clean(raw: String): String = raw.filter { it.isDigit() }.take(LENGTH)

    fun error(raw: String): String? {
        val digits = clean(raw)
        return when {
            digits.isEmpty() -> "Enter the 10-digit code from the award email."
            digits.length < LENGTH -> "The code has 10 digits. You have entered ${digits.length}."
            else -> null
        }
    }

    /** "1234567890" -> "12345 67890", the way the email shows it. */
    fun pretty(raw: String): String {
        val d = clean(raw)
        return if (d.length > 5) d.substring(0, 5) + " " + d.substring(5) else d
    }
}
