package za.ac.tendertrack.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Contract phases with photo proof (supabase/phase_proof.sql).
 *
 * Every awarded contract has the same five phases, each worth a share of the
 * award. A phase is signed off in four steps, in this order:
 *   1. the supplier photographs the work (camera only, in the app);
 *   2. an auditor visits the site and takes their own photo;
 *   3. the procurement officer compares the two photos and verifies the phase,
 *      which opens the next phase for the supplier;
 *   4. the procurement officer marks "Payment can be made" for the phase.
 * The database enforces the order; the app only shows where each phase is.
 */

/** Where a phase stands. The database works it out (phase_state in phase_proof.sql). */
@Serializable
enum class PhaseState {
    @SerialName("to_do") TO_DO,
    @SerialName("returned") RETURNED,
    @SerialName("awaiting_site_check") AWAITING_SITE_CHECK,
    @SerialName("awaiting_officer") AWAITING_OFFICER,
    @SerialName("verified") VERIFIED,
    @SerialName("payment_approved") PAYMENT_APPROVED;

    /** Wording for the supplier doing the work. */
    val supplierLabel: String get() = when (this) {
        TO_DO -> "Photo needed"
        RETURNED -> "Returned: new photo needed"
        AWAITING_SITE_CHECK -> "Waiting for the site check"
        AWAITING_OFFICER -> "Being checked by the department"
        VERIFIED -> "Verified"
        PAYMENT_APPROVED -> "Verified · payment approved"
    }

    /** Wording for the procurement officer and the auditor. */
    val officerLabel: String get() = when (this) {
        TO_DO -> "Waiting for the supplier's photo"
        RETURNED -> "Returned to the supplier"
        AWAITING_SITE_CHECK -> "Waiting for the auditor"
        AWAITING_OFFICER -> "Ready to compare photos"
        VERIFIED -> "Verified · approve payment"
        PAYMENT_APPROVED -> "Payment can be made"
    }

    val isDone: Boolean get() = this == VERIFIED || this == PAYMENT_APPROVED
}

/**
 * One phase of a contract, as phase_card() returns it. The supplier's own view
 * (supplier_contract_phases) leaves out the auditor's fields, so they default.
 */
@Serializable
data class ContractPhase(
    val id: String,
    @SerialName("tender_id") val tenderId: String,
    @SerialName("phase_no") val phaseNo: Int = 0,
    @SerialName("phase_name") val phaseName: String,
    @SerialName("phase_share") val phaseShare: Double? = null,
    @SerialName("phase_value") val phaseValue: Double = 0.0,
    @SerialName("target_date") val targetDate: String? = null,
    val guidance: String = "",
    val state: PhaseState = PhaseState.TO_DO,
    /** True until every earlier phase is verified. */
    val locked: Boolean = false,
    @SerialName("returned_reason") val returnedReason: String = "",
    @SerialName("supplier_photo_id") val supplierPhotoId: String? = null,
    @SerialName("supplier_photo_at") val supplierPhotoAt: String? = null,
    @SerialName("site_photo_id") val sitePhotoId: String? = null,
    @SerialName("site_checked_at") val siteCheckedAt: String? = null,
    @SerialName("site_checked_by_name") val siteCheckedByName: String? = null,
    @SerialName("site_note") val siteNote: String = "",
    @SerialName("verified_at") val verifiedAt: String? = null,
    @SerialName("payment_approved_at") val paymentApprovedAt: String? = null,
    @SerialName("reference_number") val referenceNumber: String = "",
    val title: String = "",
    val department: String = "",
    @SerialName("supplier_name") val supplierName: String = ""
) {
    /** "Phase 2 of 5 · Execution" */
    val heading: String get() = "Phase $phaseNo of $PHASE_COUNT · $phaseName"

    /** The supplier may take (or retake) the photo for this phase now. */
    val canTakePhoto: Boolean
        get() = !locked && (state == PhaseState.TO_DO || state == PhaseState.RETURNED)

    companion object {
        const val PHASE_COUNT = 5
    }
}

/** One photo, from phase_photo(). [photo] is the JPEG, base64-encoded. */
@Serializable
data class PhasePhoto(
    val id: String,
    /** "supplier" or "auditor". */
    val kind: String,
    val mime: String = "image/jpeg",
    val photo: String,
    @SerialName("taken_at") val takenAt: String? = null,
    @SerialName("taken_by_name") val takenByName: String = "",
    val note: String = "",
    val superseded: Boolean = false
)

/** The auditor's list: phases waiting for a site photo, and the latest site checks. */
@Serializable
data class SiteCheckQueue(
    val waiting: List<ContractPhase> = emptyList(),
    val done: List<ContractPhase> = emptyList()
)

/** A phase the officer has marked "Payment can be made", for Record Payment. */
@Serializable
data class PayablePhase(
    val id: String,
    @SerialName("phase_no") val phaseNo: Int = 0,
    @SerialName("phase_name") val phaseName: String,
    @SerialName("phase_value") val phaseValue: Double = 0.0,
    @SerialName("payment_approved_at") val paymentApprovedAt: String? = null,
    /** Already paid against this phase. */
    val paid: Double = 0.0
) {
    val outstanding: Double get() = (phaseValue - paid).coerceAtLeast(0.0)
}

// ---------------------------------------------------------------------------
// Alerts on the procurement officer's tiles (officer_alerts in phase_proof.sql)
// ---------------------------------------------------------------------------

/** One alert on a tender's tile, e.g. "Phase 2: compare the photos". */
@Serializable
data class TenderAlert(
    /**
     * phase_check, payment, site_check, closing_passed, bid_documents, award_locked,
     * award_unclaimed, award_claimed, flags, bids
     */
    val kind: String,
    /** danger, warning, info, success or neutral */
    val tone: String = "neutral",
    val label: String,
    @SerialName("deliverable_id") val deliverableId: String? = null
) {
    /** Alerts that open Phase checks when tapped. */
    val isPhaseAlert: Boolean get() = kind == "phase_check" || kind == "payment" || kind == "site_check"

    /** A supplier submitted (or changed) a bid with its documents on the eTender portal. */
    val isBidDocumentsAlert: Boolean get() = kind == BID_DOCUMENTS

    companion object {
        const val BID_DOCUMENTS = "bid_documents"
    }
}

@Serializable
data class TenderAlerts(
    @SerialName("tender_id") val tenderId: String,
    @SerialName("reference_number") val referenceNumber: String = "",
    val status: String = "",
    val alerts: List<TenderAlert> = emptyList()
)

/** A company that saved documents since an officer last checked its registration. */
@Serializable
data class NewDocumentSupplier(
    @SerialName("supplier_id") val supplierId: String,
    @SerialName("company_name") val companyName: String = "",
    @SerialName("new_documents") val newDocuments: Int = 0
)

@Serializable
data class AlertTotals(
    @SerialName("phase_checks") val phaseChecks: Int = 0,
    @SerialName("payments_to_approve") val paymentsToApprove: Int = 0,
    @SerialName("site_checks_waiting") val siteChecksWaiting: Int = 0,
    @SerialName("new_documents") val newDocuments: Int = 0,
    @SerialName("companies_with_new_documents") val companiesWithNewDocuments: Int = 0,
    @SerialName("closing_passed") val closingPassed: Int = 0,
    /** Bids submitted or changed on the eTender portal since an officer last checked them (portal_fixes.sql). */
    @SerialName("new_bids") val newBids: Int = 0,
    @SerialName("new_bid_documents") val newBidDocuments: Int = 0,
    @SerialName("tenders_with_new_bids") val tendersWithNewBids: Int = 0
)

@Serializable
data class OfficerAlerts(
    @SerialName("new_document_suppliers") val newDocumentSuppliers: List<NewDocumentSupplier> = emptyList(),
    val tenders: List<TenderAlerts> = emptyList(),
    val totals: AlertTotals = AlertTotals()
) {
    fun forTender(tenderId: String): List<TenderAlert> =
        tenders.firstOrNull { it.tenderId == tenderId }?.alerts ?: emptyList()

    fun newDocumentsFor(supplierId: String): Int =
        newDocumentSuppliers.firstOrNull { it.supplierId == supplierId }?.newDocuments ?: 0

    /** Phases waiting for the officer: photos to compare plus payments to approve. */
    val officerActions: Int get() = totals.phaseChecks + totals.paymentsToApprove
}

// ---------------------------------------------------------------------------
// Bid documents from the eTender portal (tender_bid_documents in portal_fixes.sql)
// ---------------------------------------------------------------------------

/** One bid and the documents its supplier confirmed with it on the portal. */
@Serializable
data class BidDocumentsEntry(
    val id: String,
    val reference: String = "",
    @SerialName("supplier_name") val supplierName: String = "",
    @SerialName("csd_number") val csdNumber: String? = null,
    @SerialName("supplier_status") val supplierStatus: String? = null,
    @SerialName("submitted_at") val submittedAt: String? = null,
    val status: String = "submitted",
    val documents: List<String> = emptyList(),
    @SerialName("documents_received") val documentsReceived: Int = 0,
    @SerialName("documents_required") val documentsRequired: Int = 0,
    /** Null while bidding is open: prices stay sealed until it closes. */
    @SerialName("bid_value") val bidValue: Double? = null,
    /** Submitted or changed since an officer last marked this tender's bid documents as checked. */
    @SerialName("is_new") val isNew: Boolean = false
)

/** A tender's bids with their documents, for the officer. */
@Serializable
data class TenderBidDocuments(
    @SerialName("tender_id") val tenderId: String,
    @SerialName("reference_number") val referenceNumber: String = "",
    val title: String = "",
    val status: String = "",
    @SerialName("closing_date") val closingDate: String? = null,
    /** True while bidding is open. */
    val sealed: Boolean = true,
    @SerialName("seen_at") val seenAt: String? = null,
    @SerialName("required_documents") val requiredDocuments: List<String> = emptyList(),
    val bids: List<BidDocumentsEntry> = emptyList()
) {
    val newBids: Int get() = bids.count { it.isNew }
}

// ---------------------------------------------------------------------------
// Two-factor sign-in (mfa_status in phase_proof.sql)
// ---------------------------------------------------------------------------

@Serializable
data class TwoFactorStatus(
    /** This account must enter a 6-digit code after its password. */
    val required: Boolean = false,
    /** The code has been entered for this session (or is not needed). */
    val satisfied: Boolean = true,
    val aal: String = "aal1",
    /** The authenticator already set up, if any. */
    @SerialName("verified_factor_id") val verifiedFactorId: String? = null,
    /** Half-finished set-ups from earlier attempts, removed before a new one. */
    @SerialName("unverified_factor_ids") val unverifiedFactorIds: List<String> = emptyList(),
    @SerialName("in_grace") val inGrace: Boolean = false
)

/** What the person needs to add TenderTrack to their authenticator app. */
data class TwoFactorSetUp(
    val factorId: String,
    /** The key to type into the app if the link does not open it. */
    val secret: String,
    /** otpauth://totp/... : opens the authenticator app with everything filled in. */
    val uri: String,
    /** The QR code from Supabase (an SVG picture), to scan with a phone. Empty if there is none. */
    val qrSvg: String = ""
) {
    /** "ABCD EFGH IJKL ..." : easier to read and type. */
    val groupedSecret: String get() = secret.chunked(4).joinToString(" ")
}
