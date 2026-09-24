package za.ac.tendertrack.ui.screens.supplier

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
import za.ac.tendertrack.data.repo.SupplierPortalRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens

data class DocumentsUiState(
    val documents: UiState<List<SupplierDocument>> = UiState.Loading,
    /** The document being recorded, if any. */
    val editing: String? = null,
    val reference: String = "",
    val expiresAt: String = "",
    val fileUrl: String = "",
    val dateError: String? = null,
    val formError: String? = null,
    val action: ActionState = ActionState.Idle
)

class DocumentsViewModel(
    private val repository: SupplierPortalRepository = ServiceLocator.supplierPortalRepository
) : ViewModel() {

    private val _state = MutableStateFlow(DocumentsUiState())
    val state: StateFlow<DocumentsUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(documents = UiState.Loading) }
        viewModelScope.launch {
            val result = try {
                UiState.Success(repository.documents())
            } catch (e: Exception) {
                UiState.Error(e.supplierMessage())
            }
            _state.update { it.copy(documents = result) }
        }
    }

    fun startEditing(doc: SupplierDocument) = _state.update {
        it.copy(editing = doc.name, reference = doc.reference, expiresAt = doc.expiresAt.orEmpty(),
            fileUrl = doc.fileUrl.orEmpty(), dateError = null, formError = null)
    }

    fun cancel() = _state.update { it.copy(editing = null, dateError = null, formError = null) }
    fun onReference(v: String) = _state.update { it.copy(reference = v, formError = null) }
    fun onExpiry(v: String) = _state.update { it.copy(expiresAt = v.trim(), dateError = null, formError = null) }
    fun onUrl(v: String) = _state.update { it.copy(fileUrl = v.trim(), formError = null) }
    fun clearAction() = _state.update { it.copy(action = ActionState.Idle) }

    fun save() {
        val current = _state.value
        val name = current.editing ?: return
        if (current.action is ActionState.Running) return
        if (current.expiresAt.isNotBlank() && !Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(current.expiresAt)) {
            _state.update { it.copy(dateError = "Use the format 2027-03-31.") }
            return
        }

        _state.update { it.copy(action = ActionState.Running) }
        viewModelScope.launch {
            try {
                repository.saveDocument(name, current.reference, current.expiresAt.ifBlank { null },
                    current.fileUrl.ifBlank { null })
                _state.update { it.copy(editing = null, action = ActionState.Succeeded("$name recorded.")) }
                load()
            } catch (e: Exception) {
                _state.update { it.copy(action = ActionState.Idle, formError = e.supplierMessage()) }
            }
        }
    }
}

/**
 * Supporting documents: what each one is, its reference, when it expires and
 * where it can be found. Uploading the files themselves needs Supabase
 * Storage, which is not set up, so a link is recorded instead.
 */
@Composable
fun DocumentsScreen(
    onBack: () -> Unit,
    viewModel: DocumentsViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.action) {
        (state.action as? ActionState.Succeeded)?.let { snackbar.showSnackbar(it.message); viewModel.clearAction() }
    }

    AppScaffold(title = "Supporting Documents", onBack = onBack, snackbarHostState = snackbar) {
        ScreenHeading(
            eyebrow = "My company",
            title = "Supporting documents",
            subtitle = "Officers check these when they verify your registration."
        )
        NoteBanner(
            title = "Files are not uploaded yet",
            text = "Record the reference and expiry date here, and a link if the document is online. " +
                "Uploading files comes with Supabase Storage.",
            tone = NoteTone.Info,
            icon = Icons.Default.Info
        )

        when (val result = state.documents) {
            is UiState.Loading -> LoadingState(message = "Loading documents…")
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> {
                val busy = state.action is ActionState.Running
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.ListGap)) {
                    result.data.forEach { doc ->
                        AppCard {
                            CardHeader(
                                title = doc.name,
                                subtitle = doc.reference.ifBlank { "No reference recorded" },
                                trailing = { StatusBadge(doc.status.displayName, doc.status.tone()) }
                            )
                            Spacer(Modifier.height(10.dp))
                            KeyValueRow(
                                "Expires",
                                doc.expiresAt?.let { Format.date(it) } ?: "—",
                                valueColor = if (doc.status == DocumentStatus.EXPIRED) AppColor.DangerInk else AppColor.Ink,
                                showDivider = false
                            )

                            if (state.editing == doc.name) {
                                Spacer(Modifier.height(Dimens.SpaceSm))
                                AppTextField(
                                    label = "Reference or certificate number",
                                    value = state.reference, onValueChange = viewModel::onReference,
                                    placeholder = "e.g. TCS PIN 7X2K9QL4", enabled = !busy
                                )
                                AppTextField(
                                    label = "Expiry date (optional)",
                                    value = state.expiresAt, onValueChange = viewModel::onExpiry,
                                    placeholder = "2027-03-31", hint = "Year-month-day.",
                                    error = state.dateError, enabled = !busy
                                )
                                AppTextField(
                                    label = "Link (optional)",
                                    value = state.fileUrl, onValueChange = viewModel::onUrl,
                                    placeholder = "A link an officer can open", enabled = !busy
                                )
                                state.formError?.let {
                                    NoteBanner(text = it, tone = NoteTone.Danger, icon = Icons.Default.Warning)
                                }
                                PrimaryButton(text = "Save", icon = Icons.Default.Check, loading = busy,
                                    onClick = viewModel::save)
                                SecondaryButton(text = "Cancel", enabled = !busy, onClick = viewModel::cancel)
                            } else {
                                Spacer(Modifier.height(Dimens.SpaceSm))
                                TextAction(
                                    text = if (doc.status == DocumentStatus.NOT_SUBMITTED) "Record this document"
                                    else "Update",
                                    color = AppColor.InfoInk,
                                    onClick = { viewModel.startEditing(doc) }
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(Dimens.SpaceSm))
                Text(
                    "A document past its expiry date is marked expired and no longer counts towards your registration.",
                    style = AppType.Tiny
                )
            }
        }
    }
}
