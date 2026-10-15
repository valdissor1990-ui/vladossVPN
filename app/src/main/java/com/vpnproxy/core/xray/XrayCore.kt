package com.vpnproxy.core.xray

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import libv2ray.Protector
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Обёртка над AndroidLibXrayLite (libv2ray.aar).
 *
 * API:
 *  - Libv2ray.initCoreEnv(assetPath, "")
 *  - Libv2ray.newCoreController(callback)
 *  - CoreController.startLoop(configJson, tunFd)  // tunFd=0 — без встроенного TUN
 *  - CoreController.stopLoop()
 *
 * В архитектуре VPNProxy Xray слушает SOCKS на 127.0.0.1:2081,
 * а TUN → SOCKS мост делает hev-socks5-tunnel. Поэтому tunFd всегда 0.
 */
@Singleton
class XrayCore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "XrayCore"

        @Volatile
        private var envInitialized = false
    }

    private var controller: CoreController? = null

    @Volatile
    private var running = false

    /** Опциональный VpnService.protect(fd) — регистрируется из VpnProxyService. */
    @Volatile
    private var protector: ((Int) -> Boolean)? = null

    fun setProtector(fn: ((Int) -> Boolean)?) {
        protector = fn
    }

    private fun ensureEnv() {
        if (envInitialized) return
        synchronized(XrayCore::class.java) {
            if (envInitialized) return
            val assetDir = File(context.filesDir, "xray").apply { mkdirs() }
            // geodata / conf assets path; empty cert path is fine for SOCKS mode
            Libv2ray.initCoreEnv(assetDir.absolutePath, "")
            envInitialized = true
            Log.i(TAG, "libv2ray env initialized at ${assetDir.absolutePath}")
        }
    }

    /**
     * @param configJson полный JSON Xray
     * @param tunFd игнорируется (архитектура через hev); передаём 0 в startLoop
     */
    suspend fun start(configJson: String, tunFd: Int = 0): Boolean = withContext(Dispatchers.IO) {
        try {
            ensureEnv()
            // Валидация JSON
            JSONObject(configJson)

            controller?.let {
                try { it.stopLoop() } catch (_: Throwable) { /* ignore */ }
            }

            val cb = object : CoreCallbackHandler {
                override fun startup(): Long = 0L
                override fun shutdown(): Long = 0L
                override fun onEmitStatus(l: Long, s: String?): Long {
                    Log.d(TAG, "status[$l]: $s")
                    return 0L
                }
            }

            val ctrl = Libv2ray.newCoreController(cb)

            // Protect outbound sockets from VPN loop
            try {
                Libv2ray.registerProtector(object : Protector {
                    override fun protectFd(fd: Int): Boolean {
                        val p = protector
                        return if (p != null) p.invoke(fd) else true
                    }
                })
            } catch (t: Throwable) {
                Log.w(TAG, "registerProtector skipped: ${t.message}")
            }

            // tunFd = 0: TUN обслуживает hev-socks5-tunnel, Xray только SOCKS-in
            ctrl.startLoop(configJson, 0)
            controller = ctrl
            running = true
            Log.i(TAG, "Xray startLoop OK (socks mode)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Xray start failed", e)
            running = false
            false
        }
    }

    suspend fun stop(): Boolean = withContext(Dispatchers.IO) {
        try {
            controller?.stopLoop()
            controller = null
            running = false
            Log.i(TAG, "Xray stopLoop OK")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Xray stop failed", e)
            running = false
            false
        }
    }

    suspend fun getStats(): String = withContext(Dispatchers.IO) {
        // libv2ray QueryStats is per-tag; return empty object if unavailable
        if (!running) return@withContext "{}"
        try {
            val ctrl = controller ?: return@withContext "{}"
            val uplink = ctrl.queryStats("proxy", "uplink")
            val downlink = ctrl.queryStats("proxy", "downlink")
            """{"uplink":$uplink,"downlink":$downlink}"""
        } catch (e: Exception) {
            Log.e(TAG, "Stats error", e)
            "{}"
        }
    }

    fun getVersion(): String = try {
        // Best-effort: package may expose version via Libv2ray if present
        "libv2ray"
    } catch (_: Throwable) {
        "N/A"
    }

    fun isRunning(): Boolean = running

    fun isAvailable(): Boolean = try {
        Class.forName("libv2ray.Libv2ray")
        true
    } catch (_: ClassNotFoundException) {
        false
    }
}
