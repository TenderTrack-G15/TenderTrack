package za.ac.tendertrack.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import za.ac.tendertrack.data.model.DashboardSummary
import za.ac.tendertrack.data.model.Profile
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens

/**
 * The navigation drawer.
 *
 * Grouped rather than flat, and it carries the four areas the review found
 * missing: Record Payment and Fund Utilisation under Finance, and Flags &
 * Compliance and Reports under Oversight.
 */
@Composable
fun AppDrawer(
    profile: Profile?,
    summary: DashboardSummary?,
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    onSignOut: () -> Unit
) {
    ModalDrawerSheet(
        drawerContainerColor = AppColor.Surface,
        drawerContentColor = AppColor.Ink,
        modifier = Modifier.widthIn(max = 320.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = Dimens.ScreenPadding, end = Dimens.ScreenPadding, top = 22.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AppIcon(Icons.Default.Security, tint = AppColor.Ink, size = 22.dp)
                Text("TenderTrack", style = AppType.H2.copy(fontSize = 19.sp))
            }

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                DrawerItem("Dashboard", Icons.Default.BarChart, currentRoute == Routes.DASHBOARD) {
                    onNavigate(Routes.DASHBOARD)
                }

                DrawerGroup("Tenders")
                DrawerItem("All Tenders", Icons.Default.Description, currentRoute == Routes.TENDERS) {
                    onNavigate(Routes.TENDERS)
                }
                DrawerSubItem("Register") { onNavigate(Routes.tenderForm()) }
                DrawerSubItem("Edit") { onNavigate(Routes.TENDERS) }
                DrawerSubItem("Publish") { onNavigate(Routes.TENDERS) }
                DrawerSubItem("Close") { onNavigate(Routes.TENDERS) }

                DrawerGroup("Suppliers & Awards")
                DrawerItem(
                    "Supplier Registrations", Icons.Default.Groups,
                    currentRoute == Routes.SUPPLIERS,
                    badge = summary?.registrationsToVerify ?: 0,
                    badgeTone = BadgeTone.Warning
                ) { onNavigate(Routes.SUPPLIERS) }
                DrawerItem("Award Management", Icons.Default.EmojiEvents, false) {
                    onNavigate(Routes.TENDERS)
                }

                DrawerGroup("Finance")
                DrawerItem("Record Payment", Icons.Default.Payments, currentRoute == Routes.RECORD_PAYMENT) {
                    onNavigate(Routes.recordPayment())
                }
                DrawerItem("Fund Utilisation", Icons.Default.AccountBalanceWallet, currentRoute == Routes.FUND_UTILISATION) {
                    onNavigate(Routes.FUND_UTILISATION)
                }

                DrawerGroup("Oversight")
                DrawerItem(
                    "Flags & Compliance", Icons.Default.Flag,
                    currentRoute == Routes.FLAGS,
                    badge = summary?.openFlags ?: 0,
                    badgeTone = BadgeTone.Danger
                ) { onNavigate(Routes.FLAGS) }
                DrawerItem("Reports", Icons.AutoMirrored.Filled.List, currentRoute == Routes.REPORTS) {
                    onNavigate(Routes.REPORTS)
                }
                DrawerItem(
                    "Notifications", Icons.Default.Notifications,
                    currentRoute == Routes.NOTIFICATIONS,
                    badge = summary?.unreadNotifications ?: 0,
                    badgeTone = BadgeTone.Info
                ) { onNavigate(Routes.NOTIFICATIONS) }

                Spacer(Modifier.height(Dimens.SpaceLg))
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(AppColor.Line)
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSignOut() }
                    .padding(horizontal = Dimens.ScreenPadding, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Avatar(profile?.initials ?: "?")
                Column(Modifier.weight(1f)) {
                    Text(profile?.fullName ?: "Not signed in", style = AppType.KvValue)
                    // The role is displayed, never selected.
                    Text(profile?.role?.displayName ?: "", style = AppType.Tiny.copy(color = AppColor.Muted))
                }
                AppIcon(Icons.AutoMirrored.Filled.Logout, tint = AppColor.Muted, size = 18.dp, contentDescription = "Sign out")
            }
        }
    }
}

@Composable
private fun DrawerGroup(label: String) {
    Text(
        label.uppercase(),
        style = AppType.Eyebrow.copy(color = AppColor.MutedLight, fontSize = 10.5.sp),
        modifier = Modifier.padding(start = Dimens.ScreenPadding, end = Dimens.ScreenPadding, top = 14.dp, bottom = 5.dp)
    )
}

@Composable
private fun DrawerItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    badge: Int = 0,
    badgeTone: BadgeTone = BadgeTone.Danger,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) AppColor.SurfaceMuted else AppColor.Surface)
            .clickable { onClick() }
            .padding(horizontal = Dimens.ScreenPadding, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AppIcon(icon, tint = if (selected) AppColor.Ink else AppColor.Muted, size = 18.dp)
        Text(
            label,
            style = AppType.Body.copy(
                fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold
                else androidx.compose.ui.text.font.FontWeight.Medium,
                color = if (selected) AppColor.Ink else AppColor.InkSoft
            ),
            modifier = Modifier.weight(1f)
        )
        CountPill(badge, badgeTone)
    }
}

@Composable
private fun DrawerSubItem(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = AppType.Meta.copy(color = AppColor.InkSoft),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(start = 50.dp, end = Dimens.ScreenPadding, top = 9.dp, bottom = 9.dp)
    )
}
