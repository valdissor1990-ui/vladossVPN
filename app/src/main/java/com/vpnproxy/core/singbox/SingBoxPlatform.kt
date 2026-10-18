package com.vpnproxy.core.singbox

import android.content.Context
import android.content.pm.PackageManager
import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.vpnproxy.core.splittunnel.SplitTunnelManager
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
        val service = vpnService ?: error("TUN is unavailable in proxy-only probe mode")
        check(VpnService.prepare(context) == null) { "VPN permission is missing" }
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

        if (options.autoRoute) {
            if (options.dnsMode.value != Libbox.DNSModeDisabled) {
                val dns = options.dnsServerAddress
                while (dns.hasNext()) builder.addDnsServer(dns.next())
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val routes4 = options.inet4RouteAddress
                if (routes4.hasNext()) while (routes4.hasNext()) builder.addRoute(routes4.next().toIpPrefix())
                else if (options.inet4Address.hasNext()) builder.addRoute("0.0.0.0", 0)

                val routes6 = options.inet6RouteAddress
                if (routes6.hasNext()) while (routes6.hasNext()) builder.addRoute(routes6.next().toIpPrefix())
                else if (options.inet6Address.hasNext()) builder.addRoute("::", 0)

                val excludes4 = options.inet4RouteExcludeAddress
                while (excludes4.hasNext()) builder.excludeRoute(excludes4.next().toIpPrefix())
                val excludes6 = options.inet6RouteExcludeAddress
                while (excludes6.hasNext()) builder.excludeRoute(excludes6.next().toIpPrefix())
            } else {
                val routes4 = options.inet4RouteRange
                while (routes4.hasNext()) {
                    val address = routes4.next()
                    builder.addRoute(address.address(), address.prefix())
                }
                val routes6 = options.inet6RouteRange
                while (routes6.hasNext()) {
                    val address = routes6.next()
                    builder.addRoute(address.address(), address.prefix())
                }
            }
        }

        // Apply saved INCLUDE_SELECTED / EXCLUDE_SELECTED policy in the same builder
        // that establishes the TUN. The VPN app is always excluded from per-app routing.
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

        val pfd = builder.establish() ?: error("VpnService.Builder.establish() returned null")
        tunDescriptor = pfd
        Log.i(TAG, "TUN established: fd=${pfd.fd}, mtu=1400")
        return pfd.fd
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
    override fun usePlatformAutoRedirect(): Boolean = unsupported("usePlatformAutoRedirect", false)

    override fun findConnectionOwner(ipProtocol: Int, sourceAddress: String, sourcePort: Int,
        destinationAddress: String, destinationPort: Int): ConnectionOwner =
        unsupported("findConnectionOwner")

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = unsupported("startDefaultInterfaceMonitor")
    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = unsupported("closeDefaultInterfaceMonitor")
    override fun getInterfaces(): NetworkInterfaceIterator = unsupported("getInterfaces")
    override fun clearDNSCache() = unsupported("clearDNSCache")
    override fun readWIFIState(): WIFIState? = unsupportedNullable("readWIFIState")
    override fun localDNSTransport(): LocalDNSTransport? = unsupportedNullable("localDNSTransport")
    override fun startNeighborMonitor(listener: NeighborUpdateListener?) = unsupported("startNeighborMonitor")
    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) = unsupported("closeNeighborMonitor")
    override fun checkPlatformShell() = unsupported("checkPlatformShell")
    override fun openShellSession(user: PlatformUser?, command: String?, environ: StringIterator?, term: String?,
        rows: Int, cols: Int): ShellSession = unsupported("openShellSession")
    override fun readSystemSSHHostKey(): String = unsupported("readSystemSSHHostKey", "")
    override fun lookupSFTPServer(): String = unsupported("lookupSFTPServer", "")
    override fun tailscaleHostname(): String = unsupported("tailscaleHostname", "")
    override fun createBridge(options: BridgeOptions?): BridgeSession = unsupported("createBridge")
    override fun createAutoRedirect(options: ByteArray?, handler: AutoRedirectHandler?): AutoRedirectSession =
        unsupported("createAutoRedirect")
    override fun lookupUser(username: String?): PlatformUser = unsupported("lookupUser")
    override fun registerMyInterface(name: String?) = unsupported("registerMyInterface")

    private fun <T> unsupported(method: String, fallback: T): T {
        Log.w(TAG, "unsupported PlatformInterface method: $method")
        return fallback
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
