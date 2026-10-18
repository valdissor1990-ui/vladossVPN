package com.vpnproxy.core.tunnel

import android.content.Context
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.amnezia.awg.hevtunnel.TProxyService
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TUN + hev-socks5-tunnel.
 *
 * MTU 1400 — безопасный размер для мобильных сетей и UDP (Zello и т.п.).
 * 8500 вызывал фрагментацию и потерю UDP-пакетов голоса.
 */
@Singleton
class TunnelManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "TunnelManager"

        const val SOCKS_HOST = "127.0.0.1"
        const val SOCKS_PORT = 10808
        const val TUN_ADDRESS = "10.255.0.2"
        const val TUN_PREFIX = 30
        /** Mobile-safe MTU; avoids UDP fragmentation on LTE/WiFi. */
        const val TUN_MTU = 1400
        const val DNS_SERVER = "1.1.1.1"
    }

    private var vpnInterface: ParcelFileDescriptor? = null

    @Volatile
    private var tproxyRunning: Boolean = false

    fun establishTun(builder: VpnService.Builder): ParcelFileDescriptor? {
        return try {
            builder
                .setMtu(TUN_MTU)
                .addAddress(TUN_ADDRESS, TUN_PREFIX)
                .addDnsServer(DNS_SERVER)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .setSession("VPNProxy")
                .setBlocking(true)

            // Kill-switch style: no underlying network fallback
            builder.setUnderlyingNetworks(null)

            vpnInterface = builder.establish()
            Log.i(TAG, "TUN established: ${vpnInterface != null} fd=${vpnInterface?.fd} mtu=$TUN_MTU")
            vpnInterface
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish TUN", e)
            null
        }
    }

    fun startHev(tunFd: Int): Boolean {
        return try {
            val configYaml = buildHevConfig()
            val configFile = File(context.filesDir, "hev_config.yaml").apply {
                writeText(configYaml)
            }
            // AAR signature: static native void TProxyStartService(String, int).
            TProxyService.TProxyStartService(configFile.absolutePath, tunFd)
            tproxyRunning = true

            // The verified API has no return value; probe the documented AAR method.
            val stats = try { TProxyService.TProxyGetStats() } catch (_: Throwable) { null }
            val ok = stats != null
            if (!ok) {
                Log.w(TAG, "TProxyStartService returned void; TProxyGetStats() probe returned null")
                tproxyRunning = false
            } else {
                Log.i(TAG, "tun2socks started, stats=${stats!!.contentToString()}")
            }
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "TProxyStartService failed", t)
            tproxyRunning = false
            false
        }
    }

    fun stopHev() {
        try {
            // AAR signature: static native void TProxyStopService().
            TProxyService.TProxyStopService()
            Log.i(TAG, "TProxyStopService OK")
        } catch (t: Throwable) {
            Log.e(TAG, "TProxyStopService failed", t)
        } finally {
            tproxyRunning = false
        }
    }

    fun isHevRunning(): Boolean = tproxyRunning

    fun closeTun() {
        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        vpnInterface = null
    }

    fun tearDown() {
        stopHev()
        closeTun()
    }

    fun getTunFd(): Int = vpnInterface?.fd ?: -1

    private fun buildHevConfig(): String {
        val logFile = File(context.filesDir, "hev.log").absolutePath
        // udp: udp — explicit UDP relay via SOCKS5 for Zello and other UDP apps
        return """
            tunnel:
              mtu: $TUN_MTU
              ipv4: $TUN_ADDRESS
            socks5:
              port: $SOCKS_PORT
              address: $SOCKS_HOST
              udp: udp
            misc:
              task-stack-size: 24576
              connect-timeout: 5000
              read-write-timeout: 60000
              log-level: warn
              log-file: $logFile
        """.trimIndent()
    }
}
