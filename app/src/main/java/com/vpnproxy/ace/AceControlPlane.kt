package com.vpnproxy.ace

import android.content.Context
import android.util.Log

/**
 * Main-process facade for ACE Stage B.
 * Starts/stops `:ace` only. No VPN / Smart Connect control.
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

    fun triggerPanic(context: Context) {
        Log.i(TAG, "Trigger ACE panic test")
        AceProcessService.triggerPanic(context.applicationContext)
    }
}
