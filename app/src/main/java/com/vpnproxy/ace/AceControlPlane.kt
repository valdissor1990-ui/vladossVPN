package com.vpnproxy.ace

import android.content.Context
import android.util.Log

/**
 * Main-process facade for ACE.
 *
 * Stage B: start/stop the `:ace` process only.
 * Does **not** send commands to VpnService.
 * Does **not** touch Smart Connect.
 */
object AceControlPlane {

    private const val TAG = "AceControlPlane"

    fun start(context: Context) {
        Log.i(TAG, "Starting ACE process (:ace)")
        AceProcessService.start(context.applicationContext)
    }

    fun stop(context: Context) {
        Log.i(TAG, "Stopping ACE process (:ace)")
        AceProcessService.stop(context.applicationContext)
    }
}
