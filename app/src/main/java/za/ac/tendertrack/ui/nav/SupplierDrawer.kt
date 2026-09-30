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
import za.ac.tendertrack.data.repo.AwardRepository
import za.ac.tendertrack.data.repo.RegistrationRepository
import za.ac.tendertrack.data.repo.SupplierPortalRepository
import za.ac.tendertrack.ui.components.*
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens

/*
 * The supplier's navigation panel: the same look as the procurement officer's
 * drawer (AppDrawer.kt), with the supplier's own destinations. It lives in the
 * supplier graph, so NavGraph.kt needs no changes; the officer's drawer stays
 * closed on "supplier_" routes as before.
 */

/** The counts shown next to the panel's items. */
data class SupplierDrawerInfo(
    val companyName: String = "",
    val awaitingCode: Int = 0,
    val documentsOutstanding: Int = 0,
    /** False until the company has registered for TenderTrack as well as on the portal. */
    val registeredForTenderTrack: Boolean = true
)

class SupplierDrawerViewModel(
    private val portal: SupplierPortalRepository = ServiceLocator.supplierPortalRepository,
    private val awards: AwardRepository = AwardRepository.instance,
    private val registration: RegistrationRepository = RegistrationRepository.instance
) : ViewModel() {

    private val _info = MutableStateFlow(SupplierDrawerInfo())
    val info: StateFlow<SupplierDrawerInfo> = _info.asStateFlow()

    /** Called when the panel opens, so the counts are current without slowing every screen down. */
    fun refresh() {
        viewModelScope.launch {
            val status = runCatching { registration.status() }.getOrNull()
            val profile = runCatching { portal.myProfile() }.getOrNull()
            val registered = status?.appRegistered != false
            val list = if (registered) runCatching { awards.awards() }.getOrDefault(emptyList()) else emptyList()
            _info.value = SupplierDrawerInfo(
                companyName = profile?.companyName ?: status?.companyName.orEmpty(),
                awaitingCode = list.count { it.canEnterCode },
                documentsOutstanding = profile?.let { (it.documentsRequired - it.documentsReceived).coerceAtLeast(0) } ?: 0,
                registeredForTenderTrack = registered
            )
        }
    }
}

/**
 * Wraps a supplier screen in the navigation panel. The screen receives
 * `openDrawer` for its menu button; the panel also opens with a swipe from the
 * left edge.
 */
@Composable
fun SupplierDrawerHost(
    navController: NavHostController,
    currentRoute: String,
    currentProfile: () -> Profile?,
    onSignOut: () -> Unit,
    content: @Composable (openDrawer: () -> Unit) -> Unit
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val viewModel: SupplierDrawerViewModel = viewModel(key = "supplier_drawer")
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
        if (route == currentRoute) return
        navController.navigate(route) {
            launchSingleTop = true
            // Dashboard stays underneath, so Back from any panel destination returns to it.
            popUpTo(SupplierRoutes.HOME) { inclusive = false }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SupplierDrawer(
                profile = currentProfile(),
                info = info,
                currentRoute = currentRoute,
                onNavigate = { route -> go(route) },
                onRegister = {
                    close()
                    navController.navigate(AccountRoutes.SUPPLIER_SIGN_UP) { launchSingleTop = true }
                },
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
private fun SupplierDrawer(
    profile: Profile?,
    info: SupplierDrawerInfo,
    currentRoute: String,
    onNavigate: (String) -> Unit,
    onRegister: () -> Unit,
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
                info.companyName.ifBlank { "Supplier" },
                style = AppType.Meta,
                maxLines = 2,
                modifier = Modifier.padding(start = Dimens.ScreenPadding + 32.dp, end = Dimens.ScreenPadding, bottom = 14.dp)
            )

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                if (!info.registeredForTenderTrack) {
                    DrawerItem(
                        "Register for TenderTrack", Icons.Default.HowToReg, selected = false,
                        badge = 1, badgeTone = BadgeTone.Warning, onClick = onRegister
                    )
                }

                DrawerItem("Dashboard", Icons.Default.Dashboard, currentRoute == SupplierRoutes.HOME) {
                    onNavigate(SupplierRoutes.HOME)
                }

                DrawerGroup("Awards & contracts")
                DrawerItem(
                    "My awards", Icons.Default.EmojiEvents,
                    currentRoute == SupplierRoutes.AWARDS,
                    badge = info.awaitingCode,
                    badgeTone = BadgeTone.Warning
                ) { onNavigate(SupplierRoutes.AWARDS) }

                DrawerGroup("My company")
                DrawerItem("Company profile", Icons.Default.Business, currentRoute == SupplierRoutes.COMPANY) {
                    onNavigate(SupplierRoutes.COMPANY)
                }
                DrawerItem(
                    "Supporting documents", Icons.Default.Description,
                    currentRoute == SupplierRoutes.DOCUMENTS,
                    badge = info.documentsOutstanding,
                    badgeTone = BadgeTone.Danger
                ) { onNavigate(SupplierRoutes.DOCUMENTS) }
                DrawerItem("Banking details", Icons.Default.AccountBalance, currentRoute == SupplierRoutes.BANKING) {
                    onNavigate(SupplierRoutes.BANKING)
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
                    Text("Supplier · sign out", style = AppType.Tiny.copy(color = AppColor.Muted))
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
