package com.watchapp.ui

import androidx.compose.runtime.Composable
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.watchapp.di.Container

object Routes {
    const val MAIN = "main"
    const val DEBUG = "debug"
    const val PERMISSIONS = "permissions"
}

@Composable
fun AppNav(container: Container) {
    val nav = rememberSwipeDismissableNavController()
    SwipeDismissableNavHost(
        navController = nav,
        startDestination = Routes.MAIN,
    ) {
        composable(Routes.MAIN) {
            MainScreen(
                container = container,
                onOpenDebug = { nav.navigate(Routes.DEBUG) },
                onOpenPermissions = { nav.navigate(Routes.PERMISSIONS) },
            )
        }
        composable(Routes.DEBUG) {
            DebugScreen(container = container)
        }
        composable(Routes.PERMISSIONS) {
            PermissionsScreen(
                onAllGranted = { nav.popBackStack(Routes.MAIN, inclusive = false) },
            )
        }
    }
}
