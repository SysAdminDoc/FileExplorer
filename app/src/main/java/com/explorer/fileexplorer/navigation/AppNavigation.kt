package com.explorer.fileexplorer.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.explorer.fileexplorer.feature.apps.AppsScreen
import com.explorer.fileexplorer.feature.browser.BrowserScreen
import com.explorer.fileexplorer.feature.cloud.CloudScreen
import com.explorer.fileexplorer.feature.editor.EditorScreen
import com.explorer.fileexplorer.feature.network.NetworkScreen
import com.explorer.fileexplorer.feature.search.SearchScreen
import com.explorer.fileexplorer.feature.security.SecurityScreen
import com.explorer.fileexplorer.feature.settings.SettingsScreen

object Routes {
    const val BROWSER = "browser"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val NETWORK = "network"
    const val CLOUD = "cloud"
    const val SECURITY = "security"
    const val EDITOR = "editor/{filePath}"
    const val APPS = "apps"

    fun editorRoute(filePath: String) = "editor/${java.net.URLEncoder.encode(filePath, "UTF-8")}"
}

@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = Routes.BROWSER) {
        composable(Routes.BROWSER) {
            BrowserScreen(
                onOpenSearch = { navController.navigate(Routes.SEARCH) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenNetwork = { navController.navigate(Routes.NETWORK) },
                onOpenCloud = { navController.navigate(Routes.CLOUD) },
                onOpenSecurity = { navController.navigate(Routes.SECURITY) },
                onOpenApps = { navController.navigate(Routes.APPS) },
                onOpenEditor = { path -> navController.navigate(Routes.editorRoute(path)) },
            )
        }

        composable(Routes.SEARCH) {
            SearchScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToFolder = { navController.popBackStack() },
            )
        }

        composable(Routes.SETTINGS) { SettingsScreen(onNavigateBack = { navController.popBackStack() }) }
        composable(Routes.NETWORK) { NetworkScreen(onNavigateBack = { navController.popBackStack() }) }
        composable(Routes.CLOUD) { CloudScreen(onNavigateBack = { navController.popBackStack() }) }
        composable(Routes.SECURITY) { SecurityScreen(onNavigateBack = { navController.popBackStack() }) }
        composable(Routes.APPS) { AppsScreen(onNavigateBack = { navController.popBackStack() }) }

        composable(
            route = Routes.EDITOR,
            arguments = listOf(navArgument("filePath") { type = NavType.StringType }),
        ) { backStackEntry ->
            val filePath = java.net.URLDecoder.decode(
                backStackEntry.arguments?.getString("filePath") ?: "", "UTF-8")
            EditorScreen(filePath = filePath, onNavigateBack = { navController.popBackStack() })
        }
    }
}
