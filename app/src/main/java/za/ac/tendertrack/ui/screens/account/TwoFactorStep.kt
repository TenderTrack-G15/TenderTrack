package za.ac.tendertrack.ui.screens.account

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import za.ac.tendertrack.core.friendlyMessage
import za.ac.tendertrack.data.model.TwoFactorSetUp
import za.ac.tendertrack.data.model.UserRole
import za.ac.tendertrack.data.repo.AUTHENTICATOR_ISSUER
import za.ac.tendertrack.data.repo.SampleTwoFactorRepository
import za.ac.tendertrack.data.repo.TWO_FACTOR_ROLES
import za.ac.tendertrack.data.repo.TwoFactorRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens

/*
 * The 6-digit code step after the password, for procurement officers,
 * suppliers and auditors: the same two-factor sign-in as the admin portal.
 * Used by SignInScreen and by SupplierSignUpScreen (which signs in with the
 * eTender portal account).
 *
 * The phone that runs TenderTrack usually also has the authenticator app, so
 * instead of a QR code to scan there is a button that opens the authenticator
 * app with TenderTrack filled in (an otpauth:// link), and the set-up key to
 * copy if the link does not open it.
 */

/** Wrong codes allowed before the person is signed out. */
const val MAX_WRONG_CODES = 5

/** Where the code step stands. */
data class TwoFactorUi(
    val factorId: String,
    /** Set the first time: what to add to the authenticator app. */
    val setUp: TwoFactorSetUp? = null,
    val code: String = "",
    val error: String? = null,
    val wrongCodes: Int = 0,
    val submitting: Boolean = false
)

/** The code step's logic, shared by the screens that sign people in. */
class TwoFactorGate(private val repository: TwoFactorRepository = TwoFactorRepository.instance) {

    /**
     * Straight after the password: null when this account needs no code,
     * otherwise the code step (with the set-up details the first time).
     */
    suspend fun begin(role: UserRole): TwoFactorUi? {
        if (role !in TWO_FACTOR_ROLES) return null
        val status = repository.status()
        if (!status.required || status.satisfied) return null
        status.verifiedFactorId?.let { return TwoFactorUi(factorId = it) }
        val setUp = repository.startSetUp(status.unverifiedFactorIds)
        return TwoFactorUi(factorId = setUp.factorId, setUp = setUp)
    }

    /** Checks the code: null when it is right, otherwise the step with what went wrong. */
    suspend fun check(step: TwoFactorUi): TwoFactorUi? {
        val code = step.code.filter { it.isDigit() }
        if (code.length != 6) return step.copy(error = "Enter the 6-digit code from your authenticator app.")
        return try {
            repository.verify(step.factorId, code)
            null
        } catch (e: Exception) {
            val wrong = step.wrongCodes + 1
            val raw = e.message.orEmpty()
            val left = MAX_WRONG_CODES - wrong
            val message = when {
                raw.contains("expired", true) -> "That code has expired. Type the newest code from the app."
                raw.contains("TOTP", true) || raw.contains("verification", true) || raw.contains("invalid", true) ->
                    "That code is not right. Check that the phone's date and time are set automatically, then type " +
                        "the newest code. $left ${if (left == 1) "try" else "tries"} left."
                else -> e.twoFactorProblem()
            }
            step.copy(code = "", error = message, wrongCodes = wrong)
        }
    }

    companion object {
        fun normalise(raw: String): String = raw.filter { it.isDigit() }.take(6)
    }
}

/** A problem starting two-factor sign-in, in words a person can act on. */
fun Throwable.twoFactorProblem(): String {
    val raw = message.orEmpty()
    return when {
        raw.contains("disabled", true) || raw.contains("not enabled", true) ->
            "Two-factor sign-in is turned off for this TenderTrack project. In Supabase, turn on " +
                "Authentication → Multi-Factor → TOTP, then sign in again."
        raw.contains("Could not find the function", true) || raw.contains("PGRST202") ->
            "The database has not been updated for two-factor sign-in yet. Run supabase/phase_proof.sql in Supabase."
        else -> friendlyMessage().lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            ?: "Two-factor sign-in could not start. Please try again."
    }
}

/** The code step: set-up the first time, then the code field. */
@Composable
fun TwoFactorStep(
    step: TwoFactorUi,
    onCode: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var notice by remember { mutableStateOf<String?>(null) }
    val setUp = step.setUp

    ScreenHeading(
        eyebrow = "Two-factor sign-in",
        title = if (setUp != null) "Set up your authenticator" else "Enter your code",
        subtitle = if (setUp != null) "Once only. After this, just type the code." else "From your authenticator app"
    )

    if (setUp != null) {
        NoteBanner(
            title = "Why this is needed",
            text = "Procurement officers, suppliers and auditors type a 6-digit code from an authenticator app " +
                "after their password, as administrators do. Someone who learns your password still can't sign in. " +
                "It is one authenticator for TenderTrack and the eTender portal.",
            tone = NoteTone.Info,
            icon = Icons.Default.Shield
        )
        AppCard {
            SetUpStep(1, "Install an authenticator app", "Google Authenticator or Microsoft Authenticator, from the Play Store.")
            SetUpStep(2, "Add TenderTrack to it", "Tap \"Open authenticator app\" below. If nothing opens, copy the set-up " +
                "key, then in the app choose \"Enter a setup key\" (time-based).")
            SetUpStep(3, "Type the 6-digit code", "Come back here and type the code the app shows for TenderTrack.", last = true)
        }
        SecondaryButton(
            text = "Open authenticator app",
            icon = Icons.AutoMirrored.Filled.OpenInNew,
            onClick = {
                notice = try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(setUp.uri)))
                    null
                } catch (e: ActivityNotFoundException) {
                    "No authenticator app is installed. Install Google Authenticator or Microsoft Authenticator, " +
                        "then tap this again, or copy the set-up key."
                }
            }
        )
        AppCard {
            KeyValueRow("Account name", AUTHENTICATOR_ISSUER)
            KeyValueRow("Type", "Time-based", showDivider = false)
            Spacer(Modifier.height(Dimens.SpaceSm))
            Text("Set-up key", style = AppType.Label)
            Text(
                setUp.groupedSecret,
                style = AppType.KvValue.copy(fontFamily = FontFamily.Monospace, letterSpacing = 1.sp, color = AppColor.Ink)
            )
        }
        SecondaryButton(
            text = "Copy set-up key",
            icon = Icons.Default.ContentCopy,
            onClick = {
                clipboard.setText(AnnotatedString(setUp.secret))
                notice = "Set-up key copied. Paste it into your authenticator app."
            }
        )
        notice?.let { NoteBanner(text = it, tone = NoteTone.Neutral, icon = Icons.Default.Info) }
    } else {
        Text(
            "Open your authenticator app and type the 6-digit code shown for TenderTrack.",
            style = AppType.Body.copy(color = AppColor.InkSoft)
        )
    }

    AppTextField(
        label = "6-digit code",
        value = step.code,
        onValueChange = onCode,
        placeholder = "000000",
        leadingIcon = Icons.Default.Key,
        keyboardType = KeyboardType.Number,
        hint = "The code changes every 30 seconds.",
        error = step.error,
        enabled = !step.submitting
    )
    PrimaryButton(
        text = if (setUp != null) "Turn on and sign in" else "Verify and sign in",
        icon = Icons.Default.Check,
        loading = step.submitting,
        enabled = step.code.length == 6,
        onClick = onSubmit
    )
    TextAction(text = "Cancel and sign out", color = AppColor.Muted, onClick = onCancel)

    if (TwoFactorRepository.instance is SampleTwoFactorRepository) {
        NoteBanner(
            title = "Sample data",
            text = "No Supabase project is configured, so the code is always ${SampleTwoFactorRepository.SAMPLE_CODE}.",
            tone = NoteTone.Neutral
        )
    }
}

@Composable
private fun SetUpStep(number: Int, title: String, text: String, last: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = if (last) 0.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(AppColor.Ink),
            contentAlignment = Alignment.Center
        ) {
            Text("$number", style = AppType.Badge.copy(color = AppColor.OnInk))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = AppType.KvValue)
            Text(text, style = AppType.Meta)
        }
    }
}
