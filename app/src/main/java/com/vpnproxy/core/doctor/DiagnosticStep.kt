package com.vpnproxy.core.doctor

/**
 * Ordered steps of the connection Doctor. The order is user-facing: a failure early in the
 * list explains the later `SKIP` steps, so the UI stays honest instead of showing nine errors.
 */
enum class DiagnosticStep(
    val title: String,
    val requirement: Requirement
) {
    SUBSCRIPTION("Подписка", Requirement.ANY),
    PROFILE("Профиль", Requirement.ANY),
    DNS("DNS", Requirement.VPN_UP),
    TCP("TCP", Requirement.VPN_UP),
    TLS("TLS / Reality", Requirement.VPN_DOWN),
    PROXY("Прокси-проба", Requirement.VPN_DOWN),
    TUN("TUN", Requirement.VPN_UP),
    TRAFFIC("Трафик", Requirement.VPN_UP),
    SERVICES("Сервисы", Requirement.VPN_UP);

    enum class Requirement {
        /** Runs with or without an active tunnel. */
        ANY,

        /** Needs a live tunnel and the mixed inbound. */
        VPN_UP,

        /** Needs the core stopped and the user's explicit consent for an isolated start. */
        VPN_DOWN
    }
}

/**
 * Why a step cannot run right now. A missing prerequisite is a `SKIP`, never a `FAIL`:
 * the Doctor must not blame the network for a check it did not perform.
 */
fun DiagnosticStep.skipReason(context: DoctorContext): String? = when (requirement) {
    DiagnosticStep.Requirement.ANY -> null
    DiagnosticStep.Requirement.VPN_UP -> when {
        context.coreTransitioning -> "Ядро перезапускается, повторите через несколько секунд."
        !context.tunnelUp -> "Нужен активный VPN."
        else -> null
    }
    DiagnosticStep.Requirement.VPN_DOWN -> when {
        context.coreTransitioning -> "Ядро перезапускается, повторите через несколько секунд."
        context.tunnelUp -> "Требуется остановка VPN."
        !context.isolatedCoreConsent -> "Нужно согласие на остановку VPN."
        else -> null
    }
}
