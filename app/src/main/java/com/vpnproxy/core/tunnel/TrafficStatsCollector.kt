package com.vpnproxy.core.tunnel

import android.net.TrafficStats
import android.os.Process
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Сборщик статистики трафика.
 *
 * Использует TrafficStats.getUidRxBytes(Process.myUid()) — трафик конкретно
 * нашего приложения, а не всего устройства. Xray/HEV native stats не опрашиваются.
 */
@Singleton
class TrafficStatsCollector @Inject constructor() {
    companion object {
        private const val TAG = "TrafficStats"
        private const val UPDATE_INTERVAL_MS = 1000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var collectJob: Job? = null

    private val _traffic = MutableStateFlow(TrafficSnapshot())
    val traffic: StateFlow<TrafficSnapshot> = _traffic.asStateFlow()

    private val myUid = Process.myUid()

    private var lastRx = 0L
    private var lastTx = 0L
    private var lastTs = 0L
    private var sessionRx = 0L
    private var sessionTx = 0L
    private var totalRx = 0L
    private var totalTx = 0L

    fun start() {
        if (collectJob?.isActive == true) return
        lastRx = TrafficStats.getUidRxBytes(myUid).coerceAtLeast(0)
        lastTx = TrafficStats.getUidTxBytes(myUid).coerceAtLeast(0)
        lastTs = System.currentTimeMillis()

        collectJob = scope.launch {
            while (isActive) {
                try { collectOnce() } catch (e: Exception) { Log.e(TAG, "collect", e) }
                delay(UPDATE_INTERVAL_MS)
            }
        }
    }

    private suspend fun collectOnce() {
        val now = System.currentTimeMillis()
        val elapsed = (now - lastTs).coerceAtLeast(1L) / 1000.0

        val rx = TrafficStats.getUidRxBytes(myUid).coerceAtLeast(0)
        val tx = TrafficStats.getUidTxBytes(myUid).coerceAtLeast(0)

        val dRx = (rx - lastRx).coerceAtLeast(0)
        val dTx = (tx - lastTx).coerceAtLeast(0)
        lastRx = rx; lastTx = tx; lastTs = now

        sessionRx += dRx; sessionTx += dTx
        totalRx += dRx; totalTx += dTx

        // Xray/HEV stats are not used: libv2ray exposes no Java stats API,
        // and the HEV TProxyGetStats() format is undocumented and AAR-version-dependent.
        // Per-app TrafficStats counters are the source of truth.
        _traffic.value = TrafficSnapshot(
            downloadMbps = (dRx * 8.0 / 1_000_000.0 / elapsed).toFloat(),
            uploadMbps = (dTx * 8.0 / 1_000_000.0 / elapsed).toFloat(),
            sessionRx = sessionRx, sessionTx = sessionTx,
            totalRx = totalRx, totalTx = totalTx,
            xrayUplink = 0L, xrayDownlink = 0L,
            timestamp = now
        )
    }

    fun stop() { collectJob?.cancel(); collectJob = null }

    fun resetSession() {
        sessionRx = 0; sessionTx = 0
        lastRx = TrafficStats.getUidRxBytes(myUid).coerceAtLeast(0)
        lastTx = TrafficStats.getUidTxBytes(myUid).coerceAtLeast(0)
    }

    data class TrafficSnapshot(
        val downloadMbps: Float = 0f,
        val uploadMbps: Float = 0f,
        val sessionRx: Long = 0L,
        val sessionTx: Long = 0L,
        val totalRx: Long = 0L,
        val totalTx: Long = 0L,
        @Deprecated("Not populated — libv2ray has no stats API")
        val xrayUplink: Long = 0L,
        @Deprecated("Not populated — libv2ray has no stats API")
        val xrayDownlink: Long = 0L,
        val timestamp: Long = 0L
    )
}
