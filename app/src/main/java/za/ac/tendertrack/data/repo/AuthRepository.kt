package za.ac.tendertrack.data.repo

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * from) rather than being chosen anywhere in the UI.
 */
interface AuthRepository {
    suspend fun signIn(email: String, password: String): Profile
    suspend fun signOut()
    suspend fun currentProfile(): Profile?

    /**
     * Creates a supplier login together with its company profile (D3 5.3).
     * The database applies the Supplier role and creates the registration,
     * status "awaiting verification" (supplier_accounts.sql).
     */
    suspend fun signUpSupplier(email: String, password: String, company: CompanyProfile): SignUpResult

    /*
     * Password reset is not here on purpose. It needs the project to have its own
     * email provider: Supabase's built-in sender only delivers to members of the
     * project's team. Until then an administrator resets a password in the
     * Supabase dashboard, and ForgotPasswordScreen explains that. The setup guide
     * has the code to add back when an email provider is configured.
     */
}

class SupabaseAuthRepository(private val client: SupabaseClient) : AuthRepository {

    override suspend fun signIn(email: String, password: String): Profile =
        withContext(Dispatchers.IO) {
            client.auth.signInWith(Email) {
                this.email = email.trim()
                this.password = password
            }
            currentProfile() ?: error("Signed in, but no profile record exists for this account.")
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

    override suspend fun signUpSupplier(email: String, password: String, company: CompanyProfile): SignUpResult =
        withContext(Dispatchers.IO) {
            client.auth.signUpWith(Email) {
                this.email = email.trim()
                this.password = password
                // Read by handle_new_user() in supplier_accounts.sql.
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
 * An email starting with "admin" signs in as the sample administrator and one
 * starting with "supplier" as a sample supplier, so every role's screens can be
 * demonstrated without Supabase. Any other email signs in as the sample
 * procurement officer. A supplier who signs up in sample mode can sign in again
 * with the same email.
 */
class SampleAuthRepository : AuthRepository {

    private var current: Profile? = null

    override suspend fun signIn(email: String, password: String): Profile {
        if (password.length < 8) error("Invalid login credentials")
        val clean = email.trim()
        val base = when {
            clean.lowercase().startsWith("admin") -> SampleAdmin.profile
            clean.lowercase().startsWith("supplier") || SampleSupplierAccounts.hasAccount(clean) ->
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

/** Roles that have their own screens in this version of the app. */
val SIGN_IN_ROLES = setOf(UserRole.PROCUREMENT_OFFICER, UserRole.ADMINISTRATOR, UserRole.SUPPLIER)

/** True when the profile may use the Procurement Officer screens. */
fun Profile.canUseProcurementOfficerScreens(): Boolean =
    role == UserRole.PROCUREMENT_OFFICER || role == UserRole.ADMINISTRATOR
