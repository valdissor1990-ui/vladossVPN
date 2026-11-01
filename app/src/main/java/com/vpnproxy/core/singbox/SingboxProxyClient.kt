package com.vpnproxy.core.singbox

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Compatibility facade for temporary proxy-only checks. This runs a CommandServer in-process;
 * it does not bind an Android Service and never creates a :singbox process.
 */
object SingboxProxyClient {
    private const val TAG = "SingboxProxyClient"
    private const val PORT_READY_TIMEOUT_MS = 6_000L
    private const val PORT_READY_POLL_MS = 50L
    private const val PORT_CONNECT_TIMEOUT_MS = 100
    @Volatile private var activeCore: SingBoxCore? = null

    @Synchronized
    fun start(context: Context, configJson: String): Boolean {
        stop(context)
        val appContext = context.applicationContext
        val core = SingBoxCore(appContext, SingBoxPlatform(appContext))
        return try {
            core.start(configJson)
            val inboundArray = JSONObject(configJson).getJSONArray("inbounds")
            val ports = (0 until inboundArray.length()).map { index ->
                inboundArray.getJSONObject(index).getInt("listen_port")
            }.distinct()
            check(ports.isNotEmpty()) { "Sing-box config has no local inbound ports" }

            // Multi-profile probes open several inbounds in one core. Waiting only for
            // the first listener lets the other profile checks race core initialization
            // and incorrectly marks healthy profiles as unreachable.
            val pending = ports.toMutableSet()
            val deadline = System.nanoTime() + PORT_READY_TIMEOUT_MS * 1_000_000L
            while (pending.isNotEmpty() && System.nanoTime() < deadline) {
                val readyNow = pending.filter { port ->
                    runCatching {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress("127.0.0.1", port), PORT_CONNECT_TIMEOUT_MS)
                        }
                    }.isSuccess
                }
                pending.removeAll(readyNow.toSet())
                if (pending.isNotEmpty()) Thread.sleep(PORT_READY_POLL_MS)
            }
            check(pending.isEmpty()) {
                "Sing-box did not bind all local inbounds; ready=" + (ports.size - pending.size) + "/" + ports.size + ", pending=" + pending
            }
            activeCore = core
            Log.i(TAG, "Temporary proxy-only Sing-box ready on all " + ports.size + " inbounds: " + ports)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Temporary Sing-box startup failed", t)
            runCatching { core.stop() }
            false
        }
    }

    @Synchronized
    fun stop(context: Context? = null) {
        val core = activeCore
        activeCore = null
        runCatching { core?.stop() }.onFailure { Log.w(TAG, "Failed to stop temporary Sing-box", it) }
    }
}
