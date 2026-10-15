package com.vpnproxy.core.tunnel

import android.content.Context
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import hev.htproxy.TProxyService
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TUN + hev-socks5-tunnel.
 *
 * Реальный AAR hev.htproxy.TProxyService — НЕ Android Service, а JNI:
 *   TProxyStartService(configPath, tunFd)
 *   TProxyStopService()
 *   TProxyIsRunning()
 *
 * TUN fd создаёт наш VpnProxyService через Builder.establish(), затем
 * передаётся в hev. Один VpnService в процессе — без конфликта.
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
        const val TUN_MTU = 8500
        const val DNS_SERVER = "1.1.1.1"
    }

    private var vpnInterface: ParcelFileDescriptor? = null

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

            try {
                builder.addDisallowedApplication(context.packageName)
            } catch (_: Exception) { /* ignore */ }

            // Kill-switch style: no underlying network fallback
            builder.setUnderlyingNetworks(null)

            vpnInterface = builder.establish()
            Log.i(TAG, "TUN established: ${vpnInterface != null} fd=${vpnInterface?.fd}")
            vpnInterface
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish TUN", e)
            null
        }
    }

    fun startHev(tunFd: Int): Boolean {
        return try {
            val yaml = buildHevConfig()
            val file = File(context.filesDir, "hev.yaml").apply { writeText(yaml) }
            val ok = TProxyService.TProxyStartService(file.absolutePath, tunFd)
            Log.i(TAG, "TProxyStartService result=$ok fd=$tunFd config=${file.absolutePath}")
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "TProxyStartService failed", t)
            false
        }
    }

    fun stopHev() {
        try {
            val ok = TProxyService.TProxyStopService()
            Log.i(TAG, "TProxyStopService result=$ok")
        } catch (t: Throwable) {
            Log.w(TAG, "TProxyStopService: ${t.message}")
        }
    }

    fun isHevRunning(): Boolean = try {
        TProxyService.TProxyIsRunning()
    } catch (_: Throwable) {
        false
    }

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
