package za.ac.tendertrack.data.repo

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import za.ac.tendertrack.data.SupabaseModule
import za.ac.tendertrack.data.model.Profile
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
}

/** Offline stand-in. Accepts any password of a valid length. */
class SampleAuthRepository : AuthRepository {

    private var signedIn = false

    override suspend fun signIn(email: String, password: String): Profile {
        if (password.length < 8) error("Invalid login credentials")
        signedIn = true
        return SampleData.currentUser.copy(email = email.trim())
    }

    override suspend fun signOut() {
        signedIn = false
    }

    override suspend fun currentProfile(): Profile? =
        if (signedIn) SampleData.currentUser else null
}

/** True when the profile may use the Procurement Officer screens. */
fun Profile.canUseProcurementOfficerScreens(): Boolean =
    role == UserRole.PROCUREMENT_OFFICER || role == UserRole.ADMINISTRATOR
