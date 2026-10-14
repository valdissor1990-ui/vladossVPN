package com.vpnproxy.core.tunnel

import android.content.Context
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.vpnproxy.core.xray.XrayCore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Управление TUN-интерфейсом и связкой VpnService ↔ tun2socks ↔ Xray.
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
        const val TUN_PREFIX = 32
        const val TUN_MTU = 1500
        const val SOCKS_PORT = 2081
        const val DNS_SERVER = "10.255.0.1"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tun2socksProcess: Process? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _tunnelState = MutableStateFlow(TunnelState.DISCONNECTED)
    val tunnelState: StateFlow<TunnelState> = _tunnelState

    /**
     * Создаёт TUN-интерфейс через VpnService.Builder.
     * Вызывается из VpnProxyService при старте.
     */
    fun establishTun(builder: VpnService.Builder): ParcelFileDescriptor? {
        return try {
            builder
                .setMtu(TUN_MTU)
                .addAddress(TUN_ADDRESS, TUN_PREFIX)
                .addDnsServer(DNS_SERVER)
                .addRoute("0.0.0.0", 0)          // Весь IPv4 через туннель
                .addRoute("::", 0)                // Весь IPv6 через туннель (блокируем утечки)
                .setBlocking(true)

            // Исключаем само приложение, чтобы Xray мог подключиться к серверу
            try {
                builder.addDisallowedApplication(context.packageName)
            } catch (_: Exception) { /* ignore */ }

            // Kill Switch: если туннель неактивен — блокируем трафик
            builder.setUnderlyingNetworks(null)

            vpnInterface = builder.establish()
            Log.i(TAG, "TUN established: ${vpnInterface != null}")
            _tunnelState.value = TunnelState.ESTABLISHED
            vpnInterface
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish TUN", e)
            _tunnelState.value = TunnelState.ERROR
            null
        }
    }

    /**
     * Запускает tun2socks (hev-socks5-tunnel) — мост между TUN и SOCKS5 Xray.
     * Использует нативную библиотеку или бинарник.
     */
    suspend fun startTun2Socks(tunFd: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            // hev-socks5-tunnel настраивается через YAML-конфиг
            val configYaml = buildHevConfig(tunFd)
            val configFile = File(context.filesDir, "hev_config.yaml")
            configFile.writeText(configYaml)

            // Запуск через JNI (libhev-socks5-tunnel.so)
            val result = HevTunnelBridge.start(configFile.absolutePath)
            Log.i(TAG, "tun2socks start result=$result")
            result == 0
        } catch (e: Exception) {
            Log.e(TAG, "tun2socks failed", e)
            false
        }
    }

    private fun buildHevConfig(tunFd: Int): String = """
        tunnel:
          ipv4: $TUN_ADDRESS
          mtu: $TUN_MTU
        socks5:
          port: $SOCKS_PORT
          address: 127.0.0.1
        misc:
          log-file: ${File(context.filesDir, "hev.log").absolutePath}
          task-stack-size: 24576
    """.trimIndent()

    /** Останавливает туннель и освобождает ресурсы. */
    suspend fun tearDown() {
        try {
            HevTunnelBridge.stop()
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

    enum class TunnelState {
        DISCONNECTED, ESTABLISHED, ERROR
    }
}

/** JNI-мост к libhev-socks5-tunnel.so */
object HevTunnelBridge {
    init {
        try {
            System.loadLibrary("hev-socks5-tunnel")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("HevTunnelBridge", "Failed to load hev-socks5-tunnel", e)
        }
    }

    external fun start(configPath: String): Int
    external fun stop(): Int
}
