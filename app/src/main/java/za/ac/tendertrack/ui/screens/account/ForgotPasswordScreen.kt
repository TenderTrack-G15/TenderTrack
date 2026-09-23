package za.ac.tendertrack.ui.screens.account

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppType

/**
 * Forgot Password.
 *
 * Resetting a password needs an email, and a Supabase project without its own
 * email provider only delivers to members of the project's team, at two
 * messages an hour. So in this version an administrator resets the password
 * instead, and this screen explains how to ask for that.
 *
 * The in-app version (a code emailed to the person, typed in here with a new
 * password) is written up in the setup guide and can be switched on once a
 * transactional email provider is configured.
 */
@Composable
fun ForgotPasswordScreen(
    onBack: () -> Unit,
    onSignIn: () -> Unit
) {
    AppScaffold(title = "Forgot password", onBack = onBack) {
        ScreenHeading(
            eyebrow = "Forgot password",
            title = "Ask an administrator to reset it",
            subtitle = "Passwords are reset for you, so an account cannot be taken over by email alone."
        )

        AppCard {
            CardHeader(title = "What to do")
            Text("1.  Contact your department's TenderTrack administrator.", style = AppType.Body)
            Text("2.  Give them the email address you sign in with.", style = AppType.Body)
            Text("3.  They set a new password and give it to you directly.", style = AppType.Body)
            Text("4.  Sign in with it, and ask for another change if anyone else has seen it.", style = AppType.Body)
        }

        NoteBanner(
            title = "Suppliers",
            text = "If you registered as a supplier, use the email address on your registration and contact " +
                "the department that published the tender you are following.",
            tone = NoteTone.Info,
            icon = Icons.Default.Info
        )

        PrimaryButton(text = "Back to sign in", icon = Icons.Default.Lock, onClick = onSignIn)

        FootNote(
            "Resetting your own password inside the app will be switched on once TenderTrack is " +
                "connected to an email service."
        )
    }
}
