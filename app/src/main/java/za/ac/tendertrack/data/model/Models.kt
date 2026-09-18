package za.ac.tendertrack.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// Roles
// ---------------------------------------------------------------------------

/**
 * The seven roles in the system.
 *
 * A role is never chosen in the UI. It is stored against the user's profile,
 * carried as a claim on the Supabase JWT and enforced by row-level security
 * policies in Postgres. The app reads it only to decide what to show.
 */
@Serializable
enum class UserRole {
    @SerialName("public") PUBLIC,
    @SerialName("supplier") SUPPLIER,
    @SerialName("procurement_officer") PROCUREMENT_OFFICER,
    @SerialName("evaluation_committee") EVALUATION_COMMITTEE,
    @SerialName("finance_officer") FINANCE_OFFICER,
    @SerialName("auditor") AUDITOR,
    @SerialName("administrator") ADMINISTRATOR;

    val displayName: String
        get() = when (this) {
            PUBLIC -> "Public"
            SUPPLIER -> "Supplier"
            PROCUREMENT_OFFICER -> "Procurement Officer"
            EVALUATION_COMMITTEE -> "Evaluation Committee"
            FINANCE_OFFICER -> "Finance Officer"
            AUDITOR -> "Auditor"
            ADMINISTRATOR -> "Administrator"
        }
}

@Serializable
data class Profile(
    val id: String,
    val email: String,
    @SerialName("full_name") val fullName: String,
    val role: UserRole,
    val department: String? = null
) {
    val initials: String
        get() = fullName.split(" ")
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
}

// ---------------------------------------------------------------------------
// Tender lifecycle — FR2
// ---------------------------------------------------------------------------

/**
 * The one and only tender status vocabulary.
 *
 * Supplier registrations have their own separate states in
 * [SupplierVerificationStatus]; the two are deliberately never mixed.
 */
@Serializable
enum class TenderStatus {
    @SerialName("registered") REGISTERED,
    @SerialName("published") PUBLISHED,
    @SerialName("under_evaluation") UNDER_EVALUATION,
    @SerialName("awarded") AWARDED,
    @SerialName("in_progress") IN_PROGRESS,
    @SerialName("completed") COMPLETED;

    val displayName: String
        get() = when (this) {
            REGISTERED -> "Registered"
            PUBLISHED -> "Open for bids"
            UNDER_EVALUATION -> "Under evaluation"
            AWARDED -> "Awarded"
            IN_PROGRESS -> "In progress"
            COMPLETED -> "Completed"
        }

    /**
     * The only status a tender may move to next. Returning a single value (or
     * none) is what stops a tender skipping evaluation and jumping to awarded.
     */
    fun next(): TenderStatus? = when (this) {
        REGISTERED -> PUBLISHED
        PUBLISHED -> UNDER_EVALUATION
        UNDER_EVALUATION -> AWARDED
        AWARDED -> IN_PROGRESS
        IN_PROGRESS -> COMPLETED
        COMPLETED -> null
    }

    companion object {
        /** Ordered for the stepper. */
        val lifecycle = listOf(REGISTERED, PUBLISHED, UNDER_EVALUATION, AWARDED, IN_PROGRESS, COMPLETED)
    }
}

@Serializable
data class Tender(
    val id: String,
    @SerialName("reference_number") val referenceNumber: String,
    val title: String,
    val description: String = "",
    val department: String,
    val category: String,
    @SerialName("estimated_budget") val estimatedBudget: Double,
    @SerialName("closing_date") val closingDate: String,          // ISO-8601, e.g. 2026-09-18T11:00:00Z
    @SerialName("contract_period_months") val contractPeriodMonths: Int = 12,
    val status: TenderStatus,
    @SerialName("awarded_supplier_id") val awardedSupplierId: String? = null,
    @SerialName("awarded_supplier_name") val awardedSupplierName: String? = null,
    @SerialName("awarded_value") val awardedValue: Double? = null,
    @SerialName("awarded_at") val awardedAt: String? = null,
    @SerialName("paid_to_date") val paidToDate: Double = 0.0,
    @SerialName("open_flag_count") val openFlagCount: Int = 0,
    @SerialName("created_at") val createdAt: String? = null
) {
    /** Fraction of the awarded value that has actually been disbursed — FR11. */
    val utilisation: Float
        get() {
            val awarded = awardedValue ?: return 0f
            if (awarded <= 0.0) return 0f
            return (paidToDate / awarded).toFloat().coerceIn(0f, 1f)
        }

    val remainingValue: Double
        get() = (awardedValue ?: 0.0) - paidToDate
}

/** What the Register / Edit Tender form submits. */
@Serializable
data class TenderDraft(
    @SerialName("reference_number") val referenceNumber: String,
    val title: String,
    val description: String,
    val department: String,
    val category: String,
    @SerialName("estimated_budget") val estimatedBudget: Double,
    @SerialName("closing_date") val closingDate: String,
    @SerialName("contract_period_months") val contractPeriodMonths: Int,
    val status: TenderStatus = TenderStatus.REGISTERED
)

// ---------------------------------------------------------------------------
// Supplier registrations — separate vocabulary from the tender lifecycle
// ---------------------------------------------------------------------------

@Serializable
enum class SupplierVerificationStatus {
    @SerialName("awaiting_verification") AWAITING_VERIFICATION,
    @SerialName("verified") VERIFIED,
    @SerialName("not_approved") NOT_APPROVED;

    val displayName: String
        get() = when (this) {
            AWAITING_VERIFICATION -> "Awaiting verification"
            VERIFIED -> "Verified"
            NOT_APPROVED -> "Not approved"
        }
}

@Serializable
data class Supplier(
    val id: String,
    @SerialName("company_name") val companyName: String,
    @SerialName("registration_number") val registrationNumber: String,
    @SerialName("csd_number") val csdNumber: String,
    @SerialName("tax_clearance_expiry") val taxClearanceExpiry: String? = null,
    @SerialName("bbbee_level") val bbbeeLevel: Int? = null,
    @SerialName("business_type") val businessType: String = "",
    @SerialName("contact_email") val contactEmail: String = "",
    @SerialName("physical_address") val physicalAddress: String = "",
    @SerialName("documents_received") val documentsReceived: Int = 0,
    @SerialName("documents_required") val documentsRequired: Int = 4,
    val status: SupplierVerificationStatus,
    @SerialName("submitted_at") val submittedAt: String,
    @SerialName("decision_reason") val decisionReason: String? = null
) {
    val documentsComplete: Boolean get() = documentsReceived >= documentsRequired
}

// ---------------------------------------------------------------------------
// Payments — FR10, FR12
// ---------------------------------------------------------------------------

@Serializable
data class Payment(
    val id: String,
    @SerialName("tender_id") val tenderId: String,
    @SerialName("tender_reference") val tenderReference: String = "",
    val milestone: String,
    val amount: Double,
    @SerialName("paid_on") val paidOn: String,
    @SerialName("invoice_number") val invoiceNumber: String,
    @SerialName("recorded_by") val recordedBy: String = ""
)

@Serializable
data class PaymentDraft(
    @SerialName("tender_id") val tenderId: String,
    val milestone: String,
    val amount: Double,
    @SerialName("paid_on") val paidOn: String,
    @SerialName("invoice_number") val invoiceNumber: String
)

/** Financial-year totals behind the Fund Utilisation screen — FR11. */
@Serializable
data class FundSummary(
    @SerialName("financial_year") val financialYear: String,
    val allocated: Double,
    val committed: Double,
    val disbursed: Double
) {
    val uncommitted: Double get() = (allocated - committed).coerceAtLeast(0.0)
    val disbursedFraction: Float get() = if (allocated <= 0) 0f else (disbursed / allocated).toFloat()
    val committedFraction: Float get() = if (allocated <= 0) 0f else ((committed - disbursed) / allocated).toFloat()
    val uncommittedFraction: Float get() = if (allocated <= 0) 0f else (uncommitted / allocated).toFloat()
}

// ---------------------------------------------------------------------------
// Compliance flags — FR6 to FR8
// ---------------------------------------------------------------------------

@Serializable
enum class FlagSeverity {
    @SerialName("high") HIGH,
    @SerialName("medium") MEDIUM,
    @SerialName("low") LOW;

    val displayName: String get() = name.lowercase().replaceFirstChar { it.uppercase() }
}

@Serializable
enum class FlagStatus {
    @SerialName("open") OPEN,
    @SerialName("under_investigation") UNDER_INVESTIGATION,
    @SerialName("resolved") RESOLVED;

    val displayName: String
        get() = when (this) {
            OPEN -> "Open"
            UNDER_INVESTIGATION -> "Under investigation"
            RESOLVED -> "Resolved"
        }
}

@Serializable
enum class FlagOutcome {
    @SerialName("cleared") CLEARED,
    @SerialName("corrective_action") CORRECTIVE_ACTION,
    @SerialName("escalated") ESCALATED;

    val displayName: String
        get() = when (this) {
            CLEARED -> "Cleared"
            CORRECTIVE_ACTION -> "Corrective action required"
            ESCALATED -> "Escalated to Treasury"
        }
}

@Serializable
data class ComplianceFlag(
    val id: String,
    val reference: String,
    @SerialName("tender_id") val tenderId: String,
    @SerialName("tender_reference") val tenderReference: String,
    @SerialName("tender_title") val tenderTitle: String,
    val title: String,
    val description: String,
    @SerialName("rule_triggered") val ruleTriggered: String,
    val severity: FlagSeverity,
    val status: FlagStatus,
    @SerialName("raised_at") val raisedAt: String,
    @SerialName("raised_automatically") val raisedAutomatically: Boolean = true,
    @SerialName("assigned_to") val assignedTo: String? = null,
    val notes: List<FlagNote> = emptyList()
)

@Serializable
data class FlagNote(
    val id: String,
    val author: String,
    val text: String,
    @SerialName("created_at") val createdAt: String
)

// ---------------------------------------------------------------------------
// Audit trail — FR5
// ---------------------------------------------------------------------------

@Serializable
data class AuditEntry(
    val id: String,
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_id") val entityId: String,
    val action: String,
    val detail: String,
    val actor: String,
    @SerialName("created_at") val createdAt: String
)

// ---------------------------------------------------------------------------
// Notifications — FR15
// ---------------------------------------------------------------------------

@Serializable
enum class NotificationKind {
    @SerialName("flag") FLAG,
    @SerialName("deadline") DEADLINE,
    @SerialName("award_code") AWARD_CODE,
    @SerialName("registration") REGISTRATION,
    @SerialName("payment") PAYMENT
}

@Serializable
data class AppNotification(
    val id: String,
    val kind: NotificationKind,
    val title: String,
    val body: String,
    @SerialName("created_at") val createdAt: String,
    val read: Boolean = false
)

// ---------------------------------------------------------------------------
// Dashboard rollup
// ---------------------------------------------------------------------------

data class DashboardSummary(
    val countsByStatus: Map<TenderStatus, Int>,
    val funds: FundSummary,
    val openFlags: Int,
    val registrationsToVerify: Int,
    val unreadNotifications: Int,
    val closingWithin30Days: Int,
    val overdueEvaluations: Int,
    val unclaimedAwardCodes: Int,
    val oldestRegistrationDays: Int
) {
    fun count(status: TenderStatus): Int = countsByStatus[status] ?: 0
}

// ---------------------------------------------------------------------------
// Reports — FR17
// ---------------------------------------------------------------------------

enum class ReportType(val label: String) {
    LIFECYCLE_SUMMARY("Tender lifecycle summary"),
    AWARD_REGISTER("Award register"),
    PAYMENTS("Payments and fund utilisation"),
    SUPPLIER_REGISTER("Supplier register"),
    COMPLIANCE_FLAGS("Compliance flags"),
    AUDIT_TRAIL("Audit trail")
}

enum class ReportFormat(val label: String, val extension: String, val mimeType: String) {
    PDF("PDF", "pdf", "application/pdf"),
    EXCEL("Excel", "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    CSV("CSV", "csv", "text/csv")
}

data class GeneratedReport(
    val fileName: String,
    val rowCount: Int,
    val content: String
)
