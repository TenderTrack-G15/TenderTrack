package za.ac.tendertrack.ui.screens.account

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import za.ac.tendertrack.core.friendlyMessage
import za.ac.tendertrack.data.model.*
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType

/*
 * Pieces shared by the Welcome, Sign In, Supplier Sign-up, Forgot Password and
 * Supplier Home screens. Everything visual comes from the existing components
 * and the AppColor / AppType / Dimens tokens.
 */

/** The TenderTrack mark: the same briefcase-in-a-rounded-square as Sign In. */
@Composable
fun TenderTrackMark(size: Dp = 64.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .border(2.dp, AppColor.Ink, RoundedCornerShape(size * 0.28f)),
        contentAlignment = Alignment.Center
    ) {
        AppIcon(Icons.Default.BusinessCenter, tint = AppColor.Ink, size = size * 0.56f)
    }
}

// ---------------------------------------------------------------------------
// Company profile fields (D3 5.3 "Company Profile")
// ---------------------------------------------------------------------------

/**
 * The company-profile fields, used by sign-up step 2 and by "correct and
 * resubmit". onChange passes the field that changed so its error can be cleared.
 */
@Composable
fun CompanyProfileFields(
    form: CompanyForm,
    errors: Map<CompanyField, String>,
    enabled: Boolean,
    showMobile: Boolean,
    onChange: (CompanyField?, CompanyForm) -> Unit
) {
    AppTextField(
        label = "Company name",
        value = form.companyName,
        onValueChange = { onChange(CompanyField.COMPANY_NAME, form.copy(companyName = it)) },
        placeholder = "As registered with CIPC",
        leadingIcon = Icons.Default.Business,
        error = errors[CompanyField.COMPANY_NAME],
        enabled = enabled
    )
    AppTextField(
        label = "Company registration number",
        value = form.registrationNumber,
        onValueChange = { onChange(CompanyField.REGISTRATION_NUMBER, form.copy(registrationNumber = it.trim())) },
        placeholder = "e.g. 2019/451236/07",
        hint = "Your CIPC registration number.",
        error = errors[CompanyField.REGISTRATION_NUMBER],
        enabled = enabled
    )
    AppTextField(
        label = "CSD number",
        value = form.csdNumber,
        onValueChange = { onChange(CompanyField.CSD_NUMBER, form.copy(csdNumber = it.trim().uppercase())) },
        placeholder = "e.g. MAAA0451236",
        hint = "Your Central Supplier Database supplier number.",
        error = errors[CompanyField.CSD_NUMBER],
        enabled = enabled
    )
    AppTextField(
        label = "Tax compliance PIN",
        value = form.taxPin,
        onValueChange = { onChange(CompanyField.TAX_PIN, form.copy(taxPin = it.trim().uppercase())) },
        placeholder = "From SARS eFiling",
        error = errors[CompanyField.TAX_PIN],
        enabled = enabled
    )
    AppDropdownField(
        label = "B-BBEE level",
        selected = form.bbbeeLevel,
        options = SupplierRules.bbbeeLevels,
        optionLabel = { SupplierRules.bbbeeLabel(it) },
        onSelect = { onChange(null, form.copy(bbbeeLevel = it)) },
        placeholder = SupplierRules.bbbeeLabel(null)
    )
    AppDropdownField(
        label = "Business type",
        selected = form.businessType,
        options = SupplierRules.businessTypes,
        optionLabel = { it },
        onSelect = { onChange(CompanyField.BUSINESS_TYPE, form.copy(businessType = it)) },
        placeholder = "Choose a business type",
        error = errors[CompanyField.BUSINESS_TYPE]
    )
    AppDropdownField(
        label = "Province",
        selected = form.province,
        options = SupplierRules.provinces,
        optionLabel = { it },
        onSelect = { onChange(CompanyField.PROVINCE, form.copy(province = it)) },
        placeholder = "Choose a province",
        error = errors[CompanyField.PROVINCE]
    )
    AppTextField(
        label = "Authorised representative",
        value = form.representative,
        onValueChange = { onChange(CompanyField.REPRESENTATIVE, form.copy(representative = it)) },
        placeholder = "Full name",
        leadingIcon = Icons.Default.Person,
        error = errors[CompanyField.REPRESENTATIVE],
        enabled = enabled
    )
    if (showMobile) {
        MobileField(
            value = form.mobileNumber,
            error = errors[CompanyField.MOBILE],
            enabled = enabled,
            onValueChange = { onChange(CompanyField.MOBILE, form.copy(mobileNumber = it)) }
        )
    }
}

@Composable
fun MobileField(value: String, error: String?, enabled: Boolean, onValueChange: (String) -> Unit) {
    AppTextField(
        label = "Mobile number",
        value = value,
        onValueChange = onValueChange,
        placeholder = "e.g. 082 123 4567",
        leadingIcon = Icons.Default.Phone,
        keyboardType = KeyboardType.Phone,
        error = error,
        enabled = enabled
    )
}

// ---------------------------------------------------------------------------
// Registration status (D3 5.3 "Registration under review" / "not approved")
// ---------------------------------------------------------------------------

fun SupplierVerificationStatus.supplierLabel(): String = when (this) {
    SupplierVerificationStatus.AWAITING_VERIFICATION -> "Under review"
    SupplierVerificationStatus.VERIFIED -> "Verified"
    SupplierVerificationStatus.NOT_APPROVED -> "Not approved"
}

fun SupplierVerificationStatus.supplierTone(): BadgeTone = when (this) {
    SupplierVerificationStatus.AWAITING_VERIFICATION -> BadgeTone.Warning
    SupplierVerificationStatus.VERIFIED -> BadgeTone.Success
    SupplierVerificationStatus.NOT_APPROVED -> BadgeTone.Danger
}

/** One company detail row, used on the Supplier Home screen. */
@Composable
fun CompanyDetails(registration: SupplierRegistration) {
    AppCard {
        CardHeader(title = "Company profile", subtitle = "As submitted for verification")
        Spacer(Modifier.height(10.dp))
        KeyValueRow("Company", registration.companyName)
        KeyValueRow("Registration number", registration.registrationNumber)
        KeyValueRow("CSD number", registration.csdNumber)
        KeyValueRow("Tax compliance PIN", registration.taxPin.ifBlank { "—" })
        KeyValueRow("B-BBEE", SupplierRules.bbbeeLabel(registration.bbbeeLevel))
        KeyValueRow("Business type", registration.businessType.ifBlank { "—" })
        KeyValueRow("Province", registration.province.ifBlank { "—" })
        KeyValueRow("Representative", registration.representative.ifBlank { "—" })
        KeyValueRow("Mobile", registration.mobileNumber.ifBlank { "—" })
        KeyValueRow("Email", registration.contactEmail.ifBlank { "—" }, showDivider = false)
    }
}

// ---------------------------------------------------------------------------
// Feedback
// ---------------------------------------------------------------------------

/**
 * Supabase's sign-up and password-reset errors, in plain language. The raw
 * message can also carry the request URL on later lines, so only the first
 * line is ever shown.
 */
fun Throwable.accountMessage(): String {
    val raw = friendlyMessage().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    val text = (message ?: "") + " " + raw
    return when {
        text.contains("already registered", true) ->
            "An account with this email already exists. Sign in, or use Forgot password."
        text.contains("Database error saving new user", true) ->
            "The account could not be created: one of the company details was refused. " +
                "Check the registration and CSD numbers and try again."
        text.contains("not authorized", true) ->
            "Emails cannot be sent to this address yet. The project's email service only " +
                "reaches members of the Supabase team (see the setup guide)."
        text.contains("rate limit", true) || text.contains("security purposes", true) ->
            "Too many emails were requested. Wait a few minutes, then try again."
        text.contains("different from the old password", true) ->
            "Choose a password that is different from your old one."
        raw.isNotBlank() -> raw
        else -> "Something went wrong. Please try again."
    }
}

/** Shared "Passwords do not match" check. */
fun confirmError(password: String, confirm: String): String? =
    if (confirm != password) "The passwords do not match." else null

/** Small centred note under a button. */
@Composable
fun FootNote(text: String) {
    Text(text, style = AppType.Tiny, modifier = Modifier.fillMaxWidth(),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}
