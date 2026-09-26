package za.ac.tendertrack.ui.screens.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import za.ac.tendertrack.data.ServiceLocator
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens

/**
 * Welcome — the first screen (Deliverable 3, section 5.1: "provides guest
 * entry to the public screens and a link to supplier registration").
 *
 * Most people using TenderTrack are members of the public, so their button is
 * the one filled (highlighted) action. Government officials and suppliers have
 * separate sign-in pages below it; a new supplier registers from the supplier
 * page. "Forgot password" lives on those sign-in pages, not here.
 */
@Composable
fun WelcomeScreen(
    onContinueAsPublic: () -> Unit,
    onGovernmentLogin: () -> Unit,
    onSupplierLogin: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(AppColor.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(Dimens.SpaceLg))

        // Wordmark, as in the reference design: the mark beside the name.
        Row(verticalAlignment = Alignment.CenterVertically) {
            TenderTrackMark(size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Text("TenderTrack", style = AppType.H1.copy(fontSize = 24.sp))
        }

        Spacer(Modifier.height(48.dp))
        Text(
            "Welcome to TenderTrack",
            style = AppType.H1.copy(fontSize = 30.sp),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Dimens.SpaceSm))
        Text(
            "South Africa's public procurement transparency platform",
            style = AppType.Meta,
            textAlign = TextAlign.Center
        )
        AnnouncementsBanner(AnnouncementViewer.PUBLIC, Modifier.padding(top = 24.dp))

        // -- 1. Members of the public: the highlighted action (FR13–FR15) -------
        Spacer(Modifier.height(40.dp))
        PrimaryButton(
            text = "Continue as a member of the public",
            icon = Icons.Default.Visibility,
            onClick = onContinueAsPublic
        )
        Spacer(Modifier.height(Dimens.SpaceSm))
        FootNote("View published tenders, spending and delivery progress, or flag a tender for review. No account needed.")

        // -- 2. Government officials -----------------------------------------------
        Spacer(Modifier.height(Dimens.SpaceXl))
        SecondaryButton(
            text = "Government Official Login",
            icon = Icons.Default.Lock,
            onClick = onGovernmentLogin
        )

        // -- 3. Suppliers: sign in first, register from there (D3 5.3) --------------
        Spacer(Modifier.height(Dimens.SpaceMd))
        SecondaryButton(
            text = "Supplier Login",
            icon = Icons.Default.Storefront,
            onClick = onSupplierLogin
        )

        if (ServiceLocator.usingSampleData) {
            Spacer(Modifier.height(Dimens.SpaceXl))
            NoteBanner(
                title = "Running on sample data",
                text = "No Supabase project is configured, so the app is using the bundled demo data.",
                tone = NoteTone.Info,
                icon = Icons.Default.Info
            )
        }
    }
}
