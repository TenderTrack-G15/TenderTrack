package za.ac.tendertrack.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import za.ac.tendertrack.data.model.Profile
import za.ac.tendertrack.data.model.TenderStatus
import za.ac.tendertrack.ui.screens.auditor.*

/**
 * The Auditor flow. All routes start with "auditor_", which keeps the officer
 * drawer closed here: the Auditor has no officer functions at all.
 *
 * Navigation panel: every auditor screen sits in the auditor's own panel
 * (AuditorDrawer.kt), which opens with a swipe from the left edge. The main
 * screens (Dashboard, Tender Records, Compliance Reports, Audit Logs) show the
 * menu button; a tender record keeps its Back arrow, as on the officer's side.
 */
object AuditorRoutes {
    const val HOME = "auditor_home"
    /** Optional status, so the panel can open the list already filtered. */
    const val RECORDS = "auditor_records?status={status}"
    const val RECORD = "auditor_record/{tenderId}"
    const val LOGS = "auditor_logs"
    /** Optional filter, so the panel can open "Needs attention" directly. */
    const val COMPLIANCE = "auditor_compliance?filter={filter}"

    private const val NEEDS_ATTENTION = "attention"

    /** Tender Records, showing every status, or only [status]. */
    fun records(status: TenderStatus? = null) =
        if (status == null) "auditor_records" else "auditor_records?status=${status.name}"

    /** Compliance Reports, showing every tender, or only those with a check not met. */
    fun compliance(needsAttention: Boolean = false) =
        if (needsAttention) "auditor_compliance?filter=$NEEDS_ATTENTION" else "auditor_compliance"

    fun record(id: String) = "auditor_record/$id"

    fun isAuditorRoute(route: String?): Boolean = route?.startsWith("auditor_") == true

    internal fun statusOf(entry: NavBackStackEntry): TenderStatus? =
        entry.arguments?.getString("status")?.let { name -> TenderStatus.entries.firstOrNull { it.name == name } }

    internal fun needsAttention(entry: NavBackStackEntry): Boolean =
        entry.arguments?.getString("filter") == NEEDS_ATTENTION
}

fun NavGraphBuilder.auditorGraph(
    navController: NavHostController,
    currentProfile: () -> Profile?,
    onSignOut: () -> Unit
) {
    // Adds an auditor destination wrapped in the navigation panel.
    fun screen(
        route: String,
        arguments: List<NamedNavArgument> = emptyList(),
        content: @Composable (entry: NavBackStackEntry, openDrawer: () -> Unit) -> Unit
    ) {
        composable(route = route, arguments = arguments) { entry ->
            AuditorDrawerHost(navController, route, currentProfile, onSignOut) { openDrawer ->
                content(entry, openDrawer)
            }
        }
    }

    screen(AuditorRoutes.HOME) { _, openDrawer ->
        AuditorHomeScreen(
            auditorName = currentProfile()?.fullName ?: "Auditor",
            onOpenRecords = { navController.navigate(AuditorRoutes.records()) },
            onOpenLogs = { navController.navigate(AuditorRoutes.LOGS) },
            onOpenCompliance = { navController.navigate(AuditorRoutes.compliance()) },
            onSignOut = onSignOut,
            onMenu = openDrawer
        )
    }

    screen(
        route = AuditorRoutes.RECORDS,
        arguments = listOf(navArgument("status") { type = NavType.StringType; defaultValue = "" })
    ) { entry, openDrawer ->
        TenderRecordsScreen(
            onBack = { navController.popBackStack() },
            onOpenRecord = { id -> navController.navigate(AuditorRoutes.record(id)) },
            status = AuditorRoutes.statusOf(entry),
            onMenu = openDrawer
        )
    }

    screen(
        route = AuditorRoutes.RECORD,
        arguments = listOf(navArgument("tenderId") { type = NavType.StringType })
    ) { entry, _ ->
        TenderRecordScreen(
            tenderId = entry.arguments?.getString("tenderId").orEmpty(),
            onBack = { navController.popBackStack() }
        )
    }

    screen(AuditorRoutes.LOGS) { _, openDrawer ->
        AuditLogsScreen(
            onBack = { navController.popBackStack() },
            onMenu = openDrawer
        )
    }

    screen(
        route = AuditorRoutes.COMPLIANCE,
        arguments = listOf(navArgument("filter") { type = NavType.StringType; defaultValue = "" })
    ) { entry, openDrawer ->
        ComplianceReportsScreen(
            onBack = { navController.popBackStack() },
            onOpenRecord = { id -> navController.navigate(AuditorRoutes.record(id)) },
            onlyProblems = AuditorRoutes.needsAttention(entry),
            onMenu = openDrawer
        )
    }
}
