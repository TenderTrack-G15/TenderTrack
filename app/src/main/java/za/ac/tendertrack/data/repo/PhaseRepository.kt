package za.ac.tendertrack.data.repo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import za.ac.tendertrack.core.Format
import za.ac.tendertrack.data.SupabaseModule
import za.ac.tendertrack.data.model.*
import java.io.ByteArrayOutputStream

/**
 * Contract phases with photo proof, for all three roles (supabase/phase_proof.sql):
 *   supplier            photographs the current phase;
 *   auditor             adds a site photo of the same phase;
 *   procurement officer compares the two, verifies or returns the phase, and
 *                       marks "Payment can be made".
 * The database checks every rule (the order, the one-phase-at-a-time lock, who
 * may do what, that a photo is a real JPEG). This class only passes calls on.
 */
interface PhaseRepository {

    // -- Supplier ----------------------------------------------------------------

    /** The supplier's own contract, phase by phase. */
    suspend fun supplierPhases(tenderId: String): List<ContractPhase>

    /** The supplier's photo for the current phase: a JPEG from the camera, base64-encoded. */
    suspend fun submitSupplierPhoto(deliverableId: String, photoBase64: String): ContractPhase

    // -- Auditor -----------------------------------------------------------------

    suspend fun siteCheckQueue(): SiteCheckQueue

    /** The auditor's own photo of the same phase, taken on site, with an optional note. */
    suspend fun submitSiteCheck(deliverableId: String, photoBase64: String, note: String): ContractPhase

    // -- Procurement officer -----------------------------------------------------

    /** Phases needing the officer, or every phase of one contract when [tenderId] is given. */
    suspend fun officerQueue(tenderId: String? = null): List<ContractPhase>

    /** Photos match: the phase is verified and the next one opens. No match: back to the supplier. */
    suspend fun verify(deliverableId: String, photosMatch: Boolean, comment: String): ContractPhase

    /** "Payment can be made" for a verified phase. */
    suspend fun approvePayment(deliverableId: String): ContractPhase

    /** Phases marked "Payment can be made", for Record Payment. */
    suspend fun payablePhases(tenderId: String): List<PayablePhase>

    /** Everything shown on the dashboard tiles, tender cards and registration rows. */
    suspend fun officerAlerts(): OfficerAlerts

    /** Clears a company's new-documents count without verifying or rejecting it. */
    suspend fun markDocumentsChecked(supplierId: String)

    /** A tender's bids and the documents each supplier confirmed on the eTender portal. */
    suspend fun bidDocuments(tenderId: String): TenderBidDocuments

    /** The officer has checked them: the tender's "New bid documents" alert goes away. */
    suspend fun markBidDocumentsChecked(tenderId: String)

    // -- Any of the three (the database decides who may see which photo) ---------

    suspend fun photo(photoId: String): PhasePhoto

    companion object {
        /** The real database when Supabase is configured, sample data otherwise. */
        val instance: PhaseRepository by lazy {
            SupabaseModule.client?.let { SupabasePhaseRepository(it) } ?: SamplePhaseRepository()
        }
    }
}

/**
 * The officer's alerts, kept in one place so the dashboard, the drawer and the
 * tender list show the same numbers without each asking the database.
 */
object OfficerAlertsStore {
    private val _alerts = MutableStateFlow<OfficerAlerts?>(null)
    val alerts: StateFlow<OfficerAlerts?> = _alerts.asStateFlow()

    /**
     * Fetches the alerts again. If they cannot be read (for example before
     * phase_proof.sql has been run) the screens simply show no alerts.
     */
    suspend fun refresh(repository: PhaseRepository = PhaseRepository.instance): OfficerAlerts? =
        runCatching { repository.officerAlerts() }.getOrNull()?.also { _alerts.value = it }

    fun clear() {
        _alerts.value = null
    }
}

/** Database names used here. Kept with the repository so no other file needs editing. */
private object PhaseApi {
    const val SUPPLIER_CONTRACT_PHASES = "supplier_contract_phases"
    const val SUBMIT_PHASE_PHOTO = "submit_phase_photo"
    const val SITE_CHECK_QUEUE = "site_check_queue"
    const val SUBMIT_SITE_CHECK = "submit_site_check"
    const val OFFICER_PHASE_QUEUE = "officer_phase_queue"
    const val VERIFY_PHASE = "verify_phase"
    const val APPROVE_PHASE_PAYMENT = "approve_phase_payment"
    const val PAYABLE_PHASES = "payable_phases"
    const val OFFICER_ALERTS = "officer_alerts"
    const val MARK_DOCUMENTS_REVIEWED = "mark_supplier_documents_reviewed"
    const val PHASE_PHOTO = "phase_photo"
    const val TENDER_BID_DOCUMENTS = "tender_bid_documents"
    const val MARK_BID_DOCUMENTS_SEEN = "mark_bid_documents_seen"
}

// ---------------------------------------------------------------------------
// Supabase
// ---------------------------------------------------------------------------

class SupabasePhaseRepository(private val client: SupabaseClient) : PhaseRepository {

    private suspend fun call(function: String, args: JsonObject = buildJsonObject { }) =
        client.postgrest.rpc(function, args)

    override suspend fun supplierPhases(tenderId: String): List<ContractPhase> = withContext(Dispatchers.IO) {
        call(PhaseApi.SUPPLIER_CONTRACT_PHASES, buildJsonObject { put("p_tender_id", tenderId) })
            .decodeAs<List<ContractPhase>>()
    }

    override suspend fun submitSupplierPhoto(deliverableId: String, photoBase64: String): ContractPhase =
        withContext(Dispatchers.IO) {
            call(PhaseApi.SUBMIT_PHASE_PHOTO, buildJsonObject {
                put("p_deliverable_id", deliverableId)
                put("p_photo", photoBase64)
            }).decodeAs<ContractPhase>()
        }

    override suspend fun siteCheckQueue(): SiteCheckQueue = withContext(Dispatchers.IO) {
        call(PhaseApi.SITE_CHECK_QUEUE).decodeAs<SiteCheckQueue>()
    }

    override suspend fun submitSiteCheck(deliverableId: String, photoBase64: String, note: String): ContractPhase =
        withContext(Dispatchers.IO) {
            call(PhaseApi.SUBMIT_SITE_CHECK, buildJsonObject {
                put("p_deliverable_id", deliverableId)
                put("p_photo", photoBase64)
                put("p_note", note.trim())
            }).decodeAs<ContractPhase>()
        }

    override suspend fun officerQueue(tenderId: String?): List<ContractPhase> = withContext(Dispatchers.IO) {
        call(PhaseApi.OFFICER_PHASE_QUEUE, buildJsonObject { if (tenderId != null) put("p_tender_id", tenderId) })
            .decodeAs<List<ContractPhase>>()
    }

    override suspend fun verify(deliverableId: String, photosMatch: Boolean, comment: String): ContractPhase =
        withContext(Dispatchers.IO) {
            call(PhaseApi.VERIFY_PHASE, buildJsonObject {
                put("p_deliverable_id", deliverableId)
                put("p_photos_match", photosMatch)
                put("p_comment", comment.trim())
            }).decodeAs<ContractPhase>()
        }

    override suspend fun approvePayment(deliverableId: String): ContractPhase = withContext(Dispatchers.IO) {
        call(PhaseApi.APPROVE_PHASE_PAYMENT, buildJsonObject { put("p_deliverable_id", deliverableId) })
            .decodeAs<ContractPhase>()
    }

    override suspend fun payablePhases(tenderId: String): List<PayablePhase> = withContext(Dispatchers.IO) {
        call(PhaseApi.PAYABLE_PHASES, buildJsonObject { put("p_tender_id", tenderId) })
            .decodeAs<List<PayablePhase>>()
    }

    override suspend fun officerAlerts(): OfficerAlerts = withContext(Dispatchers.IO) {
        call(PhaseApi.OFFICER_ALERTS).decodeAs<OfficerAlerts>()
    }

    override suspend fun markDocumentsChecked(supplierId: String) {
        withContext(Dispatchers.IO) {
            call(PhaseApi.MARK_DOCUMENTS_REVIEWED, buildJsonObject { put("p_supplier_id", supplierId) })
        }
    }

    override suspend fun photo(photoId: String): PhasePhoto = withContext(Dispatchers.IO) {
        call(PhaseApi.PHASE_PHOTO, buildJsonObject { put("p_photo_id", photoId) }).decodeAs<PhasePhoto>()
    }

    override suspend fun bidDocuments(tenderId: String): TenderBidDocuments = withContext(Dispatchers.IO) {
        call(PhaseApi.TENDER_BID_DOCUMENTS, buildJsonObject { put("p_tender_id", tenderId) }).decodeAs<TenderBidDocuments>()
    }

    override suspend fun markBidDocumentsChecked(tenderId: String) {
        withContext(Dispatchers.IO) {
            call(PhaseApi.MARK_BID_DOCUMENTS_SEEN, buildJsonObject { put("p_tender_id", tenderId) })
        }
    }
}

// ---------------------------------------------------------------------------
// Sample data (no Supabase configured)
// ---------------------------------------------------------------------------

/**
 * Offline stand-in with the same rules as the database. One shared copy, so on
 * a single phone you can sign in as the supplier, then the auditor, then the
 * procurement officer, and follow one phase all the way through.
 *
 *   t-001  GP/IT/2290   Infratech (the sample supplier): every phase still to do.
 *   t-006  GP/CLD/1187  Siyakhula: phases 1 and 2 paid, phase 3 ready to compare.
 *   t-002  WC/SF/1042   open for bids: a new bid from Lethabo Digital with its documents.
 */
class SamplePhaseRepository : PhaseRepository {

    private data class Contract(
        val tenderId: String,
        val reference: String,
        val title: String,
        val department: String,
        val supplier: String,
        val value: Double
    )

    private val contracts = listOf(
        Contract("t-001", "GP/IT/2290", "Network Hardware Supply", "Gauteng Dept of e-Government",
            "Infratech Solutions (Pty) Ltd", 17_950_000.0),
        Contract("t-006", "GP/CLD/1187", "Cloud Hosting Services", "Gauteng Dept of e-Government",
            "Siyakhula Technologies", 9_450_000.0)
    )

    private val photos = mutableMapOf<String, PhasePhoto>()
    private var nextId = 1
    private val phases: MutableList<ContractPhase> = contracts.flatMap { c -> standardPhases(c) }.toMutableList()
    private val documentsChecked = mutableSetOf<String>()
    private val bidDocumentsChecked = mutableSetOf<String>()

    /** WC/SF/1042 (open for bids): one bid already checked, one new from the eTender portal. */
    private val sampleRequired = listOf(
        "Company registration certificate (CIPC)", "Tax clearance certificate / PIN", "B-BBEE certificate or affidavit",
        "Technical proposal", "Pricing schedule (SBD 3.1)"
    )
    private val sampleBids = listOf(
        BidDocumentsEntry("b-101", "BID-2026-00987", "Vuka IT Consulting", "MAAA0451236", "awaiting_verification",
            "2026-09-22T09:14:00Z", documents = sampleRequired, documentsReceived = 5, documentsRequired = 5),
        BidDocumentsEntry("b-102", "BID-2026-01000", "Lethabo Digital CC", "MAAA0778120", "awaiting_verification",
            "2026-09-30T14:02:00Z", documents = sampleRequired, documentsReceived = 5, documentsRequired = 5, isNew = true)
    )

    init {
        // GP/CLD/1187: two phases done and approved, the third waiting for the officer.
        val cld = phases.filter { it.tenderId == "t-006" }
        replace(cld[0].copy(state = PhaseState.PAYMENT_APPROVED, verifiedAt = "2026-07-08T09:00:00Z",
            paymentApprovedAt = "2026-07-08T10:00:00Z"))
        replace(cld[1].copy(state = PhaseState.PAYMENT_APPROVED, verifiedAt = "2026-08-13T09:00:00Z",
            paymentApprovedAt = "2026-08-13T10:00:00Z"))
        val supplierPhoto = addPhoto("supplier", Color.rgb(30, 64, 120), "Servers racked", "S. Dlamini", "")
        val sitePhoto = addPhoto("auditor", Color.rgb(22, 101, 52), "Site visit", "Sample Auditor",
            "Racks installed in the Midrand data centre, as in the supplier photo.")
        replace(cld[2].copy(state = PhaseState.AWAITING_OFFICER, supplierPhotoId = supplierPhoto.id,
            supplierPhotoAt = "2026-10-14T10:00:00Z", sitePhotoId = sitePhoto.id,
            siteCheckedAt = "2026-10-16T12:00:00Z", siteCheckedByName = sitePhoto.takenByName,
            siteNote = sitePhoto.note))
        relock()
    }

    private fun standardPhases(c: Contract): List<ContractPhase> {
        val names = listOf("Project start", "Execution", "Implementation", "Testing & acceptance", "Handover & close-out")
        val shares = listOf(10.0, 25.0, 30.0, 20.0, 15.0)
        val guidance = listOf(
            "Kick-off and mobilisation: photograph the team or the equipment delivered on site.",
            "Work under way: photograph the work in progress at the site.",
            "Installed and in place: photograph the system or equipment installed.",
            "Testing: photograph the system being tested, or the signed test sheet.",
            "Handover: photograph the finished work, or the signed handover certificate."
        )
        val dates = listOf("2026-09-15", "2026-11-30", "2027-02-28", "2027-04-15", "2027-05-31")
        var used = 0.0
        return names.indices.map { i ->
            val value = if (i == names.lastIndex) c.value - used else Math.round(c.value * shares[i]) / 100.0
            used += value
            ContractPhase(
                id = "${c.tenderId}-p${i + 1}", tenderId = c.tenderId, phaseNo = i + 1, phaseName = names[i],
                phaseShare = shares[i], phaseValue = value, targetDate = dates[i], guidance = guidance[i],
                referenceNumber = c.reference, title = c.title, department = c.department, supplierName = c.supplier
            )
        }
    }

    private fun replace(phase: ContractPhase) {
        val i = phases.indexOfFirst { it.id == phase.id }
        if (i >= 0) phases[i] = phase
    }

    /** Only the first phase not yet verified is open, as in the database. */
    private fun relock() {
        contracts.forEach { c ->
            val current = phases.filter { it.tenderId == c.tenderId && !it.state.isDone }.minOfOrNull { it.phaseNo }
            phases.filter { it.tenderId == c.tenderId }.forEach { p ->
                replace(p.copy(locked = !p.state.isDone && p.phaseNo != current))
            }
        }
    }

    private fun find(id: String): ContractPhase =
        phases.firstOrNull { it.id == id } ?: throw IllegalArgumentException("That phase no longer exists.")

    private fun addPhoto(kind: String, color: Int, label: String, by: String, note: String, base64: String? = null): PhasePhoto {
        val photo = PhasePhoto(
            id = "photo-${nextId++}", kind = kind, photo = base64 ?: samplePicture(color, label),
            takenAt = Format.nowIso(), takenByName = by, note = note
        )
        photos[photo.id] = photo
        return photo
    }

    /** A plain coloured picture with a label, standing in for a camera photo. */
    private fun samplePicture(color: Int, label: String): String {
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(color)
        val paint = Paint().apply {
            this.color = Color.WHITE
            textSize = 42f
            isAntiAlias = true
        }
        canvas.drawText(label, 32f, 80f, paint)
        canvas.drawText("Sample photo", 32f, 440f, paint)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    override suspend fun supplierPhases(tenderId: String): List<ContractPhase> =
        phases.filter { it.tenderId == tenderId }.sortedBy { it.phaseNo }
            .map { it.copy(sitePhotoId = null, siteNote = "", siteCheckedByName = null) }

    override suspend fun submitSupplierPhoto(deliverableId: String, photoBase64: String): ContractPhase {
        val phase = find(deliverableId)
        val claimed = (AwardRepository.instance as? SampleAwardRepository)?.isClaimed(phase.tenderId) ?: true
        check(claimed) { "Claim this award with the code from your email before starting its phases." }
        check(!phase.state.isDone) { "\"${phase.phaseName}\" is already verified." }
        check(phase.state != PhaseState.AWAITING_SITE_CHECK && phase.state != PhaseState.AWAITING_OFFICER) {
            "A photo for \"${phase.phaseName}\" is already in. It is waiting for the site check and the procurement officer."
        }
        if (phase.locked) {
            val blocking = phases.filter { it.tenderId == phase.tenderId && !it.state.isDone }.minByOrNull { it.phaseNo }
            error("\"${blocking?.phaseName}\" must be verified by the procurement officer before you can start \"${phase.phaseName}\".")
        }
        val photo = addPhoto("supplier", 0, "", "N. Pillay", "", base64 = photoBase64)
        val updated = phase.copy(
            state = PhaseState.AWAITING_SITE_CHECK, supplierPhotoId = photo.id, supplierPhotoAt = photo.takenAt,
            sitePhotoId = null, siteCheckedAt = null, siteCheckedByName = null, siteNote = "", returnedReason = ""
        )
        replace(updated)
        return updated
    }

    override suspend fun siteCheckQueue(): SiteCheckQueue = SiteCheckQueue(
        waiting = phases.filter { it.state == PhaseState.AWAITING_SITE_CHECK }.sortedBy { it.supplierPhotoAt },
        done = phases.filter { it.sitePhotoId != null }.sortedByDescending { it.siteCheckedAt }
    )

    override suspend fun submitSiteCheck(deliverableId: String, photoBase64: String, note: String): ContractPhase {
        val phase = find(deliverableId)
        check(phase.state == PhaseState.AWAITING_SITE_CHECK) {
            if (phase.sitePhotoId != null) "This phase already has a site check."
            else "There is no supplier photo waiting for a site check on this phase."
        }
        require(note.trim().length <= 500) { "Keep the note under 500 characters." }
        val photo = addPhoto("auditor", 0, "", "Sample Auditor", note.trim(), base64 = photoBase64)
        val updated = phase.copy(
            state = PhaseState.AWAITING_OFFICER, sitePhotoId = photo.id, siteCheckedAt = photo.takenAt,
            siteCheckedByName = photo.takenByName, siteNote = photo.note
        )
        replace(updated)
        return updated
    }

    override suspend fun officerQueue(tenderId: String?): List<ContractPhase> =
        if (tenderId != null) phases.filter { it.tenderId == tenderId }.sortedBy { it.phaseNo }
        else phases.filter {
            it.state == PhaseState.AWAITING_OFFICER || it.state == PhaseState.VERIFIED || it.state == PhaseState.AWAITING_SITE_CHECK
        }.sortedWith(compareBy({
            when (it.state) { PhaseState.AWAITING_OFFICER -> 0; PhaseState.VERIFIED -> 1; else -> 2 }
        }, { it.supplierPhotoAt }))

    override suspend fun verify(deliverableId: String, photosMatch: Boolean, comment: String): ContractPhase {
        val phase = find(deliverableId)
        check(phase.supplierPhotoId != null && (phase.state == PhaseState.AWAITING_OFFICER || phase.state == PhaseState.AWAITING_SITE_CHECK)) {
            "This phase has no supplier photo waiting to be checked."
        }
        check(phase.sitePhotoId != null) { "Wait for the auditor's site photo before verifying this phase." }
        require(photosMatch || comment.trim().length >= 5) { "Say what does not match (at least 5 characters) when returning a phase." }
        val updated = if (photosMatch) phase.copy(state = PhaseState.VERIFIED, verifiedAt = Format.nowIso(), returnedReason = "")
        else phase.copy(
            state = PhaseState.RETURNED, returnedReason = comment.trim(), supplierPhotoId = null, supplierPhotoAt = null,
            sitePhotoId = null, siteCheckedAt = null, siteCheckedByName = null, siteNote = ""
        )
        replace(updated)
        relock()
        return find(deliverableId)
    }

    override suspend fun approvePayment(deliverableId: String): ContractPhase {
        val phase = find(deliverableId)
        check(phase.state == PhaseState.VERIFIED) {
            if (phase.state == PhaseState.PAYMENT_APPROVED) "Payment for this phase was already approved."
            else "Verify the phase first: compare the supplier's and the auditor's photos."
        }
        val updated = phase.copy(state = PhaseState.PAYMENT_APPROVED, paymentApprovedAt = Format.nowIso())
        replace(updated)
        return updated
    }

    override suspend fun payablePhases(tenderId: String): List<PayablePhase> =
        phases.filter { it.tenderId == tenderId && it.paymentApprovedAt != null }.sortedBy { it.phaseNo }.map {
            PayablePhase(it.id, it.phaseNo, it.phaseName, it.phaseValue, it.paymentApprovedAt)
        }

    override suspend fun officerAlerts(): OfficerAlerts {
        val byTender = phases.groupBy { it.tenderId }.mapNotNull { (tenderId, list) ->
            val alerts = list.sortedBy { it.phaseNo }.mapNotNull { p ->
                when (p.state) {
                    PhaseState.AWAITING_OFFICER -> TenderAlert("phase_check", "danger", "Phase ${p.phaseNo}: compare the photos", p.id)
                    PhaseState.VERIFIED -> TenderAlert("payment", "warning", "Phase ${p.phaseNo}: approve payment", p.id)
                    PhaseState.AWAITING_SITE_CHECK ->
                        TenderAlert("site_check", "neutral", "Phase ${p.phaseNo}: waiting for the auditor's site photo", p.id)
                    else -> null
                }
            }
            if (alerts.isEmpty()) null else TenderAlerts(tenderId, list.first().referenceNumber, "in_progress", alerts)
        }
        val newDocs = if ("s-004" in documentsChecked) emptyList()
        else listOf(NewDocumentSupplier("s-004", "Lethabo Digital CC", 1))
        val newBids = if ("t-002" in bidDocumentsChecked) emptyList() else sampleBids.filter { it.isNew }
        val bidTiles = if (newBids.isEmpty()) emptyList() else listOf(
            TenderAlerts("t-002", "WC/SF/1042", "published", listOf(
                TenderAlert(TenderAlert.BID_DOCUMENTS, "warning",
                    "New bid documents from ${newBids.single().supplierName} (${newBids.sumOf { it.documentsReceived }})")
            ))
        )
        return OfficerAlerts(
            newDocumentSuppliers = newDocs,
            tenders = byTender + bidTiles,
            totals = AlertTotals(
                phaseChecks = phases.count { it.state == PhaseState.AWAITING_OFFICER },
                paymentsToApprove = phases.count { it.state == PhaseState.VERIFIED },
                siteChecksWaiting = phases.count { it.state == PhaseState.AWAITING_SITE_CHECK },
                newDocuments = newDocs.sumOf { it.newDocuments },
                companiesWithNewDocuments = newDocs.size,
                newBids = newBids.size,
                newBidDocuments = newBids.sumOf { it.documentsReceived },
                tendersWithNewBids = if (newBids.isEmpty()) 0 else 1
            )
        )
    }

    override suspend fun markDocumentsChecked(supplierId: String) {
        documentsChecked += supplierId
    }

    override suspend fun photo(photoId: String): PhasePhoto =
        photos[photoId] ?: throw IllegalArgumentException("That photo no longer exists.")

    override suspend fun bidDocuments(tenderId: String): TenderBidDocuments {
        if (tenderId != "t-002") return TenderBidDocuments(tenderId = tenderId)
        val checked = tenderId in bidDocumentsChecked
        return TenderBidDocuments(
            tenderId = tenderId, referenceNumber = "WC/SF/1042", title = "SaaS CRM Integration", status = "published",
            closingDate = "2026-11-10T10:00:00Z", sealed = true, requiredDocuments = sampleRequired,
            bids = sampleBids.map { if (checked) it.copy(isNew = false) else it }.sortedByDescending { it.submittedAt }
        )
    }

    override suspend fun markBidDocumentsChecked(tenderId: String) {
        bidDocumentsChecked += tenderId
    }
}
