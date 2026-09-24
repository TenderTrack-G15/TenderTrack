package za.ac.tendertrack.ui.nav

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import za.ac.tendertrack.data.model.Profile
import za.ac.tendertrack.ui.screens.SignInAudience
import za.ac.tendertrack.ui.screens.SignInScreen
import za.ac.tendertrack.ui.screens.account.*

/**
 * Welcome, supplier sign-up, forgot password and the supplier's home. All
 * routes start with "account_", which keeps the officer drawer closed here.
 * Sign In itself stays in Routes / NavGraph.
 */
object AccountRoutes {
    const val WELCOME = "account_welcome"
    const val SUPPLIER_SIGN_IN = "account_supplier_sign_in"
    const val SUPPLIER_SIGN_UP = "account_supplier_sign_up"
    const val FORGOT_PASSWORD = "account_forgot_password"


    fun isAccountRoute(route: String?): Boolean = route?.startsWith("account_") == true
}

/**
 * Adds the account screens to the app's NavHost with one call from
 * TenderTrackNavGraph.
 *
 * Welcome is the app's first screen. After signing in or signing up, NavGraph's
 * onSignedIn opens the right home for the role and clears the back stack, so
 * the phone's Back button then leaves the app rather than returning to a form.
 */
fun NavGraphBuilder.accountGraph(
    navController: NavHostController,
    onSignedIn: (Profile) -> Unit
) {
    composable(AccountRoutes.WELCOME) {
        WelcomeScreen(
            // Welcome stays underneath, so Back from these screens returns here.
            onContinueAsPublic = { navController.navigate(CitizenRoutes.HOME) { launchSingleTop = true } },
            onGovernmentLogin = { navController.navigate(Routes.SIGN_IN) { launchSingleTop = true } },
            onSupplierLogin = { navController.navigate(AccountRoutes.SUPPLIER_SIGN_IN) { launchSingleTop = true } }
        )
    }

    // Suppliers sign in here; registering is a link on this page.
    composable(AccountRoutes.SUPPLIER_SIGN_IN) {
        SignInScreen(
            audience = SignInAudience.SUPPLIER,
            onBack = { navController.popBackStack() },
            onSignedIn = onSignedIn,
            onForgotPassword = { navController.navigate(AccountRoutes.FORGOT_PASSWORD) },
            onSwitchAudience = {
                navController.navigate(Routes.SIGN_IN) {
                    popUpTo(AccountRoutes.SUPPLIER_SIGN_IN) { inclusive = true }
                    launchSingleTop = true
                }
            },
            onRegister = { navController.navigate(AccountRoutes.SUPPLIER_SIGN_UP) }
        )
    }

    composable(AccountRoutes.SUPPLIER_SIGN_UP) {
        SupplierSignUpScreen(
            onBack = { navController.popBackStack() },
            // Back to the supplier sign-in page this was opened from.
            onSignIn = {
                navController.navigate(AccountRoutes.SUPPLIER_SIGN_IN) {
                    popUpTo(AccountRoutes.SUPPLIER_SIGN_UP) { inclusive = true }
                    launchSingleTop = true
                }
            },
            onSignedIn = onSignedIn
        )
    }

    composable(AccountRoutes.FORGOT_PASSWORD) {
        ForgotPasswordScreen(
            onBack = { navController.popBackStack() },
            // Returns to whichever sign-in page opened it.
            onSignIn = { navController.popBackStack() }
        )
    }


}
