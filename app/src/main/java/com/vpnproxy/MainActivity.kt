package com.vpnproxy

import android.Manifest
import android.app.Activity
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.ui.debug.DebugScreen
import com.vpnproxy.ui.home.HomeScreen
import com.vpnproxy.ui.home.HomeViewModel
import com.vpnproxy.ui.servers.ServersListScreen
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

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            VPNProxyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    VPNProxyNavHost(
                        onRequestVpnPermission = { requestVpnPermission() }
                    )
                }
            }
        }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            homeViewModel.onVpnPermissionGranted()
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}

@androidx.compose.runtime.Composable
private fun VPNProxyNavHost(
    onRequestVpnPermission: () -> Unit
) {
    val navController = rememberNavController()
    val currentRequester by rememberUpdatedState(onRequestVpnPermission)

    NavHost(navController = navController, startDestination = Route.Home.path) {
        composable(Route.Home.path) {
            val vm: HomeViewModel = hiltViewModel()
            LaunchedEffect(Unit) {
                vm.setVpnPermissionRequester { currentRequester() }
            }
            HomeScreen(
                viewModel = vm,
                onNavigateToSubscriptions = { navController.navigate(Route.Subscriptions.path) },
                onNavigateToDebug = { navController.navigate(Route.Debug.path) },
                onNavigateToSettings = { navController.navigate(Route.Settings.path) }
            )
        }

        composable(Route.Subscriptions.path) {
            SubscriptionsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToServers = { navController.navigate(Route.Servers.path) }
            )
        }

        composable(Route.Servers.path) {
            ServersListScreen(onBack = { navController.popBackStack() })
        }

        composable(Route.Settings.path) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }

        composable(Route.Debug.path) {
            DebugScreen(onBack = { navController.popBackStack() })
        }
    }
}

sealed class Route(val path: String) {
    data object Home : Route("home")
    data object Subscriptions : Route("subscriptions")
    data object Servers : Route("servers")
    data object Settings : Route("settings")
    data object Debug : Route("debug")
}
