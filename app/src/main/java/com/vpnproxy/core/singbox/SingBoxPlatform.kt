package com.vpnproxy.core.singbox

import android.content.Context
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import com.vpnproxy.core.connection.VpnFailureReason
import com.vpnproxy.core.connection.VpnStartException
import com.vpnproxy.core.splittunnel.SplitTunnelManager
import io.nekohasekai.libbox.*
import io.nekohasekai.libbox.RoutePrefix
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface as JavaNetworkInterface
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

    // One monitor per PlatformInterface instance. The callback and its HandlerThread are
    // retained until libbox explicitly closes the monitor.
    private val interfaceMonitorLock = Any()
    @Volatile private var interfaceMonitorCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var interfaceMonitorListener: InterfaceUpdateListener? = null
    @Volatile private var interfaceMonitorThread: HandlerThread? = null
    @Volatile private var interfaceMonitorHandler: Handler? = null
    @Volatile private var monitoredDefaultNetwork: Network? = null

    override fun usePlatformAutoDetectInterfaceControl(): Boolean {
        val enabled = true
        com.vpnproxy.logging.LogCollectorHolder.get()?.debug(TAG, "PLATFORM-AUTO-DETECT enabled=$enabled")
        Log.d(TAG, "PLATFORM-AUTO-DETECT enabled=$enabled")
        return enabled
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        val service = vpnService
        val collector = com.vpnproxy.logging.LogCollectorHolder.get()

        // libbox's PlatformInterface callback exposes the socket fd only; the outbound tag is
        // not part of this contract, so do not invent one in diagnostics.
        if (service == null) {
            val line = "protect(fd=$fd) for outbound=<unknown: libbox callback supplies fd only> result=not-applicable (no VpnService)"
            collector?.warn(TAG, line)
            Log.w(TAG, line)
            return // Proxy-only probes have no TUN to bypass.
        }

        val ok = runCatching { service.protect(fd) }
            .onFailure { error ->
                collector?.error(TAG, "protect(fd=$fd) threw for outbound=<unknown> result=false: ${error.message}")
                Log.e(TAG, "protect(fd=$fd) threw for outbound=<unknown> result=false", error)
            }
            .getOrDefault(false)

        val line = "protect(fd=$fd) for outbound=<unknown: libbox callback supplies fd only> result=$ok"
        if (ok) {
            collector?.info(TAG, line)
            Log.i(TAG, line)
        } else {
            collector?.error(TAG, "$line; protect(fd) failed — socket will be routed through TUN (loop risk)")
            Log.e(TAG, "$line; protect(fd) failed — socket will be routed through TUN (loop risk)")
            error("VpnService.protect($fd) failed; refusing an unprotected outbound socket")
        }
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
            val configuredRoutes = mutableListOf<String>()
            val configuredDns = mutableListOf<String>()
            var allowedAppsCount = 0
            var disallowedAppsCount = 0
            var disallowSelfApplied = false
            val blocking = false // Builder.setBlocking() is not used; VPN sockets are non-blocking by default.
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
                    while (dns.hasNext()) {
                        val address = dns.next()
                        builder.addDnsServer(address)
                        configuredDns += address
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val routes4 = options.inet4RouteAddress
                    if (routes4.hasNext()) while (routes4.hasNext()) {
                        val route = routes4.next()
                        builder.addRoute(route.toIpPrefix())
                        configuredRoutes += "${route.address()}/${route.prefix()}"
                    } else if (options.inet4Address.hasNext()) {
                        builder.addRoute("0.0.0.0", 0)
                        configuredRoutes += "0.0.0.0/0"
                    }

                    val routes6 = options.inet6RouteAddress
                    if (routes6.hasNext()) while (routes6.hasNext()) {
                        val route = routes6.next()
                        builder.addRoute(route.toIpPrefix())
                        configuredRoutes += "${route.address()}/${route.prefix()}"
                    } else if (options.inet6Address.hasNext()) {
                        builder.addRoute("::", 0)
                        configuredRoutes += "::/0"
                    }

                    val excludes4 = options.inet4RouteExcludeAddress
                    while (excludes4.hasNext()) builder.excludeRoute(excludes4.next().toIpPrefix())
                    val excludes6 = options.inet6RouteExcludeAddress
                    while (excludes6.hasNext()) builder.excludeRoute(excludes6.next().toIpPrefix())
                } else {
                    val routes4 = options.inet4RouteRange
                    while (routes4.hasNext()) {
                        val address = routes4.next()
                        builder.addRoute(address.address(), address.prefix())
                        configuredRoutes += "${address.address()}/${address.prefix()}"
                    }
                    val routes6 = options.inet6RouteRange
                    while (routes6.hasNext()) {
                        val address = routes6.next()
                        builder.addRoute(address.address(), address.prefix())
                        configuredRoutes += "${address.address()}/${address.prefix()}"
                    }
                }
            }

            val includes = mutableSetOf<String>()
            val excludes = mutableSetOf<String>()
            val includeIterator = options.includePackage
            while (includeIterator.hasNext()) includes += includeIterator.next()
            val excludeIterator = options.excludePackage
            while (excludeIterator.hasNext()) excludes += excludeIterator.next()
            val diagnosticOptions = DiagnosticSettings.load(context)
            val disallowSelf = diagnosticOptions.useDisallowSelfPackage
            Log.i(TAG, "DIAG USE_DISALLOW_SELF_PACKAGE=" + disallowSelf)
            com.vpnproxy.logging.LogCollectorHolder.get()?.info(TAG, "DIAG USE_DISALLOW_SELF_PACKAGE=$disallowSelf")
            Log.i(TAG, "DIAG AUTO_DETECT_INTERFACE=" + diagnosticOptions.autoDetectInterface)
            com.vpnproxy.logging.LogCollectorHolder.get()?.info(TAG, "DIAG AUTO_DETECT_INTERFACE=${diagnosticOptions.autoDetectInterface}")
            Log.i(TAG, "DIAG TUN_STACK=" + diagnosticOptions.tunStack)
            com.vpnproxy.logging.LogCollectorHolder.get()?.info(TAG, "DIAG TUN_STACK=${diagnosticOptions.tunStack}")
            Log.i(TAG, "DIAG DNS_MODE=" + diagnosticOptions.dnsMode)
            com.vpnproxy.logging.LogCollectorHolder.get()?.info(TAG, "DIAG DNS_MODE=${diagnosticOptions.dnsMode}")
            Log.i(TAG, "DIAG protect_enabled=true")
            com.vpnproxy.logging.LogCollectorHolder.get()?.info(TAG, "DIAG protect_enabled=true")
            Log.i(TAG, "DIAG disallow_self=" + disallowSelf)
            com.vpnproxy.logging.LogCollectorHolder.get()?.info(TAG, "DIAG disallow_self=$disallowSelf")

            if (splitTunnelManager != null) {
                val applied = runBlocking { splitTunnelManager.applyRules(builder, context, includes, excludes, disallowSelf) }
                allowedAppsCount = applied.allowedApps.size
                disallowedAppsCount = applied.disallowedApps.size
                disallowSelfApplied = context.packageName in applied.disallowedApps
            } else if (includes.isNotEmpty()) {
                includes.filterNot { it in excludes || it == context.packageName }.forEach { packageName ->
                    try {
                        builder.addAllowedApplication(packageName)
                        allowedAppsCount++
                    } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                        Log.w(TAG, "Unknown included package: " + packageName, e)
                    }
                }
            } else {
                val disallowed = excludes + if (disallowSelf) setOf(context.packageName) else emptySet()
                disallowed.forEach { packageName ->
                    try {
                        builder.addDisallowedApplication(packageName)
                        disallowedAppsCount++
                        if (packageName == context.packageName) disallowSelfApplied = true
                    } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                        Log.w(TAG, "Unknown excluded package: " + packageName, e)
                    }
                }
            }

            Log.i(TAG, "BUILDER disallowSelf=$disallowSelfApplied")
            com.vpnproxy.logging.LogCollectorHolder.get()?.info(TAG, "BUILDER disallowSelf=$disallowSelfApplied")
            Log.i(TAG, "BUILDER allowedApps=$allowedAppsCount")
            com.vpnproxy.logging.LogCollectorHolder.get()?.info(TAG, "BUILDER allowedApps=$allowedAppsCount")
            Log.i(TAG, "BUILDER disallowedApps=$disallowedAppsCount")
            com.vpnproxy.logging.LogCollectorHolder.get()?.info(TAG, "BUILDER disallowedApps=$disallowedAppsCount")
            Log.i(TAG, "BUILDER routes=$configuredRoutes")
            com.vpnproxy.logging.LogCollectorHolder.get()?.info(TAG, "BUILDER routes=$configuredRoutes")
            Log.i(TAG, "BUILDER dns=$configuredDns")
            com.vpnproxy.logging.LogCollectorHolder.get()?.info(TAG, "BUILDER dns=$configuredDns")
            Log.i(TAG, "BUILDER mtu=1400")
            com.vpnproxy.logging.LogCollectorHolder.get()?.info(TAG, "BUILDER mtu=1400")
            Log.i(TAG, "BUILDER blocking=$blocking")
            com.vpnproxy.logging.LogCollectorHolder.get()?.info(TAG, "BUILDER blocking=$blocking")
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
            com.vpnproxy.logging.LogCollectorHolder.get()?.info(TAG, "TUN established: fd=${pfd.fd}, mtu=1400")
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

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: error("DefaultInterfaceMonitor: ConnectivityManager unavailable")

        synchronized(interfaceMonitorLock) {
            // Be defensive if libbox starts the monitor more than once.
            interfaceMonitorCallback?.let { oldCallback ->
                runCatching { connectivity.unregisterNetworkCallback(oldCallback) }
                interfaceMonitorThread?.quitSafely()
                interfaceMonitorCallback = null
                interfaceMonitorListener = null
                interfaceMonitorHandler = null
                interfaceMonitorThread = null
                monitoredDefaultNetwork = null
            }

            val thread = HandlerThread("VLADOSSVPN-default-network").apply { start() }
            val handler = Handler(thread.looper)
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (!isCurrentMonitor(this)) return
                    if (!isPhysicalInternetNetwork(connectivity, network)) {
                        Log.d(TAG, "DefaultInterfaceMonitor: ignoring non-physical network=$network")
                        return
                    }
                    monitoredDefaultNetwork = network
                    publishDefaultInterface(connectivity, network, this, 0)
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    if (!isCurrentMonitor(this) || !isPhysicalInternetNetwork(connectivity, network)) return
                    if (monitoredDefaultNetwork == null || monitoredDefaultNetwork == network) {
                        monitoredDefaultNetwork = network
                        publishDefaultInterface(connectivity, network, this, 0)
                    }
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    if (!isCurrentMonitor(this) || !isPhysicalInternetNetwork(connectivity, network)) return
                    if (monitoredDefaultNetwork == null || monitoredDefaultNetwork == network) {
                        monitoredDefaultNetwork = network
                        publishDefaultInterface(connectivity, network, this, 0)
                    }
                }

                override fun onLost(network: Network) {
                    if (!isCurrentMonitor(this) || monitoredDefaultNetwork != network) return
                    val replacement = findCurrentPhysicalNetwork(connectivity, exclude = network)
                    monitoredDefaultNetwork = replacement
                    if (replacement == null) {
                        notifyDefaultInterface(listener, "", -1, false)
                        logMonitor("DefaultInterfaceMonitor: network lost; no physical default network")
                    } else {
                        publishDefaultInterface(connectivity, replacement, this, 0)
                    }
                }
            }

            interfaceMonitorListener = listener
            interfaceMonitorCallback = callback
            interfaceMonitorThread = thread
            interfaceMonitorHandler = handler

            try {
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                        // On Android 12+, this follows the best matching non-VPN network.
                        connectivity.registerBestMatchingNetworkCallback(request, callback, handler)
                    }
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
                        // Android 9-11's default callback can report the VPN itself. SFA uses
                        // a best-network request on these releases instead.
                        connectivity.requestNetwork(request, callback, handler)
                    }
                    else -> connectivity.registerDefaultNetworkCallback(callback, handler)
                }
            } catch (t: Throwable) {
                interfaceMonitorCallback = null
                interfaceMonitorListener = null
                interfaceMonitorHandler = null
                interfaceMonitorThread = null
                monitoredDefaultNetwork = null
                thread.quitSafely()
                Log.e(TAG, "DefaultInterfaceMonitor registration failed", t)
                throw IllegalStateException("Unable to register Android default network monitor: ${t.message}", t)
            }

            val initialNetwork = findCurrentPhysicalNetwork(connectivity)
            monitoredDefaultNetwork = initialNetwork
            val initial = initialNetwork?.let { resolveInterface(connectivity, it) }
            val initialName = initial?.name.orEmpty()
            val initialIndex = initial?.index ?: -1
            if (initialNetwork == null) {
                notifyDefaultInterface(listener, "", -1, false)
            } else if (initial != null) {
                notifyDefaultInterface(listener, initialName, initialIndex, isNetworkMetered(connectivity, initialNetwork))
            } else {
                // LinkProperties may not be ready yet; publish the real interface from the
                // callback once Android finishes configuring the network.
                notifyDefaultInterface(listener, "", -1, false)
                handler.postDelayed({
                    if (isCurrentMonitor(callback)) publishDefaultInterface(connectivity, initialNetwork, callback, 1)
                }, 100L)
            }
            logMonitor("DefaultInterfaceMonitor started: current default=${if (initialName.isBlank()) "<pending>" else initialName} index=$initialIndex")
        }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val callback: ConnectivityManager.NetworkCallback?
        val thread: HandlerThread?
        synchronized(interfaceMonitorLock) {
            callback = interfaceMonitorCallback
            thread = interfaceMonitorThread
            interfaceMonitorCallback = null
            interfaceMonitorListener = null
            interfaceMonitorHandler = null
            interfaceMonitorThread = null
            monitoredDefaultNetwork = null
        }
        if (callback != null && connectivity != null) {
            runCatching { connectivity.unregisterNetworkCallback(callback) }
                .onFailure { Log.w(TAG, "DefaultInterfaceMonitor unregister failed: ${it.message}") }
        }
        thread?.quitSafely()
        logMonitor("DefaultInterfaceMonitor stopped")
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: throw IllegalStateException("getInterfaces: ConnectivityManager unavailable")

        try {
            val javaInterfaces = JavaNetworkInterface.getNetworkInterfaces().toList()
            val result = mutableListOf<io.nekohasekai.libbox.NetworkInterface>()
            val interfaceLogEntries = mutableListOf<Triple<String, Int, List<String>>>()

            for (network in connectivity.allNetworks) {
                val linkProperties = connectivity.getLinkProperties(network) ?: continue
                val capabilities = connectivity.getNetworkCapabilities(network) ?: continue
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                ) continue

                val interfaceName = linkProperties.interfaceName ?: continue
                val javaInterface = javaInterfaces.firstOrNull { it.name == interfaceName }
                    ?: runCatching { JavaNetworkInterface.getByName(interfaceName) }.getOrNull()
                    ?: continue

                val addresses = javaInterface.interfaceAddresses.mapNotNull { address ->
                    runCatching { address.toPrefix() }.getOrNull()
                }
                val dnsServers = linkProperties.dnsServers.mapNotNull { it.hostAddress }
                val gateways = linkProperties.routes
                    .filter { it.destination.prefixLength == 0 }
                    .mapNotNull { it.gateway }
                    .filterNot { it.isAnyLocalAddress }
                    .mapNotNull { it.hostAddress }
                val boxInterface = io.nekohasekai.libbox.NetworkInterface().apply {
                    name = interfaceName
                    index = javaInterface.index
                    mtu = runCatching { javaInterface.mtu }.getOrDefault(0)
                    this.addresses = StringIteratorArray(addresses.iterator())
                    dnsServer = StringIteratorArray(dnsServers.iterator())
                    gateway = StringIteratorArray(gateways.iterator())
                    type = when {
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                        else -> Libbox.InterfaceTypeOther
                    }
                    var interfaceFlags = 0
                    if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        interfaceFlags = interfaceFlags or OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                    }
                    if (javaInterface.isLoopback) interfaceFlags = interfaceFlags or OsConstants.IFF_LOOPBACK
                    if (javaInterface.isPointToPoint) interfaceFlags = interfaceFlags or OsConstants.IFF_POINTOPOINT
                    if (runCatching { javaInterface.supportsMulticast() }.getOrDefault(false)) {
                        interfaceFlags = interfaceFlags or OsConstants.IFF_MULTICAST
                    }
                    flags = interfaceFlags
                    metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                }
                result += boxInterface
                interfaceLogEntries += Triple(interfaceName, javaInterface.index, addresses)
            }

            logMonitor("getInterfaces called: returning ${result.size} interfaces")
            interfaceLogEntries.forEachIndexed { index, entry ->
                logMonitor("Interface[$index]: name=${entry.first} index=${entry.second} addresses=${entry.third}")
            }
            if (result.isEmpty()) {
                Log.w(TAG, "getInterfaces returned zero physical interfaces; allNetworks=${connectivity.allNetworks.size}, javaInterfaces=${javaInterfaces.map { it.name }}")
            }
            return InterfaceArray(result.iterator())
        } catch (t: Throwable) {
            Log.e(TAG, "getInterfaces failed", t)
            com.vpnproxy.logging.LogCollectorHolder.get()?.error(TAG, "getInterfaces failed: ${t.message}")
            throw IllegalStateException("Unable to enumerate Android network interfaces: ${t.message}", t)
        }
    }

    private data class InterfaceSnapshot(val name: String, val index: Int)

    private fun isCurrentMonitor(callback: ConnectivityManager.NetworkCallback): Boolean =
        interfaceMonitorCallback === callback

    private fun isPhysicalInternetNetwork(connectivity: ConnectivityManager, network: Network): Boolean {
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
    }

    private fun findCurrentPhysicalNetwork(
        connectivity: ConnectivityManager,
        exclude: Network? = null
    ): Network? {
        val active = runCatching { connectivity.activeNetwork }.getOrNull()
        if (active != null && active != exclude && isPhysicalInternetNetwork(connectivity, active)) {
            return active
        }
        return connectivity.allNetworks.asSequence()
            .filter { it != exclude && isPhysicalInternetNetwork(connectivity, it) }
            .sortedByDescending { network ->
                connectivity.getNetworkCapabilities(network)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            }
            .firstOrNull()
    }

    private fun resolveInterface(connectivity: ConnectivityManager, network: Network): InterfaceSnapshot? {
        val linkProperties = connectivity.getLinkProperties(network) ?: return null
        val name = linkProperties.interfaceName?.takeIf { it.isNotBlank() } ?: return null
        val javaInterface = runCatching { JavaNetworkInterface.getByName(name) }.getOrNull() ?: return null
        return InterfaceSnapshot(name, javaInterface.index)
    }

    private fun isNetworkMetered(connectivity: ConnectivityManager, network: Network): Boolean {
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun publishDefaultInterface(
        connectivity: ConnectivityManager,
        network: Network,
        callback: ConnectivityManager.NetworkCallback,
        attempt: Int
    ) {
        if (!isCurrentMonitor(callback)) return
        val snapshot = resolveInterface(connectivity, network)
        if (snapshot == null) {
            if (attempt < 10) {
                interfaceMonitorHandler?.postDelayed({
                    if (isCurrentMonitor(callback) && monitoredDefaultNetwork == network) {
                        publishDefaultInterface(connectivity, network, callback, attempt + 1)
                    }
                }, 100L)
                return
            }
            val currentListener = interfaceMonitorListener ?: return
            notifyDefaultInterface(currentListener, "", -1, false)
            logMonitor("DefaultInterfaceMonitor: network changed but interface unresolved for network=$network")
            return
        }

        if (monitoredDefaultNetwork != network) return
        val currentListener = interfaceMonitorListener ?: return
        notifyDefaultInterface(currentListener, snapshot.name, snapshot.index, isNetworkMetered(connectivity, network))
        logMonitor("DefaultInterfaceMonitor: network changed to ${snapshot.name} index=${snapshot.index}")
    }

    private fun notifyDefaultInterface(
        listener: InterfaceUpdateListener,
        name: String,
        index: Int,
        isExpensive: Boolean
    ) {
        runCatching { listener.updateDefaultInterface(name, index, isExpensive, false) }
            .onFailure { Log.e(TAG, "InterfaceUpdateListener.updateDefaultInterface failed", it) }
    }

    private fun logMonitor(message: String) {
        Log.i(TAG, message)
        com.vpnproxy.logging.LogCollectorHolder.get()?.info(TAG, message)
    }

    private class InterfaceArray(
        private val iterator: Iterator<io.nekohasekai.libbox.NetworkInterface>
    ) : NetworkInterfaceIterator {
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): io.nekohasekai.libbox.NetworkInterface = iterator.next()
    }

    private class StringIteratorArray(private val iterator: Iterator<String>) : StringIterator {
        override fun len(): Int = 0
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): String = iterator.next()
    }

    private fun InterfaceAddress.toPrefix(): String = if (address is Inet6Address) {
        "${Inet6Address.getByAddress(address.address).hostAddress}/$networkPrefixLength"
    } else {
        "${address.hostAddress}/$networkPrefixLength"
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
