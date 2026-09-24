package za.ac.tendertrack.ui.screens.supplier

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
import za.ac.tendertrack.data.ServiceLocator
import za.ac.tendertrack.data.model.*
import za.ac.tendertrack.data.repo.SupplierPortalRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.Dimens

data class BankingUiState(
    val loading: UiState<Unit> = UiState.Loading,
    val form: BankingForm = BankingForm(),
    val errors: Map<BankingField, String> = emptyMap(),
    val formError: String? = null,
    val saving: Boolean = false,
    val doneMessage: String? = null
)

class BankingViewModel(
    private val repository: SupplierPortalRepository = ServiceLocator.supplierPortalRepository
) : ViewModel() {

    private val _state = MutableStateFlow(BankingUiState())
    val state: StateFlow<BankingUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(loading = UiState.Loading) }
        viewModelScope.launch {
            try {
                val banking = repository.banking()
                _state.update {
                    it.copy(
                        loading = UiState.Success(Unit),
                        form = banking?.let { b -> BankingForm.from(b) } ?: BankingForm()
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = UiState.Error(e.supplierMessage())) }
            }
        }
    }

    fun onChange(field: BankingField?, form: BankingForm) = _state.update {
        it.copy(form = form, errors = if (field == null) it.errors else it.errors - field, formError = null)
    }

    fun save() {
        val current = _state.value
        if (current.saving) return
        val errors = current.form.errors()
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors, formError = "Please correct the highlighted fields.") }
            return
        }
        _state.update { it.copy(saving = true, formError = null) }
        viewModelScope.launch {
            try {
                repository.saveBanking(current.form)
                _state.update { it.copy(saving = false, doneMessage = "Banking details saved.") }
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, formError = e.supplierMessage()) }
            }
        }
    }
}

/**
 * Banking details. Confidential: the database lets only this supplier and a
 * finance officer read them, and they are used only after a contract is
 * awarded. The audit trail records that they changed, never what they are.
 */
@Composable
fun BankingScreen(
    onDone: () -> Unit,
    viewModel: BankingViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(state.doneMessage) {
        state.doneMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            onDone()
        }
    }

    AppScaffold(title = "Banking Details", onBack = onDone) {
        when (val result = state.loading) {
            is UiState.Loading -> LoadingState()
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> {
                val f = state.form
                val busy = state.saving

                ScreenHeading(
                    eyebrow = "My company",
                    title = "Banking details",
                    subtitle = "Used only to pay you after a contract is awarded."
                )
                NoteBanner(
                    title = "Kept confidential",
                    text = "Only your company and a finance officer can read these. Procurement officers and " +
                        "auditors cannot, and the account number is hidden from oversight reports.",
                    tone = NoteTone.Info,
                    icon = Icons.Default.Lock
                )

                AppTextField(
                    label = "Bank name", value = f.bankName,
                    onValueChange = { viewModel.onChange(BankingField.BANK, f.copy(bankName = it)) },
                    placeholder = "e.g. First National Bank",
                    leadingIcon = Icons.Default.AccountBalance,
                    error = state.errors[BankingField.BANK], enabled = !busy
                )
                AppTextField(
                    label = "Account name", value = f.accountName,
                    onValueChange = { viewModel.onChange(BankingField.ACCOUNT_NAME, f.copy(accountName = it)) },
                    placeholder = "As it appears on the bank statement",
                    hint = "Must match the company name for payment to go through.",
                    error = state.errors[BankingField.ACCOUNT_NAME], enabled = !busy
                )
                AppTextField(
                    label = "Account number", value = f.accountNumber,
                    onValueChange = { viewModel.onChange(BankingField.ACCOUNT_NUMBER, f.copy(accountNumber = it.trim())) },
                    placeholder = "Digits only", keyboardType = KeyboardType.Number,
                    error = state.errors[BankingField.ACCOUNT_NUMBER], enabled = !busy
                )
                AppTextField(
                    label = "Branch code", value = f.branchCode,
                    onValueChange = { viewModel.onChange(BankingField.BRANCH_CODE, f.copy(branchCode = it.trim())) },
                    placeholder = "6 digits, e.g. 250655", keyboardType = KeyboardType.Number,
                    error = state.errors[BankingField.BRANCH_CODE], enabled = !busy
                )
                AppTextField(
                    label = "Link to proof of banking (optional)", value = f.proofUrl,
                    onValueChange = { viewModel.onChange(null, f.copy(proofUrl = it.trim())) },
                    placeholder = "A link to a bank-stamped letter",
                    hint = "Uploading files comes with the documents module; a link works for now.",
                    enabled = !busy
                )

                state.formError?.let { NoteBanner(text = it, tone = NoteTone.Danger, icon = Icons.Default.Warning) }
                Spacer(Modifier.height(Dimens.SpaceSm))
                PrimaryButton(text = "Save banking details", icon = Icons.Default.Check, loading = busy, onClick = viewModel::save)
                SecondaryButton(text = "Cancel", enabled = !busy, onClick = onDone)
            }
        }
    }
}
