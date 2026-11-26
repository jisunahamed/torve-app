package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.core.TorveContainer
import com.example.core.database.AppSettings
import com.example.feature.account.SettingsScreen
import com.example.feature.account.UsageAnalyticsScreen
import com.example.feature.auth.AuthScreen
import com.example.feature.auth.launchCustomTab
import com.example.feature.chat.ChatScreen
import com.example.feature.chat.ChatViewModel
import com.example.feature.inbox.InboxScreen
import com.example.feature.inbox.InboxViewModel
import com.example.feature.preview.LivePreviewScreen
import com.example.feature.preview.WorkspaceExplorerScreen
import com.example.ui.theme.TorveAiTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data object AuthRoute

@Serializable
data object InboxRoute

@Serializable
data class ChatRoute(val threadId: String)

@Serializable
data class LivePreviewRoute(val workspaceId: String, val port: Int = 8080)

@Serializable
data class WorkspaceExplorerRoute(val workspaceId: String)

@Serializable
data object UsageRoute

@Serializable
data object SettingsRoute

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Graceful regardless of grant */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as TorveApplication).container
        handleDeepLinkIntent(intent, container)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            val settings by container.preferencesStore.settingsFlow
                .collectAsStateWithLifecycle(initialValue = AppSettings())

            TorveAiTheme(themeMode = settings.themeMode) {
                TorveAppNavHost(container = container, settings = settings)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val container = (application as TorveApplication).container
        handleDeepLinkIntent(intent, container)
    }

    private fun handleDeepLinkIntent(intent: Intent?, container: TorveContainer) {
        val dataUri = intent?.data ?: return
        lifecycleScope.launch {
            container.authRepository.handleAuthCallbackUri(dataUri)
        }
    }
}

@Composable
private fun TorveAppNavHost(
    container: TorveContainer,
    settings: AppSettings
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessionState by container.authRepository.sessionState.collectAsStateWithLifecycle()
    val runningServers by container.devServerManager.runningServers.collectAsStateWithLifecycle()

    val startDestination: Any = if (sessionState.isAuthenticated) InboxRoute else AuthRoute

    LaunchedEffect(sessionState.isAuthenticated) {
        val currentRoute = navController.currentDestination?.route
        if (sessionState.isAuthenticated && currentRoute?.contains("AuthRoute") == true) {
            navController.navigate(InboxRoute) {
                popUpTo(AuthRoute) { inclusive = true }
            }
        } else if (!sessionState.isAuthenticated && currentRoute != null && !currentRoute.contains("AuthRoute")) {
            navController.navigate(AuthRoute) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable<AuthRoute> {
            AuthScreen(
                sessionState = sessionState,
                onContinueWithBrowser = {
                    val authUrl = container.authRepository.beginBrowserPkceAuth()
                    launchCustomTab(context, authUrl)
                },
                onSignInWithApiKey = { rawKey ->
                    scope.launch {
                        container.authRepository.signInWithApiKey(rawKey)
                    }
                },
                onDismissError = { container.authRepository.clearError() }
            )
        }

        composable<InboxRoute> {
            val inboxVm: InboxViewModel = viewModel(
                factory = remember(container) { InboxViewModel.Factory(container) }
            )
            InboxScreen(
                viewModel = inboxVm,
                sessionState = sessionState,
                onOpenThread = { threadId -> navController.navigate(ChatRoute(threadId)) },
                onOpenWorkspaceExplorer = { wsId -> navController.navigate(WorkspaceExplorerRoute(wsId)) },
                onOpenLivePreview = { wsId, port -> navController.navigate(LivePreviewRoute(wsId, port)) },
                onOpenUsage = { navController.navigate(UsageRoute) },
                onOpenSettings = { navController.navigate(SettingsRoute) },
                onSignOut = {
                    scope.launch {
                        container.devServerManager.stopAllServers()
                        container.authRepository.signOut()
                    }
                }
            )
        }

        composable<ChatRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<ChatRoute>()
            val chatVm: ChatViewModel = viewModel(
                key = "chat_${route.threadId}",
                factory = remember(route.threadId, container) {
                    ChatViewModel.Factory(route.threadId, container)
                }
            )
            ChatScreen(
                viewModel = chatVm,
                onNavigateBack = { navController.popBackStack() },
                onOpenLivePreview = { wsId, port ->
                    navController.navigate(LivePreviewRoute(wsId, port))
                },
                onOpenWorkspaceExplorer = { wsId ->
                    navController.navigate(WorkspaceExplorerRoute(wsId))
                }
            )
        }

        composable<LivePreviewRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<LivePreviewRoute>()
            LivePreviewScreen(
                workspaceId = route.workspaceId,
                initialPort = route.port,
                container = container,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable<WorkspaceExplorerRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<WorkspaceExplorerRoute>()
            WorkspaceExplorerScreen(
                workspaceId = route.workspaceId,
                container = container,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable<UsageRoute> {
            UsageAnalyticsScreen(
                sessionState = sessionState,
                onSelectRangeAndScope = { range, scopeParam ->
                    scope.launch {
                        container.authRepository.refreshUsage(range, scopeParam)
                    }
                },
                onRefresh = {
                    scope.launch {
                        container.authRepository.hydrateSession()
                    }
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable<SettingsRoute> {
            val diagnostics = remember(runningServers.size) {
                container.sandboxBootstrap.getDiagnostics()
            }
            SettingsScreen(
                settings = settings,
                diagnostics = diagnostics,
                runningServerCount = runningServers.size,
                keyPrefix = sessionState.credentials?.keyPrefix,
                onSetThemeMode = { mode ->
                    scope.launch { container.preferencesStore.setThemeMode(mode) }
                },
                onSetAskBeforeShell = { ask ->
                    scope.launch { container.preferencesStore.setAskBeforeShellCommands(ask) }
                },
                onSetMaxSteps = { steps ->
                    scope.launch { container.preferencesStore.setMaxAgentSteps(steps) }
                },
                onStopAllServers = {
                    container.devServerManager.stopAllServers()
                },
                onSignOutAndRevoke = {
                    scope.launch {
                        container.devServerManager.stopAllServers()
                        container.authRepository.signOut()
                    }
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
