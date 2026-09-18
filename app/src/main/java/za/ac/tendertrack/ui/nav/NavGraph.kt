package za.ac.tendertrack.ui.nav

import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import za.ac.tendertrack.core.UiState
import za.ac.tendertrack.data.ServiceLocator
import za.ac.tendertrack.data.model.Profile
import za.ac.tendertrack.ui.screens.*

/**
 * The whole Procurement Officer flow.
 *
 * Sign in sits outside the drawer; everything after it is wrapped in the drawer
 * so the navigation is available from any top-level screen.
 */
@Composable
fun TenderTrackNavGraph(navController: NavHostController = rememberNavController()) {

    var profile by remember { mutableStateOf<Profile?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Kept at this level so the drawer badges stay in step with the dashboard.
    val dashboardViewModel: DashboardViewModel = viewModel()
    val dashboardState by dashboardViewModel.state.collectAsState()
    val summary = (dashboardState as? UiState.Success)?.data

    fun closeDrawer() = scope.launch { drawerState.close() }

    fun go(route: String) {
        closeDrawer()
        navController.navigate(route) {
            launchSingleTop = true
            if (route in Routes.drawerDestinations || route.startsWith("payment")) {
                popUpTo(Routes.DASHBOARD) { inclusive = false }
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = currentRoute != Routes.SIGN_IN,
        drawerContent = {
            AppDrawer(
                profile = profile,
                summary = summary,
                currentRoute = currentRoute,
                onNavigate = ::go,
                onSignOut = {
                    closeDrawer()
                    scope.launch {
                        ServiceLocator.authRepository.signOut()
                        profile = null
                        navController.navigate(Routes.SIGN_IN) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                }
            )
        }
    ) {
        NavHost(navController = navController, startDestination = Routes.SIGN_IN) {

            composable(Routes.SIGN_IN) {
                SignInScreen(
                    onSignedIn = { signedInProfile ->
                        profile = signedInProfile
                        dashboardViewModel.load()
                        navController.navigate(Routes.DASHBOARD) {
                            popUpTo(Routes.SIGN_IN) { inclusive = true }
                        }
                    }
                )
            }

            composable(Routes.DASHBOARD) {
                DashboardScreen(
                    profile = profile,
                    onMenu = { scope.launch { drawerState.open() } },
                    onOpenNotifications = { go(Routes.NOTIFICATIONS) },
                    onOpenFlags = { go(Routes.FLAGS) },
                    onOpenSuppliers = { go(Routes.SUPPLIERS) },
                    onOpenTenders = { go(Routes.TENDERS) },
                    onOpenFunds = { go(Routes.FUND_UTILISATION) },
                    viewModel = dashboardViewModel
                )
            }

            composable(Routes.TENDERS) {
                TenderListScreen(
                    onMenu = { scope.launch { drawerState.open() } },
                    onOpenTender = { id -> navController.navigate(Routes.tenderDetail(id)) },
                    onRegisterTender = { navController.navigate(Routes.tenderForm()) }
                )
            }

            composable(
                route = Routes.TENDER_DETAIL,
                arguments = listOf(navArgument("tenderId") { type = NavType.StringType })
            ) { entry ->
                val id = entry.arguments?.getString("tenderId").orEmpty()
                TenderDetailScreen(
                    tenderId = id,
                    onBack = { navController.popBackStack() },
                    onEdit = { navController.navigate(Routes.tenderForm(it)) },
                    onLifecycle = { navController.navigate(Routes.lifecycle(it)) },
                    onAward = { navController.navigate(Routes.award(it)) },
                    onRecordPayment = { navController.navigate(Routes.recordPayment(it)) }
                )
            }

            composable(
                route = Routes.TENDER_FORM,
                arguments = listOf(navArgument("tenderId") {
                    type = NavType.StringType; defaultValue = ""
                })
            ) { entry ->
                val raw = entry.arguments?.getString("tenderId").orEmpty()
                TenderFormScreen(
                    tenderId = raw.ifBlank { null },
                    onBack = { navController.popBackStack() },
                    onSaved = { id ->
                        dashboardViewModel.load()
                        navController.navigate(Routes.tenderDetail(id)) {
                            popUpTo(Routes.TENDERS) { inclusive = false }
                        }
                    }
                )
            }

            composable(
                route = Routes.LIFECYCLE,
                arguments = listOf(navArgument("tenderId") { type = NavType.StringType })
            ) { entry ->
                LifecycleScreen(
                    tenderId = entry.arguments?.getString("tenderId").orEmpty(),
                    onBack = {
                        dashboardViewModel.load()
                        navController.popBackStack()
                    }
                )
            }

            composable(
                route = Routes.AWARD,
                arguments = listOf(navArgument("tenderId") { type = NavType.StringType })
            ) { entry ->
                AwardTenderScreen(
                    tenderId = entry.arguments?.getString("tenderId").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onAwarded = {
                        dashboardViewModel.load()
                        navController.popBackStack()
                    }
                )
            }

            composable(Routes.SUPPLIERS) {
                SupplierRegistrationsScreen(
                    onMenu = { scope.launch { drawerState.open() } },
                    onOpenSupplier = { navController.navigate(Routes.supplierReview(it)) }
                )
            }

            composable(
                route = Routes.SUPPLIER_REVIEW,
                arguments = listOf(navArgument("supplierId") { type = NavType.StringType })
            ) { entry ->
                SupplierReviewScreen(
                    supplierId = entry.arguments?.getString("supplierId").orEmpty(),
                    onBack = {
                        dashboardViewModel.load()
                        navController.popBackStack()
                    }
                )
            }

            composable(
                route = Routes.RECORD_PAYMENT,
                arguments = listOf(navArgument("tenderId") {
                    type = NavType.StringType; defaultValue = ""
                })
            ) { entry ->
                val raw = entry.arguments?.getString("tenderId").orEmpty()
                val cameFromTender = raw.isNotBlank()
                // Reached from a tender it shows Back; reached from the drawer it
                // shows the menu button. Only ever one of the two.
                val menuAction: (() -> Unit)? =
                    if (cameFromTender) null else { { scope.launch { drawerState.open() } } }
                val backAction: (() -> Unit)? =
                    if (cameFromTender) { { navController.popBackStack() } } else null
                RecordPaymentScreen(
                    tenderId = raw.ifBlank { null },
                    onMenu = menuAction,
                    onBack = backAction,
                    onRecorded = { dashboardViewModel.load() }
                )
            }

            composable(Routes.FUND_UTILISATION) {
                FundUtilisationScreen(
                    onMenu = { scope.launch { drawerState.open() } },
                    onOpenTender = { navController.navigate(Routes.tenderDetail(it)) }
                )
            }

            composable(Routes.FLAGS) {
                FlagsScreen(
                    onMenu = { scope.launch { drawerState.open() } },
                    onOpenFlag = { navController.navigate(Routes.flagDetail(it)) }
                )
            }

            composable(
                route = Routes.FLAG_DETAIL,
                arguments = listOf(navArgument("flagId") { type = NavType.StringType })
            ) { entry ->
                FlagDetailScreen(
                    flagId = entry.arguments?.getString("flagId").orEmpty(),
                    onBack = {
                        dashboardViewModel.load()
                        navController.popBackStack()
                    },
                    onOpenTender = { navController.navigate(Routes.tenderDetail(it)) }
                )
            }

            composable(Routes.REPORTS) {
                ReportsScreen(onMenu = { scope.launch { drawerState.open() } })
            }

            composable(Routes.NOTIFICATIONS) {
                NotificationsScreen(
                    onMenu = { scope.launch { drawerState.open() } },
                    onOpenFlags = { go(Routes.FLAGS) },
                    onOpenSuppliers = { go(Routes.SUPPLIERS) },
                    onOpenTenders = { go(Routes.TENDERS) }
                )
            }
        }
    }
}
