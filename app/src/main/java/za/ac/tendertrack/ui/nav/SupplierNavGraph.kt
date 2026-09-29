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
 *
 * Awards: a tender awarded on the eTender portal appears under Awards. The
 * supplier claims it on Claim award with the 10-digit code it was emailed,
 * and only then can update the contract's deliverables.
 */
object SupplierRoutes {
    const val HOME = "supplier_home"
    const val TENDER = "supplier_tender/{tenderId}"
    const val COMPANY = "supplier_company"
    const val COMPANY_EDIT = "supplier_company_edit"
    const val BANKING = "supplier_banking"
    const val DOCUMENTS = "supplier_documents"
    const val AWARDS = "supplier_awards"
    const val CLAIM = "supplier_claim/{tenderId}"
    const val CONTRACT = "supplier_contract/{tenderId}"

    fun tender(id: String) = "supplier_tender/$id"
    fun claim(id: String) = "supplier_claim/$id"
    fun contract(id: String) = "supplier_contract/$id"

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
            onOpenAwards = { navController.navigate(SupplierRoutes.AWARDS) },
            onClaim = { id -> navController.navigate(SupplierRoutes.claim(id)) },
            // A company registered on the eTender portal but not yet for TenderTrack.
            onRegister = { navController.navigate(AccountRoutes.SUPPLIER_SIGN_UP) },
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

    composable(SupplierRoutes.AWARDS) {
        MyAwardsScreen(
            onBack = { navController.popBackStack() },
            onClaim = { id -> navController.navigate(SupplierRoutes.claim(id)) },
            onOpenContract = { id -> navController.navigate(SupplierRoutes.contract(id)) }
        )
    }

    composable(
        route = SupplierRoutes.CLAIM,
        arguments = listOf(navArgument("tenderId") { type = NavType.StringType })
    ) { entry ->
        ClaimAwardScreen(
            tenderId = entry.arguments?.getString("tenderId").orEmpty(),
            onBack = { navController.popBackStack() },
            // After claiming, Back from the contract returns to where the supplier came
            // from (Awards or home), not to the code screen.
            onOpenContract = { id ->
                navController.navigate(SupplierRoutes.contract(id)) {
                    popUpTo(SupplierRoutes.CLAIM) { inclusive = true }
                    launchSingleTop = true
                }
            }
        )
    }

    composable(
        route = SupplierRoutes.CONTRACT,
        arguments = listOf(navArgument("tenderId") { type = NavType.StringType })
    ) { entry ->
        ContractScreen(
            tenderId = entry.arguments?.getString("tenderId").orEmpty(),
            onBack = { navController.popBackStack() },
            // The contract screen is replaced by Claim, so an out-of-date copy never stays behind it.
            onClaim = { id ->
                navController.navigate(SupplierRoutes.claim(id)) {
                    popUpTo(SupplierRoutes.CONTRACT) { inclusive = true }
                }
            }
        )
    }
}
