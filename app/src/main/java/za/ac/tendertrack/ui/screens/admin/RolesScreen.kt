package za.ac.tendertrack.ui.screens.admin

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import za.ac.tendertrack.core.UiState
import za.ac.tendertrack.data.ServiceLocator
import za.ac.tendertrack.data.model.AdminAccount
import za.ac.tendertrack.data.model.UserRole
import za.ac.tendertrack.data.repo.AdminRepository
import za.ac.tendertrack.ui.components.*

class RolesViewModel(
    private val repository: AdminRepository = ServiceLocator.adminRepository
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<AdminAccount>>>(UiState.Loading)
    val state: StateFlow<UiState<List<AdminAccount>>> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = try {
                UiState.Success(repository.accounts())
            } catch (e: Exception) {
                UiState.Error(e.adminMessage())
            }
        }
    }
}

/**
 * Roles and Permissions — a summary of all seven roles (Deliverable 3,
 * section 5.7): what each may and may not do, and how many active accounts
 * hold it. Roles themselves are assigned on each account's screen.
 */
@Composable
fun RolesScreen(
    onBack: () -> Unit,
    viewModel: RolesViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    AppScaffold(title = "Roles and Permissions", onBack = onBack) {
        ScreenHeading(
            eyebrow = "FR16",
            title = "Seven roles",
            subtitle = "Each account holds one role. The database checks it on every request."
        )
        when (val result = state) {
            is UiState.Loading -> LoadingState()
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> {
                UserRole.entries.forEach { role ->
                    val count = result.data.count { it.role == role && !it.suspended }
                    SectionHeader(
                        if (role == UserRole.PUBLIC) "${role.displayName} · no account"
                        else "${role.displayName} · $count active"
                    )
                    PermissionsCard(role)
                }
            }
        }
    }
}
