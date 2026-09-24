package za.ac.tendertrack.data.repo

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import za.ac.tendertrack.core.Format
import za.ac.tendertrack.data.SupabaseModule
import za.ac.tendertrack.data.model.*
import za.ac.tendertrack.data.sample.SampleData

/**
 * Everything a supplier can see and do (Deliverable 3, section 5.3).
 *
 * Not to be confused with SupplierRepository, which is the procurement
 * officer's side: listing, approving and rejecting supplier registrations.
 *
 * A supplier maintains its own company profile, banking details and supporting
 * documents, and browses published tenders. It cannot verify itself, change
 * its CSD number or edit anything belonging to a tender: those are refused by
 * the database (supplier_module.sql).
 */
interface SupplierPortalRepository {
    /** Published tenders a bidder may see. */
    suspend fun board(): List<TenderBoardItem>

    /** Everything about one tender: scope, dates, eligibility, documents, updates. */
    suspend fun pack(tenderId: String): TenderPack

    suspend fun myProfile(): SupplierProfile?
    suspend fun documents(): List<SupplierDocument>
    suspend fun banking(): BankingDetails?

    suspend fun saveProfile(form: ProfileForm): SupplierProfile
    suspend fun saveBanking(form: BankingForm)
    suspend fun saveDocument(name: String, reference: String, expiresAt: String?, fileUrl: String?): SupplierDocument
    /** Sends a corrected registration back for review after a rejection. */
    suspend fun resubmitRegistration(): SupplierProfile

    /** Used by the sign-up form before an account exists. */
    suspend fun registrationProblem(registrationNumber: String, csdNumber: String): String?
}

// ---------------------------------------------------------------------------
// Supabase
// ---------------------------------------------------------------------------

class SupabaseSupplierPortalRepository(private val client: SupabaseClient) : SupplierPortalRepository {

    override suspend fun board(): List<TenderBoardItem> = withContext(Dispatchers.IO) {
        client.from(SupabaseModule.Table.TENDER_BOARD)
            .select { order("closing_date", Order.ASCENDING) }
            .decodeList()
    }

    override suspend fun pack(tenderId: String): TenderPack = withContext(Dispatchers.IO) {
        val tender = client.from(SupabaseModule.Table.TENDER_BOARD)
            .select {
                filter { eq("id", tenderId) }
                limit(1)
            }
            .decodeSingleOrNull<TenderBoardItem>() ?: error("That tender is no longer published.")

        TenderPack(
            tender = tender,
            details = client.from(SupabaseModule.Table.TENDER_DETAILS)
                .select {
                    filter { eq("tender_id", tenderId) }
                    limit(1)
                }
                .decodeSingleOrNull(),
            eligibility = client.from(SupabaseModule.Table.TENDER_ELIGIBILITY)
                .select {
                    filter { eq("tender_id", tenderId) }
                    order("sequence", Order.ASCENDING)
                }
                .decodeList(),
            documents = client.from(SupabaseModule.Table.TENDER_DOCUMENTS)
                .select { filter { eq("tender_id", tenderId) } }
                .decodeList(),
            updates = client.from(SupabaseModule.Table.TENDER_UPDATES)
                .select {
                    filter { eq("tender_id", tenderId) }
                    order("published_at", Order.DESCENDING)
                }
                .decodeList(),
            criteria = client.from(SupabaseModule.Table.EVALUATION_CRITERIA)
                .select {
                    filter { eq("tender_id", tenderId) }
                    order("sequence", Order.ASCENDING)
                }
                .decodeList()
        )
    }

    override suspend fun myProfile(): SupplierProfile? = withContext(Dispatchers.IO) {
        val userId = client.auth.currentUserOrNull()?.id ?: return@withContext null
        client.from(SupabaseModule.Table.SUPPLIERS)
            .select { filter { eq("owner_id", userId) } }
            .decodeList<SupplierProfile>()
            .firstOrNull()
    }

    override suspend fun documents(): List<SupplierDocument> = withContext(Dispatchers.IO) {
        val supplier = myProfile() ?: return@withContext emptyList()
        client.from(SupabaseModule.Table.SUPPLIER_DOCUMENTS)
            .select {
                filter { eq("supplier_id", supplier.id) }
                order("name", Order.ASCENDING)
            }
            .decodeList()
    }

    override suspend fun banking(): BankingDetails? = withContext(Dispatchers.IO) {
        val supplier = myProfile() ?: return@withContext null
        client.from(SupabaseModule.Table.SUPPLIER_BANKING)
            .select {
                filter { eq("supplier_id", supplier.id) }
                limit(1)
            }
            .decodeSingleOrNull()
    }

    override suspend fun saveProfile(form: ProfileForm): SupplierProfile = withContext(Dispatchers.IO) {
        client.postgrest.rpc(
            SupabaseModule.Rpc.SAVE_SUPPLIER_PROFILE,
            buildJsonObject {
                put("p_company_name", form.companyName.trim())
                put("p_registration_number", form.registrationNumber.trim())
                put("p_tax_number", form.taxNumber.trim())
                put("p_vat_number", form.vatNumber.trim())
                put("p_business_type", form.businessType.orEmpty())
                put("p_year_established", form.yearEstablished.trim().toIntOrNull())
                put("p_contact_person", form.contactPerson.trim())
                put("p_job_title", form.jobTitle.trim())
                put("p_contact_email", form.contactEmail.trim())
                put("p_phone_number", form.phoneNumber.trim())
                put("p_mobile_number", form.mobileNumber.filterNot { it == ' ' || it == '-' })
                put("p_physical_address", form.physicalAddress.trim())
                put("p_postal_address", form.postalAddress.trim())
                put("p_province", form.province.orEmpty())
                put("p_industry_licences", form.industryLicences.trim())
                put("p_professional_registrations", form.professionalRegistrations.trim())
                put("p_categories", form.categories.trim())
                put("p_industry", form.industry.trim())
                put("p_expertise", form.expertise.trim())
                put("p_geographic_areas", form.geographicAreas.trim())
                put("p_company_profile", form.companyProfile.trim())
                put("p_employees", form.employees.trim().toIntOrNull())
            }
        ).decodeAs<SupplierProfile>()
    }

    override suspend fun saveBanking(form: BankingForm) {
        withContext(Dispatchers.IO) {
            client.postgrest.rpc(
                SupabaseModule.Rpc.SAVE_SUPPLIER_BANKING,
                buildJsonObject {
                    put("p_bank_name", form.bankName.trim())
                    put("p_account_name", form.accountName.trim())
                    put("p_account_number", form.accountNumber.trim())
                    put("p_branch_code", form.branchCode.trim())
                    put("p_proof_url", form.proofUrl.trim().ifBlank { null })
                }
            )
        }
    }

    override suspend fun saveDocument(
        name: String, reference: String, expiresAt: String?, fileUrl: String?
    ): SupplierDocument = withContext(Dispatchers.IO) {
        client.postgrest.rpc(
            SupabaseModule.Rpc.SAVE_SUPPLIER_DOCUMENT,
            buildJsonObject {
                put("p_name", name)
                put("p_reference", reference.trim())
                put("p_expires_at", expiresAt?.trim()?.ifBlank { null })
                put("p_file_url", fileUrl?.trim()?.ifBlank { null })
            }
        ).decodeAs<SupplierDocument>()
    }

    override suspend fun resubmitRegistration(): SupplierProfile = withContext(Dispatchers.IO) {
        client.postgrest.rpc(SupabaseModule.Rpc.RESUBMIT_SUPPLIER_REGISTRATION, buildJsonObject { })
            .decodeAs<SupplierProfile>()
    }

    override suspend fun registrationProblem(registrationNumber: String, csdNumber: String): String? =
        withContext(Dispatchers.IO) {
            val raw = client.postgrest.rpc(
                SupabaseModule.Rpc.SUPPLIER_REGISTRATION_PROBLEM,
                buildJsonObject {
                    put("p_registration_number", registrationNumber)
                    put("p_csd_number", csdNumber)
                }
            ).data
            val element = Json.parseToJsonElement(raw.ifBlank { "null" })
            if (element is JsonNull) null else element.jsonPrimitive.content
        }
}

// ---------------------------------------------------------------------------
// Sample data (no Supabase configured)
// ---------------------------------------------------------------------------

/** The sample supplier. Sign in with any email starting with "supplier". */
object SampleSupplier {
    val profile = Profile(
        id = "u-sup-001",
        email = "ops@infratech.co.za",
        fullName = "N. Pillay",
        role = UserRole.SUPPLIER,
        department = null
    )
}

/**
 * Offline stand-in. Mirrors the demo records in supplier_module.sql and
 * applies the same rules, so the screens behave the same either way.
 */
class SampleSupplierPortalRepository : SupplierPortalRepository {

    private var profile: SupplierProfile = SupplierProfile(
        id = "sup-001", companyName = "Infratech Solutions (Pty) Ltd",
        registrationNumber = "2011/004512/07", csdNumber = "MAAA0451236",
        taxNumber = "9123456789", vatNumber = "4123456789",
        businessType = "Private company (Pty) Ltd", yearEstablished = 2011,
        contactPerson = "N. Pillay", jobTitle = "Business Development Manager",
        contactEmail = "ops@infratech.co.za", phoneNumber = "011 234 5600", mobileNumber = "0825551234",
        physicalAddress = "14 Kelvin Drive, Rivonia, Johannesburg",
        postalAddress = "PO Box 4412, Rivonia, 2128", province = "Gauteng",
        taxClearanceStatus = "valid", bbbeeLevel = 2,
        industryLicences = "ECSA-registered project engineer on staff",
        professionalRegistrations = "IITPSA corporate member",
        categories = "Network infrastructure; End-user computing; Managed services",
        industry = "Information and communications technology",
        expertise = "Enterprise networking, structured cabling, service desk operations",
        geographicAreas = "Gauteng, Limpopo, Mpumalanga, North West",
        companyProfile = "Infratech Solutions has delivered network infrastructure to provincial " +
            "departments since 2011, with a permanent technical team of 48.",
        employees = 48, documentsReceived = 3, documentsRequired = 6,
        status = SupplierVerificationStatus.VERIFIED, submittedAt = "2026-01-15T09:00:00Z"
    )

    private val docs = mutableListOf(
        SupplierDocument("d1", "Company registration certificate (CIPC)", DocumentStatus.VERIFIED, "CIPC 2011/004512/07"),
        SupplierDocument("d2", "Tax clearance certificate / PIN", DocumentStatus.VERIFIED, "TCS PIN 7X2K9QL4", "2027-03-31"),
        SupplierDocument("d3", "B-BBEE certificate or affidavit", DocumentStatus.SUBMITTED, "Level 2 affidavit", "2027-01-31"),
        SupplierDocument("d4", "Proof of business address", DocumentStatus.NOT_SUBMITTED),
        SupplierDocument("d5", "Company profile", DocumentStatus.NOT_SUBMITTED),
        SupplierDocument("d6", "Proof of banking details", DocumentStatus.NOT_SUBMITTED)
    )

    private var bankingDetails: BankingDetails? = BankingDetails(
        "First National Bank", "Infratech Solutions (Pty) Ltd", "62345678901", "250655",
        updatedAt = "2026-02-01T09:00:00Z"
    )

    /** The sample tenders, as a bidder sees them: no estimate until the award. */
    private val board: List<TenderBoardItem> by lazy {
        SampleData.tenders.filter { it.status != TenderStatus.REGISTERED }.map { t ->
            TenderBoardItem(
                id = t.id, referenceNumber = t.referenceNumber, title = t.title, description = t.description,
                department = t.department, category = t.category, status = t.status,
                estimatedBudget = if (t.awardedAt != null) t.estimatedBudget else null,
                publishedAt = SampleTenderPacks.publishedAt[t.referenceNumber],
                closingDate = t.closingDate, contractPeriodMonths = t.contractPeriodMonths,
                awardedSupplierName = t.awardedSupplierName, awardedValue = t.awardedValue, awardedAt = t.awardedAt
            )
        }
    }

    override suspend fun board(): List<TenderBoardItem> = board.sortedBy { it.closingDate }

    override suspend fun pack(tenderId: String): TenderPack {
        val tender = board.firstOrNull { it.id == tenderId } ?: error("That tender is no longer published.")
        return TenderPack(
            tender = tender,
            details = SampleTenderPacks.details[tender.referenceNumber],
            eligibility = SampleTenderPacks.eligibility[tender.referenceNumber].orEmpty(),
            documents = SampleTenderPacks.documents[tender.referenceNumber].orEmpty(),
            updates = SampleTenderPacks.updates[tender.referenceNumber].orEmpty(),
            criteria = emptyList()
        )
    }

    override suspend fun myProfile(): SupplierProfile = profile

    override suspend fun documents(): List<SupplierDocument> = docs.sortedBy { it.name }

    override suspend fun banking(): BankingDetails? = bankingDetails

    override suspend fun saveProfile(form: ProfileForm): SupplierProfile {
        val errors = form.errors()
        require(errors.isEmpty()) { errors.values.first() }
        profile = profile.copy(
            companyName = form.companyName.trim(), registrationNumber = form.registrationNumber.trim(),
            taxNumber = form.taxNumber.trim(), vatNumber = form.vatNumber.trim(),
            businessType = form.businessType.orEmpty(), yearEstablished = form.yearEstablished.trim().toIntOrNull(),
            contactPerson = form.contactPerson.trim(), jobTitle = form.jobTitle.trim(),
            contactEmail = form.contactEmail.trim(), phoneNumber = form.phoneNumber.trim(),
            mobileNumber = form.mobileNumber.filterNot { it == ' ' || it == '-' },
            physicalAddress = form.physicalAddress.trim(), postalAddress = form.postalAddress.trim(),
            province = form.province.orEmpty(), industryLicences = form.industryLicences.trim(),
            professionalRegistrations = form.professionalRegistrations.trim(),
            categories = form.categories.trim(), industry = form.industry.trim(),
            expertise = form.expertise.trim(), geographicAreas = form.geographicAreas.trim(),
            companyProfile = form.companyProfile.trim(), employees = form.employees.trim().toIntOrNull()
        )
        return profile
    }

    override suspend fun saveBanking(form: BankingForm) {
        val errors = form.errors()
        require(errors.isEmpty()) { errors.values.first() }
        bankingDetails = BankingDetails(
            form.bankName.trim(), form.accountName.trim(), form.accountNumber.trim(),
            form.branchCode.trim(), form.proofUrl.trim().ifBlank { null }, Format.nowIso()
        )
    }

    override suspend fun saveDocument(
        name: String, reference: String, expiresAt: String?, fileUrl: String?
    ): SupplierDocument {
        val index = docs.indexOfFirst { it.name == name }
        require(index >= 0) { "Choose which document this is." }
        val expired = expiresAt?.let { (daysBetween(it, Format.nowIso()) ?: 0) > 0 } ?: false
        val updated = docs[index].copy(
            status = if (expired) DocumentStatus.EXPIRED else DocumentStatus.SUBMITTED,
            reference = reference.trim(), expiresAt = expiresAt?.ifBlank { null },
            fileUrl = fileUrl?.ifBlank { null }, updatedAt = Format.nowIso()
        )
        docs[index] = updated
        profile = profile.copy(
            documentsReceived = docs.count { it.status == DocumentStatus.SUBMITTED || it.status == DocumentStatus.VERIFIED }
        )
        return updated
    }

    override suspend fun resubmitRegistration(): SupplierProfile {
        require(profile.status == SupplierVerificationStatus.NOT_APPROVED) {
            "Only a registration that was not approved can be resubmitted."
        }
        profile = profile.copy(status = SupplierVerificationStatus.AWAITING_VERIFICATION, submittedAt = Format.nowIso())
        return profile
    }

    override suspend fun registrationProblem(registrationNumber: String, csdNumber: String): String? = when {
        csdNumber.trim().equals(profile.csdNumber, true) -> "This CSD number is already registered on TenderTrack."
        registrationNumber.trim() == profile.registrationNumber ->
            "This company registration number is already registered on TenderTrack."
        else -> null
    }
}

/** Demo tender packs for sample mode, mirroring supplier_module.sql. */
internal object SampleTenderPacks {

    val publishedAt = mapOf(
        "GP/IT/2290" to "2026-06-02T09:00:00Z", "GP/CLD/1187" to "2026-04-01T09:00:00Z",
        "NAT/SEC/492" to "2026-06-20T09:00:00Z", "WC/SF/1042" to "2026-07-14T09:00:00Z",
        "KZN/HW/9923" to "2026-07-28T09:00:00Z", "EC/WEB/004" to "2025-12-05T09:00:00Z"
    )

    val details = mapOf(
        "KZN/HW/9923" to TenderDetails(
            tenderId = "t-kzn",
            scopeOverview = "Supply, delivery and installation of networking hardware for 42 district health facilities in KwaZulu-Natal.",
            deliverables = "Switches, routers and wireless access points delivered to each site\n" +
                "Installation and commissioning at all 42 facilities\nTwelve months of on-site support\n" +
                "As-built documentation and asset register",
            technicalSpecs = "Layer-3 managed switches with PoE+\nWi-Fi 6 access points\n" +
                "Five-year hardware warranty\nSANS 10142 compliant installation",
            quantityRequirements = "42 sites · approximately 620 switch ports and 180 access points",
            expectedOutcomes = "Every facility connected to the provincial health network with 99.5% availability.",
            briefingAt = "2026-09-18T10:00:00Z",
            briefingVenue = "KZN Department of Health, Natalia Building, Pietermaritzburg",
            briefingCompulsory = true,
            siteVisitAt = "2026-09-25T09:00:00Z",
            expectedAwardDate = "2026-11-15",
            submissionMethod = "Sealed bid, delivered by hand to the tender box at the address in the bid document.",
            submissionFormat = "One original and two copies, each in a separate sealed envelope, clearly marked.",
            requiredDocuments = "Completed pricing schedule (SBD 3.1)\nTax compliance PIN\n" +
                "B-BBEE certificate or sworn affidavit\nCSD registration summary\nCompany registration certificate",
            technicalWeight = 20, financialWeight = 80,
            preferencePoints = "80/20 preference point system (PPPFA)",
            queryContactName = "N. Zulu", queryContactEmail = "tenders@kznhealth.gov.za",
            queryContactPhone = "033 395 2000"
        ),
        "WC/SF/1042" to TenderDetails(
            tenderId = "t-wc",
            scopeOverview = "Development and support of a citizen-facing service request portal for the Western Cape.",
            deliverables = "Discovery and design phase\nPortal build with accessibility compliance\n" +
                "Integration with the existing case management system\nTwo years of maintenance and support",
            technicalSpecs = "WCAG 2.1 AA accessibility\nPOPIA-compliant data handling\n" +
                "Hosting within South Africa\nSource code handed over on completion",
            quantityRequirements = "One portal, approximately 40 000 users a month",
            expectedOutcomes = "Citizens able to log and track service requests without visiting an office.",
            expectedAwardDate = "2026-11-20",
            submissionMethod = "Electronic submission through the provincial eTender portal.",
            submissionFormat = "Single PDF, maximum 20 MB, with the pricing schedule in a separate file.",
            requiredDocuments = "Completed pricing schedule\nTax compliance PIN\n" +
                "B-BBEE certificate or sworn affidavit\nTwo reference letters",
            technicalWeight = 30, financialWeight = 70,
            preferencePoints = "80/20 preference point system (PPPFA)",
            queryContactName = "A. Petersen", queryContactEmail = "supplychain@westerncape.gov.za",
            queryContactPhone = "021 483 0000"
        )
    )

    val eligibility = mapOf(
        "KZN/HW/9923" to listOf(
            TenderEligibility("e1", "Registered on the Central Supplier Database (CSD)", true, 1),
            TenderEligibility("e2", "Valid tax compliance status with SARS", true, 2),
            TenderEligibility("e3", "B-BBEE certificate or sworn affidavit", false, 3),
            TenderEligibility("e4", "At least three comparable installations in the past five years", true, 4),
            TenderEligibility("e5", "Manufacturer certification for the equipment offered", true, 5)
        ),
        "WC/SF/1042" to listOf(
            TenderEligibility("e6", "Registered on the Central Supplier Database (CSD)", true, 1),
            TenderEligibility("e7", "Valid tax compliance status with SARS", true, 2),
            TenderEligibility("e8", "At least two government portal projects delivered", true, 3),
            TenderEligibility("e9", "B-BBEE certificate or sworn affidavit", false, 4)
        )
    )

    val documents = mapOf(
        "KZN/HW/9923" to listOf(
            TenderDocument("td1", "Request for Proposal (RFP)", "RFP"),
            TenderDocument("td2", "Terms of Reference", "TOR"),
            TenderDocument("td3", "Technical specifications", "Specification"),
            TenderDocument("td4", "Pricing schedule (SBD 3.1)", "Pricing"),
            TenderDocument("td5", "Standard bidding forms (SBD 1, 4, 6.1)", "Forms"),
            TenderDocument("td6", "Draft contract (SLA)", "Contract")
        ),
        "WC/SF/1042" to listOf(
            TenderDocument("td7", "Request for Proposal (RFP)", "RFP"),
            TenderDocument("td8", "Terms of Reference", "TOR"),
            TenderDocument("td9", "Pricing schedule", "Pricing"),
            TenderDocument("td10", "Draft contract", "Contract")
        )
    )

    val updates = mapOf(
        "KZN/HW/9923" to listOf(
            TenderUpdate("u3", TenderUpdateKind.EXTENSION, "Closing date extended by one week",
                "Following requests at the briefing session, the closing date moved from 27 September to 4 October 2026.",
                "2026-09-19T16:00:00Z"),
            TenderUpdate("u2", TenderUpdateKind.QUESTION,
                "Q: May access points be supplied by a different manufacturer to the switches?",
                "A: Yes, provided both carry current manufacturer certification and are covered by a single support agreement.",
                "2026-09-15T14:20:00Z"),
            TenderUpdate("u1", TenderUpdateKind.CLARIFICATION, "Briefing session venue confirmed",
                "The compulsory briefing will be held in the Natalia Building auditorium. Bring proof of CSD registration.",
                "2026-09-10T08:00:00Z")
        ),
        "WC/SF/1042" to listOf(
            TenderUpdate("u4", TenderUpdateKind.AMENDMENT, "Pricing schedule reissued",
                "The pricing schedule has been reissued with the maintenance years split out. Use version 2 only.",
                "2026-08-28T11:30:00Z")
        )
    )
}

/**
 * Sample-mode supplier accounts, used by SampleAuthRepository so a supplier can
 * sign in and sign up without Supabase.
 */
object SampleSupplierAccounts {

    private val accounts = mutableMapOf(
        SampleSupplier.profile.email.lowercase() to SampleSupplier.profile
    )

    private fun key(email: String) = email.trim().lowercase()

    fun hasAccount(email: String): Boolean = accounts.containsKey(key(email))

    /** Any "supplier…" email that has not signed up sees the demo company. */
    fun profileFor(email: String): Profile =
        accounts.getOrPut(key(email)) { SampleSupplier.profile.copy(email = email.trim()) }

    /** Creates a sample supplier account from the sign-up form. */
    fun register(email: String, company: CompanyProfile): Profile {
        require(!hasAccount(email)) { "User already registered" }
        val profile = Profile(
            id = "u-sup-${System.currentTimeMillis()}",
            email = email.trim(),
            fullName = company.representative,
            role = UserRole.SUPPLIER,
            department = null
        )
        accounts[key(email)] = profile
        return profile
    }
}
