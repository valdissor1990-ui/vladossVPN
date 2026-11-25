package com.vpnproxy.core.doctor

enum class DiagnosticStatus {
    OK,
    WARN,
    FAIL,
    SKIP
}

/**
 * Result of one Doctor step.
 *
 * [summary] is what the user reads. It must stay free of URLs, UUIDs, keys and subscription
 * names: probes build it from fixed phrases and codes, never from a config dump.
 */
data class DiagnosticResult(
    val step: DiagnosticStep,
    val status: DiagnosticStatus,
    val summary: String,
    val durationMs: Long,
    val remedy: Remedy? = null,
    val skipReason: String? = null
) {
    init {
        require(durationMs >= 0L) { "durationMs must be non-negative" }
        require(status != DiagnosticStatus.SKIP || skipReason != null) {
            "a skipped step must explain why"
        }
    }
}

/** What a probe reports. `SKIP` is allowed when the profile itself makes the step inapplicable. */
data class StepOutcome(
    val status: DiagnosticStatus,
    val summary: String,
    val remedy: Remedy? = null,
    val skipReason: String? = null
) {
    init {
        require(status != DiagnosticStatus.SKIP || skipReason != null) {
            "a skipped step must explain why"
        }
        require(status == DiagnosticStatus.SKIP || skipReason == null) {
            "only a skipped step carries a skip reason"
        }
    }
}
