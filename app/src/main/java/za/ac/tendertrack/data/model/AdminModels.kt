package za.ac.tendertrack.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Models used by the Administrator screens (Deliverable 3, section 5.7).
 * Field names match supabase/admin_access.sql exactly.
 */

/** One account as the User Accounts screen sees it. */
@Serializable
data class AdminAccount(
    val id: String,
    val email: String,
    @SerialName("full_name") val fullName: String,
    val role: UserRole,
    val department: String? = null,
    val suspended: Boolean = false,
    @SerialName("suspended_reason") val suspendedReason: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    /** From Supabase's own auth.users table; null if the person has never signed in. */
    @SerialName("last_sign_in_at") val lastSignInAt: String? = null
)

/** A pending (or accepted) invitation for a staff member. */
@Serializable
data class StaffInvitation(
    val id: String,
    val email: String,
    @SerialName("full_name") val fullName: String,
    val role: UserRole,
    val department: String? = null,
    @SerialName("invited_by") val invitedBy: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("accepted_at") val acceptedAt: String? = null
) {
    val accepted: Boolean get() = acceptedAt != null
}

/** What the invitation form sends. id is null for a new invitation. */
data class InvitationDraft(
    val id: String?,
    val email: String,
    val fullName: String,
    val role: UserRole,
    val department: String?
)

/** Everything on the Admin Dashboard. */
data class AdminOverview(
    val accounts: List<AdminAccount>,
    val pendingInvitations: Int,
    val eventsToday: Int,
    val eventsThisWeek: Int,
    val recentActivity: List<AuditEntry>,
    val totalTenders: Int,
    val openFlags: Int,
    /** Awarded tenders whose award differs from the estimate by more than 10%. */
    val awardVarianceCount: Int
) {
    val activeCount: Int get() = accounts.count { !it.suspended }
    val suspendedCount: Int get() = accounts.count { it.suspended }
    val activeAdministrators: Int get() = accounts.count { it.role == UserRole.ADMINISTRATOR && !it.suspended }
    val rolesInUse: Int get() = accounts.filter { !it.suspended }.map { it.role }.distinct().size
}

// ---------------------------------------------------------------------------
// Role rules shared by the screens and the sample repository. They mirror the
// checks in admin_access.sql so the app can explain a problem before sending.
// ---------------------------------------------------------------------------

/** "procurement_officer" — the value the database functions expect. */
val UserRole.apiValue: String get() = name.lowercase()

/** Roles an administrator may give an existing account. Public is not an account. */
val assignableRoles: List<UserRole> get() = UserRole.entries.filter { it != UserRole.PUBLIC }

/** Roles that can be invited. Suppliers register themselves. */
val invitableRoles: List<UserRole>
    get() = UserRole.entries.filter { it != UserRole.PUBLIC && it != UserRole.SUPPLIER }

/** Roles that act for one department and therefore must have one. */
val UserRole.needsDepartment: Boolean
    get() = this == UserRole.PROCUREMENT_OFFICER ||
        this == UserRole.FINANCE_OFFICER ||
        this == UserRole.EVALUATION_COMMITTEE
