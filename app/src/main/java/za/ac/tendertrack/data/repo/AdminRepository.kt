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
import za.ac.tendertrack.data.ServiceLocator
import za.ac.tendertrack.data.SupabaseModule
import za.ac.tendertrack.data.model.*
import za.ac.tendertrack.data.sample.SampleData
import kotlin.math.abs

/**
 * Everything the Administrator can see or do (Deliverable 3, section 5.7;
 * FR16 role-based access; FR17 export).
 *
 * Every change goes through a database function in supabase/admin_access.sql,
 * which checks the caller is an administrator, validates the change and writes
 * it to the audit trail. The app never edits the profiles table directly.
 *
 * Deliberately absent: creating logins. That needs Supabase's service_role
 * key, which bypasses every security rule and must never ship inside an app.
 * An administrator invites a person instead, and the role is applied when
 * their login is created.
 */
interface AdminRepository {
    suspend fun accounts(): List<AdminAccount>
    suspend fun setRole(userId: String, role: UserRole, department: String?): AdminAccount
    /** Suspending is how an account is removed: the row stays, access goes. */
    suspend fun setSuspended(userId: String, suspended: Boolean, reason: String?): AdminAccount

    suspend fun invitations(): List<StaffInvitation>
    suspend fun saveInvitation(draft: InvitationDraft): StaffInvitation
    suspend fun revokeInvitation(id: String)

    suspend fun auditLog(): List<AuditEntry>
    suspend fun overview(): AdminOverview

    // FR17 — export tender and payment data
    suspend fun tendersForExport(): List<Tender>
    suspend fun paymentsForExport(): List<Payment>

    /** Used by Sign In to tell a suspended person why they cannot get in. */
    suspend fun isCurrentAccountSuspended(): Boolean
}

/** Awards more than this far from the estimate are counted as anomalies. */
internal const val AWARD_VARIANCE_THRESHOLD = 0.10

/** Rollup shared by both implementations, so sample and live match. */
internal fun buildAdminOverview(
    accounts: List<AdminAccount>,
    invitations: List<StaffInvitation>,
    audit: List<AuditEntry>,
    tenders: List<Tender>
): AdminOverview {
    val today = Format.nowIso().take(10)
    return AdminOverview(
        accounts = accounts,
        pendingInvitations = invitations.count { !it.accepted },
        eventsToday = audit.count { it.createdAt.take(10) == today },
        // daysUntil is negative for dates in the past: -7..0 is "the last week".
        eventsThisWeek = audit.count { (Format.daysUntil(it.createdAt) ?: -99) in -7..0 },
        recentActivity = audit.sortedByDescending { it.createdAt }.take(5),
        totalTenders = tenders.size,
        openFlags = tenders.sumOf { it.openFlagCount },
        awardVarianceCount = tenders.count { t ->
            val awarded = t.awardedValue
            awarded != null && t.estimatedBudget > 0 &&
                abs(awarded - t.estimatedBudget) / t.estimatedBudget > AWARD_VARIANCE_THRESHOLD
        }
    )
}

// ---------------------------------------------------------------------------
// Supabase
// ---------------------------------------------------------------------------

class SupabaseAdminRepository(private val client: SupabaseClient) : AdminRepository {

    override suspend fun accounts(): List<AdminAccount> = withContext(Dispatchers.IO) {
        client.postgrest.rpc(SupabaseModule.Rpc.ADMIN_LIST_ACCOUNTS, buildJsonObject { })
            .decodeAs<List<AdminAccount>>()
    }

    override suspend fun setRole(userId: String, role: UserRole, department: String?): AdminAccount =
        withContext(Dispatchers.IO) {
            client.postgrest.rpc(
                SupabaseModule.Rpc.ADMIN_SET_ROLE,
                buildJsonObject {
                    put("p_user_id", userId)
                    put("p_role", role.apiValue)
                    put("p_department", department)
                }
            ).decodeAs<AdminAccount>()
        }

    override suspend fun setSuspended(userId: String, suspended: Boolean, reason: String?): AdminAccount =
        withContext(Dispatchers.IO) {
            client.postgrest.rpc(
                SupabaseModule.Rpc.ADMIN_SET_SUSPENDED,
                buildJsonObject {
                    put("p_user_id", userId)
                    put("p_suspended", suspended)
                    put("p_reason", reason)
                }
            ).decodeAs<AdminAccount>()
        }

    override suspend fun invitations(): List<StaffInvitation> = withContext(Dispatchers.IO) {
        client.from(SupabaseModule.Table.STAFF_INVITATIONS)
            .select { order("created_at", Order.DESCENDING) }
            .decodeList<StaffInvitation>()
    }

    override suspend fun saveInvitation(draft: InvitationDraft): StaffInvitation = withContext(Dispatchers.IO) {
        client.postgrest.rpc(
            SupabaseModule.Rpc.ADMIN_SAVE_INVITATION,
            buildJsonObject {
                put("p_id", draft.id)
                put("p_email", draft.email)
                put("p_full_name", draft.fullName)
                put("p_role", draft.role.apiValue)
                put("p_department", draft.department)
            }
        ).decodeAs<StaffInvitation>()
    }

    override suspend fun revokeInvitation(id: String) {
        withContext(Dispatchers.IO) {
            client.postgrest.rpc(SupabaseModule.Rpc.ADMIN_REVOKE_INVITATION, buildJsonObject { put("p_id", id) })
        }
    }

    override suspend fun auditLog(): List<AuditEntry> = withContext(Dispatchers.IO) {
        client.from(SupabaseModule.Table.AUDIT)
            .select { order("created_at", Order.DESCENDING) }
            .decodeList<AuditEntry>()
    }

    override suspend fun overview(): AdminOverview =
        buildAdminOverview(accounts(), invitations(), auditLog(), tendersForExport())

    override suspend fun tendersForExport(): List<Tender> = withContext(Dispatchers.IO) {
        client.from(SupabaseModule.Table.TENDERS)
            .select { order("created_at", Order.DESCENDING) }
            .decodeList<Tender>()
    }

    override suspend fun paymentsForExport(): List<Payment> = withContext(Dispatchers.IO) {
        client.from(SupabaseModule.Table.PAYMENTS)
            .select { order("paid_on", Order.DESCENDING) }
            .decodeList<Payment>()
    }

    override suspend fun isCurrentAccountSuspended(): Boolean = withContext(Dispatchers.IO) {
        client.postgrest.rpc(SupabaseModule.Rpc.ACCOUNT_IS_SUSPENDED, buildJsonObject { })
            .decodeAs<Boolean>()
    }
}

// ---------------------------------------------------------------------------
// Sample data (no Supabase configured)
// ---------------------------------------------------------------------------

/** The sample administrator. Sign in with any email starting with "admin". */
object SampleAdmin {
    val profile = Profile(
        id = "u-admin-001",
        email = "admin@tendertrack.gov.za",
        fullName = "L. Naidoo",
        role = UserRole.ADMINISTRATOR,
        department = null
    )
}

/**
 * Offline stand-in. Applies exactly the same rules as the database functions,
 * and writes to SampleData.auditTrail so the officer's audit views see admin
 * actions too.
 */
class SampleAdminRepository : AdminRepository {

    private val accounts = mutableListOf(
        account(SampleAdmin.profile, lastSignIn = "2026-09-22T07:55:00Z"),
        account(SampleData.currentUser, lastSignIn = "2026-09-22T08:10:00Z"),
        AdminAccount("u-fin-001", "n.dlamini@gauteng.gov.za", "N. Dlamini", UserRole.FINANCE_OFFICER,
            "Gauteng Dept of e-Government", createdAt = "2026-02-03T09:00:00Z", lastSignInAt = "2026-09-19T14:20:00Z"),
        AdminAccount("u-aud-001", "s.vanwyk@agsa.co.za", "S. van Wyk", UserRole.AUDITOR,
            createdAt = "2026-02-10T09:00:00Z", lastSignInAt = "2026-09-18T10:05:00Z"),
        AdminAccount("u-eval-001", "p.botha@gauteng.gov.za", "P. Botha", UserRole.EVALUATION_COMMITTEE,
            "Gauteng Dept of e-Government", createdAt = "2026-03-01T09:00:00Z"),
        AdminAccount("u-sup-001", "ops@infratech.co.za", "Infratech Solutions (Pty) Ltd", UserRole.SUPPLIER,
            createdAt = "2026-08-31T08:30:00Z", lastSignInAt = "2026-09-20T16:40:00Z"),
        AdminAccount("u-po-002", "m.khumalo@gauteng.gov.za", "M. Khumalo", UserRole.PROCUREMENT_OFFICER,
            "Gauteng Dept of e-Government", suspended = true, suspendedReason = "Transferred to another department",
            createdAt = "2026-01-15T09:00:00Z", lastSignInAt = "2026-07-30T11:00:00Z")
    )

    private val invitations = mutableListOf(
        StaffInvitation("i-001", "z.mthembu@gauteng.gov.za", "Z. Mthembu", UserRole.PROCUREMENT_OFFICER,
            "Gauteng Dept of e-Government", "L. Naidoo", "2026-09-20T10:00:00Z", "2026-09-20T10:00:00Z")
    )

    /** In sample mode the signed-in administrator is always the sample one. */
    private val me: String get() = SampleAdmin.profile.id

    override suspend fun accounts(): List<AdminAccount> =
        accounts.sortedWith(compareBy<AdminAccount>({ it.suspended }, { it.role }, { it.fullName }))

    override suspend fun setRole(userId: String, role: UserRole, department: String?): AdminAccount {
        val index = accounts.indexOfFirst { it.id == userId }
        require(index >= 0) { "That account no longer exists." }
        val target = accounts[index]
        val dept = department?.trim()?.ifBlank { null }
        require(role != UserRole.PUBLIC) { "Public is not an account role: the public use the app without signing in." }
        require(userId != me) { "You cannot change your own role. Ask another administrator." }
        require(!role.needsDepartment || dept != null) {
            "A ${role.displayName.lowercase()} must be assigned to a department."
        }
        require(!(target.role == UserRole.ADMINISTRATOR && role != UserRole.ADMINISTRATOR &&
            !target.suspended && activeAdmins() <= 1)) {
            "This is the only active administrator. Make someone else an administrator first."
        }
        val updated = target.copy(role = role, department = dept)
        accounts[index] = updated
        audit("account", userId, "Role changed",
            "${target.email}: ${target.role.apiValue} → ${role.apiValue}${dept?.let { " ($it)" } ?: ""}")
        return updated
    }

    override suspend fun setSuspended(userId: String, suspended: Boolean, reason: String?): AdminAccount {
        val index = accounts.indexOfFirst { it.id == userId }
        require(index >= 0) { "That account no longer exists." }
        val target = accounts[index]
        require(userId != me) { "You cannot suspend or reinstate your own account." }
        if (target.suspended == suspended) return target

        val updated = if (suspended) {
            val why = reason?.trim().orEmpty()
            require(why.length >= 5) { "Give a reason for the suspension (at least 5 characters)." }
            require(!(target.role == UserRole.ADMINISTRATOR && activeAdmins() <= 1)) {
                "This is the only active administrator and cannot be suspended."
            }
            audit("account", userId, "Account suspended", "${target.email}: $why")
            target.copy(suspended = true, suspendedReason = why)
        } else {
            audit("account", userId, "Account reinstated", target.email)
            target.copy(suspended = false, suspendedReason = null)
        }
        accounts[index] = updated
        return updated
    }

    override suspend fun invitations(): List<StaffInvitation> = invitations.sortedByDescending { it.createdAt }

    override suspend fun saveInvitation(draft: InvitationDraft): StaffInvitation {
        val email = draft.email.trim().lowercase()
        val name = draft.fullName.trim()
        val dept = draft.department?.trim()?.ifBlank { null }
        require(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email)) { "Enter a valid email address." }
        require(name.length >= 2) { "Enter the person's full name." }
        require(draft.role in invitableRoles) { "Invitations are for staff roles. Suppliers register themselves." }
        require(!draft.role.needsDepartment || dept != null) {
            "A ${draft.role.displayName.lowercase()} must be assigned to a department."
        }
        require(accounts.none { it.email.equals(email, true) }) {
            "An account with this email already exists. Change its role under User accounts instead."
        }

        val now = Format.nowIso()
        return if (draft.id == null) {
            require(invitations.none { it.email.equals(email, true) }) { "This email has already been invited." }
            val created = StaffInvitation("i-${System.currentTimeMillis()}", email, name, draft.role, dept,
                SampleAdmin.profile.fullName, now, now)
            invitations.add(0, created)
            audit("invitation", created.id, "Invitation created", "$email as ${draft.role.apiValue}")
            created
        } else {
            val index = invitations.indexOfFirst { it.id == draft.id }
            require(index >= 0) { "That invitation no longer exists." }
            require(!invitations[index].accepted) {
                "This invitation has been accepted. Change the role under User accounts instead."
            }
            require(invitations.none { it.id != draft.id && it.email.equals(email, true) }) {
                "Another invitation already uses this email."
            }
            val updated = invitations[index].copy(email = email, fullName = name, role = draft.role,
                department = dept, updatedAt = now)
            invitations[index] = updated
            audit("invitation", updated.id, "Invitation updated", "$email as ${draft.role.apiValue}")
            updated
        }
    }

    override suspend fun revokeInvitation(id: String) {
        val invite = invitations.firstOrNull { it.id == id } ?: error("That invitation no longer exists.")
        require(!invite.accepted) { "This invitation has been accepted. Suspend the account instead." }
        invitations.remove(invite)
        audit("invitation", id, "Invitation revoked", invite.email)
    }

    override suspend fun auditLog(): List<AuditEntry> = SampleData.auditTrail.sortedByDescending { it.createdAt }

    override suspend fun overview(): AdminOverview =
        buildAdminOverview(accounts(), invitations(), auditLog(), tendersForExport())

    override suspend fun tendersForExport(): List<Tender> = SampleData.tenders.toList()

    override suspend fun paymentsForExport(): List<Payment> = SampleData.payments.sortedByDescending { it.paidOn }

    override suspend fun isCurrentAccountSuspended(): Boolean {
        val email = ServiceLocator.authRepository.currentProfile()?.email ?: return false
        return accounts.any { it.email.equals(email, true) && it.suspended }
    }

    // -- helpers --------------------------------------------------------------

    private fun activeAdmins() = accounts.count { it.role == UserRole.ADMINISTRATOR && !it.suspended }

    private fun audit(entityType: String, entityId: String, action: String, detail: String) {
        SampleData.auditTrail.add(
            AuditEntry("a-admin-${System.nanoTime()}", entityType, entityId, action, detail,
                SampleAdmin.profile.fullName, Format.nowIso())
        )
    }

    private fun account(profile: Profile, lastSignIn: String?) = AdminAccount(
        id = profile.id, email = profile.email, fullName = profile.fullName, role = profile.role,
        department = profile.department, createdAt = "2026-01-10T09:00:00Z", lastSignInAt = lastSignIn
    )
}
