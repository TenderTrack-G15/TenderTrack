package za.ac.tendertrack.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Registering for TenderTrack (supabase/etender_awards.sql, section 13).
 *
 * A company registers twice, on purpose: first on the eTender portal (the
 * government's record: company, compliance, banking, documents), then for
 * TenderTrack in the app. The app registration must match the portal one —
 * same account, same CSD and company registration numbers — and is confirmed
 * with a 6-digit code emailed to the company's contact address. Until both are
 * done, the database refuses to claim awards or accept deliverable updates.
 */

/** Where the signed-in supplier stands, from tendertrack_registration_status(). */
@Serializable
data class TenderTrackRegistration(
    @SerialName("has_company") val hasCompany: Boolean = false,
    @SerialName("company_name") val companyName: String = "",
    @SerialName("csd_number") val csdNumber: String = "",
    /** Worked out by the database from the company's records; nobody sets it by hand. */
    @SerialName("portal_registered") val portalRegistered: Boolean = false,
    /** What the portal registration still lacks, e.g. "banking information". Empty when complete. */
    @SerialName("portal_missing") val portalMissing: List<String> = emptyList(),
    @SerialName("app_registered") val appRegistered: Boolean = false,
    @SerialName("app_registered_at") val appRegisteredAt: String? = null,
    /** A code was sent and can still be used. */
    @SerialName("code_pending") val codePending: Boolean = false,
    /** Masked by the database, e.g. "t•••s@ubuntunet.co.za". */
    @SerialName("sent_to") val sentTo: String = ""
)

/** The answer from start_tendertrack_registration(). */
@Serializable
data class RegistrationCodeSent(
    /** True when the company was already registered for TenderTrack. */
    val already: Boolean = false,
    @SerialName("sent_to") val sentTo: String = "",
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("company_name") val companyName: String = ""
)

/** The answer from complete_tendertrack_registration(). A wrong code is an answer, not an error. */
@Serializable
data class RegistrationCheck(
    val ok: Boolean,
    val message: String,
    @SerialName("attempts_left") val attemptsLeft: Int = 0,
    @SerialName("need_new_code") val needNewCode: Boolean = false
)

/** The same formats the portal and the database check. */
object RegistrationInput {
    const val CODE_LENGTH = 6

    fun csd(raw: String): String? {
        val v = raw.trim().uppercase()
        return when {
            v.isEmpty() -> "Enter the CSD supplier number from your eTender registration."
            !Regex("^MAAA\\d{7}$").matches(v) -> "A CSD number looks like MAAA0451236."
            else -> null
        }
    }

    fun registrationNumber(raw: String): String? {
        val v = raw.trim()
        return when {
            v.isEmpty() -> "Enter the company registration number from your eTender registration."
            !Regex("^\\d{4}/\\d{6}/\\d{2}$").matches(v) -> "A registration number looks like 2019/451236/07."
            else -> null
        }
    }

    /** Digits only, at most 6. */
    fun cleanCode(raw: String): String = raw.filter { it.isDigit() }.take(CODE_LENGTH)

    fun code(raw: String): String? {
        val d = cleanCode(raw)
        return when {
            d.isEmpty() -> "Enter the 6-digit code from the email."
            d.length < CODE_LENGTH -> "The code has 6 digits. You have entered ${d.length}."
            else -> null
        }
    }
}
