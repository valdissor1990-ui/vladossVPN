package com.vpnproxy.core.tunnel

import android.net.TrafficStats
import android.util.Log
import com.vpnproxy.core.xray.XrayCore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Сборщик статистики трафика.
 *
 * Источники:
 * 1. TrafficStats (системные счётчики Rx/Tx для приложения)
 * 2. Xray Stats API (детальная статистика по outbound-трафику)
 *
 * Обновление раз в 1 секунду, Flow<StateFlow<TrafficSnapshot>>.
 */
@Singleton
class TrafficStatsCollector @Inject constructor(
    private val xrayCore: XrayCore
) {
    companion object {
        private const val TAG = "TrafficStats"
        private const val UPDATE_INTERVAL_MS = 1000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var collectJob: Job? = null

    private val _traffic = MutableStateFlow(TrafficSnapshot())
    val traffic: StateFlow<TrafficSnapshot> = _traffic.asStateFlow()

    private var lastRx = 0L
    private var lastTx = 0L
    private var lastTimestamp = 0L

    private var sessionRx = 0L
    private var sessionTx = 0L
    private var totalRx = 0L
    private var totalTx = 0L

    /** Запускает сбор статистики. */
    fun start() {
        if (collectJob?.isActive == true) return
        lastRx = TrafficStats.getTotalRxBytes().coerceAtLeast(0)
        lastTx = TrafficStats.getTotalTxBytes().coerceAtLeast(0)
        lastTimestamp = System.currentTimeMillis()

        collectJob = scope.launch {
            while (isActive) {
                try {
                    collectOnce()
                } catch (e: Exception) {
                    Log.e(TAG, "Collect error", e)
                }
                delay(UPDATE_INTERVAL_MS)
            }
        }
    }

    private suspend fun collectOnce() {
        val now = System.currentTimeMillis()
        val elapsed = (now - lastTimestamp).coerceAtLeast(1L) / 1000.0

        // Системные счётчики
        val currentRx = TrafficStats.getTotalRxBytes().coerceAtLeast(0)
        val currentTx = TrafficStats.getTotalTxBytes().coerceAtLeast(0)

        val deltaRx = (currentRx - lastRx).coerceAtLeast(0)
        val deltaTx = (currentTx - lastTx).coerceAtLeast(0)

        lastRx = currentRx
        lastTx = currentTx
        lastTimestamp = now

        sessionRx += deltaRx
        sessionTx += deltaTx
        totalRx += deltaRx
        totalTx += deltaTx

        // Xray Stats API (дополняет системные данные)
        val xrayStats = parseXrayStats(xrayCore.getStats())

        val downloadMbps = (deltaRx * 8.0 / 1_000_000.0 / elapsed).toFloat()
        val uploadMbps = (deltaTx * 8.0 / 1_000_000.0 / elapsed).toFloat()

        _traffic.value = TrafficSnapshot(
            downloadMbps = downloadMbps,
            uploadMbps = uploadMbps,
            sessionRx = sessionRx,
            sessionTx = sessionTx,
            totalRx = totalRx,
            totalTx = totalTx,
            xrayUplink = xrayStats.first,
            xrayDownlink = xrayStats.second,
            timestamp = now
        )
    }

    private fun parseXrayStats(json: String): Pair<Long, Long> {
        return try {
            val obj = JSONObject(json)
            val uplink = obj.optLong("uplink", 0)
            val downlink = obj.optLong("downlink", 0)
            uplink to downlink
        } catch (_: Exception) { 0L to 0L }
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
    }

    fun resetSession() {
        sessionRx = 0; sessionTx = 0
        lastRx = TrafficStats.getTotalRxBytes().coerceAtLeast(0)
        lastTx = TrafficStats.getTotalTxBytes().coerceAtLeast(0)
    }

    data class TrafficSnapshot(
        val downloadMbps: Float = 0f,
        val uploadMbps: Float = 0f,
        val sessionRx: Long = 0L,
        val sessionTx: Long = 0L,
        val totalRx: Long = 0L,
        val totalTx: Long = 0L,
        val xrayUplink: Long = 0L,
        val xrayDownlink: Long = 0L,
        val timestamp: Long = 0L
    )
}
