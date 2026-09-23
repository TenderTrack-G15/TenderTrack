package za.ac.tendertrack.data.repo

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import za.ac.tendertrack.core.Format
import za.ac.tendertrack.data.ServiceLocator
import za.ac.tendertrack.data.SupabaseModule
import za.ac.tendertrack.data.model.*
import za.ac.tendertrack.data.sample.SampleData

/**
 * A supplier's own registration (Deliverable 3, section 5.3): checking the
 * company is not already registered, reading the registration's status, and
 * correcting and resubmitting it after a "not approved" decision.
 *
 * Creating the registration happens in AuthRepository.signUpSupplier, because
 * the database creates it together with the login.
 */
interface SupplierAccountRepository {
    /** A message if the registration or CSD number is already on TenderTrack, otherwise null. */
    suspend fun registrationProblem(registrationNumber: String, csdNumber: String): String?

    /** The signed-in supplier's registration, or null if the account has none. */
    suspend fun myRegistration(): SupplierRegistration?

    /** Sends a corrected registration back for review. Only after "not approved". */
    suspend fun resubmit(company: CompanyProfile): SupplierRegistration
}

// ---------------------------------------------------------------------------
// Supabase
// ---------------------------------------------------------------------------

class SupabaseSupplierAccountRepository(private val client: SupabaseClient) : SupplierAccountRepository {

    override suspend fun registrationProblem(registrationNumber: String, csdNumber: String): String? =
        withContext(Dispatchers.IO) {
            val raw = client.postgrest.rpc(
                SupabaseModule.Rpc.SUPPLIER_REGISTRATION_PROBLEM,
                buildJsonObject {
                    put("p_registration_number", registrationNumber)
                    put("p_csd_number", csdNumber)
                }
            ).data
            // The function returns either a JSON string (the problem) or null.
            val element = Json.parseToJsonElement(raw.ifBlank { "null" })
            if (element is JsonNull) null else element.jsonPrimitive.content
        }

    override suspend fun myRegistration(): SupplierRegistration? = withContext(Dispatchers.IO) {
        val userId = client.auth.currentUserOrNull()?.id ?: return@withContext null
        client.from(SupabaseModule.Table.SUPPLIERS)
            .select { filter { eq("owner_id", userId) } }
            .decodeList<SupplierRegistration>()
            .firstOrNull()
    }

    override suspend fun resubmit(company: CompanyProfile): SupplierRegistration = withContext(Dispatchers.IO) {
        client.postgrest.rpc(
            SupabaseModule.Rpc.RESUBMIT_SUPPLIER_REGISTRATION,
            buildJsonObject {
                put("p_company_name", company.companyName)
                put("p_registration_number", company.registrationNumber)
                put("p_csd_number", company.csdNumber)
                put("p_tax_pin", company.taxPin)
                put("p_bbbee_level", company.bbbeeLevel)
                put("p_business_type", company.businessType)
                put("p_province", company.province)
                put("p_representative", company.representative)
                put("p_mobile_number", company.mobileNumber)
            }
        ).decodeAs<SupplierRegistration>()
    }
}

// ---------------------------------------------------------------------------
// Sample data (no Supabase configured)
// ---------------------------------------------------------------------------

/**
 * Supplier accounts for sample mode. A supplier who signs up here is also added
 * to SampleData.suppliers, so it appears in the officer's Supplier
 * Registrations screen in the same session, just as it does with Supabase.
 */
object SampleSupplierAccounts {

    private data class Account(val profile: Profile, var registration: SupplierRegistration)

    private val accounts = mutableMapOf<String, Account>()

    /** Shown to any "supplier…" email that has not signed up, to demonstrate "not approved". */
    private fun demoAccount(email: String): Account = Account(
        Profile(id = "u-sup-demo", email = email, fullName = "Thabo Nkosi", role = UserRole.SUPPLIER, department = null),
        SupplierRegistration(
            id = "5e1a7c2d-0000-4000-8000-00000000d300",
            companyName = "Siyakha ICT Solutions (Pty) Ltd",
            registrationNumber = "2020/784512/07",
            csdNumber = "MAAA0784512",
            taxPin = "7T2K9QX4LM",
            bbbeeLevel = 1,
            businessType = "Private company (Pty) Ltd",
            province = "KwaZulu-Natal",
            representative = "Thabo Nkosi",
            mobileNumber = "0731234567",
            contactEmail = email,
            status = SupplierVerificationStatus.NOT_APPROVED,
            submittedAt = "2026-09-15T09:30:00Z",
            decisionReason = "The CSD number does not match the company name on the Central Supplier Database."
        )
    )

    private fun key(email: String) = email.trim().lowercase()

    fun hasAccount(email: String): Boolean = accounts.containsKey(key(email))

    fun profileFor(email: String): Profile =
        accounts.getOrPut(key(email)) { demoAccount(email.trim()) }.profile

    fun registrationFor(email: String): SupplierRegistration? = accounts[key(email)]?.registration

    /** Same checks as the database, then creates the account and its registration. */
    fun register(email: String, company: CompanyProfile): Profile {
        require(!hasAccount(email)) { "User already registered" }
        problem(company.registrationNumber, company.csdNumber, excludeId = null)?.let { error(it) }

        val id = "sup-${System.currentTimeMillis()}"
        val now = Format.nowIso()
        val profile = Profile(id = "u-$id", email = email, fullName = company.representative,
            role = UserRole.SUPPLIER, department = null)
        val registration = SupplierRegistration(
            id = id, companyName = company.companyName, registrationNumber = company.registrationNumber,
            csdNumber = company.csdNumber, taxPin = company.taxPin, bbbeeLevel = company.bbbeeLevel,
            businessType = company.businessType, province = company.province,
            representative = company.representative, mobileNumber = company.mobileNumber,
            contactEmail = email, status = SupplierVerificationStatus.AWAITING_VERIFICATION, submittedAt = now
        )
        accounts[key(email)] = Account(profile, registration)

        // Into the officer's review queue, as the database trigger does.
        SampleData.suppliers.add(
            Supplier(
                id = id, companyName = company.companyName, registrationNumber = company.registrationNumber,
                csdNumber = company.csdNumber, bbbeeLevel = company.bbbeeLevel,
                businessType = company.businessType, contactEmail = email,
                status = SupplierVerificationStatus.AWAITING_VERIFICATION, submittedAt = now
            )
        )
        return profile
    }

    fun resubmit(email: String, company: CompanyProfile): SupplierRegistration {
        val account = accounts[key(email)] ?: error("No supplier registration belongs to this account.")
        require(account.registration.status == SupplierVerificationStatus.NOT_APPROVED) {
            "Only a registration that was not approved can be resubmitted."
        }
        problem(company.registrationNumber, company.csdNumber, excludeId = account.registration.id)?.let { error(it) }
        account.registration = account.registration.copy(
            companyName = company.companyName, registrationNumber = company.registrationNumber,
            csdNumber = company.csdNumber, taxPin = company.taxPin, bbbeeLevel = company.bbbeeLevel,
            businessType = company.businessType, province = company.province,
            representative = company.representative, mobileNumber = company.mobileNumber,
            status = SupplierVerificationStatus.AWAITING_VERIFICATION, submittedAt = Format.nowIso()
        )
        return account.registration
    }

    fun problem(registrationNumber: String, csdNumber: String, excludeId: String?): String? {
        val others = SampleData.suppliers.filter { it.id != excludeId }
        return when {
            others.any { it.csdNumber.equals(csdNumber.trim(), true) } ->
                "This CSD number is already registered on TenderTrack."
            others.any { it.registrationNumber == registrationNumber.trim() } ->
                "This company registration number is already registered on TenderTrack."
            else -> null
        }
    }
}

class SampleSupplierAccountRepository : SupplierAccountRepository {

    private suspend fun email(): String =
        ServiceLocator.authRepository.currentProfile()?.email ?: error("Not signed in.")

    override suspend fun registrationProblem(registrationNumber: String, csdNumber: String): String? =
        SampleSupplierAccounts.problem(registrationNumber, csdNumber, excludeId = null)

    override suspend fun myRegistration(): SupplierRegistration? = SampleSupplierAccounts.registrationFor(email())

    override suspend fun resubmit(company: CompanyProfile): SupplierRegistration =
        SampleSupplierAccounts.resubmit(email(), company)
}
