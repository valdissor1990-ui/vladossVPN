package com.vpnproxy.ace.ipc

import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Main-process, non-blocking telemetry egress to :ace.
 *
 * This bridge never starts ACE. If ACE is not connected, records are dropped.
 * Queueing is bounded and payloads are capped before they cross Binder.
 */
internal object AceTelemetryBridge {
    private const val TAG = "AceTelemetryBridge"
    private const val MAX_RECORD_BYTES = 16 * 1024
    private val receiver = AtomicReference<IAceTelemetryReceiver?>(null)
    private val sequence = AtomicLong()
    private val queue = Channel<String>(capacity = 128)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            for (record in queue) {
                val target = receiver.get()
                if (target == null) {
                    Log.d(TAG, "ACE unavailable; telemetry observation dropped")
                    continue
                }
                try {
                    target.onTelemetryRecord(record)
                } catch (e: RemoteException) {
                    receiver.compareAndSet(target, null)
                    Log.w(TAG, "ACE telemetry Binder disconnected; observation dropped", e)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "ACE telemetry delivery failed; observation dropped", e)
                }
            }
        }
    }

    fun attach(target: IAceTelemetryReceiver) {
        receiver.set(target)
        Log.i(TAG, "ACE telemetry receiver attached")
    }

    fun detach(target: IAceTelemetryReceiver? = null) {
        if (target == null) {
            receiver.set(null)
        } else {
            receiver.compareAndSet(target, null)
        }
    }

    /**
     * Build a schema-v1 record from an existing main-process measurement.
     * Callers must pass actual observed values; null requires an explicit reason.
     */
    fun submitMetric(
        metric: String,
        value: Double?,
        unit: String,
        outcome: String,
        missingReason: String? = null,
        source: String
    ): Boolean {
        if (receiver.get() == null) return false
        if (value == null && missingReason.isNullOrBlank()) return false
        val record = JSONObject().apply {
            put("schema_version", 1)
            put("event_id", "main-${Process.myPid()}-${sequence.incrementAndGet()}")
            put("elapsed_realtime_ms", SystemClock.elapsedRealtime())
            put("metric", metric)
            put("value", value ?: JSONObject.NULL)
            put("unit", unit)
            put("outcome", outcome)
            put("missing_reason", missingReason ?: JSONObject.NULL)
            put("restricted_context", false)
            put("source", source)
        }.toString()
        return submit(record)
    }

    /** Returns false when ACE is offline, payload is oversized, or queue is full. */
    fun submit(recordJson: String): Boolean {
        if (recordJson.toByteArray(Charsets.UTF_8).size > MAX_RECORD_BYTES) {
            Log.w(TAG, "Telemetry payload exceeds 16 KiB; observation rejected")
            return false
        }
        if (receiver.get() == null) return false
        return queue.trySend(recordJson).isSuccess
    }
}
