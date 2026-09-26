package za.ac.tendertrack.data.repo

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import za.ac.tendertrack.data.SupabaseModule
import za.ac.tendertrack.data.model.CompanyProfile
import za.ac.tendertrack.data.model.Profile
import za.ac.tendertrack.data.model.SignUpResult
import za.ac.tendertrack.data.model.UserRole
import za.ac.tendertrack.data.sample.SampleData

/**
 * Authentication and the signed-in user's profile.
 *
 * The role is read from the profiles table (which the RLS policies also read
 * from) rather than being chosen anywhere in the UI. Accounts are created,
 * changed and suspended by an administrator in the admin portal; the app sees
 * those changes the next time the person signs in.
 */
interface AuthRepository {
    suspend fun signIn(email: String, password: String): Profile
    suspend fun signOut()
    suspend fun currentProfile(): Profile?

    /**
     * Creates a supplier login together with its company profile (D3 5.3).
     * The database applies the Supplier role and creates the registration,
     * status "awaiting verification".
     */
    suspend fun signUpSupplier(email: String, password: String, company: CompanyProfile): SignUpResult

    /*
     * Password reset is not here on purpose. It needs the project to have its own
     * email provider: Supabase's built-in sender only delivers to members of the
     * project's team. Until then an administrator issues a temporary password in
     * the admin portal, and ForgotPasswordScreen explains that.
     */
}

/** Shown when an administrator has suspended the account in the admin portal. */
const val SUSPENDED_MESSAGE = "This account has been suspended. Contact your administrator."

/** Shown when an administrator tries to sign in to the app. */
const val ADMIN_PORTAL_MESSAGE =
    "Administrators sign in to the TenderTrack admin portal on their own computer, not to the app."

class AccountSuspendedException : Exception(SUSPENDED_MESSAGE)

/** The one column needed to tell whether an account is suspended. */
@Serializable
private data class SuspensionFlag(val suspended: Boolean = false)

class SupabaseAuthRepository(private val client: SupabaseClient) : AuthRepository {

    override suspend fun signIn(email: String, password: String): Profile =
        withContext(Dispatchers.IO) {
            client.auth.signInWith(Email) {
                this.email = email.trim()
                this.password = password
            }
            val profile = currentProfile() ?: error("Signed in, but no profile record exists for this account.")

            // The admin portal blocks a suspended account's login, so it normally
            // never gets this far. This covers the moment in between, and the
            // database refuses the account's data either way.
            if (isSuspended(profile.id)) {
                client.auth.signOut()
                throw AccountSuspendedException()
            }
            profile
        }

    override suspend fun signOut() = withContext(Dispatchers.IO) {
        client.auth.signOut()
    }

    override suspend fun currentProfile(): Profile? = withContext(Dispatchers.IO) {
        val userId = client.auth.currentUserOrNull()?.id ?: return@withContext null
        client.from(SupabaseModule.Table.PROFILES)
            .select {
                filter { eq("id", userId) }
                limit(1)
            }
            .decodeSingleOrNull<Profile>()
    }

    /** False if the check cannot be made (for example before admin_portal.sql has been run). */
    private suspend fun isSuspended(userId: String): Boolean = try {
        client.from(SupabaseModule.Table.PROFILES)
            .select(Columns.list("suspended")) {
                filter { eq("id", userId) }
                limit(1)
            }
            .decodeSingleOrNull<SuspensionFlag>()
            ?.suspended ?: false
    } catch (e: Exception) {
        false
    }

    override suspend fun signUpSupplier(email: String, password: String, company: CompanyProfile): SignUpResult =
        withContext(Dispatchers.IO) {
            client.auth.signUpWith(Email) {
                this.email = email.trim()
                this.password = password
                // Read by handle_new_user() in the database.
                data = buildJsonObject {
                    put("account_type", "supplier")
                    put("full_name", company.representative)
                    put("company_name", company.companyName)
                    put("registration_number", company.registrationNumber)
                    put("csd_number", company.csdNumber)
                    put("tax_pin", company.taxPin)
                    put("bbbee_level", company.bbbeeLevel?.toString() ?: "")
                    put("business_type", company.businessType)
                    put("province", company.province)
                    put("representative", company.representative)
                    put("mobile_number", company.mobileNumber)
                }
            }
            // With "Confirm email" off, Supabase signs the new supplier straight in.
            if (client.auth.currentSessionOrNull() != null) {
                SignUpResult.SignedIn(
                    currentProfile() ?: error("Account created, but its profile could not be loaded.")
                )
            } else {
                SignUpResult.ConfirmEmail
            }
        }
}

/**
 * Offline stand-in. Accepts any password of a valid length.
 *
 * An email starting with "auditor" signs in as the sample auditor and one
 * starting with "supplier" as a sample supplier. One starting with "admin"
 * signs in as an administrator, so you can see the app send administrators to
 * the admin portal. Any other email signs in as the sample procurement officer.
 */
class SampleAuthRepository : AuthRepository {

    private var current: Profile? = null

    override suspend fun signIn(email: String, password: String): Profile {
        if (password.length < 8) error("Invalid login credentials")
        val clean = email.trim()
        val lower = clean.lowercase()
        val base = when {
            lower.startsWith("admin") -> Profile(
                id = "u-admin-sample",
                email = clean,
                fullName = "Sample Administrator",
                role = UserRole.ADMINISTRATOR,
                department = null
            )
            lower.startsWith("auditor") -> SampleAuditor.profile
            lower.startsWith("supplier") || SampleSupplierAccounts.hasAccount(clean) ->
                SampleSupplierAccounts.profileFor(clean)
            else -> SampleData.currentUser
        }
        return base.copy(email = clean).also { current = it }
    }

    override suspend fun signOut() {
        current = null
    }

    override suspend fun currentProfile(): Profile? = current

    override suspend fun signUpSupplier(email: String, password: String, company: CompanyProfile): SignUpResult {
        val profile = SampleSupplierAccounts.register(email.trim(), company)
        current = profile
        return SignUpResult.SignedIn(profile)
    }
}

/**
 * Roles that sign in to the app. Administrators are not here: they work in the
 * admin portal, which needs two-factor sign-in (see admin-portal/README).
 */
val SIGN_IN_ROLES = setOf(UserRole.PROCUREMENT_OFFICER, UserRole.AUDITOR, UserRole.SUPPLIER)

/** True when the profile may use the Procurement Officer screens. */
fun Profile.canUseProcurementOfficerScreens(): Boolean = role == UserRole.PROCUREMENT_OFFICER
