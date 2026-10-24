package com.vpnproxy.core.probe

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class ProbeOutcome { CONNECTED, REFUSED, TIMEOUT, UNREACHABLE, DNS_ERROR, IO_ERROR, CANCELLED, UNKNOWN }
enum class ProbeMethod { TCP_CONNECT, PROTOCOL_HANDSHAKE, VPN_HTTP, VPN_DOWNLOAD }
enum class ProbeStage { ENDPOINT, HANDSHAKE, TUNNEL, TRAFFIC }
enum class SemanticStatus { AVAILABLE, UNAVAILABLE, UNVERIFIED }
enum class AttemptOutcome { STARTED, SUCCEEDED, FAILED, CANCELLED }

data class ProbeContext(
    val serverId: String,
    val configFingerprint: String,
    val networkFingerprint: String,
    val subscriptionVersion: String? = null,
    val engineInstanceId: String? = null
) {
    init {
        require(serverId.isNotBlank() && configFingerprint.isNotBlank() && networkFingerprint.isNotBlank())
    }

    fun contextKey(): String = digest(listOf("probe-context-v1", serverId, configFingerprint, networkFingerprint).joinToString("\u001f"))
}

data class ProbeResult(
    val id: String,
    val serverId: String,
    val configFingerprint: String,
    val networkFingerprint: String,
    val method: ProbeMethod,
    val stage: ProbeStage,
    val probeOutcome: String,
    val observedAtMs: Long,
    val durationMs: Long? = null,
    val subscriptionVersion: String? = null,
    val engineInstanceId: String? = null
) {
    init {
        require(id.isNotBlank() && serverId.isNotBlank())
        require(configFingerprint.isNotBlank() && networkFingerprint.isNotBlank())
        require(observedAtMs >= 0L && (durationMs == null || durationMs >= 0L))
    }

    fun contextKey(): String = ProbeContext(serverId, configFingerprint, networkFingerprint).contextKey()
}

data class AttemptHistory(
    val id: String,
    val serverId: String,
    val configFingerprint: String,
    val startedAtMs: Long,
    val completedAtMs: Long?,
    val outcome: AttemptOutcome
) {
    init {
        require(id.isNotBlank() && serverId.isNotBlank() && configFingerprint.isNotBlank())
        require(startedAtMs >= 0L && (completedAtMs == null || completedAtMs >= startedAtMs))
        require((outcome == AttemptOutcome.STARTED) == (completedAtMs == null))
    }
}

data class DerivedStatus(val status: SemanticStatus, val reason: String)

/** Derives a status inside one method/stage/context group; never aggregates across methods. */
object StatusDeriver {
    fun deriveGroup(results: List<ProbeResult>, nowMs: Long, maxAgeMs: Long): DerivedStatus {
        require(nowMs >= 0L && maxAgeMs >= 0L)
        if (results.isEmpty()) return DerivedStatus(SemanticStatus.UNVERIFIED, "no_result")
        require(results.map { listOf(it.serverId, it.configFingerprint, it.networkFingerprint, it.method, it.stage) }.distinct().size == 1) {
            "deriveGroup requires one server/method/stage/context group"
        }
        val fresh = results.filter { nowMs >= it.observedAtMs && nowMs - it.observedAtMs <= maxAgeMs }
        if (fresh.isEmpty()) return DerivedStatus(SemanticStatus.UNVERIFIED, "stale_result")
        val mapped = fresh.map { result ->
            val outcome = runCatching { ProbeOutcome.valueOf(result.probeOutcome) }.getOrNull()
                ?: return DerivedStatus(SemanticStatus.UNVERIFIED, "unknown_outcome")
            result to statusFor(result.method, outcome)
        }
        if (mapped.map { it.second.status }.distinct().size > 1) {
            return DerivedStatus(SemanticStatus.UNVERIFIED, "conflicting_results")
        }
        return mapped.maxByOrNull { it.first.observedAtMs }!!.second
    }

    private fun statusFor(method: ProbeMethod, outcome: ProbeOutcome): DerivedStatus {
        if (outcome == ProbeOutcome.CANCELLED) return DerivedStatus(SemanticStatus.UNVERIFIED, "cancelled")
        if (method == ProbeMethod.TCP_CONNECT) return DerivedStatus(SemanticStatus.UNVERIFIED, "tcp_only")
        return when (outcome) {
            ProbeOutcome.CONNECTED -> DerivedStatus(SemanticStatus.AVAILABLE, "probe_succeeded")
            ProbeOutcome.REFUSED -> DerivedStatus(SemanticStatus.UNVERIFIED, "connection_refused")
            ProbeOutcome.TIMEOUT -> DerivedStatus(SemanticStatus.UNVERIFIED, "timeout")
            ProbeOutcome.UNREACHABLE -> DerivedStatus(SemanticStatus.UNVERIFIED, "network_unreachable")
            ProbeOutcome.DNS_ERROR -> DerivedStatus(SemanticStatus.UNVERIFIED, "dns_error")
            ProbeOutcome.IO_ERROR -> DerivedStatus(SemanticStatus.UNVERIFIED, "io_error")
            ProbeOutcome.CANCELLED -> DerivedStatus(SemanticStatus.UNVERIFIED, "cancelled")
            ProbeOutcome.UNKNOWN -> DerivedStatus(SemanticStatus.UNVERIFIED, "unknown_outcome")
        }
    }
}

object BackoffPolicy {
    fun nextAttemptAt(attempts: List<AttemptHistory>, nowMs: Long, windowMs: Long, threshold: Int, backoffMs: Long): Long? {
        require(nowMs >= 0L && windowMs >= 0L && threshold >= 1 && backoffMs >= 0L)
        val failures = attempts.asSequence()
            .filter { it.outcome == AttemptOutcome.FAILED && it.completedAtMs != null }
            .mapNotNull { it.completedAtMs }
            .filter { it <= nowMs && nowMs - it <= windowMs }
            .sorted()
            .toList()
        if (failures.size < threshold) return null
        return failures.last() + backoffMs
    }

    fun minimumRetentionMs(windowMs: Long, safetyMarginMs: Long): Long {
        require(windowMs >= 0L && safetyMarginMs >= 0L)
        return Math.addExact(windowMs, safetyMarginMs)
    }
}

private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
