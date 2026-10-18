package com.vpnproxy.core.singbox

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
    private val vpnService: VpnService,
    private val splitTunnelManager: SplitTunnelManager
) : PlatformInterface {
    companion object { private const val TAG = "SingBoxPlatform" }

    @Volatile private var tunDescriptor: ParcelFileDescriptor? = null

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        check(vpnService.protect(fd)) { "VpnService.protect($fd) failed" }
    }

    override fun openTun(options: TunOptions): Int {
        check(VpnService.prepare(vpnService) == null) { "VPN permission is missing" }
        tunDescriptor?.close()
        val builder = vpnService.Builder()
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
        runBlocking { splitTunnelManager.applyRules(builder, vpnService) }
        applyOptionPackageRules(builder, options)

        val pfd = builder.establish() ?: error("VpnService.Builder.establish() returned null")
        tunDescriptor = pfd
        Log.i(TAG, "TUN established: fd=${pfd.fd}, mtu=1400")
        return pfd.fd
    }

    private fun applyOptionPackageRules(builder: VpnService.Builder, options: TunOptions) {
        val includes = mutableSetOf<String>()
        val excludes = mutableSetOf<String>()
        val includeIterator = options.includePackage
        while (includeIterator.hasNext()) includes += includeIterator.next()
        val excludeIterator = options.excludePackage
        while (excludeIterator.hasNext()) excludes += excludeIterator.next()
        includes -= vpnService.packageName
        excludes += vpnService.packageName
        // These lists are Sing-box's own TunOptions routing directives. Invalid package
        // names are logged and skipped, rather than aborting VPN startup.
        includes.filterNot { it in excludes }.forEach { packageName ->
            try { builder.addAllowedApplication(packageName) }
            catch (e: PackageManager.NameNotFoundException) { Log.w(TAG, "Unknown included package: $packageName", e) }
        }
        excludes.forEach { packageName ->
            try { builder.addDisallowedApplication(packageName) }
            catch (e: PackageManager.NameNotFoundException) { Log.w(TAG, "Unknown excluded package: $packageName", e) }
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
    override fun usePlatformAutoRedirect(): Boolean = unsupported("usePlatformAutoRedirect", false)

    override fun findConnectionOwner(ipProtocol: Int, sourceAddress: String, sourcePort: Int,
        destinationAddress: String, destinationPort: Int): ConnectionOwner =
        unsupported("findConnectionOwner", null as ConnectionOwner)

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = unsupported<Unit>("startDefaultInterfaceMonitor")
    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = unsupported<Unit>("closeDefaultInterfaceMonitor")
    override fun getInterfaces(): NetworkInterfaceIterator = unsupported("getInterfaces", null as NetworkInterfaceIterator)
    override fun clearDNSCache() = unsupported<Unit>("clearDNSCache")
    override fun readWIFIState(): WIFIState? = unsupported("readWIFIState", null)
    override fun localDNSTransport(): LocalDNSTransport? = unsupported("localDNSTransport", null)
    override fun startNeighborMonitor(listener: NeighborUpdateListener?) = unsupported<Unit>("startNeighborMonitor")
    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) = unsupported<Unit>("closeNeighborMonitor")
    override fun checkPlatformShell() = unsupported<Unit>("checkPlatformShell")
    override fun openShellSession(user: PlatformUser?, command: String?, environ: StringIterator?, term: String?,
        rows: Int, cols: Int): ShellSession = unsupported("openShellSession", null as ShellSession)
    override fun readSystemSSHHostKey(): String = unsupported("readSystemSSHHostKey", "")
    override fun lookupSFTPServer(): String = unsupported("lookupSFTPServer", "")
    override fun tailscaleHostname(): String = unsupported("tailscaleHostname", "")
    override fun createBridge(options: BridgeOptions?): BridgeSession = unsupported("createBridge", null as BridgeSession)
    override fun createAutoRedirect(options: ByteArray?, handler: AutoRedirectHandler?): AutoRedirectSession =
        unsupported("createAutoRedirect", null as AutoRedirectSession)
    override fun lookupUser(username: String?): PlatformUser = unsupported("lookupUser", null as PlatformUser)
    override fun registerMyInterface(name: String?) = unsupported<Unit>("registerMyInterface")

    private fun <T> unsupported(method: String, fallback: T): T {
        Log.w(TAG, "unsupported PlatformInterface method: $method")
        return fallback
    }
    private fun unsupported(method: String): Nothing {
        Log.w(TAG, "unsupported PlatformInterface method: $method")
        error("unsupported PlatformInterface method: $method")
    }

    private fun RoutePrefix.toIpPrefix(): IpPrefix =
        IpPrefix(InetAddress.getByName(address()), prefix())
}
