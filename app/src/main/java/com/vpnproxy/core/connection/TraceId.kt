package com.vpnproxy.core.connection

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Correlation id for a single user connect attempt.
 * Format in logs: `TAG | traceId=UUID | message`
 */
object TraceId {
    private val current = AtomicReference<String?>(null)

    fun newId(): String {
        val id = UUID.randomUUID().toString()
        current.set(id)
        return id
    }

    fun currentOrNull(): String? = current.get()

    fun clear() {
        current.set(null)
    }

    fun format(tag: String, message: String, traceId: String? = currentOrNull()): String {
        return if (traceId.isNullOrBlank()) {
            "$tag | $message"
        } else {
            "$tag | traceId=$traceId | $message"
        }
    }
}
