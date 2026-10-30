package com.vpnproxy.ace.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import uniffi.ace_core.aceIngestTelemetry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs in `:ace`. Binds to the main-process [AceIpcServer].
 *
 * Main → ACE telemetry is one-way, observational, size-limited, and buffered
 * with bounded backpressure before Rust schema validation/ingestion.
 */
class AceIpcClient(private val context: Context) {

    private val remoteRef = AtomicReference<IAceEventReceiver?>(null)
    private val connected = CountDownLatch(1)
    private val telemetryQueue = Channel<String>(capacity = 128)
    private val telemetryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var disconnected = false

    private val telemetryReceiver = object : IAceTelemetryReceiver.Stub() {
        override fun onTelemetryRecord(recordJson: String?) {
            val record = recordJson ?: return
            if (record.toByteArray(Charsets.UTF_8).size > MAX_RECORD_BYTES) {
                Log.w(TAG, "Rejected oversized telemetry Binder payload")
                return
            }
            if (disconnected || telemetryQueue.trySend(record).isFailure) {
                Log.w(TAG, "ACE telemetry queue full or client stopped; observation dropped")
            }
        }
    }

    init {
        telemetryScope.launch {
            for (record in telemetryQueue) {
                runCatching { aceIngestTelemetry(record) }
                    .onFailure { Log.w(TAG, "Rust rejected telemetry record", it) }
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val remote = IAceEventReceiver.Stub.asInterface(service)
            remoteRef.set(remote)
            Log.i(TAG, "Connected to AceIpcServer")
            if (remote != null) {
                telemetryScope.launch {
                    runCatching { remote.registerTelemetryReceiver(telemetryReceiver) }
                        .onFailure { Log.w(TAG, "Telemetry receiver registration failed", it) }
                }
            }
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remoteRef.set(null)
            Log.w(TAG, "Disconnected from AceIpcServer")
        }
    }

    /** Bind and wait up to [timeoutMs] for onServiceConnected. */
    fun connectBlocking(timeoutMs: Long = 5_000L): Boolean {
        val ok = runCatching {
            context.bindService(
                Intent(ACTION_BIND).setPackage(context.packageName),
                connection,
                Context.BIND_AUTO_CREATE
            )
        }.getOrElse {
            Log.e(TAG, "bind failed", it)
            false
        }
        if (!ok) return false
        val ready = connected.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!ready) Log.e(TAG, "bind timeout after ${timeoutMs}ms")
        return ready && remoteRef.get() != null
    }

    fun disconnect() {
        disconnected = true
        remoteRef.get()?.let { remote ->
            runCatching { remote.unregisterTelemetryReceiver() }
                .onFailure { Log.d(TAG, "Telemetry receiver unregister skipped", it) }
        }
        runCatching { context.unbindService(connection) }
        remoteRef.set(null)
        telemetryQueue.close()
        telemetryScope.cancel()
    }

    fun forwardTestEvent(payload: String) {
        val remote = remoteRef.get()
        if (remote == null) {
            Log.w(TAG, "forwardTestEvent dropped: not connected")
            return
        }
        runCatching { remote.onTestEvent(payload) }
            .onFailure { Log.e(TAG, "forwardTestEvent failed", it) }
    }

    fun forwardError(message: String) {
        val remote = remoteRef.get() ?: return
        runCatching { remote.onError(message) }
            .onFailure { Log.e(TAG, "forwardError failed", it) }
    }

    companion object {
        private const val TAG = "AceIpcClient"
        private const val MAX_RECORD_BYTES = 16 * 1024
        const val ACTION_BIND = "com.vpnproxy.ace.BIND_RECEIVER"
    }
}
