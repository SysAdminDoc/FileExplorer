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
import com.explorer.fileexplorer.feature.browser.CollectionsScreen
import com.explorer.fileexplorer.feature.browser.TrashScreen
import com.explorer.fileexplorer.feature.browser.StorageAnalyzerScreen
import com.explorer.fileexplorer.feature.cloud.CloudScreen
import com.explorer.fileexplorer.feature.editor.EditorScreen
import com.explorer.fileexplorer.feature.editor.HexEditorScreen
import com.explorer.fileexplorer.feature.network.NetworkScreen
import com.explorer.fileexplorer.feature.network.ShareServerScreen
import com.explorer.fileexplorer.feature.search.SearchScreen
import com.explorer.fileexplorer.feature.security.SecurityScreen
import com.explorer.fileexplorer.feature.settings.SettingsScreen
import com.explorer.fileexplorer.feature.transfer.TransferQueueScreen
import com.explorer.fileexplorer.feature.browser.ShizukuScreen

object Routes {
    const val BROWSER = "browser"
    const val BROWSER_AT_PATH = "browser/{path}"
    const val SEARCH = "search"
    const val COLLECTIONS = "collections"
    const val SETTINGS = "settings"
    const val NETWORK = "network"
    const val SHIZUKU = "shizuku"
    const val SHARE_SERVER = "share-server"
    const val CLOUD = "cloud"
    const val SECURITY = "security"
    const val EDITOR = "editor/{filePath}"
    const val HEX_EDITOR = "hex/{filePath}"
    const val APPS = "apps"
    const val TRASH = "trash"
    const val ANALYZER = "analyzer"
    const val TRANSFERS = "transfers"

    fun editorRoute(filePath: String) = "editor/${java.net.URLEncoder.encode(filePath, "UTF-8")}"
    fun hexEditorRoute(filePath: String) = "hex/${java.net.URLEncoder.encode(filePath, "UTF-8")}"
    fun browserAtPath(path: String) = "browser/${java.net.URLEncoder.encode(path, "UTF-8")}"
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
                onOpenShizuku = { navController.navigate(Routes.SHIZUKU) },
                onOpenServer = { navController.navigate(Routes.SHARE_SERVER) },
                onOpenCloud = { navController.navigate(Routes.CLOUD) },
                onOpenCollections = { navController.navigate(Routes.COLLECTIONS) },
                onOpenSecurity = { navController.navigate(Routes.SECURITY) },
                onOpenApps = { navController.navigate(Routes.APPS) },
                onOpenTrash = { navController.navigate(Routes.TRASH) },
                onOpenAnalyzer = { navController.navigate(Routes.ANALYZER) },
                onOpenTransfers = { navController.navigate(Routes.TRANSFERS) },
                onOpenEditor = { path -> navController.navigate(Routes.editorRoute(path)) },
                onOpenHexEditor = { path -> navController.navigate(Routes.hexEditorRoute(path)) },
            )
        }
        composable(
            route = Routes.BROWSER_AT_PATH,
            arguments = listOf(navArgument("path") { type = NavType.StringType }),
        ) { backStackEntry ->
            BrowserScreen(
                initialPath = java.net.URLDecoder.decode(
                    backStackEntry.arguments?.getString("path") ?: "", "UTF-8",
                ),
                onOpenSearch = { navController.navigate(Routes.SEARCH) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenNetwork = { navController.navigate(Routes.NETWORK) },
                onOpenShizuku = { navController.navigate(Routes.SHIZUKU) },
                onOpenServer = { navController.navigate(Routes.SHARE_SERVER) },
                onOpenCloud = { navController.navigate(Routes.CLOUD) },
                onOpenCollections = { navController.navigate(Routes.COLLECTIONS) },
                onOpenSecurity = { navController.navigate(Routes.SECURITY) },
                onOpenApps = { navController.navigate(Routes.APPS) },
                onOpenTrash = { navController.navigate(Routes.TRASH) },
                onOpenAnalyzer = { navController.navigate(Routes.ANALYZER) },
                onOpenTransfers = { navController.navigate(Routes.TRANSFERS) },
                onOpenEditor = { path -> navController.navigate(Routes.editorRoute(path)) },
                onOpenHexEditor = { path -> navController.navigate(Routes.hexEditorRoute(path)) },
            )
        }

        composable(Routes.SEARCH) {
            SearchScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToFolder = { navController.popBackStack() },
            )
        }

        composable(Routes.COLLECTIONS) {
            CollectionsScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS) { SettingsScreen(onNavigateBack = { navController.popBackStack() }) }
        composable(Routes.NETWORK) { NetworkScreen(onNavigateBack = { navController.popBackStack() }) }
        composable(Routes.SHIZUKU) {
            ShizukuScreen(
                onNavigateBack = { navController.popBackStack() },
                onOpenAndroidData = { path -> navController.navigate(Routes.browserAtPath(path)) },
            )
        }
        composable(Routes.SHARE_SERVER) { ShareServerScreen(onNavigateBack = { navController.popBackStack() }) }
        composable(Routes.CLOUD) { CloudScreen(onNavigateBack = { navController.popBackStack() }) }
        composable(Routes.SECURITY) { SecurityScreen(onNavigateBack = { navController.popBackStack() }) }
        composable(Routes.APPS) { AppsScreen(onNavigateBack = { navController.popBackStack() }) }
        composable(Routes.TRASH) { TrashScreen(onNavigateBack = { navController.popBackStack() }) }
        composable(Routes.ANALYZER) { StorageAnalyzerScreen(onNavigateBack = { navController.popBackStack() }) }
        composable(Routes.TRANSFERS) { TransferQueueScreen(onNavigateBack = { navController.popBackStack() }) }

        composable(
            route = Routes.EDITOR,
            arguments = listOf(navArgument("filePath") { type = NavType.StringType }),
        ) { backStackEntry ->
            val filePath = java.net.URLDecoder.decode(
                backStackEntry.arguments?.getString("filePath") ?: "", "UTF-8")
            EditorScreen(filePath = filePath, onNavigateBack = { navController.popBackStack() })
        }

        composable(
            route = Routes.HEX_EDITOR,
            arguments = listOf(navArgument("filePath") { type = NavType.StringType }),
        ) { backStackEntry ->
            val filePath = java.net.URLDecoder.decode(
                backStackEntry.arguments?.getString("filePath") ?: "", "UTF-8")
            HexEditorScreen(filePath = filePath, onNavigateBack = { navController.popBackStack() })
        }
    }
}
