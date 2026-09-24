package za.ac.tendertrack.ui.screens.supplier

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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

data class CompanyProfileUiState(
    val loading: UiState<Unit> = UiState.Loading,
    val form: ProfileForm = ProfileForm(),
    val errors: Map<ProfileField, String> = emptyMap(),
    val formError: String? = null,
    val saving: Boolean = false,
    val doneMessage: String? = null
)

class CompanyProfileViewModel(
    private val repository: SupplierPortalRepository = ServiceLocator.supplierPortalRepository
) : ViewModel() {

    private val _state = MutableStateFlow(CompanyProfileUiState())
    val state: StateFlow<CompanyProfileUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(loading = UiState.Loading) }
        viewModelScope.launch {
            try {
                val profile = repository.myProfile()
                _state.update {
                    if (profile == null) it.copy(loading = UiState.Error("This account has no supplier registration."))
                    else it.copy(loading = UiState.Success(Unit), form = ProfileForm.from(profile))
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = UiState.Error(e.supplierMessage())) }
            }
        }
    }

    fun onChange(field: ProfileField?, form: ProfileForm) = _state.update {
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
                repository.saveProfile(current.form)
                _state.update { it.copy(saving = false, doneMessage = "Company details saved.") }
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, formError = e.supplierMessage()) }
            }
        }
    }
}

/**
 * Edit the company's own details: company information, contact information,
 * compliance registrations and business capabilities. The CSD number and the
 * verification status are not editable here: only an officer changes those.
 */
@Composable
fun CompanyProfileScreen(
    onDone: () -> Unit,
    viewModel: CompanyProfileViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(state.doneMessage) {
        state.doneMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            onDone()
        }
    }

    AppScaffold(title = "Company Details", onBack = onDone) {
        when (val result = state.loading) {
            is UiState.Loading -> LoadingState()
            is UiState.Error -> ErrorState(result.message, onRetry = viewModel::load)
            is UiState.Success -> {
                val f = state.form
                val busy = state.saving

                ScreenHeading(
                    eyebrow = "My company",
                    title = "Company details",
                    subtitle = "Officers check these against CIPC, CSD and SARS records."
                )

                SectionHeader("Company information")
                AppTextField(
                    label = "Company name", value = f.companyName,
                    onValueChange = { viewModel.onChange(ProfileField.COMPANY_NAME, f.copy(companyName = it)) },
                    placeholder = "As registered with CIPC",
                    error = state.errors[ProfileField.COMPANY_NAME], enabled = !busy
                )
                AppTextField(
                    label = "Company registration number", value = f.registrationNumber,
                    onValueChange = { viewModel.onChange(ProfileField.REGISTRATION_NUMBER, f.copy(registrationNumber = it.trim())) },
                    placeholder = "e.g. 2019/451236/07",
                    error = state.errors[ProfileField.REGISTRATION_NUMBER], enabled = !busy
                )
                AppTextField(
                    label = "Tax number", value = f.taxNumber,
                    onValueChange = { viewModel.onChange(ProfileField.TAX_NUMBER, f.copy(taxNumber = it.trim())) },
                    placeholder = "SARS income tax number",
                    keyboardType = KeyboardType.Number, enabled = !busy
                )
                AppTextField(
                    label = "VAT number (if registered)", value = f.vatNumber,
                    onValueChange = { viewModel.onChange(ProfileField.VAT_NUMBER, f.copy(vatNumber = it.trim())) },
                    placeholder = "Leave blank if not VAT registered",
                    keyboardType = KeyboardType.Number, enabled = !busy
                )
                AppDropdownField(
                    label = "Business type", selected = f.businessType,
                    options = SupplierChecks.businessTypes, optionLabel = { it },
                    onSelect = { viewModel.onChange(ProfileField.BUSINESS_TYPE, f.copy(businessType = it)) },
                    placeholder = "Choose a business type", error = state.errors[ProfileField.BUSINESS_TYPE]
                )
                AppTextField(
                    label = "Year established", value = f.yearEstablished,
                    onValueChange = { viewModel.onChange(ProfileField.YEAR_ESTABLISHED, f.copy(yearEstablished = it.trim())) },
                    placeholder = "e.g. 2015", keyboardType = KeyboardType.Number,
                    error = state.errors[ProfileField.YEAR_ESTABLISHED], enabled = !busy
                )

                SectionHeader("Contact information")
                AppTextField(
                    label = "Contact person", value = f.contactPerson,
                    onValueChange = { viewModel.onChange(ProfileField.CONTACT_PERSON, f.copy(contactPerson = it)) },
                    placeholder = "Full name", leadingIcon = Icons.Default.Person, enabled = !busy
                )
                AppTextField(
                    label = "Job title", value = f.jobTitle,
                    onValueChange = { viewModel.onChange(ProfileField.JOB_TITLE, f.copy(jobTitle = it)) },
                    placeholder = "e.g. Director", enabled = !busy
                )
                AppTextField(
                    label = "Email address", value = f.contactEmail,
                    onValueChange = { viewModel.onChange(ProfileField.CONTACT_EMAIL, f.copy(contactEmail = it.trim())) },
                    placeholder = "you@company.co.za", leadingIcon = Icons.Default.MailOutline,
                    keyboardType = KeyboardType.Email, error = state.errors[ProfileField.CONTACT_EMAIL], enabled = !busy
                )
                AppTextField(
                    label = "Telephone number", value = f.phoneNumber,
                    onValueChange = { viewModel.onChange(ProfileField.PHONE, f.copy(phoneNumber = it)) },
                    placeholder = "e.g. 011 234 5600", keyboardType = KeyboardType.Phone, enabled = !busy
                )
                AppTextField(
                    label = "Mobile number", value = f.mobileNumber,
                    onValueChange = { viewModel.onChange(ProfileField.MOBILE, f.copy(mobileNumber = it)) },
                    placeholder = "e.g. 082 123 4567", leadingIcon = Icons.Default.Phone,
                    keyboardType = KeyboardType.Phone, error = state.errors[ProfileField.MOBILE], enabled = !busy
                )
                AppTextField(
                    label = "Physical address", value = f.physicalAddress,
                    onValueChange = { viewModel.onChange(ProfileField.PHYSICAL_ADDRESS, f.copy(physicalAddress = it)) },
                    placeholder = "Street, suburb, city", singleLine = false, minHeight = 80.dp, enabled = !busy
                )
                AppTextField(
                    label = "Postal address", value = f.postalAddress,
                    onValueChange = { viewModel.onChange(ProfileField.POSTAL_ADDRESS, f.copy(postalAddress = it)) },
                    placeholder = "PO Box or the same as the physical address",
                    singleLine = false, minHeight = 80.dp, enabled = !busy
                )
                AppDropdownField(
                    label = "Province", selected = f.province,
                    options = SupplierChecks.provinces, optionLabel = { it },
                    onSelect = { viewModel.onChange(ProfileField.PROVINCE, f.copy(province = it)) },
                    placeholder = "Choose a province", error = state.errors[ProfileField.PROVINCE]
                )

                SectionHeader("Compliance")
                AppTextField(
                    label = "Industry licences and certifications", value = f.industryLicences,
                    onValueChange = { viewModel.onChange(ProfileField.LICENCES, f.copy(industryLicences = it)) },
                    placeholder = "e.g. ECSA-registered engineer on staff",
                    singleLine = false, minHeight = 80.dp, enabled = !busy
                )
                AppTextField(
                    label = "Professional registrations", value = f.professionalRegistrations,
                    onValueChange = { viewModel.onChange(ProfileField.REGISTRATIONS, f.copy(professionalRegistrations = it)) },
                    placeholder = "e.g. IITPSA corporate member",
                    hint = "Tax clearance and B-BBEE are recorded under Supporting documents.",
                    singleLine = false, minHeight = 80.dp, enabled = !busy
                )

                SectionHeader("Business capabilities")
                AppTextField(
                    label = "Industry or sector", value = f.industry,
                    onValueChange = { viewModel.onChange(ProfileField.INDUSTRY, f.copy(industry = it)) },
                    placeholder = "e.g. Information and communications technology", enabled = !busy
                )
                AppTextField(
                    label = "Goods and services offered", value = f.categories,
                    onValueChange = { viewModel.onChange(ProfileField.CATEGORIES, f.copy(categories = it)) },
                    placeholder = "Separate with semicolons", singleLine = false, minHeight = 80.dp, enabled = !busy
                )
                AppTextField(
                    label = "Areas of expertise", value = f.expertise,
                    onValueChange = { viewModel.onChange(ProfileField.EXPERTISE, f.copy(expertise = it)) },
                    placeholder = "What your company does best", singleLine = false, minHeight = 80.dp, enabled = !busy
                )
                AppTextField(
                    label = "Geographic areas served", value = f.geographicAreas,
                    onValueChange = { viewModel.onChange(ProfileField.AREAS, f.copy(geographicAreas = it)) },
                    placeholder = "e.g. Gauteng, Limpopo", enabled = !busy
                )
                AppTextField(
                    label = "Number of employees", value = f.employees,
                    onValueChange = { viewModel.onChange(ProfileField.EMPLOYEES, f.copy(employees = it.trim())) },
                    placeholder = "e.g. 48", keyboardType = KeyboardType.Number,
                    error = state.errors[ProfileField.EMPLOYEES], enabled = !busy
                )
                AppTextField(
                    label = "Company profile", value = f.companyProfile,
                    onValueChange = { viewModel.onChange(ProfileField.PROFILE, f.copy(companyProfile = it)) },
                    placeholder = "A short description of your company and its track record",
                    singleLine = false, minHeight = 120.dp, enabled = !busy
                )

                state.formError?.let { NoteBanner(text = it, tone = NoteTone.Danger, icon = Icons.Default.Warning) }
                Spacer(Modifier.height(Dimens.SpaceSm))
                PrimaryButton(text = "Save changes", icon = Icons.Default.Check, loading = busy, onClick = viewModel::save)
                SecondaryButton(text = "Cancel", enabled = !busy, onClick = onDone)
            }
        }
    }
}
