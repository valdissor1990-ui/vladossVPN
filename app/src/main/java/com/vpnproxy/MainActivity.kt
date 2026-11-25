package com.vpnproxy

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vpnproxy.core.feature.FeatureFlags
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.service.VpnProxyService
import com.vpnproxy.ui.debug.DebugScreen
import com.vpnproxy.ui.durability.DurabilityScreen
import com.vpnproxy.ui.home.HomeScreen
import com.vpnproxy.ui.home.HomeViewModel
import com.vpnproxy.ui.servers.ServersListScreen
import com.vpnproxy.ui.services.ServicesScreen
import com.vpnproxy.ui.settings.SettingsScreen
import com.vpnproxy.ui.subscriptions.SubscriptionsScreen
import com.vpnproxy.ui.theme.VPNProxyTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Единственная Activity приложения.
 * Отвечает за: Navigation Host, запрос VPN-разрешения, runtime-разрешения.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var logCollector: LogCollector

    private val homeViewModel: HomeViewModel by viewModels()
    private var vpnPermissionRequestInFlight = false

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        vpnPermissionRequestInFlight = false
        if (result.resultCode == Activity.RESULT_OK) {
            logCollector.info(TAG, "VPN permission granted")
            homeViewModel.onVpnPermissionGranted()
        } else {
            logCollector.warn(TAG, "VPN permission denied")
            homeViewModel.onVpnPermissionDenied()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        logCollector.info(TAG, "POST_NOTIFICATIONS granted=$granted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        logCollector.info(TAG, "MainActivity created")

        requestNotificationPermissionIfNeeded()

        setContent {
            VPNProxyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    VPNProxyNavHost(
                        homeViewModel = homeViewModel,
                        onRequestVpnPermission = { requestVpnPermission() }
                    )
                }
            }
        }

        // Quick Settings tile handoff. `savedInstanceState == null` keeps a rotation or a
        // process restore from replaying an old connect request.
        if (savedInstanceState == null) handleAutoConnect(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAutoConnect(intent)
    }

    /** The QS tile cannot run the scan/preflight flow, so it asks the app to connect. */
    private fun handleAutoConnect(source: Intent?) {
        if (source?.getBooleanExtra(EXTRA_AUTO_CONNECT, false) != true) return
        // Consume the extra so a later recreation of this activity cannot replay it.
        source.removeExtra(EXTRA_AUTO_CONNECT)
        if (VpnProxyService.isRunning.value || VpnProxyService.isCoreTransitioning.value) {
            logCollector.info(TAG, "Auto-connect ignored: tunnel already running or transitioning")
            return
        }
        logCollector.info(TAG, "Auto-connect requested by Quick Settings tile")
        homeViewModel.toggleConnection()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            logCollector.info(TAG, "POST_NOTIFICATIONS already granted")
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun requestVpnPermission() {
        if (vpnPermissionRequestInFlight) {
            logCollector.warn(TAG, "VPN permission request already in flight; ignoring duplicate request")
            return
        }

        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionRequestInFlight = true
            vpnPermissionLauncher.launch(intent)
        } else {
            vpnPermissionRequestInFlight = false
            homeViewModel.onVpnPermissionGranted()
        }
    }

    companion object {
        private const val TAG = "MainActivity"

        /** Set by [com.vpnproxy.service.VpnTileService] to request a connect after launch. */
        const val EXTRA_AUTO_CONNECT = "com.vpnproxy.extra.AUTO_CONNECT"
    }
}

@androidx.compose.runtime.Composable
private fun VPNProxyNavHost(
    homeViewModel: HomeViewModel,
    onRequestVpnPermission: () -> Unit
) {
    val navController = rememberNavController()
    val currentRequester by rememberUpdatedState(onRequestVpnPermission)

    NavHost(navController = navController, startDestination = Route.Home.path) {
        composable(Route.Home.path) {
            LaunchedEffect(homeViewModel) {
                homeViewModel.setVpnPermissionRequester { currentRequester() }
            }
            HomeScreen(
                viewModel = homeViewModel,
                onNavigateToSubscriptions = { navController.navigate(Route.Subscriptions.path) },
                onNavigateToDebug = { navController.navigate(Route.Debug.path) },
                onNavigateToSettings = { navController.navigate(Route.Settings.path) },
                onNavigateToServices = {
                    if (FeatureFlags.SERVICE_CHECK_ENABLED) navController.navigate(Route.Services.path)
                },
                showServicesEntry = FeatureFlags.SERVICE_CHECK_ENABLED
            )
        }

        composable(Route.Subscriptions.path) {
            SubscriptionsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToServers = { navController.navigate(Route.Servers.path) }
            )
        }

        composable(Route.Servers.path) {
            ServersListScreen(
                onBack = { navController.popBackStack() },
                onNavigateHome = {
                    navController.navigate(Route.Home.path) {
                        popUpTo(Route.Home.path) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onNavigateSettings = { navController.navigate(Route.Settings.path) { launchSingleTop = true } }
            )
        }

        composable(Route.Settings.path) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToDurability = { navController.navigate(Route.Durability.path) }
            )
        }

        composable(Route.Durability.path) {
            DurabilityScreen(onBack = { navController.popBackStack() })
        }

        composable(Route.Debug.path) {
            DebugScreen(onBack = { navController.popBackStack() })
        }

        if (FeatureFlags.SERVICE_CHECK_ENABLED) {
            composable(Route.Services.path) {
                ServicesScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

sealed class Route(val path: String) {
    data object Home : Route("home")
    data object Subscriptions : Route("subscriptions")
    data object Servers : Route("servers")
    data object Settings : Route("settings")
    data object Debug : Route("debug")
    data object Services : Route("services")
    data object Durability : Route("durability")
}
