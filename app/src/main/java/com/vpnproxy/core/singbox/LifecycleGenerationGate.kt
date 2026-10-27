package com.vpnproxy.core.singbox

import java.util.concurrent.atomic.AtomicLong

/**
 * Monotonic request epochs for the Sing-box lifecycle.
 * A request may perform native start only while its epoch is still current.
 */
internal class LifecycleGenerationGate {
    private val generation = AtomicLong(0L)

    fun next(): Long = generation.incrementAndGet()

    fun current(): Long = generation.get()

    fun isCurrent(requestGeneration: Long): Boolean = requestGeneration == generation.get()
}
