package com.vpnproxy.core.diagnostics

import android.content.Context
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local-only, structured server diagnostics journal.
 *
 * Only typed, allow-listed fields are persisted. Do not add raw server addresses, profile names,
 * subscription URLs, credentials, UUIDs, Reality keys, or arbitrary exception messages here.
 * No network or upload code belongs in this class.
 */
@Singleton
class ServerDiagnosticsStore private constructor(
    private val journal: File
) {
    @Inject
    constructor(@ApplicationContext context: Context) :
        this(File(context.filesDir, DIRECTORY_NAME).resolve(FILE_NAME))

    @VisibleForTesting
    internal constructor(directory: File, testOnly: Boolean) :
        this(File(directory, FILE_NAME))

    fun record(event: ServerDiagnosticEvent) {
        synchronized(lock) {
            try {
                journal.parentFile?.mkdirs()
                rotateIfNeeded()
                FileOutputStream(journal, true).bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.appendLine(event.toJsonLine())
                }
            } catch (_: Exception) {
                // Diagnostics must never interfere with VPN operation.
            }
        }
    }

    /** Returns the current local journal file for explicit user-initiated export/sharing. */
    fun currentJournal(): File? = journal.takeIf { it.isFile && it.length() > 0L }

    fun clear() {
        synchronized(lock) {
            journal.delete()
            File(journal.parentFile, "$FILE_NAME.1").delete()
        }
    }

    private fun rotateIfNeeded() {
        if (!journal.exists() || journal.length() < MAX_FILE_BYTES) return
        val previous = File(journal.parentFile, "$FILE_NAME.1")
        if (previous.exists()) previous.delete()
        journal.renameTo(previous)
    }

    private fun ServerDiagnosticEvent.toJsonLine(): String {
        val fields = linkedMapOf<String, Any?>(
            "schemaVersion" to 1,
            "timestamp" to timestampEpochMs,
            "event" to eventType,
            "runId" to runId,
            "serverId" to serverId,
            "protocol" to normalizeProtocol(protocol),
            "stage" to stage,
            "dnsMs" to dnsMs,
            "connectMs" to connectMs,
            "handshakeMs" to handshakeMs,
            "downloadMbps" to downloadMbps?.takeIf { it.isFinite() && it >= 0.0 },
            "uploadMbps" to uploadMbps?.takeIf { it.isFinite() && it >= 0.0 },
            "durationMs" to durationMs,
            "bytesDownloaded" to bytesDownloaded,
            "bytesUploaded" to bytesUploaded,
            "success" to success,
            "errorCode" to normalizeErrorCode(errorCode)
        )
        return fields.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            val encoded = when (value) {
                null -> "null"
                is String -> value.jsonString()
                else -> value.toString()
            }
            "${key.jsonString()}:$encoded"
        }
    }

    private fun Double.finiteNumber(): String? =
        if (isFinite() && this >= 0.0) toString() else null

    private fun normalizeProtocol(value: String?): String? {
        val allowed = setOf("vless", "vmess", "trojan", "shadowsocks", "hysteria2", "tuic", "unknown")
        return value?.lowercase()?.takeIf { it in allowed } ?: if (value == null) null else "unknown"
    }

    private fun normalizeErrorCode(value: String?): String? {
        // Codes are intentionally machine-readable and never copied from exception messages.
        return value?.uppercase()?.takeIf { it.matches(Regex("[A-Z0-9_]{1,48}")) }
    }

    private fun String.jsonString(): String = buildString(length + 2) {
        append('"')
        for (char in this@jsonString) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }

    companion object {
        private const val DIRECTORY_NAME = "server-diagnostics"
        private const val FILE_NAME = "server-diagnostics.jsonl"
        private const val MAX_FILE_BYTES = 2L * 1024L * 1024L
        private val lock = Any()

        /** Stable per-run correlation ID; it contains no server or account information. */
        fun newRunId(): String = UUID.randomUUID().toString()
    }
}

/** Only allow-listed metrics are accepted; no arbitrary map or message field is available. */
data class ServerDiagnosticEvent(
    val eventType: String,
    val runId: String,
    val serverId: String? = null,
    val protocol: String? = null,
    val stage: String? = null,
    val dnsMs: Long? = null,
    val connectMs: Long? = null,
    val handshakeMs: Long? = null,
    val downloadMbps: Double? = null,
    val uploadMbps: Double? = null,
    val durationMs: Long? = null,
    val bytesDownloaded: Long? = null,
    val bytesUploaded: Long? = null,
    val success: Boolean? = null,
    val errorCode: String? = null,
    val timestampEpochMs: Long = System.currentTimeMillis()
) {
    init {
        require(eventType.matches(Regex("[a-z_]{1,48}"))) { "eventType must be a stable event code" }
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "runId must be opaque" }
        require(serverId == null || serverId.matches(Regex("[a-fA-F0-9]{8,64}"))) {
            "serverId must be a hexadecimal hash, not a hostname or profile name"
        }
        require(stage == null || stage.matches(Regex("[a-z_]{1,32}"))) {
            "stage must be a stable code, not free-form text"
        }
        require(listOf(dnsMs, connectMs, handshakeMs, durationMs, bytesDownloaded, bytesUploaded)
            .filterNotNull().all { it >= 0L }) { "timings and byte counts must be non-negative" }
    }
}
