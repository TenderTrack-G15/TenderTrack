package za.ac.tendertrack.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A notice written by an administrator in the admin portal (the website on the
 * administrator's computer) and shown as a banner in the app.
 * Table: announcements — see supabase/admin_portal.sql, section 8.
 *
 * The database decides who may read which notice, so the app only ever
 * receives the live notices meant for the signed-in person.
 */
@Serializable
data class Announcement(
    val id: String,
    val title: String,
    val body: String = "",
    /** "everyone", "public", "suppliers" or "staff". */
    val audience: String = "everyone",
    /** "info", "warning" or "critical". */
    val severity: String = "info",
    @SerialName("starts_at") val startsAt: String? = null,
    @SerialName("ends_at") val endsAt: String? = null
)
