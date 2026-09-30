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
import za.ac.tendertrack.ui.screens.supplier.*

/**
 * The Supplier flow (Deliverable 3, section 5.3). All routes start with
 * "supplier_", which keeps the officer drawer closed here.
 *
 * Awards: a tender awarded on the eTender portal appears under Awards. The
 * supplier claims it on Claim award with the 10-digit code it was emailed,
 * and only then can update the contract's deliverables.
 *
 * Navigation panel: every supplier screen sits in the supplier's own panel
 * (SupplierDrawer.kt), which opens with a swipe from the left edge. The main
 * screens (Dashboard, Awards, My Company) show the menu button; the screens
 * opened from them keep their Back arrow, as on the officer's side.
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
    // Adds a supplier destination wrapped in the navigation panel.
    fun screen(
        route: String,
        arguments: List<NamedNavArgument> = emptyList(),
        content: @Composable (entry: NavBackStackEntry, openDrawer: () -> Unit) -> Unit
    ) {
        composable(route = route, arguments = arguments) { entry ->
            SupplierDrawerHost(navController, route, currentProfile, onSignOut) { openDrawer ->
                content(entry, openDrawer)
            }
        }
    }

    val tenderIdArgument = listOf(navArgument("tenderId") { type = NavType.StringType })

    screen(SupplierRoutes.HOME) { _, openDrawer ->
        SupplierHomeScreen(
            supplierName = currentProfile()?.fullName ?: "Supplier",
            onOpenTender = { id -> navController.navigate(SupplierRoutes.tender(id)) },
            onOpenCompany = { navController.navigate(SupplierRoutes.COMPANY) },
            onOpenAwards = { navController.navigate(SupplierRoutes.AWARDS) },
            onClaim = { id -> navController.navigate(SupplierRoutes.claim(id)) },
            // A company registered on the eTender portal but not yet for TenderTrack.
            onRegister = { navController.navigate(AccountRoutes.SUPPLIER_SIGN_UP) },
            onSignOut = onSignOut,
            onMenu = openDrawer
        )
    }

    screen(SupplierRoutes.TENDER, tenderIdArgument) { entry, _ ->
        SupplierTenderScreen(
            tenderId = entry.arguments?.getString("tenderId").orEmpty(),
            onBack = { navController.popBackStack() }
        )
    }

    screen(SupplierRoutes.COMPANY) { _, openDrawer ->
        MyCompanyScreen(
            onBack = { navController.popBackStack() },
            onEditProfile = { navController.navigate(SupplierRoutes.COMPANY_EDIT) },
            onOpenBanking = { navController.navigate(SupplierRoutes.BANKING) },
            onOpenDocuments = { navController.navigate(SupplierRoutes.DOCUMENTS) },
            onMenu = openDrawer
        )
    }

    screen(SupplierRoutes.COMPANY_EDIT) { _, _ ->
        CompanyProfileScreen(onDone = { navController.popBackStack() })
    }

    screen(SupplierRoutes.BANKING) { _, _ ->
        BankingScreen(onDone = { navController.popBackStack() })
    }

    screen(SupplierRoutes.DOCUMENTS) { _, _ ->
        DocumentsScreen(onBack = { navController.popBackStack() })
    }

    screen(SupplierRoutes.AWARDS) { _, openDrawer ->
        MyAwardsScreen(
            onBack = { navController.popBackStack() },
            onClaim = { id -> navController.navigate(SupplierRoutes.claim(id)) },
            onOpenContract = { id -> navController.navigate(SupplierRoutes.contract(id)) },
            onMenu = openDrawer
        )
    }

    screen(SupplierRoutes.CLAIM, tenderIdArgument) { entry, _ ->
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

    screen(SupplierRoutes.CONTRACT, tenderIdArgument) { entry, _ ->
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
