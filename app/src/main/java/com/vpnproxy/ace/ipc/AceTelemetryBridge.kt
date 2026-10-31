package com.vpnproxy.ace.ipc

import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Main-process, non-blocking telemetry egress to :ace.
 * This bridge never starts ACE. Offline, oversized, and excess records are dropped.
 */
internal object AceTelemetryBridge {
    private const val TAG = "AceTelemetryBridge"
    private val receiver = AtomicReference<IAceTelemetryReceiver?>(null)
    private val sequence = AtomicLong()
    private val queue = BoundedTelemetryQueue()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            while (true) {
                val record = queue.receive() ?: break
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
            return
        }

        // AIDL may materialize a different Java proxy wrapper for the same remote
        // Binder on a later transaction. Compare the Binder identity, not wrapper
        // object identity, and CAS the exact observed reference to avoid detaching
        // a receiver attached concurrently after this one.
        val targetBinder = target.asBinder()
        while (true) {
            val current = receiver.get() ?: return
            if (current.asBinder() != targetBinder) return
            if (receiver.compareAndSet(current, null)) return
        }
    }

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
        if (receiver.get() == null) return false
        val accepted = queue.offer(recordJson)
        if (!accepted) Log.w(TAG, "Telemetry payload rejected (oversized or queue full)")
        return accepted
    }
}
