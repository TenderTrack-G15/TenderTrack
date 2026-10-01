package za.ac.tendertrack.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import za.ac.tendertrack.data.model.TenderStatus
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens

/*
 * The public (citizen) navigation panel: the same look as the procurement
 * officer's drawer (AppDrawer.kt), with the public's own destinations. A member
 * of the public has no account, so the panel shows no name and its last row
 * returns to the welcome screen instead of signing out. It lives in the citizen
 * graph, so NavGraph.kt needs no changes; the officer's drawer stays closed on
 * "public_" routes as before.
 */

/** The statuses the panel offers as shortcuts into Search Tenders, in lifecycle order. */
internal val CITIZEN_PANEL_STATUSES = listOf(
    TenderStatus.PUBLISHED,
    TenderStatus.UNDER_EVALUATION,
    TenderStatus.AWARDED,
    TenderStatus.IN_PROGRESS,
    TenderStatus.COMPLETED
)

/**
 * Wraps a citizen screen in the navigation panel. The screen receives
 * `openDrawer` for its menu button; the panel also opens with a swipe from the
 * left edge.
 */
@Composable
fun CitizenDrawerHost(
    navController: NavHostController,
    currentRoute: String,
    content: @Composable (openDrawer: () -> Unit) -> Unit
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    fun close() {
        scope.launch { drawerState.close() }
    }

    // Back closes the panel first, instead of leaving the screen behind it.
    BackHandler(enabled = drawerState.isOpen) { close() }

    fun go(route: String) {
        close()
        if (route == CitizenRoutes.HOME && currentRoute == CitizenRoutes.HOME) return
        navController.navigate(route) {
            launchSingleTop = true
            // The dashboard stays underneath, so Back from any panel destination returns to it.
            popUpTo(CitizenRoutes.HOME) { inclusive = false }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            CitizenDrawer(
                currentRoute = currentRoute,
                onNavigate = { route -> go(route) },
                onLeave = {
                    close()
                    // Back to the screen the public view was opened from (Welcome).
                    navController.popBackStack(CitizenRoutes.HOME, inclusive = true)
                }
            )
        }
    ) {
        content { scope.launch { drawerState.open() } }
    }
}

@Composable
private fun CitizenDrawer(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    onLeave: () -> Unit
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
                    .padding(start = Dimens.ScreenPadding, end = Dimens.ScreenPadding, top = 22.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AppIcon(Icons.Default.Security, tint = AppColor.Ink, size = 22.dp)
                Text("TenderTrack", style = AppType.H2.copy(fontSize = 19.sp))
            }
            Text(
                "Public view · no account needed",
                style = AppType.Meta,
                modifier = Modifier.padding(start = Dimens.ScreenPadding + 32.dp, end = Dimens.ScreenPadding, bottom = 14.dp)
            )

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                DrawerItem("Dashboard", Icons.Default.BarChart, currentRoute == CitizenRoutes.HOME) {
                    onNavigate(CitizenRoutes.HOME)
                }

                DrawerGroup("Tenders")
                DrawerItem("Search tenders", Icons.Default.Search, currentRoute == CitizenRoutes.TENDERS) {
                    onNavigate(CitizenRoutes.tenders())
                }
                // Each opens Search Tenders with that status already chosen.
                CITIZEN_PANEL_STATUSES.forEach { status ->
                    DrawerSubItem(status.displayName) { onNavigate(CitizenRoutes.tenders(status)) }
                }

                DrawerGroup("Money")
                DrawerItem("Spending by department", Icons.Default.AccountBalance, currentRoute == CitizenRoutes.SPEND) {
                    onNavigate(CitizenRoutes.SPEND)
                }

                DrawerGroup("Spotted something wrong?")
                DrawerItem("Flag a tender for review", Icons.Default.Flag, currentRoute == CitizenRoutes.REPORT) {
                    onNavigate(CitizenRoutes.report())
                }
                DrawerItem("Track a report I made", Icons.Default.FindInPage, currentRoute == CitizenRoutes.TRACK) {
                    onNavigate(CitizenRoutes.track())
                }

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
                    .clickable { onLeave() }
                    .padding(horizontal = Dimens.ScreenPadding, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AppIcon(Icons.Default.Public, tint = AppColor.Ink, size = 22.dp)
                Column(Modifier.weight(1f)) {
                    Text("Public visitor", style = AppType.KvValue)
                    Text("Not signed in · back to welcome", style = AppType.Tiny.copy(color = AppColor.Muted))
                }
                AppIcon(Icons.AutoMirrored.Filled.Login, tint = AppColor.Muted, size = 18.dp, contentDescription = "Back to welcome")
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
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) AppColor.Ink else AppColor.InkSoft
            ),
            modifier = Modifier.weight(1f)
        )
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
