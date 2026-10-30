package com.vpnproxy.ace.ipc

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Runs in the **main** process. Receives events from `:ace` via Binder.
 * Stage B: log only. No VPN control.
 */
class AceIpcServer : Service() {

    private val binder = object : IAceEventReceiver.Stub() {
        override fun onTestEvent(payload: String?) {
            Log.i(TAG, "event from :ace: ${payload.orEmpty()}")
        }

        override fun onError(message: String?) {
            Log.e(TAG, "error from :ace: ${message.orEmpty()}")
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    companion object {
        private const val TAG = "AceIpcServer"
    }
}
