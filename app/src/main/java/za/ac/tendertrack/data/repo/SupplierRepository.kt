package za.ac.tendertrack.data.repo

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import za.ac.tendertrack.data.SupabaseModule
import za.ac.tendertrack.data.model.Supplier
import za.ac.tendertrack.data.model.SupplierVerificationStatus
import za.ac.tendertrack.data.sample.SampleData

/**
 * Supplier registrations.
 *
 * This is company verification only. It has nothing to do with evaluating bids,
 * and its statuses are deliberately separate from the tender lifecycle.
 */
interface SupplierRepository {
    suspend fun list(): List<Supplier>
    suspend fun byId(id: String): Supplier?
    suspend fun verified(): List<Supplier>
    suspend fun approve(id: String): Supplier
    suspend fun reject(id: String, reason: String): Supplier
}

class SupabaseSupplierRepository(private val client: SupabaseClient) : SupplierRepository {

    override suspend fun list(): List<Supplier> = withContext(Dispatchers.IO) {
        client.from(SupabaseModule.Table.SUPPLIERS)
            .select { order("submitted_at", Order.ASCENDING) }
            .decodeList()
    }

    override suspend fun byId(id: String): Supplier? = withContext(Dispatchers.IO) {
        client.from(SupabaseModule.Table.SUPPLIERS)
            .select {
                filter { eq("id", id) }
                limit(1)
            }
            .decodeSingleOrNull()
    }

    override suspend fun verified(): List<Supplier> = withContext(Dispatchers.IO) {
        client.from(SupabaseModule.Table.SUPPLIERS)
            .select {
                filter { eq("status", "verified") }
                order("company_name", Order.ASCENDING)
            }
            .decodeList()
    }

    override suspend fun approve(id: String): Supplier = withContext(Dispatchers.IO) {
        client.from(SupabaseModule.Table.SUPPLIERS)
            .update({
                set("status", "verified")
                set("decision_reason", null as String?)
            }) {
                filter { eq("id", id) }
                select()
            }
            .decodeSingle()
    }

    override suspend fun reject(id: String, reason: String): Supplier = withContext(Dispatchers.IO) {
        client.from(SupabaseModule.Table.SUPPLIERS)
            .update({
                set("status", "not_approved")
                set("decision_reason", reason)
            }) {
                filter { eq("id", id) }
                select()
            }
            .decodeSingle()
    }
}

class SampleSupplierRepository : SupplierRepository {

    override suspend fun list(): List<Supplier> = SampleData.suppliers.toList()

    override suspend fun byId(id: String): Supplier? = SampleData.suppliers.firstOrNull { it.id == id }

    override suspend fun verified(): List<Supplier> =
        SampleData.suppliers.filter { it.status == SupplierVerificationStatus.VERIFIED }
            .sortedBy { it.companyName }

    override suspend fun approve(id: String): Supplier = replace(id) {
        it.copy(status = SupplierVerificationStatus.VERIFIED, decisionReason = null)
    }

    override suspend fun reject(id: String, reason: String): Supplier = replace(id) {
        it.copy(status = SupplierVerificationStatus.NOT_APPROVED, decisionReason = reason)
    }

    private fun replace(id: String, transform: (Supplier) -> Supplier): Supplier {
        val index = SampleData.suppliers.indexOfFirst { it.id == id }
        require(index >= 0) { "Supplier registration not found." }
        val updated = transform(SampleData.suppliers[index])
        SampleData.suppliers[index] = updated
        return updated
    }
}
