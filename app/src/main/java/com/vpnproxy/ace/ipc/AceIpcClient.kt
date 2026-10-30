package com.vpnproxy.ace.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs in `:ace`. Binds to [AceIpcServer] in main.
 * [connectBlocking] waits until Binder is ready so Stage B emit is not lost.
 */
class AceIpcClient(private val context: Context) {

    private val remoteRef = AtomicReference<IAceEventReceiver?>(null)
    private val connected = CountDownLatch(1)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remoteRef.set(IAceEventReceiver.Stub.asInterface(service))
            Log.i(TAG, "Connected to AceIpcServer")
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remoteRef.set(null)
            Log.w(TAG, "Disconnected from AceIpcServer")
        }
    }

    /**
     * Bind and wait up to [timeoutMs] for onServiceConnected.
     * @return true if Binder is ready
     */
    fun connectBlocking(timeoutMs: Long = 5_000L): Boolean {
        val intent = Intent(ACTION_BIND).setPackage(context.packageName)
        val ok = runCatching {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
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
        runCatching { context.unbindService(connection) }
        remoteRef.set(null)
    }

    fun forwardTestEvent(payload: String) {
        val r = remoteRef.get()
        if (r == null) {
            Log.w(TAG, "forwardTestEvent dropped: not connected")
            return
        }
        runCatching { r.onTestEvent(payload) }
            .onFailure { Log.e(TAG, "forwardTestEvent failed", it) }
    }

    fun forwardError(message: String) {
        val r = remoteRef.get() ?: return
        runCatching { r.onError(message) }
            .onFailure { Log.e(TAG, "forwardError failed", it) }
    }

    companion object {
        private const val TAG = "AceIpcClient"
        const val ACTION_BIND = "com.vpnproxy.ace.BIND_RECEIVER"
    }
}
