package za.ac.tendertrack.ui.nav

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import za.ac.tendertrack.data.model.Profile
import za.ac.tendertrack.ui.screens.admin.*

/**
 * Every destination in the Administrator flow. All routes start with "admin_",
 * which is how the main NavGraph keeps the officer drawer closed here — the
 * Administrator has their own menu, with no tender actions (Deliverable 3).
 */
object AdminRoutes {
    const val HOME = "admin_home"
    const val ACCOUNTS = "admin_accounts?filter={filter}"
    const val ACCOUNT = "admin_account/{accountId}"
    const val ROLES = "admin_roles"
    const val INVITATIONS = "admin_invitations"
    const val INVITATION = "admin_invitation?invitationId={invitationId}"
    const val AUDIT = "admin_audit"
    const val EXPORT = "admin_export"

    fun accounts(filter: AccountFilter = AccountFilter.ALL) = "admin_accounts?filter=${filter.name}"
    fun account(id: String) = "admin_account/$id"
    fun invitation(id: String? = null) = "admin_invitation?invitationId=${id ?: ""}"

    fun isAdminRoute(route: String?): Boolean = route?.startsWith("admin_") == true
}

/**
 * Adds the Administrator screens to the app's NavHost, with one call from
 * TenderTrackNavGraph.
 *
 * Back-stack: Admin Dashboard is the root (Sign In is removed when an
 * administrator signs in, like the officer dashboard). Every other admin
 * screen returns to where it was opened from.
 */
fun NavGraphBuilder.adminGraph(
    navController: NavHostController,
    currentProfile: () -> Profile?,
    onSignOut: () -> Unit
) {
    composable(AdminRoutes.HOME) {
        AdminDashboardScreen(
            adminName = currentProfile()?.fullName ?: "Administrator",
            onOpenAccounts = { filter -> navController.navigate(AdminRoutes.accounts(filter)) },
            onOpenInvitations = { navController.navigate(AdminRoutes.INVITATIONS) },
            onOpenRoles = { navController.navigate(AdminRoutes.ROLES) },
            onOpenAudit = { navController.navigate(AdminRoutes.AUDIT) },
            onOpenExport = { navController.navigate(AdminRoutes.EXPORT) },
            onSignOut = onSignOut
        )
    }

    composable(
        route = AdminRoutes.ACCOUNTS,
        arguments = listOf(navArgument("filter") { type = NavType.StringType; defaultValue = "ALL" })
    ) { entry ->
        val raw = entry.arguments?.getString("filter").orEmpty()
        UserAccountsScreen(
            initialFilter = AccountFilter.entries.firstOrNull { it.name == raw } ?: AccountFilter.ALL,
            onBack = { navController.popBackStack() },
            onOpenAccount = { id -> navController.navigate(AdminRoutes.account(id)) },
            onInvite = { navController.navigate(AdminRoutes.INVITATIONS) }
        )
    }

    composable(
        route = AdminRoutes.ACCOUNT,
        arguments = listOf(navArgument("accountId") { type = NavType.StringType })
    ) { entry ->
        AccountDetailScreen(
            accountId = entry.arguments?.getString("accountId").orEmpty(),
            currentAdminId = currentProfile()?.id,
            onBack = { navController.popBackStack() }
        )
    }

    composable(AdminRoutes.ROLES) {
        RolesScreen(onBack = { navController.popBackStack() })
    }

    composable(AdminRoutes.INVITATIONS) {
        InvitationsScreen(
            onBack = { navController.popBackStack() },
            onNew = { navController.navigate(AdminRoutes.invitation()) },
            onOpen = { id -> navController.navigate(AdminRoutes.invitation(id)) }
        )
    }

    composable(
        route = AdminRoutes.INVITATION,
        arguments = listOf(navArgument("invitationId") { type = NavType.StringType; defaultValue = "" })
    ) { entry ->
        InvitationFormScreen(
            invitationId = entry.arguments?.getString("invitationId").orEmpty().ifBlank { null },
            onDone = { navController.popBackStack() }
        )
    }

    composable(AdminRoutes.AUDIT) {
        AuditLogScreen(onBack = { navController.popBackStack() })
    }

    composable(AdminRoutes.EXPORT) {
        DataExportScreen(onBack = { navController.popBackStack() })
    }
}
