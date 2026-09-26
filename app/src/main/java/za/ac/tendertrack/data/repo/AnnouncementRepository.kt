package za.ac.tendertrack.data.repo

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import za.ac.tendertrack.data.SupabaseModule
import za.ac.tendertrack.data.model.Announcement

/**
 * Announcements published by an administrator in the admin portal.
 * Read-only in the app: they are written only in the portal.
 */
interface AnnouncementRepository {
    /** The live notices for any of these audiences, newest first. */
    suspend fun liveFor(audiences: Set<String>): List<Announcement>
}

class SupabaseAnnouncementRepository(private val client: SupabaseClient) : AnnouncementRepository {

    override suspend fun liveFor(audiences: Set<String>): List<Announcement> = withContext(Dispatchers.IO) {
        // The security rule (announcement_visible in admin_portal.sql) already
        // returns only published notices inside their date range, and only the
        // ones this person may see. The audience filter picks the ones meant
        // for this screen.
        client.from(SupabaseModule.Table.ANNOUNCEMENTS)
            .select {
                filter { isIn("audience", audiences.toList()) }
                order("starts_at", Order.DESCENDING)
                limit(3)
            }
            .decodeList<Announcement>()
    }
}

/** Offline stand-in: one notice for everyone, so the banner can be seen without Supabase. */
class SampleAnnouncementRepository : AnnouncementRepository {

    private val notices = listOf(
        Announcement(
            id = "ann-sample-1",
            title = "Notices from the administrator appear here",
            body = "With Supabase connected, these come from the TenderTrack admin portal.",
            audience = "everyone",
            severity = "info"
        )
    )

    override suspend fun liveFor(audiences: Set<String>): List<Announcement> =
        notices.filter { it.audience in audiences }
}
