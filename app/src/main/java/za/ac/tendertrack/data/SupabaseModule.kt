package za.ac.tendertrack.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.serializer.KotlinXSerializer
import kotlinx.serialization.json.Json
import za.ac.tendertrack.BuildConfig

/**
 * Creates and holds the Supabase client.
 *
 * The URL and anon key come from gradle.properties via BuildConfig. When they
 * are blank the client is not created at all and [isConfigured] is false, which
 * the repositories use to fall back to bundled sample data so the app still
 * runs on a machine that has not been pointed at a project yet.
 */
object SupabaseModule {

    private val url: String = BuildConfig.SUPABASE_URL
    private val anonKey: String = BuildConfig.SUPABASE_ANON_KEY

    val isConfigured: Boolean = url.isNotBlank() && anonKey.isNotBlank()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    val client: SupabaseClient? by lazy {
        if (!isConfigured) return@lazy null
        createSupabaseClient(supabaseUrl = url, supabaseKey = anonKey) {
            defaultSerializer = KotlinXSerializer(json)
            install(Auth)
            install(Postgrest)
            install(Realtime)
        }
    }

    /** Table names, in one place so a typo cannot appear in only one repository. */
    object Table {
        const val PROFILES = "profiles"
        const val TENDERS = "tenders"
        const val SUPPLIERS = "suppliers"
        const val PAYMENTS = "payments"
        const val FLAGS = "compliance_flags"
        const val FLAG_NOTES = "flag_notes"
        const val AUDIT = "audit_trail"
        const val NOTIFICATIONS = "notifications"
        const val BUDGETS = "department_budgets"
        const val BIDS = "bids"
        const val EVALUATION_CRITERIA = "evaluation_criteria"
        const val EVALUATION_SCORES = "evaluation_scores"
        const val EVALUATION_RESULTS = "evaluation_results"
        const val AWARD_APPROVALS = "award_approvals"
        const val TENDER_CHANGES = "tender_changes"

        // Public / citizen screens -------------------------------------------
        /** Public view of tenders: estimate hidden until award (hide_estimates.sql). */
        const val TENDERS_PUBLIC = "tenders_public"
        const val DELIVERABLES = "deliverables"
        /** Redacted view of payments: no invoice numbers, no officials' names. */
        const val PAYMENTS_PUBLIC = "payments_public"

        /** Notices written in the admin portal — see supabase/admin_portal.sql */
        const val ANNOUNCEMENTS = "announcements"

        const val TENDER_BOARD = "tender_board"
        const val TENDER_DETAILS = "tender_details"
        const val TENDER_ELIGIBILITY = "tender_eligibility"
        const val TENDER_DOCUMENTS = "tender_documents"
        const val TENDER_UPDATES = "tender_updates"
        const val SUPPLIER_DOCUMENTS = "supplier_documents"
        const val SUPPLIER_BANKING = "supplier_banking"
    }

    /** Postgres functions called with rpc(). */
    object Rpc {
        /** Issues the 10-digit award code server-side — see supabase/schema.sql. */
        const val AWARD_TENDER = "award_tender"
        const val ADVANCE_TENDER_STATUS = "advance_tender_status"
        const val RECORD_PAYMENT = "record_payment"

        // Citizen reports — see supabase/public_access.sql -------------------
        const val SUBMIT_CITIZEN_REPORT = "submit_citizen_report"
        const val CITIZEN_REPORT_STATUS = "citizen_report_status"
        const val ADD_CITIZEN_REPORT_INFORMATION = "add_citizen_report_information"
        const val WITHDRAW_CITIZEN_REPORT = "withdraw_citizen_report"

        // Supplier sign-up — see supabase/supplier_accounts.sql ----------------
        const val SAVE_SUPPLIER_PROFILE = "save_supplier_profile"
        const val SAVE_SUPPLIER_BANKING = "save_supplier_banking"
        const val SAVE_SUPPLIER_DOCUMENT = "save_supplier_document"
        const val RESUBMIT_SUPPLIER_REGISTRATION = "resubmit_supplier_registration"
        const val SUPPLIER_REGISTRATION_PROBLEM = "supplier_registration_problem"

    }
}
