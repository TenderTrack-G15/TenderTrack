package za.ac.tendertrack.ui.screens.admin

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import za.ac.tendertrack.core.UiState
import za.ac.tendertrack.core.Validate
import za.ac.tendertrack.data.ServiceLocator
import za.ac.tendertrack.data.model.*
import za.ac.tendertrack.data.repo.AdminRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.viewModelFactory

data class InvitationFormUiState(
    /** Loading only when editing an existing invitation. */
    val loading: UiState<Unit> = UiState.Success(Unit),
    val email: String = "",
    val fullName: String = "",
    val role: UserRole? = null,
    val department: String = "",
    val emailError: String? = null,
    val nameError: String? = null,
    val roleError: String? = null,
    val departmentError: String? = null,
    val formError: String? = null,
    val saving: Boolean = false,
    val confirmRevoke: Boolean = false,
    /** Set when the form has finished (saved or revoked); the screen then closes. */
    val doneMessage: String? = null
)

class InvitationFormViewModel(
    private val invitationId: String?,
    private val repository: AdminRepository = ServiceLocator.adminRepository
) : ViewModel() {

    private val _state = MutableStateFlow(
        InvitationFormUiState(loading = if (invitationId == null) UiState.Success(Unit) else UiState.Loading)
    )
    val state: StateFlow<InvitationFormUiState> = _state.asStateFlow()

    val isEditing: Boolean get() = invitationId != null

    init { if (invitationId != null) load() }

    fun load() {
        _state.update { it.copy(loading = UiState.Loading) }
        viewModelScope.launch {
            try {
                val invite = repository.invitations().firstOrNull { it.id == invitationId }
                _state.update {
                    if (invite == null) it.copy(loading = UiState.Error("This invitation no longer exists."))
                    else it.copy(
                        loading = UiState.Success(Unit),
                        email = invite.email,
                        fullName = invite.fullName,
                        role = invite.role,
                        department = invite.department.orEmpty()
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = UiState.Error(e.adminMessage())) }
            }
        }
    }

    fun onEmail(value: String) = _state.update { it.copy(email = value.trim(), emailError = null, formError = null) }
    fun onName(value: String) = _state.update { it.copy(fullName = value, nameError = null, formError = null) }
    fun onRole(value: UserRole) = _state.update { it.copy(role = value, roleError = null, departmentError = null, formError = null) }
    fun onDepartment(value: String) = _state.update { it.copy(department = value, departmentError = null, formError = null) }

    // -- CREATE / UPDATE ---------------------------------------------------------

    fun save() {
        val current = _state.value
        if (current.saving) return
        val role = current.role
        val emailError = Validate.email(current.email)
        val nameError = if (current.fullName.trim().length < 2) "Enter the person's full name." else null
        val roleError = if (role == null) "Choose a role." else null
        val departmentError = if (role?.needsDepartment == true && current.department.isBlank())
            "A ${role.displayName.lowercase()} must be assigned to a department." else null

        if (emailError != null || nameError != null || roleError != null || departmentError != null) {
            _state.update {
                it.copy(emailError = emailError, nameError = nameError, roleError = roleError,
                    departmentError = departmentError, formError = "Please correct the highlighted fields.")
            }
            return
        }

        _state.update { it.copy(saving = true, formError = null) }
        viewModelScope.launch {
            try {
                val saved = repository.saveInvitation(
                    InvitationDraft(invitationId, current.email, current.fullName.trim(), role!!,
                        current.department.trim().ifBlank { null })
                )
                _state.update {
                    it.copy(saving = false,
                        doneMessage = if (isEditing) "Invitation updated." else "Invitation sent for ${saved.email}.")
                }
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, formError = e.adminMessage()) }
            }
        }
    }

    // -- DELETE --------------------------------------------------------------------

    fun requestRevoke() = _state.update { it.copy(confirmRevoke = true) }
    fun dismissRevoke() = _state.update { it.copy(confirmRevoke = false) }

    fun revoke() {
        val id = invitationId ?: return
        _state.update { it.copy(confirmRevoke = false, saving = true) }
        viewModelScope.launch {
            try {
                repository.revokeInvitation(id)
                _state.update { it.copy(saving = false, doneMessage = "Invitation revoked.") }
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, formError = e.adminMessage()) }
            }
        }
    }
}

/**
 * New / Edit Invitation — create, update and revoke a staff invitation.
 * Validation mirrors admin_save_invitation() in the database.
 */
@Composable
fun InvitationFormScreen(
    invitationId: String?,
    onDone: () -> Unit
) {
    val viewModel: InvitationFormViewModel = viewModel(
        key = "admin_invitation_${invitationId ?: "new"}",
        factory = viewModelFactory { InvitationFormViewModel(invitationId) }
    )
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    // Close the form straight away. A toast (unlike a snackbar) stays visible
    // after the screen closes, so the confirmation is not lost.
    LaunchedEffect(state.doneMessage) {
        state.doneMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            onDone()
        }
    }

    if (state.confirmRevoke) {
        AdminConfirmDialog(
            title = "Revoke this invitation?",
            message = "${state.fullName} will not get a role when their login is created. " +
                "The revocation is recorded in the audit trail.",
            confirmText = "Revoke",
            danger = true,
            onConfirm = viewModel::revoke,
            onDismiss = viewModel::dismissRevoke
        )
    }

    AppScaffold(
        title = if (viewModel.isEditing) "Edit Invitation" else "New Invitation",
        onBack = onDone
    ) {
        when (val result = state.loading) {
            is UiState.Loading -> LoadingState()
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> {
                ScreenHeading(
                    eyebrow = "Staff invitation",
                    title = if (viewModel.isEditing) state.fullName.ifBlank { "Invitation" } else "Invite a staff member",
                    subtitle = "The role is applied when a login with this email is created."
                )
                AppTextField(
                    label = "Email address",
                    value = state.email,
                    onValueChange = viewModel::onEmail,
                    placeholder = "name@department.gov.za",
                    leadingIcon = Icons.Default.MailOutline,
                    keyboardType = KeyboardType.Email,
                    error = state.emailError,
                    enabled = !state.saving
                )
                AppTextField(
                    label = "Full name",
                    value = state.fullName,
                    onValueChange = viewModel::onName,
                    placeholder = "e.g. Z. Mthembu",
                    leadingIcon = Icons.Default.Person,
                    error = state.nameError,
                    enabled = !state.saving
                )
                AppDropdownField(
                    label = "Role",
                    selected = state.role,
                    options = invitableRoles,
                    optionLabel = { it.displayName },
                    onSelect = viewModel::onRole,
                    placeholder = "Choose a role",
                    hint = state.role?.permissions()?.summary,
                    error = state.roleError
                )
                AppTextField(
                    label = if (state.role?.needsDepartment == true) "Department" else "Department (optional)",
                    value = state.department,
                    onValueChange = viewModel::onDepartment,
                    placeholder = "e.g. Gauteng Dept of e-Government",
                    error = state.departmentError,
                    enabled = !state.saving
                )
                state.role?.let { PermissionsCard(it) }

                state.formError?.let { NoteBanner(text = it, tone = NoteTone.Danger, icon = Icons.Default.Warning) }

                PrimaryButton(
                    text = if (viewModel.isEditing) "Save changes" else "Send invitation",
                    icon = Icons.Default.Check,
                    loading = state.saving,
                    onClick = viewModel::save
                )
                if (viewModel.isEditing) {
                    SecondaryButton(
                        text = "Revoke invitation",
                        icon = Icons.Default.Delete,
                        danger = true,
                        enabled = !state.saving,
                        onClick = viewModel::requestRevoke
                    )
                }
            }
        }
    }
}
