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
import za.ac.tendertrack.data.SupabaseModule
import za.ac.tendertrack.data.model.Payment
import za.ac.tendertrack.data.model.PaymentDraft
import za.ac.tendertrack.data.sample.SampleData

/**
 * Payments against awarded tenders — FR10 and FR12.
 *
 * The rule that a payment may not take total disbursement above the awarded
 * value is enforced in the database as well as here, so it holds regardless of
 * which client writes the row.
 */
interface PaymentRepository {
    suspend fun forTender(tenderId: String): List<Payment>
    suspend fun all(): List<Payment>
    suspend fun record(draft: PaymentDraft): Payment
}

class SupabasePaymentRepository(private val client: SupabaseClient) : PaymentRepository {

    override suspend fun forTender(tenderId: String): List<Payment> = withContext(Dispatchers.IO) {
        client.from(SupabaseModule.Table.PAYMENTS)
            .select {
                filter { eq("tender_id", tenderId) }
                order("paid_on", Order.DESCENDING)
            }
            .decodeList()
    }

    override suspend fun all(): List<Payment> = withContext(Dispatchers.IO) {
        client.from(SupabaseModule.Table.PAYMENTS)
            .select { order("paid_on", Order.DESCENDING) }
            .decodeList()
    }

    /**
     * Goes through the record_payment function rather than a plain insert, so
     * the over-payment check, the paid_to_date update, the budget update and the
     * audit entry all happen in one transaction.
     */
    override suspend fun record(draft: PaymentDraft): Payment = withContext(Dispatchers.IO) {
        client.postgrest.rpc(
            SupabaseModule.Rpc.RECORD_PAYMENT,
            buildJsonObject {
                put("p_tender_id", draft.tenderId)
                put("p_milestone", draft.milestone)
                put("p_amount", draft.amount)
                put("p_paid_on", draft.paidOn)
                put("p_invoice", draft.invoiceNumber)
            }
        )
        forTender(draft.tenderId).firstOrNull()
            ?: error("The payment was recorded but could not be read back.")
    }
}

class SamplePaymentRepository : PaymentRepository {

    override suspend fun forTender(tenderId: String): List<Payment> =
        SampleData.payments.filter { it.tenderId == tenderId }.sortedByDescending { it.paidOn }

    override suspend fun all(): List<Payment> = SampleData.payments.sortedByDescending { it.paidOn }

    override suspend fun record(draft: PaymentDraft): Payment {
        val index = SampleData.tenders.indexOfFirst { it.id == draft.tenderId }
        require(index >= 0) { "Tender not found." }
        val tender = SampleData.tenders[index]
        val awarded = tender.awardedValue
            ?: error("A payment can only be recorded against an awarded tender.")

        // Mirrors the database check constraint.
        require(tender.paidToDate + draft.amount <= awarded + 0.005) {
            "This payment would take total disbursement to " +
                "${Format.money(tender.paidToDate + draft.amount)}, above the awarded value of " +
                "${Format.money(awarded)}."
        }

        val payment = Payment(
            id = "p-${System.currentTimeMillis()}",
            tenderId = draft.tenderId,
            tenderReference = tender.referenceNumber,
            milestone = draft.milestone,
            amount = draft.amount,
            paidOn = draft.paidOn,
            invoiceNumber = draft.invoiceNumber,
            recordedBy = SampleData.currentUser.fullName
        )
        SampleData.payments.add(0, payment)
        SampleData.tenders[index] = tender.copy(paidToDate = tender.paidToDate + draft.amount)
        return payment
    }
}
