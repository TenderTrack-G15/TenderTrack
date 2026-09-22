package za.ac.tendertrack.ui.screens.admin

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.SnackbarHostState
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
import za.ac.tendertrack.core.ActionState
import za.ac.tendertrack.core.Format
import za.ac.tendertrack.core.UiState
import za.ac.tendertrack.data.ServiceLocator
import za.ac.tendertrack.data.model.*
import za.ac.tendertrack.data.repo.AdminRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens
import za.ac.tendertrack.ui.viewModelFactory

// ---------------------------------------------------------------------------
// State + ViewModel
// ---------------------------------------------------------------------------

/** Which confirmation, if any, is on screen. */
enum class AccountConfirm { NONE, ROLE, SUSPEND, REINSTATE }

data class AccountDetailUiState(
    val account: UiState<AdminAccount> = UiState.Loading,
    val role: UserRole? = null,
    val department: String = "",
    val departmentError: String? = null,
    val suspendReason: String = "",
    val reasonError: String? = null,
    val confirm: AccountConfirm = AccountConfirm.NONE,
    val action: ActionState = ActionState.Idle
) {
    private val loaded: AdminAccount? get() = (account as? UiState.Success)?.data

    /** True when the role or department on screen differs from what is saved. */
    val roleChanged: Boolean
        get() = loaded?.let { it.role != role || (it.department ?: "") != department.trim() } ?: false
}

class AccountDetailViewModel(
    private val accountId: String,
    private val repository: AdminRepository = ServiceLocator.adminRepository
) : ViewModel() {

    private val _state = MutableStateFlow(AccountDetailUiState())
    val state: StateFlow<AccountDetailUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(account = UiState.Loading) }
        viewModelScope.launch {
            try {
                val account = repository.accounts().firstOrNull { it.id == accountId }
                if (account == null) {
                    _state.update { it.copy(account = UiState.Error("This account no longer exists.")) }
                } else {
                    show(account)
                }
            } catch (e: Exception) {
                _state.update { it.copy(account = UiState.Error(e.adminMessage())) }
            }
        }
    }

    private fun show(account: AdminAccount) = _state.update {
        it.copy(
            account = UiState.Success(account),
            role = account.role,
            department = account.department.orEmpty(),
            departmentError = null
        )
    }

    fun onRole(value: UserRole) = _state.update { it.copy(role = value, departmentError = null) }
    fun onDepartment(value: String) = _state.update { it.copy(department = value, departmentError = null) }
    fun onReason(value: String) = _state.update { it.copy(suspendReason = value, reasonError = null) }
    fun dismissConfirm() = _state.update { it.copy(confirm = AccountConfirm.NONE) }
    fun clearAction() = _state.update { it.copy(action = ActionState.Idle) }

    // -- UPDATE: role and department (FR16) ------------------------------------

    fun requestRoleChange() {
        val current = _state.value
        val role = current.role ?: return
        if (role.needsDepartment && current.department.isBlank()) {
            _state.update {
                it.copy(departmentError = "A ${role.displayName.lowercase()} must be assigned to a department.")
            }
            return
        }
        _state.update { it.copy(confirm = AccountConfirm.ROLE) }
    }

    fun saveRole() {
        val current = _state.value
        val role = current.role ?: return
        _state.update { it.copy(confirm = AccountConfirm.NONE, action = ActionState.Running) }
        viewModelScope.launch {
            try {
                val updated = repository.setRole(accountId, role, current.department.trim().ifBlank { null })
                show(updated)
                _state.update { it.copy(action = ActionState.Succeeded("Role changed to ${updated.role.displayName}.")) }
            } catch (e: Exception) {
                _state.update { it.copy(action = ActionState.Failed(e.adminMessage())) }
            }
        }
    }

    // -- UPDATE / soft DELETE: suspend and reinstate ----------------------------

    fun requestSuspend() {
        if (_state.value.suspendReason.trim().length < MIN_REASON) {
            _state.update { it.copy(reasonError = "Give a reason of at least $MIN_REASON characters.") }
            return
        }
        _state.update { it.copy(confirm = AccountConfirm.SUSPEND) }
    }

    fun requestReinstate() = _state.update { it.copy(confirm = AccountConfirm.REINSTATE) }

    fun setSuspended(suspended: Boolean) {
        val reason = _state.value.suspendReason.trim()
        _state.update { it.copy(confirm = AccountConfirm.NONE, action = ActionState.Running) }
        viewModelScope.launch {
            try {
                val updated = repository.setSuspended(accountId, suspended, if (suspended) reason else null)
                show(updated)
                _state.update {
                    it.copy(
                        suspendReason = "",
                        action = ActionState.Succeeded(
                            if (suspended) "Account suspended. It has lost all access."
                            else "Account reinstated."
                        )
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(action = ActionState.Failed(e.adminMessage())) }
            }
        }
    }

    companion object {
        const val MIN_REASON = 5
    }
}

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

/**
 * One account: assign its role and department scope (Deliverable 3, Roles and
 * permissions), and suspend or reinstate it. Every change is confirmed first
 * and is written to the audit trail by the database.
 */
@Composable
fun AccountDetailScreen(
    accountId: String,
    currentAdminId: String?,
    onBack: () -> Unit
) {
    val viewModel: AccountDetailViewModel = viewModel(
        key = "admin_account_$accountId",
        factory = viewModelFactory { AccountDetailViewModel(accountId) }
    )
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.action) {
        when (val a = state.action) {
            is ActionState.Succeeded -> { snackbar.showSnackbar(a.message); viewModel.clearAction() }
            is ActionState.Failed -> { snackbar.showSnackbar(a.message); viewModel.clearAction() }
            else -> Unit
        }
    }

    val account = (state.account as? UiState.Success)?.data
    when (state.confirm) {
        AccountConfirm.ROLE -> AdminConfirmDialog(
            title = "Change this role?",
            message = "${account?.fullName} will become ${state.role?.displayName}" +
                (state.department.trim().takeIf { it.isNotBlank() }?.let { " for $it" } ?: "") +
                ". Their permissions change on their next request.",
            confirmText = "Change role",
            onConfirm = viewModel::saveRole,
            onDismiss = viewModel::dismissConfirm
        )
        AccountConfirm.SUSPEND -> AdminConfirmDialog(
            title = "Suspend this account?",
            message = "${account?.fullName} will lose all access immediately. The account and its history are kept.",
            confirmText = "Suspend",
            danger = true,
            onConfirm = { viewModel.setSuspended(true) },
            onDismiss = viewModel::dismissConfirm
        )
        AccountConfirm.REINSTATE -> AdminConfirmDialog(
            title = "Reinstate this account?",
            message = "${account?.fullName} will get back the access of their role.",
            confirmText = "Reinstate",
            onConfirm = { viewModel.setSuspended(false) },
            onDismiss = viewModel::dismissConfirm
        )
        AccountConfirm.NONE -> Unit
    }

    AppScaffold(title = "Account", onBack = onBack, snackbarHostState = snackbar) {
        when (val result = state.account) {
            is UiState.Loading -> LoadingState()
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> AccountBody(
                state = state,
                account = result.data,
                isSelf = result.data.id == currentAdminId,
                viewModel = viewModel
            )
        }
    }
}

@Composable
private fun AccountBody(
    state: AccountDetailUiState,
    account: AdminAccount,
    isSelf: Boolean,
    viewModel: AccountDetailViewModel
) {
    val busy = state.action is ActionState.Running

    ScreenHeading(
        eyebrow = account.role.displayName,
        title = account.fullName,
        subtitle = account.email,
        trailing = { StatusBadge(account.statusLabel(), account.statusTone()) }
    )

    AppCard {
        CardHeader(title = "Account")
        Spacer(Modifier.height(10.dp))
        KeyValueRow("Role", account.role.displayName)
        KeyValueRow("Department", account.department ?: "—")
        KeyValueRow("Created", Format.date(account.createdAt))
        KeyValueRow(
            "Last sign-in",
            account.lastSignInAt?.let { Format.dateTime(it) } ?: "Never",
            showDivider = account.suspended
        )
        if (account.suspended) {
            KeyValueRow("Suspended because", account.suspendedReason ?: "—",
                valueColor = AppColor.DangerInk, showDivider = false)
        }
    }

    if (isSelf) {
        NoteBanner(
            title = "This is your account",
            text = "You cannot change your own role or suspend yourself. Another administrator must do it, " +
                "so there is always a second person behind a change to an administrator.",
            tone = NoteTone.Info,
            icon = Icons.Default.Shield
        )
        return
    }

    // -- Role and department -----------------------------------------------------
    SectionHeader("Role and department")
    AppDropdownField(
        label = "Role",
        selected = state.role,
        options = assignableRoles,
        optionLabel = { it.displayName },
        onSelect = viewModel::onRole,
        hint = state.role?.permissions()?.summary
    )
    AppTextField(
        label = if (state.role?.needsDepartment == true) "Department" else "Department (optional)",
        value = state.department,
        onValueChange = viewModel::onDepartment,
        placeholder = "e.g. Gauteng Dept of e-Government",
        hint = "Procurement officers, finance officers and the evaluation committee act for one department.",
        error = state.departmentError,
        enabled = !busy
    )
    state.role?.let { PermissionsCard(it) }
    PrimaryButton(
        text = "Save role",
        icon = Icons.Default.Check,
        enabled = state.roleChanged && !busy,
        loading = busy,
        onClick = viewModel::requestRoleChange
    )

    // -- Status --------------------------------------------------------------------
    SectionHeader("Account status")
    if (account.suspended) {
        Text("Reinstating restores the access of the account's role.", style = AppType.Meta)
        SecondaryButton(
            text = "Reinstate account",
            icon = Icons.Default.LockOpen,
            enabled = !busy,
            onClick = viewModel::requestReinstate
        )
    } else {
        Text(
            "Suspending removes all access at once but keeps the account and its history (FR3).",
            style = AppType.Meta
        )
        AppTextField(
            label = "Reason for suspension",
            value = state.suspendReason,
            onValueChange = viewModel::onReason,
            placeholder = "e.g. Left the department",
            hint = "Recorded in the audit trail.",
            error = state.reasonError,
            enabled = !busy
        )
        SecondaryButton(
            text = "Suspend account",
            icon = Icons.Default.Block,
            danger = true,
            enabled = !busy,
            onClick = viewModel::requestSuspend
        )
    }
}

/** What a role grants and denies, shown while choosing one. */
@Composable
fun PermissionsCard(role: UserRole) {
    val permissions = role.permissions()
    AppCard(background = AppColor.SurfaceMuted) {
        CardHeader(title = "${role.displayName} can", subtitle = permissions.summary)
        Spacer(Modifier.height(Dimens.SpaceSm))
        permissions.grants.forEach { Text("✓  $it", style = AppType.Body) }
        if (permissions.denies.isNotEmpty()) {
            Spacer(Modifier.height(Dimens.SpaceSm))
            Text("Cannot", style = AppType.Label)
            permissions.denies.forEach { Text("✕  $it", style = AppType.Body.copy(color = AppColor.Muted)) }
        }
    }
}
