package com.vpnproxy.core.singbox

import android.content.Context
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.vpnproxy.core.connection.VpnFailureReason
import com.vpnproxy.core.connection.VpnStartException
import com.vpnproxy.core.splittunnel.SplitTunnelManager
import com.vpnproxy.BuildConfig
import com.vpnproxy.util.DebugFlags
import io.nekohasekai.libbox.*
import io.nekohasekai.libbox.RoutePrefix
import java.net.InetAddress
import kotlinx.coroutines.runBlocking

/**
 * Android implementation of libbox 1.14.3's PlatformInterface.
 * The VPN service owns the TUN descriptor; libbox receives only its fd.
 */
class SingBoxPlatform(
    private val context: Context,
    private val vpnService: VpnService? = null,
    private val splitTunnelManager: SplitTunnelManager? = null
) : PlatformInterface {
    companion object { private const val TAG = "SingBoxPlatform" }

    @Volatile private var tunDescriptor: ParcelFileDescriptor? = null

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        val service = vpnService
        if (service == null) {
            Log.d(TAG, "No active VPN service for fd=$fd; probe mode has no TUN to bypass")
            return
        }
        check(service.protect(fd)) { "VpnService.protect($fd) failed" }
    }

    override fun openTun(options: TunOptions): Int {
        try {
            Log.i(TAG, "openTun requested by libbox")
            val service = vpnService ?: error("TUN is unavailable in proxy-only probe mode")

            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent != null) {
                throw VpnStartException(VpnFailureReason.PermissionDenied)
            }

            tunDescriptor?.close()
            val builder = service.Builder()
                .setSession("VPNProxy · Sing-box")
                .setMtu(1400)

            val v4 = options.inet4Address
            while (v4.hasNext()) {
                val address = v4.next()
                builder.addAddress(address.address(), address.prefix())
            }
            val v6 = options.inet6Address
            while (v6.hasNext()) {
                val address = v6.next()
                builder.addAddress(address.address(), address.prefix())
            }

            val appliedDns = mutableListOf<String>()
            val appliedRoutes = mutableListOf<String>()
            val excludedRoutes = mutableListOf<String>()
            if (options.autoRoute) {
                if (options.dnsMode.value != Libbox.DNSModeDisabled) {
                    val dns = options.dnsServerAddress
                    while (dns.hasNext()) {
                        val dnsAddress = dns.next()
                        builder.addDnsServer(dnsAddress)
                        appliedDns += dnsAddress
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val routes4 = options.inet4RouteAddress
                    if (routes4.hasNext()) while (routes4.hasNext()) {
                        val route = routes4.next().toIpPrefix()
                        builder.addRoute(route)
                        appliedRoutes += route.toString()
                    } else if (options.inet4Address.hasNext()) {
                        builder.addRoute("0.0.0.0", 0)
                        appliedRoutes += "0.0.0.0/0"
                    }

                    val routes6 = options.inet6RouteAddress
                    if (routes6.hasNext()) while (routes6.hasNext()) {
                        val route = routes6.next().toIpPrefix()
                        builder.addRoute(route)
                        appliedRoutes += route.toString()
                    } else if (options.inet6Address.hasNext()) {
                        builder.addRoute("::", 0)
                        appliedRoutes += "::/0"
                    }

                    val excludes4 = options.inet4RouteExcludeAddress
                    while (excludes4.hasNext()) {
                        val route = excludes4.next().toIpPrefix()
                        builder.excludeRoute(route)
                        excludedRoutes += route.toString()
                    }
                    val excludes6 = options.inet6RouteExcludeAddress
                    while (excludes6.hasNext()) {
                        val route = excludes6.next().toIpPrefix()
                        builder.excludeRoute(route)
                        excludedRoutes += route.toString()
                    }
                } else {
                    val routes4 = options.inet4RouteRange
                    while (routes4.hasNext()) {
                        val address = routes4.next()
                        builder.addRoute(address.address(), address.prefix())
                        appliedRoutes += "${address.address()}/${address.prefix()}"
                    }
                    val routes6 = options.inet6RouteRange
                    while (routes6.hasNext()) {
                        val address = routes6.next()
                        builder.addRoute(address.address(), address.prefix())
                        appliedRoutes += "${address.address()}/${address.prefix()}"
                    }
                }
            }

            val includes = mutableSetOf<String>()
            val excludes = mutableSetOf<String>()
            val includeIterator = options.includePackage
            while (includeIterator.hasNext()) includes += includeIterator.next()
            val excludeIterator = options.excludePackage
            while (excludeIterator.hasNext()) excludes += excludeIterator.next()
            if (splitTunnelManager != null) {
                runBlocking { splitTunnelManager.applyRules(builder, context, includes, excludes) }
            } else if (includes.isNotEmpty()) {
                includes.filterNot { it in excludes || it == context.packageName }.forEach { builder.addAllowedApplication(it) }
            } else {
                (excludes + context.packageName).forEach { builder.addDisallowedApplication(it) }
            }

            Log.i(TAG, "TUN routes: $appliedRoutes")
            Log.i(TAG, "TUN dns: $appliedDns")
            Log.i(TAG, "TUN excluded routes: $excludedRoutes")
            Log.i(
                TAG,
                "TUN stack=${if (BuildConfig.DEBUG && DebugFlags.DIAGNOSTIC_MODE) DebugFlags.DIAG_TUN_STACK else "system"} " +
                    "strict_route=${if (BuildConfig.DEBUG && DebugFlags.DIAGNOSTIC_MODE) DebugFlags.DIAG_STRICT_ROUTE else true} mtu=1400 auto_route=${options.autoRoute}"
            )
            Log.d(TAG, "Attempting to create TUN interface...")
            val pfd = try {
                builder.establish()
            } catch (t: Throwable) {
                Log.e(TAG, "VpnService.Builder.establish() threw an exception", t)
                throw t
            }
            if (pfd == null) {
                val reason = classifyEstablishNull()
                Log.e(TAG, "establish() returned null → $reason")
                throw VpnStartException(reason)
            }
            tunDescriptor = pfd
            Log.d(TAG, "TUN created successfully, fd=${pfd.fd}")
            Log.i(TAG, "TUN established: fd=${pfd.fd}, mtu=1400")
            return pfd.fd
        } catch (e: VpnStartException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to open TUN / establish VPN interface", t)
            throw VpnStartException(
                VpnFailureReason.CoreError("Sing-box openTun failed: ${t.message}"),
                t
            )
        }
    }

    /**
     * Best-effort classification when establish() returns null.
     * prepare()!=null → permission; active VPN transport → another VPN; else TunConflict.
     */
    private fun classifyEstablishNull(): VpnFailureReason {
        val prepare = VpnService.prepare(context)
        if (prepare != null) return VpnFailureReason.PermissionDenied
        if (isAnotherVpnActive()) return VpnFailureReason.AnotherVpnActive
        return VpnFailureReason.TunConflict
    }

    private fun isAnotherVpnActive(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val active = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(active) ?: return false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } else {
                @Suppress("DEPRECATION")
                cm.allNetworks.any { net ->
                    val caps = cm.getNetworkCapabilities(net) ?: return@any false
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                }
            }
        } catch (_: Throwable) {
            false
        }
    }

    fun closeTun() {
        runCatching { tunDescriptor?.close() }.onFailure { Log.w(TAG, "Failed to close TUN", it) }
        tunDescriptor = null
    }

    override fun useProcFS(): Boolean = true
    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun usePlatformShell(): Boolean = unsupported("usePlatformShell", false)
    override fun usePlatformBridge(): Boolean = unsupported("usePlatformBridge", false)

    override fun findConnectionOwner(ipProtocol: Int, sourceAddress: String, sourcePort: Int,
        destinationAddress: String, destinationPort: Int): ConnectionOwner =
        unsupported("findConnectionOwner")

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = unsupportedUnit("startDefaultInterfaceMonitor")
    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = unsupportedUnit("closeDefaultInterfaceMonitor")
    override fun getInterfaces(): NetworkInterfaceIterator {
        unsupportedUnit("getInterfaces")
        return object : NetworkInterfaceIterator {
            override fun hasNext(): Boolean = false
            override fun next(): io.nekohasekai.libbox.NetworkInterface = error("no platform interfaces exposed")
        }
    }
    override fun clearDNSCache() = unsupportedUnit("clearDNSCache")
    override fun readWIFIState(): WIFIState? = unsupportedNullable("readWIFIState")
    override fun localDNSTransport(): LocalDNSTransport? = unsupportedNullable("localDNSTransport")
    override fun startNeighborMonitor(listener: NeighborUpdateListener?) = unsupportedUnit("startNeighborMonitor")
    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) = unsupportedUnit("closeNeighborMonitor")
    override fun checkPlatformShell() = unsupportedUnit("checkPlatformShell")
    override fun openShellSession(user: PlatformUser?, command: String?, environ: StringIterator?, term: String?,
        rows: Int, cols: Int): ShellSession = unsupported("openShellSession")
    override fun readSystemSSHHostKey(): String = unsupported("readSystemSSHHostKey", "")
    override fun lookupSFTPServer(): String = unsupported("lookupSFTPServer", "")
    override fun tailscaleHostname(): String = unsupported("tailscaleHostname", "")
    override fun createBridge(options: BridgeOptions?): BridgeSession = unsupported("createBridge")
    override fun lookupUser(username: String?): PlatformUser = unsupported("lookupUser")
    override fun registerMyInterface(name: String?) = unsupportedUnit("registerMyInterface")
    override fun sendNotification(notification: Notification) = unsupportedUnit("sendNotification")
    override fun cancelNotification(identifier: String, typeID: Int) = unsupportedUnit("cancelNotification")

    private fun <T> unsupported(method: String, fallback: T): T {
        Log.w(TAG, "unsupported PlatformInterface method: $method")
        return fallback
    }
    private fun unsupportedUnit(method: String) {
        Log.w(TAG, "unsupported PlatformInterface method: $method")
    }
    private fun <T> unsupportedNullable(method: String): T? {
        Log.w(TAG, "unsupported PlatformInterface method: $method")
        return null
    }
    private fun unsupported(method: String): Nothing {
        Log.w(TAG, "unsupported PlatformInterface method: $method")
        error("unsupported PlatformInterface method: $method")
    }

    private fun RoutePrefix.toIpPrefix(): IpPrefix =
        IpPrefix(InetAddress.getByName(address()), prefix())
}
