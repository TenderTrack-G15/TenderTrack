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

        // Public / citizen screens -------------------------------------------
        const val DELIVERABLES = "deliverables"
        /** Redacted view of payments: no invoice numbers, no officials' names. */
        const val PAYMENTS_PUBLIC = "payments_public"
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
    }
}
