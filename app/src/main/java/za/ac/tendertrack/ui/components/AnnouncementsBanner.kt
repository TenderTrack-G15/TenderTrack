package za.ac.tendertrack.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import za.ac.tendertrack.data.ServiceLocator
import za.ac.tendertrack.data.model.Announcement
import za.ac.tendertrack.data.repo.AnnouncementRepository
import za.ac.tendertrack.ui.theme.Dimens
import za.ac.tendertrack.ui.viewModelFactory

/**
 * Who is looking at the banner. Each screen shows the notices for its own
 * group plus the ones for everyone.
 */
enum class AnnouncementViewer(val audiences: Set<String>) {
    /** Welcome screen: anyone, signed in or not. */
    PUBLIC(setOf("everyone", "public")),
    SUPPLIER(setOf("everyone", "suppliers")),
    /** Procurement officers and auditors. */
    STAFF(setOf("everyone", "staff"))
}

class AnnouncementsViewModel(
    private val viewer: AnnouncementViewer,
    private val repository: AnnouncementRepository = ServiceLocator.announcementRepository
) : ViewModel() {

    private val _notices = MutableStateFlow<List<Announcement>>(emptyList())
    val notices: StateFlow<List<Announcement>> = _notices.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _notices.value = try {
                repository.liveFor(viewer.audiences)
            } catch (e: Exception) {
                // A notice is never worth an error message on someone's home screen.
                emptyList()
            }
        }
    }
}

/**
 * The administrator's notices, as banners in the app's own style (NoteBanner).
 * Shows nothing at all when there are none, so it can sit at the top of any
 * screen:
 *
 *     AnnouncementsBanner(AnnouncementViewer.STAFF)
 */
@Composable
fun AnnouncementsBanner(viewer: AnnouncementViewer, modifier: Modifier = Modifier) {
    val viewModel: AnnouncementsViewModel = viewModel(
        key = "announcements_${viewer.name}",
        factory = viewModelFactory { AnnouncementsViewModel(viewer) }
    )
    val notices by viewModel.notices.collectAsState()
    if (notices.isEmpty()) return

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)) {
        notices.forEach { notice ->
            NoteBanner(
                title = if (notice.body.isBlank()) null else notice.title,
                text = notice.body.ifBlank { notice.title },
                tone = when (notice.severity) {
                    "critical" -> NoteTone.Danger
                    "warning" -> NoteTone.Warning
                    else -> NoteTone.Info
                },
                icon = if (notice.severity == "info") Icons.Default.Campaign else Icons.Default.Warning
            )
        }
    }
}
