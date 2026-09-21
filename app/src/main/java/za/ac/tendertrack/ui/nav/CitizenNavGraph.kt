package za.ac.tendertrack.ui.nav

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
 * Back-stack: Sign In → Public Dashboard → (search / spend / report / track).
 * Back from the dashboard returns to Sign In.
 */
fun NavGraphBuilder.citizenGraph(navController: NavHostController) {

    composable(CitizenRoutes.HOME) {
        PublicHomeScreen(
            onBack = { navController.popBackStack() },
            onOpenTenders = { status -> navController.navigate(CitizenRoutes.tenders(status)) },
            onOpenSpend = { navController.navigate(CitizenRoutes.SPEND) { launchSingleTop = true } },
            onFlagTender = { navController.navigate(CitizenRoutes.report()) },
            onTrackReport = { navController.navigate(CitizenRoutes.track()) }
        )
    }

    composable(
        route = CitizenRoutes.TENDERS,
        arguments = listOf(navArgument("status") { type = NavType.StringType; defaultValue = "" })
    ) { entry ->
        val raw = entry.arguments?.getString("status").orEmpty()
        // An unknown value falls back to "all" rather than crashing.
        val status = TenderStatus.entries.firstOrNull { it.name == raw }
        PublicTenderListScreen(
            initialStatus = status,
            onBack = { navController.popBackStack() },
            onOpenTender = { id -> navController.navigate(CitizenRoutes.tenderDetail(id)) }
        )
    }

    composable(
        route = CitizenRoutes.TENDER_DETAIL,
        arguments = listOf(navArgument("tenderId") { type = NavType.StringType })
    ) { entry ->
        PublicTenderDetailScreen(
            tenderId = entry.arguments?.getString("tenderId").orEmpty(),
            onBack = { navController.popBackStack() },
            onFlag = { id -> navController.navigate(CitizenRoutes.report(id)) }
        )
    }

    composable(
        route = CitizenRoutes.REPORT,
        arguments = listOf(navArgument("tenderId") { type = NavType.StringType; defaultValue = "" })
    ) { entry ->
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

    composable(
        route = CitizenRoutes.TRACK,
        arguments = listOf(navArgument("reference") { type = NavType.StringType; defaultValue = "" })
    ) { entry ->
        val raw = entry.arguments?.getString("reference").orEmpty()
        TrackReportScreen(
            initialReference = raw.ifBlank { null },
            onBack = { navController.popBackStack() },
            onOpenTender = { id -> navController.navigate(CitizenRoutes.tenderDetail(id)) }
        )
    }

    composable(CitizenRoutes.SPEND) {
        PublicSpendScreen(
            onBack = { navController.popBackStack() },
            onOpenTenders = { navController.navigate(CitizenRoutes.tenders()) }
        )
    }
}
