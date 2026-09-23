package za.ac.tendertrack.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Supplier sign-up, supplier registration and password reset (Deliverable 3,
 * sections 5.1 and 5.3). Field names match supabase/supplier_accounts.sql.
 */

/** The company profile a supplier submits (D3 5.3 "Company Profile"), already cleaned up. */
data class CompanyProfile(
    val companyName: String,
    val registrationNumber: String,
    val csdNumber: String,
    val taxPin: String,
    /** null = not rated / non-compliant. */
    val bbbeeLevel: Int?,
    val businessType: String,
    val province: String,
    val representative: String,
    val mobileNumber: String
)

/** What happened after sign-up. */
sealed interface SignUpResult {
    /** "Confirm email" is off in Supabase, so the supplier is signed straight in. */
    data class SignedIn(val profile: Profile) : SignUpResult
    /** "Confirm email" is on: the supplier must confirm, then sign in. */
    data object ConfirmEmail : SignUpResult
}

/** A supplier's own registration, as their home screen shows it. */
@Serializable
data class SupplierRegistration(
    val id: String,
    @SerialName("company_name") val companyName: String,
    @SerialName("registration_number") val registrationNumber: String,
    @SerialName("csd_number") val csdNumber: String,
    @SerialName("tax_pin") val taxPin: String = "",
    @SerialName("bbbee_level") val bbbeeLevel: Int? = null,
    @SerialName("business_type") val businessType: String = "",
    val province: String = "",
    val representative: String = "",
    @SerialName("mobile_number") val mobileNumber: String = "",
    @SerialName("contact_email") val contactEmail: String = "",
    @SerialName("documents_received") val documentsReceived: Int = 0,
    @SerialName("documents_required") val documentsRequired: Int = 4,
    val status: SupplierVerificationStatus,
    @SerialName("submitted_at") val submittedAt: String,
    @SerialName("decision_reason") val decisionReason: String? = null
) {
    /** Short reference to quote to an officer, e.g. SUP-3F2A91C0. */
    val reference: String get() = "SUP-" + id.replace("-", "").take(8).uppercase()
}

// ---------------------------------------------------------------------------
// The editable form, shared by sign-up (step 2) and "correct and resubmit"
// ---------------------------------------------------------------------------

enum class CompanyField {
    COMPANY_NAME, REGISTRATION_NUMBER, CSD_NUMBER, TAX_PIN, BUSINESS_TYPE, PROVINCE, REPRESENTATIVE, MOBILE
}

data class CompanyForm(
    val companyName: String = "",
    val registrationNumber: String = "",
    val csdNumber: String = "",
    val taxPin: String = "",
    val bbbeeLevel: Int? = null,
    val businessType: String? = null,
    val province: String? = null,
    val representative: String = "",
    val mobileNumber: String = ""
) {
    /** Field-by-field problems; empty when the form is valid. */
    fun errors(includeMobile: Boolean): Map<CompanyField, String> = buildMap {
        SupplierRules.companyName(companyName)?.let { put(CompanyField.COMPANY_NAME, it) }
        SupplierRules.registrationNumber(registrationNumber)?.let { put(CompanyField.REGISTRATION_NUMBER, it) }
        SupplierRules.csdNumber(csdNumber)?.let { put(CompanyField.CSD_NUMBER, it) }
        SupplierRules.taxPin(taxPin)?.let { put(CompanyField.TAX_PIN, it) }
        if (businessType == null) put(CompanyField.BUSINESS_TYPE, "Choose a business type.")
        if (province == null) put(CompanyField.PROVINCE, "Choose a province.")
        SupplierRules.representative(representative)?.let { put(CompanyField.REPRESENTATIVE, it) }
        if (includeMobile) SupplierRules.mobile(mobileNumber)?.let { put(CompanyField.MOBILE, it) }
    }

    /** Cleaned-up values, exactly as the database stores them. Call only when valid. */
    fun toProfile(): CompanyProfile = CompanyProfile(
        companyName = companyName.trim(),
        registrationNumber = registrationNumber.trim(),
        csdNumber = csdNumber.trim().uppercase(),
        taxPin = taxPin.trim().uppercase(),
        bbbeeLevel = bbbeeLevel,
        businessType = businessType.orEmpty(),
        province = province.orEmpty(),
        representative = representative.trim(),
        mobileNumber = SupplierRules.cleanMobile(mobileNumber)
    )

    companion object {
        fun from(r: SupplierRegistration) = CompanyForm(
            companyName = r.companyName,
            registrationNumber = r.registrationNumber,
            csdNumber = r.csdNumber,
            taxPin = r.taxPin,
            bbbeeLevel = r.bbbeeLevel,
            businessType = r.businessType.ifBlank { null },
            province = r.province.ifBlank { null },
            representative = r.representative,
            mobileNumber = r.mobileNumber
        )
    }
}

/**
 * The rules for a supplier's details. Identical to validate_supplier_profile()
 * in supplier_accounts.sql, so the form catches problems before sending and
 * the database refuses anything that gets past it.
 */
object SupplierRules {
    val provinces = listOf(
        "Eastern Cape", "Free State", "Gauteng", "KwaZulu-Natal", "Limpopo",
        "Mpumalanga", "Northern Cape", "North West", "Western Cape"
    )

    val businessTypes = listOf(
        "Private company (Pty) Ltd", "Public company (Ltd)", "Close corporation (CC)",
        "Sole proprietor", "Partnership", "Non-profit company (NPC)", "Co-operative"
    )

    /** null first = "Not rated / non-compliant". */
    val bbbeeLevels: List<Int?> = listOf<Int?>(null) + (1..8).toList()

    fun bbbeeLabel(level: Int?): String = if (level == null) "Not rated / non-compliant" else "Level $level"

    private val registrationPattern = Regex("^\\d{4}/\\d{6}/\\d{2}$")
    private val csdPattern = Regex("^MAAA\\d{7}$", RegexOption.IGNORE_CASE)
    private val taxPinPattern = Regex("^[A-Za-z0-9]{6,20}$")
    private val mobilePattern = Regex("^(\\+27|0)[6-8]\\d{8}$")

    /** "082 123 4567" → "0821234567". */
    fun cleanMobile(value: String): String = value.filterNot { it == ' ' || it == '-' }

    fun companyName(v: String): String? =
        if (v.trim().length < 2) "Enter the company name." else null

    fun registrationNumber(v: String): String? =
        if (!registrationPattern.matches(v.trim())) "Use the CIPC format, e.g. 2019/451236/07." else null

    fun csdNumber(v: String): String? =
        if (!csdPattern.matches(v.trim())) "Use the CSD format, e.g. MAAA0451236." else null

    fun taxPin(v: String): String? =
        if (!taxPinPattern.matches(v.trim())) "Enter the 6–20 character PIN from SARS eFiling." else null

    fun representative(v: String): String? =
        if (v.trim().length < 2) "Enter the authorised representative's full name." else null

    fun mobile(v: String): String? =
        if (!mobilePattern.matches(cleanMobile(v))) "Enter a South African mobile number, e.g. 082 123 4567." else null
}
