package com.vpnproxy.ace

import android.util.Log
import com.vpnproxy.ace.ipc.AceIpcClient

/**
 * Runs in `:ace` process. Receives events from native ACE and forwards
 * them to the main process via Binder.
 *
 * Stage B: only Test / Initialized / Shutdown style payloads.
 */
class AceListenerImpl(
    private val ipcClient: AceIpcClient,
) : AceNativeBridge.Listener {

    override fun onEvent(event: String, payload: String) {
        Log.i(TAG, "ACE event=$event payload=$payload")
        when (event) {
            "Test" -> runCatching { ipcClient.forwardTestEvent(payload) }
                .onFailure { Log.e(TAG, "IPC forward failed", it) }
            else -> Log.d(TAG, "Unhandled event type in Stage B: $event")
        }
    }

    companion object {
        private const val TAG = "AceListener"
    }
}
