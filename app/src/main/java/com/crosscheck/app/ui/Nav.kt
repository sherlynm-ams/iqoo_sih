package com.crosscheck.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.crosscheck.app.R
import com.crosscheck.app.di.AppContainer

object Routes {
    const val LOG = "log"
    const val SETTINGS = "settings"
    const val PERMISSIONS = "permissions"
    const val VERIFY = "verify"
    const val VOICE = "voice"
    const val ARG_CLAIM_ID = "claimId"
    const val RECEIPT = "receipt/{$ARG_CLAIM_ID}"
    fun receipt(claimId: Long) = "receipt/$claimId"
}

@Composable
fun CrossCheckNavHost(container: AppContainer) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.LOG) {
        composable(Routes.LOG) {
            LogScreen(
                container = container,
                onVerifyClaim = { nav.navigate(Routes.VERIFY) },
                onVoiceQuery = { nav.navigate(Routes.VOICE) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onPermissions = { nav.navigate(Routes.PERMISSIONS) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(container = container, onBack = { nav.popBackStack() })
        }
        composable(Routes.PERMISSIONS) {
            PermissionsScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.VERIFY) {
            VerifyScreen(
                container = container,
                onBack = { nav.popBackStack() },
                onViewReceipt = { claimId -> nav.navigate(Routes.receipt(claimId)) },
            )
        }
        composable(
            Routes.RECEIPT,
            arguments = listOf(navArgument(Routes.ARG_CLAIM_ID) { type = NavType.LongType }),
        ) { entry ->
            ReceiptScreen(
                container = container,
                claimId = entry.arguments?.getLong(Routes.ARG_CLAIM_ID) ?: 0L,
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.VOICE) {
            VoiceScreen(
                container = container,
                onBack = { nav.popBackStack() },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
    }
}
