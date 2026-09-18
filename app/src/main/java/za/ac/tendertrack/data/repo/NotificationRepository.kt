package za.ac.tendertrack.data.repo

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import za.ac.tendertrack.data.SupabaseModule
import za.ac.tendertrack.data.model.AppNotification
import za.ac.tendertrack.data.sample.SampleData

/** Notifications for the signed-in officer — FR15. */
interface NotificationRepository {
    suspend fun list(): List<AppNotification>
    suspend fun markRead(id: String)
    suspend fun markAllRead()
}

class SupabaseNotificationRepository(private val client: SupabaseClient) : NotificationRepository {

    override suspend fun list(): List<AppNotification> = withContext(Dispatchers.IO) {
        client.from(SupabaseModule.Table.NOTIFICATIONS)
            .select { order("created_at", Order.DESCENDING) }
            .decodeList()
    }

    override suspend fun markRead(id: String) = withContext(Dispatchers.IO) {
        client.from(SupabaseModule.Table.NOTIFICATIONS)
            .update({ set("read", true) }) { filter { eq("id", id) } }
        Unit
    }

    override suspend fun markAllRead() = withContext(Dispatchers.IO) {
        client.from(SupabaseModule.Table.NOTIFICATIONS)
            .update({ set("read", true) }) { filter { eq("read", false) } }
        Unit
    }
}

class SampleNotificationRepository : NotificationRepository {

    override suspend fun list(): List<AppNotification> =
        SampleData.notifications.sortedByDescending { it.createdAt }

    override suspend fun markRead(id: String) {
        val index = SampleData.notifications.indexOfFirst { it.id == id }
        if (index >= 0) {
            SampleData.notifications[index] = SampleData.notifications[index].copy(read = true)
        }
    }

    override suspend fun markAllRead() {
        for (i in SampleData.notifications.indices) {
            SampleData.notifications[i] = SampleData.notifications[i].copy(read = true)
        }
    }
}
