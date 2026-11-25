package com.vpnproxy.core.doctor

/**
 * Printable report of one Doctor run.
 *
 * `exportText()` intentionally uses only step titles, statuses, durations and remedy titles.
 * Probe summaries are shown on screen but never exported, so a copied report cannot leak a
 * server address, subscription URL or profile name.
 */
data class DoctorReport(
    val startedAtMs: Long,
    val durationMs: Long,
    val results: List<DiagnosticResult>
) {
    fun count(status: DiagnosticStatus): Int = results.count { it.status == status }

    fun headline(): String = when {
        count(DiagnosticStatus.FAIL) > 0 -> "Найдены проблемы: ${count(DiagnosticStatus.FAIL)}"
        count(DiagnosticStatus.WARN) > 0 -> "Есть предупреждения: ${count(DiagnosticStatus.WARN)}"
        results.all { it.status == DiagnosticStatus.SKIP } -> "Проверки не запускались"
        else -> "Проверки пройдены"
    }

    fun exportText(): String = buildString {
        appendLine("Доктор подключения")
        appendLine("Шагов: ${results.size}, длительность: ${durationMs} мс")
        results.forEach { result ->
            append("- ${result.step.title}: ${result.status.label()} (${result.durationMs} мс)")
            result.remedy?.let { remedy ->
                append("; действие: ${remedy.title} [${remedy.safety.name}]")
            }
            appendLine()
        }
        append(headline())
    }
}

fun DiagnosticStatus.label(): String = when (this) {
    DiagnosticStatus.OK -> "OK"
    DiagnosticStatus.WARN -> "ПРЕДУПРЕЖДЕНИЕ"
    DiagnosticStatus.FAIL -> "ОШИБКА"
    DiagnosticStatus.SKIP -> "ПРОПУЩЕНО"
}
