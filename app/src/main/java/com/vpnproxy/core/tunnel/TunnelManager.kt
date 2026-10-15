package com.vpnproxy.core.tunnel

import android.content.Context
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.vpnproxy.core.xray.XrayCore
import dagger.hilt.android.qualifiers.ApplicationContext
import hev.htproxy.TProxyService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Управление TUN-интерфейсом и связкой VpnService ↔ hev-socks5-tunnel ↔ Xray.
 *
 * Поток данных:
 *   kernel → TUN (VpnService) → hev-socks5-tunnel → 127.0.0.1:2081 (Xray SOCKS-in) → proxy
 */
@Singleton
class TunnelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val xrayCore: XrayCore
) {
    companion object {
        private const val TAG = "TunnelManager"
        const val TUN_ADDRESS = "10.255.0.2"
        const val TUN_GATEWAY = "10.255.0.1"
        const val TUN_PREFIX = 30
        const val TUN_MTU = 1500
        const val SOCKS_PORT = 2081
        const val DNS_SERVER = "1.1.1.1"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _tunnelState = MutableStateFlow(TunnelState.DISCONNECTED)
    val tunnelState: StateFlow<TunnelState> = _tunnelState

    fun establishTun(builder: VpnService.Builder): ParcelFileDescriptor? {
        return try {
            builder
                .setMtu(TUN_MTU)
                .addAddress(TUN_ADDRESS, TUN_PREFIX)
                .addDnsServer(DNS_SERVER)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .setBlocking(true)

            try {
                builder.addDisallowedApplication(context.packageName)
            } catch (_: Exception) { /* ignore */ }

            builder.setUnderlyingNetworks(null)

            vpnInterface = builder.establish()
            Log.i(TAG, "TUN established: ${vpnInterface != null}")
            _tunnelState.value = if (vpnInterface != null) TunnelState.ESTABLISHED else TunnelState.ERROR
            vpnInterface
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish TUN", e)
            _tunnelState.value = TunnelState.ERROR
            null
        }
    }

    /**
     * Запускает hev-socks5-tunnel (tun2socks) через официальный AAR.
     * API: hev.htproxy.TProxyService.TProxyStartService(configPath, tunFd)
     */
    suspend fun startTun2Socks(tunFd: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val configYaml = buildHevConfig()
            val configFile = File(context.filesDir, "hev_config.yaml")
            configFile.writeText(configYaml)

            val ok = TProxyService.TProxyStartService(configFile.absolutePath, tunFd)
            Log.i(TAG, "TProxyStartService result=$ok fd=$tunFd")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "tun2socks failed", e)
            false
        }
    }

    private fun buildHevConfig(): String = """
        tunnel:
          mtu: $TUN_MTU
          multi-queue: false
          ipv4: $TUN_ADDRESS
        socks5:
          port: $SOCKS_PORT
          address: 127.0.0.1
          udp: 'udp'
        misc:
          task-stack-size: 86016
          connect-timeout: 5000
          read-write-timeout: 60000
          log-file: stderr
          log-level: warn
    """.trimIndent()

    suspend fun tearDown() {
        try {
            try {
                TProxyService.TProxyStopService()
            } catch (e: Exception) {
                Log.w(TAG, "TProxyStopService: ${e.message}")
            }
            xrayCore.stop()
            vpnInterface?.close()
            vpnInterface = null
            _tunnelState.value = TunnelState.DISCONNECTED
            Log.i(TAG, "Tunnel torn down")
        } catch (e: Exception) {
            Log.e(TAG, "Teardown error", e)
        }
    }

    fun getTunFd(): Int = vpnInterface?.fd ?: -1

    fun isHevRunning(): Boolean = try {
        TProxyService.TProxyIsRunning()
    } catch (_: Throwable) {
        false
    }

    enum class TunnelState {
        DISCONNECTED, ESTABLISHED, ERROR
    }
}
