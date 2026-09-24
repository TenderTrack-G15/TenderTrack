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

data class CompanyView(
    val profile: SupplierProfile?,
    val documents: List<SupplierDocument>,
    val banking: BankingDetails?
)

data class MyCompanyUiState(
    val view: UiState<CompanyView> = UiState.Loading,
    val action: ActionState = ActionState.Idle
)

class MyCompanyViewModel(
    private val repository: SupplierPortalRepository = ServiceLocator.supplierPortalRepository
) : ViewModel() {

    private val _state = MutableStateFlow(MyCompanyUiState())
    val state: StateFlow<MyCompanyUiState> = _state.asStateFlow()

    fun load() {
        _state.update { it.copy(view = UiState.Loading) }
        viewModelScope.launch {
            val result = try {
                UiState.Success(
                    CompanyView(
                        profile = repository.myProfile(),
                        documents = runCatching { repository.documents() }.getOrDefault(emptyList()),
                        banking = runCatching { repository.banking() }.getOrNull()
                    )
                )
            } catch (e: Exception) {
                UiState.Error(e.supplierMessage())
            }
            _state.update { it.copy(view = result) }
        }
    }

    fun resubmit() {
        _state.update { it.copy(action = ActionState.Running) }
        viewModelScope.launch {
            try {
                repository.resubmitRegistration()
                _state.update { it.copy(action = ActionState.Succeeded("Registration sent back for review.")) }
                load()
            } catch (e: Exception) {
                _state.update { it.copy(action = ActionState.Failed(e.supplierMessage())) }
            }
        }
    }

    fun clearAction() = _state.update { it.copy(action = ActionState.Idle) }
}

/**
 * My Company — the supplier's registration: company information, contact
 * details, compliance, capabilities, supporting documents and banking.
 */
@Composable
fun MyCompanyScreen(
    onBack: () -> Unit,
    onEditProfile: () -> Unit,
    onOpenBanking: () -> Unit,
    onOpenDocuments: () -> Unit,
    viewModel: MyCompanyViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    // Reloads on the way back from editing, so the screen shows what was saved.
    LaunchedEffect(Unit) { viewModel.load() }

    LaunchedEffect(state.action) {
        when (val a = state.action) {
            is ActionState.Succeeded -> { snackbar.showSnackbar(a.message); viewModel.clearAction() }
            is ActionState.Failed -> { snackbar.showSnackbar(a.message); viewModel.clearAction() }
            else -> Unit
        }
    }

    AppScaffold(title = "My Company", onBack = onBack, snackbarHostState = snackbar) {
        when (val result = state.view) {
            is UiState.Loading -> LoadingState(message = "Loading your registration…")
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> {
                val profile = result.data.profile
                if (profile == null) {
                    EmptyState(
                        title = "No registration",
                        message = "This account has no supplier registration. Sign out and register a company " +
                            "with \"Register an account\".",
                        icon = Icons.Default.Business
                    )
                    return@AppScaffold
                }

                ScreenHeading(
                    eyebrow = profile.reference,
                    title = profile.companyName,
                    subtitle = "Submitted ${Format.date(profile.submittedAt)}",
                    trailing = { StatusBadge(profile.status.supplierLabel(), profile.status.supplierTone()) }
                )

                when (profile.status) {
                    SupplierVerificationStatus.AWAITING_VERIFICATION -> NoteBanner(
                        title = "Under review",
                        text = "A procurement officer is checking your details against CIPC, CSD and SARS records.",
                        tone = NoteTone.Info,
                        icon = Icons.Default.HourglassTop
                    )
                    SupplierVerificationStatus.VERIFIED -> NoteBanner(
                        title = "Verified",
                        text = "Your company is verified and may bid on published tenders.",
                        tone = NoteTone.Info,
                        icon = Icons.Default.Verified
                    )
                    SupplierVerificationStatus.NOT_APPROVED -> {
                        NoteBanner(
                            title = "Not approved",
                            text = profile.decisionReason ?: "Correct your details below and send them back.",
                            tone = NoteTone.Danger,
                            icon = Icons.Default.ErrorOutline
                        )
                        PrimaryButton(
                            text = "Resubmit for review",
                            icon = Icons.Default.Send,
                            loading = state.action is ActionState.Running,
                            onClick = viewModel::resubmit
                        )
                    }
                }

                SectionHeader("Company information")
                AppCard {
                    KeyValueRow("Company name", profile.companyName)
                    KeyValueRow("Registration number", profile.registrationNumber)
                    KeyValueRow("CSD number", profile.csdNumber)
                    KeyValueRow("Tax number", profile.taxNumber.ifBlank { "—" })
                    KeyValueRow("VAT number", profile.vatNumber.ifBlank { "Not registered for VAT" })
                    KeyValueRow("Business type", profile.businessType.ifBlank { "—" })
                    KeyValueRow("Year established", profile.yearEstablished?.toString() ?: "—", showDivider = false)
                }

                SectionHeader("Contact information")
                AppCard {
                    KeyValueRow("Contact person", profile.contactPerson.ifBlank { "—" })
                    KeyValueRow("Job title", profile.jobTitle.ifBlank { "—" })
                    KeyValueRow("Email", profile.contactEmail.ifBlank { "—" })
                    KeyValueRow("Telephone", profile.phoneNumber.ifBlank { "—" })
                    KeyValueRow("Mobile", profile.mobileNumber.ifBlank { "—" })
                    KeyValueRow("Physical address", profile.physicalAddress.ifBlank { "—" })
                    KeyValueRow("Postal address", profile.postalAddress.ifBlank { "—" })
                    KeyValueRow("Province", profile.province.ifBlank { "—" }, showDivider = false)
                }

                SectionHeader("Compliance")
                AppCard {
                    KeyValueRow("Tax clearance", profile.taxClearanceStatus.replace('_', ' ')
                        .replaceFirstChar { it.uppercase() })
                    KeyValueRow("B-BBEE level", profile.bbbeeLevel?.let { "Level $it" } ?: "Not rated")
                    KeyValueRow("Industry licences", profile.industryLicences.ifBlank { "—" })
                    KeyValueRow("Professional registrations", profile.professionalRegistrations.ifBlank { "—" })
                    KeyValueRow(
                        "Documents",
                        "${profile.documentsReceived} of ${profile.documentsRequired} provided",
                        valueColor = if (profile.documentsReceived < profile.documentsRequired)
                            AppColor.DangerInk else AppColor.Ink,
                        showDivider = false
                    )
                    Spacer(Modifier.height(Dimens.SpaceSm))
                    TextAction(text = "Manage documents", color = AppColor.InfoInk, onClick = onOpenDocuments)
                }

                SectionHeader("Business capabilities")
                AppCard {
                    KeyValueRow("Industry", profile.industry.ifBlank { "—" })
                    KeyValueRow("Categories", profile.categories.ifBlank { "—" })
                    KeyValueRow("Areas of expertise", profile.expertise.ifBlank { "—" })
                    KeyValueRow("Areas served", profile.geographicAreas.ifBlank { "—" })
                    KeyValueRow("Employees", profile.employees?.toString() ?: "—",
                        showDivider = profile.companyProfile.isNotBlank())
                    if (profile.companyProfile.isNotBlank()) {
                        Spacer(Modifier.height(Dimens.SpaceSm))
                        Text(profile.companyProfile, style = AppType.Body.copy(color = AppColor.InkSoft))
                    }
                }

                SectionHeader("Banking details")
                AppCard {
                    val banking = result.data.banking
                    if (banking == null) {
                        Text("No banking details have been provided yet.", style = AppType.Meta)
                    } else {
                        KeyValueRow("Bank", banking.bankName)
                        KeyValueRow("Account name", banking.accountName)
                        KeyValueRow("Account number", banking.maskedAccountNumber)
                        KeyValueRow("Branch code", banking.branchCode, showDivider = false)
                    }
                    Spacer(Modifier.height(Dimens.SpaceSm))
                    Text(
                        "Kept confidential: only your company and a finance officer can see these, and they " +
                            "are used only after a contract is awarded.",
                        style = AppType.Tiny
                    )
                }

                PrimaryButton(text = "Edit company details", icon = Icons.Default.Edit, onClick = onEditProfile)
                SecondaryButton(text = "Banking details", icon = Icons.Default.AccountBalance, onClick = onOpenBanking)
                SecondaryButton(text = "Supporting documents", icon = Icons.Default.Description, onClick = onOpenDocuments)
            }
        }
    }
}
