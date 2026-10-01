package za.ac.tendertrack.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import za.ac.tendertrack.data.model.TenderStatus
import za.ac.tendertrack.ui.screens.citizen.*

/**
 * Every destination in the Public / citizen flow.
 *
 * All routes start with "public_", which is how the main NavGraph knows to
 * keep the officer drawer closed on these screens.
 */
object CitizenRoutes {
    const val HOME = "public_home"
    const val TENDERS = "public_tenders?status={status}"
    const val TENDER_DETAIL = "public_tender/{tenderId}"
    const val REPORT = "public_report?tenderId={tenderId}"
    const val TRACK = "public_track?reference={reference}"
    const val SPEND = "public_spend"

    fun tenders(status: TenderStatus? = null) = "public_tenders?status=${status?.name ?: ""}"
    fun tenderDetail(id: String) = "public_tender/$id"
    fun report(tenderId: String? = null) = "public_report?tenderId=${tenderId ?: ""}"
    fun track(reference: String? = null) = "public_track?reference=${reference ?: ""}"

    /** True for any citizen screen. */
    fun isCitizenRoute(route: String?): Boolean = route?.startsWith("public_") == true
}

/**
 * Adds the citizen screens to the app's NavHost. Called with one line from
 * TenderTrackNavGraph, so this flow can change without touching the officer
 * navigation.
 *
 * Back-stack: Welcome → Public Dashboard → (search / spend / report / track).
 * Back from the dashboard returns to Welcome.
 *
 * Navigation panel: every citizen screen sits in the public navigation panel
 * (CitizenDrawer.kt), which opens with a swipe from the left edge. The main
 * screens (Dashboard, Search Tenders, Spending) show the menu button; a tender,
 * the report form and report tracking keep their Back arrow.
 */
fun NavGraphBuilder.citizenGraph(navController: NavHostController) {

    // Adds a citizen destination wrapped in the navigation panel.
    fun screen(
        route: String,
        arguments: List<NamedNavArgument> = emptyList(),
        content: @Composable (entry: NavBackStackEntry, openDrawer: () -> Unit) -> Unit
    ) {
        composable(route = route, arguments = arguments) { entry ->
            CitizenDrawerHost(navController, route) { openDrawer -> content(entry, openDrawer) }
        }
    }

    screen(CitizenRoutes.HOME) { _, openDrawer ->
        PublicHomeScreen(
            onBack = { navController.popBackStack() },
            onOpenTenders = { status -> navController.navigate(CitizenRoutes.tenders(status)) },
            onOpenSpend = { navController.navigate(CitizenRoutes.SPEND) { launchSingleTop = true } },
            onFlagTender = { navController.navigate(CitizenRoutes.report()) },
            onTrackReport = { navController.navigate(CitizenRoutes.track()) },
            onMenu = openDrawer
        )
    }

    screen(
        route = CitizenRoutes.TENDERS,
        arguments = listOf(navArgument("status") { type = NavType.StringType; defaultValue = "" })
    ) { entry, openDrawer ->
        val raw = entry.arguments?.getString("status").orEmpty()
        // An unknown value falls back to "all" rather than crashing.
        val status = TenderStatus.entries.firstOrNull { it.name == raw }
        PublicTenderListScreen(
            initialStatus = status,
            onBack = { navController.popBackStack() },
            onOpenTender = { id -> navController.navigate(CitizenRoutes.tenderDetail(id)) },
            onMenu = openDrawer
        )
    }

    screen(
        route = CitizenRoutes.TENDER_DETAIL,
        arguments = listOf(navArgument("tenderId") { type = NavType.StringType })
    ) { entry, _ ->
        PublicTenderDetailScreen(
            tenderId = entry.arguments?.getString("tenderId").orEmpty(),
            onBack = { navController.popBackStack() },
            onFlag = { id -> navController.navigate(CitizenRoutes.report(id)) }
        )
    }

    screen(
        route = CitizenRoutes.REPORT,
        arguments = listOf(navArgument("tenderId") { type = NavType.StringType; defaultValue = "" })
    ) { entry, _ ->
        val raw = entry.arguments?.getString("tenderId").orEmpty()
        ReportConcernScreen(
            tenderId = raw.ifBlank { null },
            onBack = { navController.popBackStack() },
            onTrack = { reference ->
                // Replace the finished form with the tracking screen, so Back
                // does not return to a form that has already been submitted.
                navController.navigate(CitizenRoutes.track(reference)) {
                    popUpTo(CitizenRoutes.REPORT) { inclusive = true }
                }
            }
        )
    }

    screen(
        route = CitizenRoutes.TRACK,
        arguments = listOf(navArgument("reference") { type = NavType.StringType; defaultValue = "" })
    ) { entry, _ ->
        val raw = entry.arguments?.getString("reference").orEmpty()
        TrackReportScreen(
            initialReference = raw.ifBlank { null },
            onBack = { navController.popBackStack() },
            onOpenTender = { id -> navController.navigate(CitizenRoutes.tenderDetail(id)) }
        )
    }

    screen(CitizenRoutes.SPEND) { _, openDrawer ->
        PublicSpendScreen(
            onBack = { navController.popBackStack() },
            onOpenTenders = { navController.navigate(CitizenRoutes.tenders()) },
            onMenu = openDrawer
        )
    }
}
