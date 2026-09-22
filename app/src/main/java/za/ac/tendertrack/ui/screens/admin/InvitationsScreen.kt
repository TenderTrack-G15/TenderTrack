package za.ac.tendertrack.ui.screens.admin

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
import za.ac.tendertrack.data.ServiceLocator
import za.ac.tendertrack.data.model.StaffInvitation
import za.ac.tendertrack.data.repo.AdminRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.Dimens

enum class InvitationFilter(val label: String) { PENDING("Pending"), ACCEPTED("Accepted"), ALL("All") }

data class InvitationsUiState(
    val invitations: UiState<List<StaffInvitation>> = UiState.Loading,
    val filter: InvitationFilter = InvitationFilter.PENDING
) {
    val visible: List<StaffInvitation>
        get() = ((invitations as? UiState.Success)?.data ?: emptyList()).filter {
            when (filter) {
                InvitationFilter.PENDING -> !it.accepted
                InvitationFilter.ACCEPTED -> it.accepted
                InvitationFilter.ALL -> true
            }
        }
}

class InvitationsViewModel(
    private val repository: AdminRepository = ServiceLocator.adminRepository
) : ViewModel() {

    private val _state = MutableStateFlow(InvitationsUiState())
    val state: StateFlow<InvitationsUiState> = _state.asStateFlow()

    fun load() {
        _state.update { it.copy(invitations = UiState.Loading) }
        viewModelScope.launch {
            val result = try {
                UiState.Success(repository.invitations())
            } catch (e: Exception) {
                UiState.Error(e.adminMessage())
            }
            _state.update { it.copy(invitations = result) }
        }
    }

    fun onFilter(value: InvitationFilter) = _state.update { it.copy(filter = value) }
}

/**
 * Staff invitations — how accounts are created (Deliverable 3: "supports
 * creating ... accounts"). An administrator invites a person with a role; when
 * their login is created with the same email, the role is applied
 * automatically. Pending invitations can be edited or revoked.
 */
@Composable
fun InvitationsScreen(
    onBack: () -> Unit,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    viewModel: InvitationsViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    // Reloads each time the list comes into view, e.g. after saving or revoking.
    LaunchedEffect(Unit) { viewModel.load() }

    AppScaffold(title = "Staff Invitations", onBack = onBack) {
        ScreenHeading(
            eyebrow = "User accounts",
            title = "Invite staff",
            subtitle = "Choose the role before the person's login exists."
        )
        PrimaryButton(text = "New invitation", icon = Icons.Default.PersonAdd, onClick = onNew)
        NoteBanner(
            title = "How it works",
            text = "After inviting someone, create their login in Supabase (Authentication → Users → " +
                "Add user) with the same email. Their role is applied automatically. For security, " +
                "logins cannot be created from inside the app.",
            tone = NoteTone.Info,
            icon = Icons.Default.Info
        )

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)
        ) {
            InvitationFilter.entries.forEach { option ->
                FilterChip(
                    text = option.label,
                    selected = state.filter == option,
                    onClick = { viewModel.onFilter(option) }
                )
            }
        }

        when (val result = state.invitations) {
            is UiState.Loading -> LoadingState()
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> {
                val visible = state.visible
                if (visible.isEmpty()) {
                    EmptyState(
                        title = "No invitations",
                        message = if (state.filter == InvitationFilter.PENDING) "Nobody is waiting to join."
                        else "Nothing to show for this filter.",
                        icon = Icons.Default.MailOutline
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.ListGap)) {
                        visible.forEach { invite ->
                            InvitationCard(invite, onClick = if (invite.accepted) null else { { onOpen(invite.id) } })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InvitationCard(invite: StaffInvitation, onClick: (() -> Unit)?) {
    AppCard(onClick = onClick) {
        CardHeader(
            title = invite.fullName,
            subtitle = invite.email,
            trailing = {
                StatusBadge(
                    if (invite.accepted) "Accepted" else "Pending",
                    if (invite.accepted) BadgeTone.Success else BadgeTone.Warning
                )
            }
        )
        Spacer(Modifier.height(10.dp))
        KeyValueRow("Role", invite.role.displayName)
        KeyValueRow("Department", invite.department ?: "—")
        KeyValueRow("Invited by", "${invite.invitedBy} · ${Format.date(invite.createdAt)}", showDivider = invite.accepted)
        if (invite.accepted) {
            KeyValueRow("Accepted", Format.dateTime(invite.acceptedAt), showDivider = false)
        }
        if (!invite.accepted) {
            Spacer(Modifier.height(Dimens.SpaceXs))
            Text("Tap to edit or revoke", style = za.ac.tendertrack.ui.theme.AppType.Meta)
        }
    }
}
