package za.ac.tendertrack.data.repo

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.mfa.FactorType
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import za.ac.tendertrack.data.SupabaseModule
import za.ac.tendertrack.data.model.TwoFactorSetUp
import za.ac.tendertrack.data.model.TwoFactorStatus
import za.ac.tendertrack.data.model.UserRole

/**
 * Two-factor sign-in with an authenticator app, the same as the admin portal.
 *
 * After the password, procurement officers, suppliers and auditors type a
 * 6-digit code from an authenticator app (Google Authenticator, Microsoft
 * Authenticator, ...). The first time, they add TenderTrack to the app. The
 * database refuses these accounts everything except their own profile until
 * the code has been entered (mfa_request_gate in supabase/phase_proof.sql), so
 * the code step cannot be skipped by going round the app.
 *
 * It is one authenticator per account, shared with the eTender portal.
 */
interface TwoFactorRepository {

    /** Whether this account needs the code now, and whether an authenticator is already set up. */
    suspend fun status(): TwoFactorStatus

    /**
     * First time only: creates the authenticator entry. Half-finished set-ups
     * from earlier attempts ([leftOver]) are removed first.
     */
    suspend fun startSetUp(leftOver: List<String>): TwoFactorSetUp

    /** Checks a 6-digit code. Throws if it is wrong; on success the session is upgraded. */
    suspend fun verify(factorId: String, code: String)

    /**
     * True when a session that was already open (for example one left from an
     * earlier, unfinished sign-in) still owes its code. Such a session can read
     * nothing, so the screen signs it out and starts again.
     */
    suspend fun sessionOwesCode(role: UserRole): Boolean =
        role in TWO_FACTOR_ROLES && status().let { it.required && !it.satisfied && !it.inGrace }

    companion object {
        val instance: TwoFactorRepository by lazy {
            SupabaseModule.client?.let { SupabaseTwoFactorRepository(it) } ?: SampleTwoFactorRepository()
        }
    }
}

/** The roles that sign in with a password and then a 6-digit code. */
val TWO_FACTOR_ROLES = setOf(UserRole.PROCUREMENT_OFFICER, UserRole.SUPPLIER, UserRole.AUDITOR)

/** The name shown in the authenticator app. */
const val AUTHENTICATOR_ISSUER = "TenderTrack"

class SupabaseTwoFactorRepository(private val client: SupabaseClient) : TwoFactorRepository {

    override suspend fun status(): TwoFactorStatus = withContext(Dispatchers.IO) {
        client.postgrest.rpc("mfa_status", buildJsonObject { }).decodeAs<TwoFactorStatus>()
    }

    override suspend fun startSetUp(leftOver: List<String>): TwoFactorSetUp = withContext(Dispatchers.IO) {
        leftOver.forEach { id -> runCatching { client.auth.mfa.unenroll(id) } }
        val stamp = System.currentTimeMillis() / 1000
        val factor = client.auth.mfa.enroll(FactorType.TOTP, friendlyName = "TenderTrack app $stamp") {
            issuer = AUTHENTICATOR_ISSUER
        }
        TwoFactorSetUp(factorId = factor.id, secret = factor.data.secret, uri = factor.data.uri, qrSvg = factor.data.qrCode)
    }

    override suspend fun verify(factorId: String, code: String) {
        withContext(Dispatchers.IO) {
            val challenge = client.auth.mfa.createChallenge(factorId)
            client.auth.mfa.verifyChallenge(factorId = factorId, challengeId = challenge.id, code = code, saveSession = true)
        }
    }
}

/**
 * Offline stand-in. Every sign-in asks for the code, which is always 123456.
 * The first one shows a sample set-up key.
 */
class SampleTwoFactorRepository : TwoFactorRepository {
    private var setUp = false

    override suspend fun status(): TwoFactorStatus =
        TwoFactorStatus(required = true, satisfied = false, verifiedFactorId = if (setUp) SAMPLE_FACTOR else null)

    /** Sample sessions only exist after a completed sign-in. */
    override suspend fun sessionOwesCode(role: UserRole): Boolean = false

    override suspend fun startSetUp(leftOver: List<String>): TwoFactorSetUp = TwoFactorSetUp(
        factorId = SAMPLE_FACTOR,
        secret = SAMPLE_SECRET,
        uri = "otpauth://totp/$AUTHENTICATOR_ISSUER:sample?secret=$SAMPLE_SECRET&issuer=$AUTHENTICATOR_ISSUER"
    )

    override suspend fun verify(factorId: String, code: String) {
        if (code != SAMPLE_CODE) throw IllegalArgumentException("Invalid TOTP code entered")
        setUp = true
    }

    companion object {
        const val SAMPLE_CODE = "123456"
        const val SAMPLE_SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"
        private const val SAMPLE_FACTOR = "sample-factor"
    }
}
