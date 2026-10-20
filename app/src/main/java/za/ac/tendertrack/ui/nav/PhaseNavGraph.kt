package za.ac.tendertrack.ui.nav

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import za.ac.tendertrack.ui.screens.phases.PhaseCheckScreen
import za.ac.tendertrack.ui.screens.phases.PhaseChecksScreen

/**
 * The procurement officer's Phase checks: comparing each supplier photo with
 * the auditor's site photo, verifying the phase and marking "Payment can be
 * made". Added to NavGraph.kt with one call:
 *
 *     phaseGraph(
 *         navController = navController,
 *         onMenu = { scope.launch { drawerState.open() } },
 *         onChanged = { dashboardViewModel.load() }
 *     )
 *
 * These are officer screens, so the officer's drawer works on them as usual.
 */
object PhaseRoutes {
    /** Every phase waiting for someone; with a tender, all phases of that contract. */
    const val CHECKS = "phase_checks?tenderId={tenderId}"
    const val CHECK = "phase_check/{tenderId}/{deliverableId}"

    fun checks(tenderId: String? = null) = "phase_checks?tenderId=${tenderId ?: ""}"
    fun check(tenderId: String, deliverableId: String) = "phase_check/$tenderId/$deliverableId"
}

fun NavGraphBuilder.phaseGraph(
    navController: NavHostController,
    onMenu: () -> Unit,
    onChanged: () -> Unit = {}
) {
    composable(
        route = PhaseRoutes.CHECKS,
        arguments = listOf(navArgument("tenderId") { type = NavType.StringType; defaultValue = "" })
    ) { entry ->
        val tenderId = entry.arguments?.getString("tenderId").orEmpty().ifBlank { null }
        PhaseChecksScreen(
            tenderId = tenderId,
            // From the drawer: the menu button. For one contract (from a tender card): Back.
            onMenu = if (tenderId == null) onMenu else null,
            onBack = { navController.popBackStack() },
            onOpen = { phase -> navController.navigate(PhaseRoutes.check(phase.tenderId, phase.id)) }
        )
    }

    composable(
        route = PhaseRoutes.CHECK,
        arguments = listOf(
            navArgument("tenderId") { type = NavType.StringType },
            navArgument("deliverableId") { type = NavType.StringType }
        )
    ) { entry ->
        PhaseCheckScreen(
            tenderId = entry.arguments?.getString("tenderId").orEmpty(),
            deliverableId = entry.arguments?.getString("deliverableId").orEmpty(),
            onBack = { navController.popBackStack() },
            // Another phase of the same contract replaces this one, so Back still returns to the list.
            onOpenPhase = { phase ->
                navController.navigate(PhaseRoutes.check(phase.tenderId, phase.id)) {
                    popUpTo(PhaseRoutes.CHECK) { inclusive = true }
                }
            },
            onRecordPayment = { tenderId -> navController.navigate(Routes.recordPayment(tenderId)) },
            onChanged = onChanged
        )
    }
}
