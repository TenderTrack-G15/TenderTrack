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
import za.ac.tendertrack.data.model.AdminAccount
import za.ac.tendertrack.data.model.UserRole
import za.ac.tendertrack.data.model.assignableRoles
import za.ac.tendertrack.data.repo.AdminRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens
import za.ac.tendertrack.ui.viewModelFactory

/** Which accounts the list opens on — set by the dashboard tile that was tapped. */
enum class AccountFilter(val label: String) {
    ALL("All"), ACTIVE("Active"), SUSPENDED("Suspended"), ADMINISTRATORS("Administrators")
}

/** Label for "no role filter" in the dropdown. */
private const val ALL_ROLES = "All roles"

// ---------------------------------------------------------------------------
// State + ViewModel
// ---------------------------------------------------------------------------

data class UserAccountsUiState(
    val accounts: UiState<List<AdminAccount>> = UiState.Loading,
    val query: String = "",
    val filter: AccountFilter = AccountFilter.ALL,
    val role: String = ALL_ROLES
) {
    val visible: List<AdminAccount>
        get() {
            val all = (accounts as? UiState.Success)?.data ?: return emptyList()
            return all.filter { account ->
                val matchesQuery = query.isBlank() ||
                    account.fullName.contains(query, true) ||
                    account.email.contains(query, true) ||
                    (account.department?.contains(query, true) ?: false)
                val matchesFilter = when (filter) {
                    AccountFilter.ALL -> true
                    AccountFilter.ACTIVE -> !account.suspended
                    AccountFilter.SUSPENDED -> account.suspended
                    AccountFilter.ADMINISTRATORS -> account.role == UserRole.ADMINISTRATOR
                }
                matchesQuery && matchesFilter && (role == ALL_ROLES || account.role.displayName == role)
            }
        }
}

class UserAccountsViewModel(
    initialFilter: AccountFilter,
    private val repository: AdminRepository = ServiceLocator.adminRepository
) : ViewModel() {

    private val _state = MutableStateFlow(UserAccountsUiState(filter = initialFilter))
    val state: StateFlow<UserAccountsUiState> = _state.asStateFlow()

    fun load() {
        _state.update { it.copy(accounts = UiState.Loading) }
        viewModelScope.launch {
            val result = try {
                UiState.Success(repository.accounts())
            } catch (e: Exception) {
                UiState.Error(e.adminMessage())
            }
            _state.update { it.copy(accounts = result) }
        }
    }

    fun onQuery(value: String) = _state.update { it.copy(query = value) }
    fun onFilter(value: AccountFilter) = _state.update { it.copy(filter = value) }
    fun onRole(value: String) = _state.update { it.copy(role = value) }
}

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

/**
 * User Accounts — every account with its role, last sign-in and status.
 * Tapping one opens it for role assignment, suspension or reinstatement.
 */
@Composable
fun UserAccountsScreen(
    initialFilter: AccountFilter,
    onBack: () -> Unit,
    onOpenAccount: (String) -> Unit,
    onInvite: () -> Unit
) {
    val viewModel: UserAccountsViewModel = viewModel(
        key = "admin_accounts_${initialFilter.name}",
        factory = viewModelFactory { UserAccountsViewModel(initialFilter) }
    )
    val state by viewModel.state.collectAsState()

    // Runs each time the screen comes into view, including on the way back from
    // an account whose role or status was just changed, so the list is current.
    LaunchedEffect(Unit) { viewModel.load() }

    AppScaffold(
        title = "User Accounts",
        onBack = onBack,
        actions = listOf(TopBarAction(Icons.Default.PersonAdd, "Invite staff") { onInvite() })
    ) {
        SearchField(
            value = state.query,
            onValueChange = viewModel::onQuery,
            placeholder = "Search name, email or department"
        )

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)
        ) {
            AccountFilter.entries.forEach { option ->
                // onClick passed by name: it is not FilterChip's last parameter.
                FilterChip(
                    text = option.label,
                    selected = state.filter == option,
                    onClick = { viewModel.onFilter(option) }
                )
            }
        }

        AppDropdownField(
            label = "Role",
            selected = state.role,
            options = listOf(ALL_ROLES) + assignableRoles.map { it.displayName },
            optionLabel = { it },
            onSelect = viewModel::onRole
        )

        when (val result = state.accounts) {
            is UiState.Loading -> LoadingState(message = "Loading accounts…")
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> {
                val visible = state.visible
                Text("${visible.size} of ${result.data.size} accounts", style = AppType.Meta)
                if (visible.isEmpty()) {
                    EmptyState(
                        title = "No accounts match",
                        message = "Try a different search, or choose All.",
                        icon = Icons.Default.Person
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.ListGap)) {
                        visible.forEach { account -> AccountCard(account) { onOpenAccount(account.id) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountCard(account: AdminAccount, onClick: () -> Unit) {
    AppCard(onClick = onClick) {
        CardHeader(
            title = account.fullName,
            subtitle = account.email,
            trailing = { StatusBadge(account.statusLabel(), account.statusTone()) }
        )
        Spacer(Modifier.height(10.dp))
        KeyValueRow("Role", account.role.displayName)
        KeyValueRow("Department", account.department ?: "—")
        KeyValueRow(
            "Last sign-in",
            account.lastSignInAt?.let { Format.dateTime(it) } ?: "Never",
            valueColor = if (account.lastSignInAt == null) AppColor.Muted else AppColor.Ink,
            showDivider = false
        )
    }
}
