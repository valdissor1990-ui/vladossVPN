package com.vpnproxy.core.doctor

enum class RemedySafety {
    /** Can run from the report screen without extra confirmation. */
    SAFE_AUTO,

    /** Changes the active profile or restarts the core: ask first. */
    REQUIRES_CONFIRM
}

enum class SettingsSection(val title: String) {
    SUBSCRIPTIONS("Подписки"),
    DNS("DNS"),
    DURABILITY("Устойчивость")
}

/**
 * A concrete next action for a failed or questionable step.
 *
 * The Doctor itself never executes `REQUIRES_CONFIRM` remedies: the UI must show the action
 * and ask. This split is the core safety rule of wave 1c.
 */
sealed class Remedy(
    val safety: RemedySafety,
    val title: String
) {
    data object Recheck : Remedy(RemedySafety.SAFE_AUTO, "Проверить снова")
    data class OpenSettings(val section: SettingsSection) :
        Remedy(RemedySafety.SAFE_AUTO, "Открыть: ${section.title}")
    data object RefreshSubscription : Remedy(RemedySafety.SAFE_AUTO, "Обновить подписку")

    data object SwitchServer : Remedy(RemedySafety.REQUIRES_CONFIRM, "Сменить сервер")
    data object ReimportProfile : Remedy(RemedySafety.REQUIRES_CONFIRM, "Импортировать профиль заново")
    data object RestartCore : Remedy(RemedySafety.REQUIRES_CONFIRM, "Перезапустить ядро")
    // Navigation only: the executor opens the section and the user decides. Marking these
    // REQUIRES_CONFIRM would block the screen behind a dialog for no safety gain.
    data object ChangeDns : Remedy(RemedySafety.SAFE_AUTO, "Изменить DNS")
    data object EnableTlsFragment : Remedy(RemedySafety.SAFE_AUTO, "Включить фрагментацию TLS")
}
