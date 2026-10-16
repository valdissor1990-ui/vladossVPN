package com.vpnproxy.core.xray

import android.content.Context
import android.util.Log
import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig
import com.vpnproxy.logging.LogCollectorHolder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Обёртка над AndroidLibXrayLite (libv2ray.aar).
 *
 * Проверено javap v26.10.10:
 *   Libv2ray.initCoreEnv(String assetPath, String certPath)
 *   Libv2ray.newCoreController(CoreCallbackHandler)
 *   CoreController.startLoop(String configJson, int tunFd)  // 0 = без встроенного TUN
 *   CoreController.stopLoop()
 *
 * TUN обслуживает hev-socks5-tunnel; Xray только SOCKS-in на 127.0.0.1.
 */
@Singleton
class XrayCore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "XrayCore"

        @Volatile private var envReady = false
    }

    private var controller: CoreController? = null

    @Volatile
    private var running = false

    private val callback = object : CoreCallbackHandler {
        override fun startup(): Long = 0L
        override fun shutdown(): Long = 0L
        override fun onEmitStatus(code: Long, message: String?): Long {
            val text = "onEmitStatus code=$code msg=$message"
            Log.w(TAG, text)
            LogCollectorHolder.get()?.let { collector ->
                if (code == 0L) collector.info(TAG, text)
                else collector.error(TAG, text)
            }
            return 0L
        }
    }

    /** Инициализация env (geoip/geosite из AAR assets + filesDir). */
    fun initEnv(): Boolean {
        if (envReady) return true
        return try {
            val dir = File(context.filesDir, "xray").apply { mkdirs() }
            // (assetPath, certFile) — geoip/geosite подхватываются из AAR assets
            Libv2ray.initCoreEnv(dir.absolutePath, "")
            envReady = true
            Log.i(TAG, "initCoreEnv OK path=${dir.absolutePath}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "initCoreEnv failed", t)
            false
        }
    }

    /**
     * @param configJson полный JSON Xray
     * @param tunFd 0 — TUN у hev; иначе fd встроенного TUN
     */
    suspend fun start(configJson: String, tunFd: Int = 0): Boolean = withContext(Dispatchers.IO) {
        var ctrl: CoreController? = null
        try {
            if (!initEnv()) return@withContext false
            JSONObject(configJson) // syntax validation before JNI

            controller?.let {
                try { it.stopLoop() } catch (_: Throwable) {}
            }

            ctrl = Libv2ray.newCoreController(callback)
            controller = ctrl
            running = false
            Log.i(TAG, "startLoop invoking tunFd=$tunFd")
            // This libv2ray API exposes startLoop(configJson, tunFd), not a result code.
            // A thrown exception and callback statuses are therefore logged separately.
            ctrl.startLoop(configJson, tunFd)
            running = true
            Log.i(TAG, "startLoop returned normally tunFd=$tunFd")
            true
        } catch (t: Throwable) {
            val masked = maskConfigForLog(configJson)
            Log.e(TAG, "startLoop failed tunFd=$tunFd\n$masked", t)
            LogCollectorHolder.get()?.error(TAG, "startLoop failed tunFd=$tunFd\n$masked", t)
            try { ctrl?.stopLoop() } catch (_: Throwable) {}
            controller = null
            running = false
            false
        }
    }

    /**
     * Маскирует идентификаторы и ключи, прежде чем писать Xray JSON в диагностический лог.
     * Адрес узла скрывается; transport/path/SNI остаются для диагностики.
     */
    private fun maskConfigForLog(json: String): String {
        var masked = json
        val replacements = listOf(
            Regex("\\\"id\\\"\\s*:\\s*\\\"[^\\\"]+\\\"") to "\\\"id\\\":\\\"***UUID***\\\"",
            Regex("\\\"publicKey\\\"\\s*:\\s*\\\"[^\\\"]+\\\"") to "\\\"publicKey\\\":\\\"***PBK***\\\"",
            Regex("\\\"shortId\\\"\\s*:\\s*\\\"[^\\\"]*\\\"") to "\\\"shortId\\\":\\\"***SID***\\\"",
            Regex("\\\"address\\\"\\s*:\\s*\\\"[^\\\"]+\\\"") to "\\\"address\\\":\\\"***HOST***\\\"",
            Regex("\\\"password\\\"\\s*:\\s*\\\"[^\\\"]*\\\"") to "\\\"password\\\":\\\"***MASKED***\\\"",
            Regex("\\\"token\\\"\\s*:\\s*\\\"[^\\\"]*\\\"") to "\\\"token\\\":\\\"***MASKED***\\\""
        )
        for ((pattern, replacement) in replacements) {
            masked = pattern.replace(masked, replacement)
        }
        return masked
    }

    suspend fun stop(): Boolean = withContext(Dispatchers.IO) {
        try {
            controller?.stopLoop()
            controller = null
            running = false
            Log.i(TAG, "stopLoop OK")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "stopLoop failed", t)
            running = false
            false
        }
    }

    fun buildClientConfig(serverConfig: ParsedConfig, socksPort: Int): String {
        val outbound = JSONObject(serverConfig.toXrayOutbound().toString())
        return JSONObject().apply {
            put("log", JSONObject().put("loglevel", "warning"))
            put("inbounds", JSONArray().put(JSONObject().apply {
                put("tag", "socks-in")
                put("port", socksPort)
                put("listen", "127.0.0.1")
                put("protocol", "socks")
                put("settings", JSONObject().apply {
                    put("auth", "noauth")
                    put("udp", true)
                })
                put("sniffing", JSONObject().apply {
                    put("enabled", true)
                    put("destOverride", JSONArray().put("http").put("tls").put("quic"))
                })
            }))
            put("outbounds", JSONArray().put(outbound))
            put("routing", JSONObject().apply {
                put("domainStrategy", "IPIfNonMatch")
                put("rules", JSONArray())
            })
        }.toString()
    }

    fun isRunning(): Boolean = running

    fun isAvailable(): Boolean = try {
        Class.forName("libv2ray.Libv2ray")
        true
    } catch (_: ClassNotFoundException) {
        false
    }

    fun getVersion(): String = try {
        Libv2ray.checkVersionX() ?: "libv2ray"
    } catch (_: Throwable) {
        "libv2ray"
    }
}
