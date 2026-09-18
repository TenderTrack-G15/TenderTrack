package za.ac.tendertrack.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import za.ac.tendertrack.core.Format
import za.ac.tendertrack.core.UiState
import za.ac.tendertrack.core.friendlyMessage
import za.ac.tendertrack.data.ServiceLocator
import za.ac.tendertrack.data.model.AppNotification
import za.ac.tendertrack.data.model.NotificationKind
import za.ac.tendertrack.data.repo.NotificationRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens

data class NotificationsUiState(
    val items: UiState<List<AppNotification>> = UiState.Loading,
    val unreadOnly: Boolean = false
) {
    val visible: List<AppNotification>
        get() {
            val all = (items as? UiState.Success)?.data ?: return emptyList()
            return if (unreadOnly) all.filter { !it.read } else all
        }

    val unreadCount: Int
        get() = (items as? UiState.Success)?.data?.count { !it.read } ?: 0
}

class NotificationsViewModel(
    private val repository: NotificationRepository = ServiceLocator.notificationRepository
) : ViewModel() {

    private val _state = MutableStateFlow(NotificationsUiState())
    val state: StateFlow<NotificationsUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(items = UiState.Loading) }
        viewModelScope.launch {
            val result = try {
                UiState.Success(repository.list())
            } catch (e: Exception) {
                UiState.Error(e.friendlyMessage())
            }
            _state.update { it.copy(items = result) }
        }
    }

    fun onUnreadOnly(value: Boolean) = _state.update { it.copy(unreadOnly = value) }

    fun markRead(id: String) {
        viewModelScope.launch {
            repository.markRead(id)
            load()
        }
    }

    fun markAllRead() {
        viewModelScope.launch {
            repository.markAllRead()
            load()
        }
    }
}

/** Notifications — FR15. */
@Composable
fun NotificationsScreen(
    onMenu: () -> Unit,
    onOpenFlags: () -> Unit,
    onOpenSuppliers: () -> Unit,
    onOpenTenders: () -> Unit,
    viewModel: NotificationsViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    AppScaffold(
        title = "Notifications",
        onMenu = onMenu,
        actions = listOf(
            TopBarAction(Icons.Default.DoneAll, "Mark all read") { viewModel.markAllRead() }
        )
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)
        ) {
            FilterChip(
                text = "All",
                selected = !state.unreadOnly,
                onClick = { viewModel.onUnreadOnly(false) }
            )
            FilterChip(
                text = "Unread (${state.unreadCount})",
                selected = state.unreadOnly,
                onClick = { viewModel.onUnreadOnly(true) }
            )
        }

        when (val result = state.items) {
            is UiState.Loading -> LoadingState()
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> {
                val visible = state.visible
                if (visible.isEmpty()) {
                    EmptyState(
                        title = "Nothing to read",
                        message = if (state.unreadOnly) "You have read everything."
                        else "Notifications about flags, deadlines and payments appear here.",
                        icon = Icons.Default.Notifications
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.ListGap)) {
                        visible.forEach { item ->
                            NotificationCard(item) {
                                viewModel.markRead(item.id)
                                when (item.kind) {
                                    NotificationKind.FLAG -> onOpenFlags()
                                    NotificationKind.REGISTRATION -> onOpenSuppliers()
                                    else -> onOpenTenders()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationCard(item: AppNotification, onClick: () -> Unit) {
    AppCard(onClick = onClick, accent = if (item.read) null else item.kind.accent()) {
        Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            AppIcon(item.kind.icon(), tint = item.kind.accent(), size = 18.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = AppType.CardTitle.copy(
                        color = if (item.read) AppColor.InkSoft else AppColor.Ink
                    )
                )
                Spacer(Modifier.height(3.dp))
                Text(item.body, style = AppType.Meta)
                Spacer(Modifier.height(6.dp))
                Text(Format.dateTime(item.createdAt), style = AppType.Tiny)
            }
            if (!item.read) {
                StatusBadge("New", BadgeTone.Info, showDot = false)
            }
        }
    }
}

private fun NotificationKind.icon(): ImageVector = when (this) {
    NotificationKind.FLAG -> Icons.Default.Flag
    NotificationKind.DEADLINE -> Icons.Default.Schedule
    NotificationKind.AWARD_CODE -> Icons.Default.VpnKey
    NotificationKind.REGISTRATION -> Icons.Default.Groups
    NotificationKind.PAYMENT -> Icons.Default.Payments
}

private fun NotificationKind.accent(): Color = when (this) {
    NotificationKind.FLAG -> AppColor.DangerBar
    NotificationKind.DEADLINE -> AppColor.WarnBar
    NotificationKind.AWARD_CODE -> AppColor.InfoInk
    NotificationKind.REGISTRATION -> AppColor.Muted
    NotificationKind.PAYMENT -> AppColor.SuccessBar
}
