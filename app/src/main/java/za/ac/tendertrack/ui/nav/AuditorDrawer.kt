package za.ac.tendertrack.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import za.ac.tendertrack.data.ServiceLocator
import za.ac.tendertrack.data.model.Profile
import za.ac.tendertrack.data.model.TenderStatus
import za.ac.tendertrack.data.repo.AuditorRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens

/*
 * The auditor's navigation panel: the same look as the procurement officer's
 * drawer (AppDrawer.kt), with the auditor's own destinations. Everything in it
 * is read-only, like the rest of the auditor's screens. It lives in the
 * auditor graph, so NavGraph.kt needs no changes; the officer's drawer stays
 * closed on "auditor_" routes as before.
 */

/** The count shown next to Compliance reports. */
data class AuditorDrawerInfo(
    /** Tenders with at least one compliance check not met. */
    val tendersWithProblem: Int = 0
)

class AuditorDrawerViewModel(
    private val repository: AuditorRepository = ServiceLocator.auditorRepository
) : ViewModel() {

    private val _info = MutableStateFlow(AuditorDrawerInfo())
    val info: StateFlow<AuditorDrawerInfo> = _info.asStateFlow()

    /** Called when the panel opens, so the count is current without slowing every screen down. */
    fun refresh() {
        viewModelScope.launch {
            // If the reports can't be read, the panel keeps the last count rather than showing an error.
            val reports = runCatching { repository.complianceReports() }.getOrNull() ?: return@launch
            _info.value = AuditorDrawerInfo(tendersWithProblem = reports.count { !it.compliant })
        }
    }
}

/**
 * Wraps an auditor screen in the navigation panel. The screen receives
 * `openDrawer` for its menu button; the panel also opens with a swipe from the
 * left edge.
 */
@Composable
fun AuditorDrawerHost(
    navController: NavHostController,
    currentRoute: String,
    currentProfile: () -> Profile?,
    onSignOut: () -> Unit,
    content: @Composable (openDrawer: () -> Unit) -> Unit
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val viewModel: AuditorDrawerViewModel = viewModel(key = "auditor_drawer")
    val info by viewModel.info.collectAsState()

    LaunchedEffect(drawerState.currentValue) {
        if (drawerState.currentValue == DrawerValue.Open) viewModel.refresh()
    }

    fun close() {
        scope.launch { drawerState.close() }
    }

    // Back closes the panel first, instead of leaving the screen behind it.
    BackHandler(enabled = drawerState.isOpen) { close() }

    fun go(route: String) {
        close()
        if (route == AuditorRoutes.HOME && currentRoute == AuditorRoutes.HOME) return
        navController.navigate(route) {
            launchSingleTop = true
            // The dashboard stays underneath, so Back from any panel destination returns to it.
            popUpTo(AuditorRoutes.HOME) { inclusive = false }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AuditorDrawer(
                profile = currentProfile(),
                info = info,
                currentRoute = currentRoute,
                onNavigate = { route -> go(route) },
                onSignOut = {
                    close()
                    onSignOut()
                }
            )
        }
    ) {
        content { scope.launch { drawerState.open() } }
    }
}

@Composable
private fun AuditorDrawer(
    profile: Profile?,
    info: AuditorDrawerInfo,
    currentRoute: String,
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
                    .padding(start = Dimens.ScreenPadding, end = Dimens.ScreenPadding, top = 22.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AppIcon(Icons.Default.Security, tint = AppColor.Ink, size = 22.dp)
                Text("TenderTrack", style = AppType.H2.copy(fontSize = 19.sp))
            }
            Text(
                "Auditor · read-only",
                style = AppType.Meta,
                modifier = Modifier.padding(start = Dimens.ScreenPadding + 32.dp, end = Dimens.ScreenPadding, bottom = 14.dp)
            )

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                DrawerItem("Dashboard", Icons.Default.BarChart, currentRoute == AuditorRoutes.HOME) {
                    onNavigate(AuditorRoutes.HOME)
                }

                DrawerGroup("Tender records")
                DrawerItem("All tender records", Icons.Default.Description, currentRoute == AuditorRoutes.RECORDS) {
                    onNavigate(AuditorRoutes.records())
                }
                // Each opens Tender Records with that status chip already chosen.
                listOf(TenderStatus.PUBLISHED, TenderStatus.UNDER_EVALUATION, TenderStatus.AWARDED).forEach { status ->
                    DrawerSubItem(status.displayName) { onNavigate(AuditorRoutes.records(status)) }
                }

                DrawerGroup("Verification")
                DrawerItem(
                    "Compliance reports", Icons.Default.FactCheck,
                    currentRoute == AuditorRoutes.COMPLIANCE,
                    badge = info.tendersWithProblem,
                    badgeTone = BadgeTone.Danger
                ) { onNavigate(AuditorRoutes.compliance()) }
                DrawerSubItem("Needs attention") { onNavigate(AuditorRoutes.compliance(needsAttention = true)) }
                DrawerItem("Audit logs", Icons.Default.History, currentRoute == AuditorRoutes.LOGS) {
                    onNavigate(AuditorRoutes.LOGS)
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
                    .clickable { onSignOut() }
                    .padding(horizontal = Dimens.ScreenPadding, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Avatar(profile?.initials ?: "?")
                Column(Modifier.weight(1f)) {
                    Text(profile?.fullName ?: "Not signed in", style = AppType.KvValue)
                    Text("Auditor · sign out", style = AppType.Tiny.copy(color = AppColor.Muted))
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
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
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
