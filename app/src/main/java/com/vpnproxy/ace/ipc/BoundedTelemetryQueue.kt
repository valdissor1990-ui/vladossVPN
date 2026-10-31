package com.vpnproxy.ace.ipc

import kotlinx.coroutines.channels.Channel

/**
 * Bounded, non-blocking admission queue shared by ACE telemetry ingress/egress.
 * Producers never suspend; oversized and excess records are rejected.
 */
internal class BoundedTelemetryQueue(
    capacity: Int = DEFAULT_CAPACITY,
    private val maxRecordBytes: Int = DEFAULT_MAX_RECORD_BYTES
) {
    private val channel = Channel<String>(capacity)

    fun offer(record: String): Boolean {
        if (record.toByteArray(Charsets.UTF_8).size > maxRecordBytes) return false
        return channel.trySend(record).isSuccess
    }

    suspend fun receive(): String? = channel.receiveCatching().getOrNull()

    fun close() {
        channel.close()
    }

    companion object {
        const val DEFAULT_CAPACITY = 128
        const val DEFAULT_MAX_RECORD_BYTES = 16 * 1024
    }
}
