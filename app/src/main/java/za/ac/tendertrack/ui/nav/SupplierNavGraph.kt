package za.ac.tendertrack.ui.nav

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import za.ac.tendertrack.data.model.Profile
import za.ac.tendertrack.ui.screens.supplier.*

/**
 * The Supplier flow (Deliverable 3, section 5.3). All routes start with
 * "supplier_", which keeps the officer drawer closed here.
 */
object SupplierRoutes {
    const val HOME = "supplier_home"
    const val TENDER = "supplier_tender/{tenderId}"
    const val COMPANY = "supplier_company"
    const val COMPANY_EDIT = "supplier_company_edit"
    const val BANKING = "supplier_banking"
    const val DOCUMENTS = "supplier_documents"

    fun tender(id: String) = "supplier_tender/$id"

    fun isSupplierRoute(route: String?): Boolean = route?.startsWith("supplier_") == true
}

fun NavGraphBuilder.supplierGraph(
    navController: NavHostController,
    currentProfile: () -> Profile?,
    onSignOut: () -> Unit
) {
    composable(SupplierRoutes.HOME) {
        SupplierHomeScreen(
            supplierName = currentProfile()?.fullName ?: "Supplier",
            onOpenTender = { id -> navController.navigate(SupplierRoutes.tender(id)) },
            onOpenCompany = { navController.navigate(SupplierRoutes.COMPANY) },
            onSignOut = onSignOut
        )
    }

    composable(
        route = SupplierRoutes.TENDER,
        arguments = listOf(navArgument("tenderId") { type = NavType.StringType })
    ) { entry ->
        SupplierTenderScreen(
            tenderId = entry.arguments?.getString("tenderId").orEmpty(),
            onBack = { navController.popBackStack() }
        )
    }

    composable(SupplierRoutes.COMPANY) {
        MyCompanyScreen(
            onBack = { navController.popBackStack() },
            onEditProfile = { navController.navigate(SupplierRoutes.COMPANY_EDIT) },
            onOpenBanking = { navController.navigate(SupplierRoutes.BANKING) },
            onOpenDocuments = { navController.navigate(SupplierRoutes.DOCUMENTS) }
        )
    }

    composable(SupplierRoutes.COMPANY_EDIT) {
        CompanyProfileScreen(onDone = { navController.popBackStack() })
    }

    composable(SupplierRoutes.BANKING) {
        BankingScreen(onDone = { navController.popBackStack() })
    }

    composable(SupplierRoutes.DOCUMENTS) {
        DocumentsScreen(onBack = { navController.popBackStack() })
    }
}
