package com.vpnproxy.ace

import android.util.Log

/**
 * Thin bridge over UniFFI-generated bindings.
 *
 * Stage B LIMITATION: if `libace_core.so` is not packaged yet, methods
 * return safe fallbacks / throw controlled exceptions so the `:ace`
 * process still starts for IPC smoke tests.
 *
 * When UniFFI Kotlin bindings are generated and the .so is present,
 * replace the body of each method with the real `uniffi.ace_core.*` calls.
 */
object AceNativeBridge {

    private const val TAG = "AceNativeBridge"

    @Volatile
    private var loaded = false

    @Volatile
    private var listener: Listener? = null

    interface Listener {
        fun onEvent(event: String, payload: String)
    }

    init {
        loaded = runCatching {
            System.loadLibrary("ace_core")
            true
        }.getOrElse {
            Log.w(TAG, "libace_core.so not loaded: ${it.message}")
            false
        }
    }

    fun isNativeLoaded(): Boolean = loaded

    fun version(): String {
        if (!loaded) return "0.1.0-stub"
        // TODO(Stage B NDK): return uniffi.ace_core.aceVersion()
        return "0.1.0-stub"
    }

    fun initialize(listener: Listener) {
        this.listener = listener
        if (!loaded) {
            Log.w(TAG, "initialize: native unavailable; listener registered in stub mode")
            listener.onEvent("Initialized", version())
            return
        }
        // TODO(Stage B NDK): uniffi.ace_core.aceInitialize(...)
        listener.onEvent("Initialized", version())
    }

    fun shutdown() {
        if (loaded) {
            // TODO(Stage B NDK): uniffi.ace_core.aceShutdown()
        }
        listener?.onEvent("Shutdown", "")
        listener = null
    }

    fun ping(): String {
        if (!loaded) return "pong-stub"
        // TODO(Stage B NDK): return uniffi.ace_core.acePing()
        return "pong-stub"
    }

    fun emitTestEvent(payload: String) {
        if (loaded) {
            // TODO(Stage B NDK): uniffi.ace_core.aceEmitTestEvent(payload)
        }
        listener?.onEvent("Test", payload)
            ?: Log.w(TAG, "emitTestEvent: no listener")
    }

    fun triggerTestPanic(): Result<Unit> {
        if (!loaded) {
            return Result.failure(IllegalStateException("native not loaded; cannot test panic"))
        }
        // TODO(Stage B NDK): call uniffi.ace_core.aceTriggerTestPanic() and map AceError.Panic
        return Result.failure(UnsupportedOperationException("native panic test not wired yet"))
    }
}
