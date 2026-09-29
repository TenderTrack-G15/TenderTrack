package za.ac.tendertrack.data.repo

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import za.ac.tendertrack.data.SupabaseModule
import za.ac.tendertrack.data.model.*

/**
 * Registering a company for TenderTrack after it registered on the eTender
 * portal. The rules live in the database (etender_awards.sql, section 13):
 * the numbers must match the portal registration of the signed-in account,
 * codes expire after 15 minutes, 5 wrong tries end a code, and a new code can
 * be asked for once a minute.
 */
interface RegistrationRepository {
    suspend fun status(): TenderTrackRegistration

    /** Checks the numbers and emails a 6-digit code to the company's contact address. */
    suspend fun start(csdNumber: String, registrationNumber: String): RegistrationCodeSent

    suspend fun complete(code: String): RegistrationCheck

    companion object {
        /** The real database when Supabase is configured, sample data otherwise. */
        val instance: RegistrationRepository by lazy {
            SupabaseModule.client?.let { SupabaseRegistrationRepository(it) } ?: SampleRegistrationRepository()
        }
    }
}

/** Database names used here. Kept with the repository so no other file needs editing. */
private object RegistrationApi {
    const val STATUS = "tendertrack_registration_status"
    const val START = "start_tendertrack_registration"
    const val COMPLETE = "complete_tendertrack_registration"
}

class SupabaseRegistrationRepository(private val client: SupabaseClient) : RegistrationRepository {

    override suspend fun status(): TenderTrackRegistration = withContext(Dispatchers.IO) {
        client.postgrest.rpc(RegistrationApi.STATUS, buildJsonObject { }).decodeAs<TenderTrackRegistration>()
    }

    override suspend fun start(csdNumber: String, registrationNumber: String): RegistrationCodeSent =
        withContext(Dispatchers.IO) {
            client.postgrest.rpc(
                RegistrationApi.START,
                buildJsonObject {
                    put("p_csd_number", csdNumber.trim().uppercase())
                    put("p_registration_number", registrationNumber.trim())
                }
            ).decodeAs<RegistrationCodeSent>()
        }

    override suspend fun complete(code: String): RegistrationCheck = withContext(Dispatchers.IO) {
        client.postgrest.rpc(
            RegistrationApi.COMPLETE,
            buildJsonObject { put("p_code", RegistrationInput.cleanCode(code)) }
        ).decodeAs<RegistrationCheck>()
    }
}

/**
 * Offline stand-in with the same rules. The sample company is Infratech
 * (CSD MAAA0451236, registration 2011/004512/07) and the sample code is 123456;
 * the registration screen says so when sample data is in use.
 */
class SampleRegistrationRepository : RegistrationRepository {

    private var registered = false
    private var codeSent = false
    private var attempts = 0

    override suspend fun status() = TenderTrackRegistration(
        hasCompany = true, companyName = "Infratech Solutions (Pty) Ltd", csdNumber = SAMPLE_CSD,
        portalRegistered = true, appRegistered = registered, codePending = codeSent && attempts < 5,
        sentTo = "o•••s@infratech.co.za"
    )

    override suspend fun start(csdNumber: String, registrationNumber: String): RegistrationCodeSent {
        if (registered) return RegistrationCodeSent(already = true, companyName = "Infratech Solutions (Pty) Ltd")
        require(csdNumber.trim().uppercase() == SAMPLE_CSD && registrationNumber.trim() == SAMPLE_REGISTRATION) {
            "These numbers do not match the company registered on the eTender portal with this account."
        }
        codeSent = true
        attempts = 0
        return RegistrationCodeSent(sentTo = "o•••s@infratech.co.za", companyName = "Infratech Solutions (Pty) Ltd")
    }

    override suspend fun complete(code: String): RegistrationCheck {
        if (registered) return RegistrationCheck(true, "You are already registered for TenderTrack.")
        if (!codeSent || attempts >= 5) return RegistrationCheck(false, "There is no valid code. Ask for a new one.", needNewCode = true)
        val digits = RegistrationInput.cleanCode(code)
        if (digits.length != RegistrationInput.CODE_LENGTH) {
            return RegistrationCheck(false, "Enter all 6 digits of the code from the email.", 5 - attempts)
        }
        if (digits != SAMPLE_CODE) {
            attempts += 1
            val left = 5 - attempts
            return if (left <= 0) RegistrationCheck(false, "That code is not right. Ask for a new code.", 0, needNewCode = true)
            else RegistrationCheck(false, "That code is not right. $left ${if (left == 1) "attempt" else "attempts"} left.", left)
        }
        registered = true
        codeSent = false
        return RegistrationCheck(true, "Infratech Solutions (Pty) Ltd is registered for TenderTrack.")
    }

    companion object {
        const val SAMPLE_CSD = "MAAA0451236"
        const val SAMPLE_REGISTRATION = "2011/004512/07"
        const val SAMPLE_CODE = "123456"
    }
}
