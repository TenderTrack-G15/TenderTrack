package za.ac.tendertrack.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Supplier module. Field names match supabase/supplier_module.sql.
 */

// ---------------------------------------------------------------------------
// The company
// ---------------------------------------------------------------------------

@Serializable
enum class DocumentStatus {
    @SerialName("not_submitted") NOT_SUBMITTED,
    @SerialName("submitted") SUBMITTED,
    @SerialName("verified") VERIFIED,
    @SerialName("expired") EXPIRED;

    val displayName: String get() = when (this) {
        NOT_SUBMITTED -> "Not submitted"
        SUBMITTED -> "Submitted"
        VERIFIED -> "Verified"
        EXPIRED -> "Expired"
    }
}

/** The supplier's own registration: company, contact, compliance and capabilities. */
@Serializable
data class SupplierProfile(
    val id: String,
    @SerialName("company_name") val companyName: String,
    @SerialName("registration_number") val registrationNumber: String,
    @SerialName("csd_number") val csdNumber: String,
    @SerialName("tax_number") val taxNumber: String = "",
    @SerialName("vat_number") val vatNumber: String = "",
    @SerialName("business_type") val businessType: String = "",
    @SerialName("year_established") val yearEstablished: Int? = null,
    @SerialName("contact_person") val contactPerson: String = "",
    @SerialName("job_title") val jobTitle: String = "",
    @SerialName("contact_email") val contactEmail: String = "",
    @SerialName("phone_number") val phoneNumber: String = "",
    @SerialName("mobile_number") val mobileNumber: String = "",
    @SerialName("physical_address") val physicalAddress: String = "",
    @SerialName("postal_address") val postalAddress: String = "",
    val province: String = "",
    @SerialName("tax_clearance_status") val taxClearanceStatus: String = "not_submitted",
    @SerialName("tax_clearance_expiry") val taxClearanceExpiry: String? = null,
    @SerialName("bbbee_level") val bbbeeLevel: Int? = null,
    @SerialName("bbbee_expiry") val bbbeeExpiry: String? = null,
    @SerialName("industry_licences") val industryLicences: String = "",
    @SerialName("professional_registrations") val professionalRegistrations: String = "",
    val categories: String = "",
    val industry: String = "",
    val expertise: String = "",
    @SerialName("geographic_areas") val geographicAreas: String = "",
    @SerialName("company_profile") val companyProfile: String = "",
    val employees: Int? = null,
    @SerialName("documents_received") val documentsReceived: Int = 0,
    @SerialName("documents_required") val documentsRequired: Int = 6,
    val status: SupplierVerificationStatus,
    @SerialName("decision_reason") val decisionReason: String? = null,
    @SerialName("submitted_at") val submittedAt: String = ""
) {
    val reference: String get() = "SUP-" + id.replace("-", "").take(8).uppercase()
}

/** One supporting document and where it stands. */
@Serializable
data class SupplierDocument(
    val id: String,
    val name: String,
    val status: DocumentStatus,
    val reference: String = "",
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("file_url") val fileUrl: String? = null,
    @SerialName("updated_at") val updatedAt: String = ""
)

/** Banking details. Only the supplier and a finance officer may read these. */
@Serializable
data class BankingDetails(
    @SerialName("bank_name") val bankName: String,
    @SerialName("account_name") val accountName: String,
    @SerialName("account_number") val accountNumber: String,
    @SerialName("branch_code") val branchCode: String,
    @SerialName("proof_url") val proofUrl: String? = null,
    @SerialName("updated_at") val updatedAt: String = ""
) {
    /** Never show a full account number on screen unless the supplier asks. */
    val maskedAccountNumber: String
        get() = if (accountNumber.length <= 4) accountNumber else "•••• " + accountNumber.takeLast(4)
}

// ---------------------------------------------------------------------------
// The tender, as a bidder sees it
// ---------------------------------------------------------------------------

/** A tender on the board. The estimate stays hidden until the award. */
@Serializable
data class TenderBoardItem(
    val id: String,
    @SerialName("reference_number") val referenceNumber: String,
    val title: String,
    val description: String = "",
    val department: String,
    val category: String = "",
    val status: TenderStatus,
    @SerialName("estimated_budget") val estimatedBudget: Double? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("closing_date") val closingDate: String,
    @SerialName("contract_period_months") val contractPeriodMonths: Int = 12,
    @SerialName("awarded_supplier_name") val awardedSupplierName: String? = null,
    @SerialName("awarded_value") val awardedValue: Double? = null,
    @SerialName("awarded_at") val awardedAt: String? = null
) {
    val openForBids: Boolean get() = status == TenderStatus.PUBLISHED
}

@Serializable
data class TenderDetails(
    @SerialName("tender_id") val tenderId: String,
    @SerialName("scope_overview") val scopeOverview: String = "",
    val deliverables: String = "",
    @SerialName("technical_specs") val technicalSpecs: String = "",
    @SerialName("quantity_requirements") val quantityRequirements: String = "",
    @SerialName("expected_outcomes") val expectedOutcomes: String = "",
    @SerialName("briefing_at") val briefingAt: String? = null,
    @SerialName("briefing_venue") val briefingVenue: String = "",
    @SerialName("briefing_compulsory") val briefingCompulsory: Boolean = false,
    @SerialName("site_visit_at") val siteVisitAt: String? = null,
    @SerialName("expected_award_date") val expectedAwardDate: String? = null,
    @SerialName("submission_method") val submissionMethod: String = "",
    @SerialName("submission_format") val submissionFormat: String = "",
    @SerialName("required_documents") val requiredDocuments: String = "",
    @SerialName("technical_weight") val technicalWeight: Int? = null,
    @SerialName("financial_weight") val financialWeight: Int? = null,
    @SerialName("preference_points") val preferencePoints: String = "",
    @SerialName("query_contact_name") val queryContactName: String = "",
    @SerialName("query_contact_email") val queryContactEmail: String = "",
    @SerialName("query_contact_phone") val queryContactPhone: String = ""
)

@Serializable
data class TenderEligibility(
    val id: String,
    val requirement: String,
    val mandatory: Boolean = true,
    val sequence: Int = 1
)

@Serializable
data class TenderDocument(
    val id: String,
    val name: String,
    @SerialName("doc_type") val docType: String = "",
    @SerialName("file_url") val fileUrl: String? = null
)

@Serializable
enum class TenderUpdateKind {
    @SerialName("clarification") CLARIFICATION,
    @SerialName("question") QUESTION,
    @SerialName("amendment") AMENDMENT,
    @SerialName("extension") EXTENSION;

    val displayName: String get() = when (this) {
        CLARIFICATION -> "Clarification"
        QUESTION -> "Question and answer"
        AMENDMENT -> "Amendment"
        EXTENSION -> "Deadline extension"
    }
}

@Serializable
data class TenderUpdate(
    val id: String,
    val kind: TenderUpdateKind,
    val title: String,
    val body: String = "",
    @SerialName("published_at") val publishedAt: String
)

/** Everything a bidder needs about one tender. */
data class TenderPack(
    val tender: TenderBoardItem,
    val details: TenderDetails?,
    val eligibility: List<TenderEligibility>,
    val documents: List<TenderDocument>,
    val updates: List<TenderUpdate>,
    val criteria: List<EvaluationCriterion>
)

// ---------------------------------------------------------------------------
// Forms
// ---------------------------------------------------------------------------

enum class ProfileField {
    COMPANY_NAME, REGISTRATION_NUMBER, TAX_NUMBER, VAT_NUMBER, BUSINESS_TYPE, YEAR_ESTABLISHED,
    CONTACT_PERSON, JOB_TITLE, CONTACT_EMAIL, PHONE, MOBILE, PHYSICAL_ADDRESS, POSTAL_ADDRESS,
    PROVINCE, LICENCES, REGISTRATIONS, CATEGORIES, INDUSTRY, EXPERTISE, AREAS, PROFILE, EMPLOYEES
}

data class ProfileForm(
    val companyName: String = "",
    val registrationNumber: String = "",
    val taxNumber: String = "",
    val vatNumber: String = "",
    val businessType: String? = null,
    val yearEstablished: String = "",
    val contactPerson: String = "",
    val jobTitle: String = "",
    val contactEmail: String = "",
    val phoneNumber: String = "",
    val mobileNumber: String = "",
    val physicalAddress: String = "",
    val postalAddress: String = "",
    val province: String? = null,
    val industryLicences: String = "",
    val professionalRegistrations: String = "",
    val categories: String = "",
    val industry: String = "",
    val expertise: String = "",
    val geographicAreas: String = "",
    val companyProfile: String = "",
    val employees: String = ""
) {
    /** The same rules as save_supplier_profile() in the database. */
    fun errors(): Map<ProfileField, String> = buildMap {
        if (companyName.trim().length < 2) put(ProfileField.COMPANY_NAME, "Enter the company name.")
        SupplierChecks.registrationNumber(registrationNumber)?.let { put(ProfileField.REGISTRATION_NUMBER, it) }
        SupplierChecks.email(contactEmail)?.let { put(ProfileField.CONTACT_EMAIL, it) }
        SupplierChecks.mobile(mobileNumber)?.let { put(ProfileField.MOBILE, it) }
        if (businessType == null) put(ProfileField.BUSINESS_TYPE, "Choose a business type.")
        if (province == null) put(ProfileField.PROVINCE, "Choose a province.")
        SupplierChecks.year(yearEstablished)?.let { put(ProfileField.YEAR_ESTABLISHED, it) }
        SupplierChecks.count(employees)?.let { put(ProfileField.EMPLOYEES, it) }
    }

    companion object {
        fun from(p: SupplierProfile) = ProfileForm(
            companyName = p.companyName, registrationNumber = p.registrationNumber, taxNumber = p.taxNumber,
            vatNumber = p.vatNumber, businessType = p.businessType.ifBlank { null },
            yearEstablished = p.yearEstablished?.toString().orEmpty(), contactPerson = p.contactPerson,
            jobTitle = p.jobTitle, contactEmail = p.contactEmail, phoneNumber = p.phoneNumber,
            mobileNumber = p.mobileNumber, physicalAddress = p.physicalAddress, postalAddress = p.postalAddress,
            province = p.province.ifBlank { null }, industryLicences = p.industryLicences,
            professionalRegistrations = p.professionalRegistrations, categories = p.categories,
            industry = p.industry, expertise = p.expertise, geographicAreas = p.geographicAreas,
            companyProfile = p.companyProfile, employees = p.employees?.toString().orEmpty()
        )
    }
}

enum class BankingField { BANK, ACCOUNT_NAME, ACCOUNT_NUMBER, BRANCH_CODE }

data class BankingForm(
    val bankName: String = "",
    val accountName: String = "",
    val accountNumber: String = "",
    val branchCode: String = "",
    val proofUrl: String = ""
) {
    fun errors(): Map<BankingField, String> = buildMap {
        if (bankName.trim().length < 2) put(BankingField.BANK, "Enter the bank name.")
        if (accountName.trim().length < 2) put(BankingField.ACCOUNT_NAME, "Enter the name the account is held in.")
        if (!Regex("^\\d{6,17}$").matches(accountNumber.trim()))
            put(BankingField.ACCOUNT_NUMBER, "Account number must be 6 to 17 digits.")
        if (!Regex("^\\d{6}$").matches(branchCode.trim()))
            put(BankingField.BRANCH_CODE, "Branch code must be 6 digits.")
    }

    companion object {
        fun from(b: BankingDetails) = BankingForm(b.bankName, b.accountName, b.accountNumber, b.branchCode,
            b.proofUrl.orEmpty())
    }
}

/** Shared rules, kept the same as the database's. */
object SupplierChecks {
    val businessTypes = listOf(
        "Private company (Pty) Ltd", "Public company (Ltd)", "Close corporation (CC)",
        "Sole proprietor", "Partnership", "Non-profit organisation (NPO/NPC)", "Co-operative", "Trust"
    )

    val provinces = listOf(
        "Eastern Cape", "Free State", "Gauteng", "KwaZulu-Natal", "Limpopo",
        "Mpumalanga", "Northern Cape", "North West", "Western Cape"
    )

    fun registrationNumber(v: String): String? =
        if (!Regex("^\\d{4}/\\d{6}/\\d{2}$").matches(v.trim())) "Use the CIPC format, e.g. 2019/451236/07." else null

    fun csdNumber(v: String): String? =
        if (!Regex("^MAAA\\d{7}$", RegexOption.IGNORE_CASE).matches(v.trim())) "Use the CSD format, e.g. MAAA0451236." else null

    fun email(v: String): String? =
        if (!Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(v.trim())) "Enter a valid email address." else null

    fun mobile(v: String): String? =
        if (!Regex("^(\\+27|0)[6-8]\\d{8}$").matches(v.filterNot { it == ' ' || it == '-' }))
            "Enter a South African mobile number, e.g. 082 123 4567." else null

    fun year(v: String): String? {
        if (v.isBlank()) return null
        val year = v.trim().toIntOrNull() ?: return "Enter a year, e.g. 2015."
        return if (year < 1800 || year > 2100) "Check the year the company was established." else null
    }

    fun count(v: String): String? {
        if (v.isBlank()) return null
        val n = v.trim().toIntOrNull() ?: return "Enter a number."
        return if (n < 0) "Cannot be negative." else null
    }
}
