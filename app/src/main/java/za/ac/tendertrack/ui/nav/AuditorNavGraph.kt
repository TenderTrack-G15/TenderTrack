package za.ac.tendertrack.ui.nav

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import za.ac.tendertrack.data.model.Profile
import za.ac.tendertrack.ui.screens.auditor.*

/**
 * The Auditor flow. All routes start with "auditor_", which keeps the officer
 * drawer closed here: the Auditor has no officer functions at all.
 */
object AuditorRoutes {
    const val HOME = "auditor_home"
    const val RECORDS = "auditor_records"
    const val RECORD = "auditor_record/{tenderId}"
    const val LOGS = "auditor_logs"
    const val COMPLIANCE = "auditor_compliance"

    fun record(id: String) = "auditor_record/$id"

    fun isAuditorRoute(route: String?): Boolean = route?.startsWith("auditor_") == true
}

fun NavGraphBuilder.auditorGraph(
    navController: NavHostController,
    currentProfile: () -> Profile?,
    onSignOut: () -> Unit
) {
    composable(AuditorRoutes.HOME) {
        AuditorHomeScreen(
            auditorName = currentProfile()?.fullName ?: "Auditor",
            onOpenRecords = { navController.navigate(AuditorRoutes.RECORDS) },
            onOpenLogs = { navController.navigate(AuditorRoutes.LOGS) },
            onOpenCompliance = { navController.navigate(AuditorRoutes.COMPLIANCE) },
            onSignOut = onSignOut
        )
    }

    composable(AuditorRoutes.RECORDS) {
        TenderRecordsScreen(
            onBack = { navController.popBackStack() },
            onOpenRecord = { id -> navController.navigate(AuditorRoutes.record(id)) }
        )
    }

    composable(
        route = AuditorRoutes.RECORD,
        arguments = listOf(navArgument("tenderId") { type = NavType.StringType })
    ) { entry ->
        TenderRecordScreen(
            tenderId = entry.arguments?.getString("tenderId").orEmpty(),
            onBack = { navController.popBackStack() }
        )
    }

    composable(AuditorRoutes.LOGS) {
        AuditLogsScreen(onBack = { navController.popBackStack() })
    }

    composable(AuditorRoutes.COMPLIANCE) {
        ComplianceReportsScreen(
            onBack = { navController.popBackStack() },
            onOpenRecord = { id -> navController.navigate(AuditorRoutes.record(id)) }
        )
    }
}
