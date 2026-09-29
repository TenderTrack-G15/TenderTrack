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
 * or update its deliverables — the database refuses both.
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
    @SerialName("deliverables_awaiting") val deliverablesAwaiting: Int = 0
) {
    val isClaimed: Boolean get() = claimStatus == AwardClaimStatus.CLAIMED
    val canEnterCode: Boolean get() = claimStatus == AwardClaimStatus.PENDING

    /** Share of the contract's phases the department has verified, 0..1. */
    val progress: Float
        get() = if (deliverablesTotal == 0) 0f else deliverablesVerified.toFloat() / deliverablesTotal
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

/** A contract phase's state. Same values as the deliverable_status type. */
@Serializable
enum class ContractPhaseStatus {
    @SerialName("not_started") NOT_STARTED,
    @SerialName("awaiting_verification") AWAITING_VERIFICATION,
    @SerialName("verified") VERIFIED,
    @SerialName("overdue") OVERDUE;

    /** Wording for the supplier doing the work. */
    val displayName: String get() = when (this) {
        NOT_STARTED -> "Not started"
        AWAITING_VERIFICATION -> "Evidence submitted"
        VERIFIED -> "Verified"
        OVERDUE -> "Overdue"
    }
}

/** One phase of an awarded contract, from the `deliverables` table. */
@Serializable
data class ContractDeliverable(
    val id: String,
    @SerialName("tender_id") val tenderId: String,
    @SerialName("phase_name") val phaseName: String,
    @SerialName("target_date") val targetDate: String,
    @SerialName("phase_value") val phaseValue: Double = 0.0,
    val status: ContractPhaseStatus = ContractPhaseStatus.NOT_STARTED,
    @SerialName("evidence_url") val evidenceUrl: String? = null,
    @SerialName("evidence_note") val evidenceNote: String? = null,
    @SerialName("evidence_at") val evidenceAt: String? = null,
    @SerialName("verified_at") val verifiedAt: String? = null
) {
    /** The officer sent the evidence back; the reason starts the note. */
    val wasReturned: Boolean
        get() = status != ContractPhaseStatus.VERIFIED && evidenceNote?.startsWith("Returned:") == true

    /** The supplier may (re)submit evidence for this phase. */
    val canSubmit: Boolean
        get() = status == ContractPhaseStatus.NOT_STARTED || status == ContractPhaseStatus.OVERDUE
}

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

/** Evidence for one phase: a link, a description, or both. */
data class EvidenceForm(val link: String = "", val note: String = "") {
    fun errors(): Map<String, String> = buildMap {
        val url = link.trim()
        if (url.isEmpty() && note.isBlank()) put("link", "Add a link to the evidence, or describe what was delivered.")
        if (url.isNotEmpty() && !Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(url)) {
            put("link", "The link must start with http:// or https://")
        }
        if (note.length > 1000) put("note", "Keep the description under 1000 characters.")
    }
}
