package com.vpnproxy.ace.ipc

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Main-process Binder endpoint for :ace.
 * Accepts observational events and a reverse telemetry receiver registration.
 * It exposes no VPN, Libbox, NetworkExecutor, or Smart Connect controls.
 */
class AceIpcServer : Service() {

    private val binder = object : IAceEventReceiver.Stub() {
        override fun onTestEvent(payload: String?) {
            Log.i(TAG, "event from :ace: ${payload.orEmpty()}")
        }

        override fun onError(message: String?) {
            Log.e(TAG, "error from :ace: ${message.orEmpty()}")
        }

        override fun registerTelemetryReceiver(receiver: IAceTelemetryReceiver?) {
            if (receiver == null) {
                Log.w(TAG, "Ignoring null telemetry receiver registration")
                return
            }
            AceTelemetryBridge.attach(receiver)
        }

        override fun unregisterTelemetryReceiver(receiver: IAceTelemetryReceiver?) {
            if (receiver == null) return
            AceTelemetryBridge.detach(receiver)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        AceTelemetryBridge.detach()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AceIpcServer"
    }
}
