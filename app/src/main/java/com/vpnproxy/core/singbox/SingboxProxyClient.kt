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
    @Volatile private var activeCore: SingBoxCore? = null

    @Synchronized
    fun start(context: Context, configJson: String): Boolean {
        stop(context)
        val appContext = context.applicationContext
        val core = SingBoxCore(appContext, SingBoxPlatform(appContext))
        return try {
            core.start(configJson)
            val port = JSONObject(configJson).getJSONArray("inbounds").getJSONObject(0).getInt("listen_port")
            var ready = false
            repeat(60) {
                if (!ready) {
                    try {
                        Socket().use { socket -> socket.connect(InetSocketAddress("127.0.0.1", port), 200) }
                        ready = true
                    } catch (_: Exception) {
                        Thread.sleep(100)
                    }
                }
            }
            check(ready) { "Sing-box did not bind 127.0.0.1:$port" }
            activeCore = core
            Log.i(TAG, "Temporary proxy-only Sing-box ready on 127.0.0.1:$port")
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
