package com.vpnproxy.core.xray

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * JNI-обёртка над libxray.so (Xray-core).
 *
 * Загружает нативную библиотеку, запускает/останавливает Xray с переданным JSON-конфигом,
 * предоставляет статистику трафика через Xray Stats API.
 */
@Singleton
class XrayCore @Inject constructor() {

    companion object {
        private const val TAG = "XrayCore"
        private var libraryLoaded = false

        init {
            try {
                System.loadLibrary("xray")
                libraryLoaded = true
                Log.i(TAG, "libxray.so loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load libxray.so: ${e.message}")
            }
        }

        fun isAvailable(): Boolean = libraryLoaded
    }

    // --- Native methods (реализация в libxray.so) ---
    private external fun nativeStartXray(configJson: String, tunFd: Int): Int
    private external fun nativeStopXray(): Int
    private external fun nativeGetStats(): String
    private external fun nativeVersion(): String

    @Volatile private var running = false

    /**
     * Запускает Xray-core с указанным конфигом.
     * @param configJson полный JSON конфигурации Xray
     * @param tunFd дескриптор TUN-интерфейса (из VpnService)
     * @return true если запуск успешен
     */
    suspend fun start(configJson: String, tunFd: Int): Boolean = withContext(Dispatchers.IO) {
        if (!libraryLoaded) {
            Log.e(TAG, "Cannot start: library not loaded")
            return@withContext false
        }
        try {
            // Валидация конфига перед запуском
            JSONObject(configJson)
            val result = nativeStartXray(configJson, tunFd)
            running = result == 0
            Log.i(TAG, "Xray start result=$result, running=$running")
            running
        } catch (e: Exception) {
            Log.e(TAG, "Xray start failed", e)
            false
        }
    }

    /** Останавливает Xray-core. */
    suspend fun stop(): Boolean = withContext(Dispatchers.IO) {
        if (!libraryLoaded) return@withContext false
        try {
            val result = nativeStopXray()
            running = false
            Log.i(TAG, "Xray stop result=$result")
            result == 0
        } catch (e: Exception) {
            Log.e(TAG, "Xray stop failed", e)
            false
        }
    }

    /** Возвращает статистику трафика в JSON-формате. */
    suspend fun getStats(): String = withContext(Dispatchers.IO) {
        if (!libraryLoaded || !running) return@withContext "{}"
        try {
            nativeGetStats()
        } catch (e: Exception) {
            Log.e(TAG, "Stats error", e)
            "{}"
        }
    }

    fun getVersion(): String = if (libraryLoaded) nativeVersion() else "N/A"
    fun isRunning(): Boolean = running
}
