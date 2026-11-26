package com.vpnproxy.core.privacy

/** Where a fact comes from, so every claim in the report can be checked in the code. */
enum class PrivacySource(val title: String) {
    MANIFEST("AndroidManifest.xml"),
    BUILD_CONFIG("BuildConfig"),
    DEVICE_ID_MANAGER("DeviceIdManager"),
    SUBSCRIPTION_REPOSITORY("SubscriptionRepository"),
    UPDATE_CHECKER("UpdateChecker"),
    LOGGING("LogCollector"),
    CRASH_HANDLER("CrashHandler"),
    RULE_SET_STORE("RuleSetStore"),
    CONNECTIVITY_PROBES("TrafficValidator / ServiceCatalog")
}

enum class PrivacyKind(val title: String) {
    STORAGE("Что хранится на устройстве"),
    NETWORK("Куда уходят запросы"),
    IDENTIFIER("Идентификаторы устройства"),
    PERMISSION("Разрешения"),
    TELEMETRY("Отчёты о сбоях и телеметрия")
}

/**
 * One verifiable statement of the privacy report.
 *
 * [detail] is built from fixed phrases only: it never contains a URL, a subscription name,
 * a profile address or a token. That is what makes the report safe to export and share.
 */
data class PrivacyFact(
    val id: String,
    val kind: PrivacyKind,
    val title: String,
    val detail: String,
    val source: PrivacySource,
    /** True only when data actually leaves the device in the current configuration. */
    val dataLeavesDevice: Boolean,
    /** True when sending requires explicit user consent. */
    val requiresOptIn: Boolean = false
)

/** Current, side-effect-free state the report is derived from. */
data class PrivacySnapshot(
    val selfUpdateEnabled: Boolean = false,
    val deviceMetadataConsent: Boolean = false,
    val hwidEnabled: Boolean = false,
    val geoRuleSetsEnabled: Boolean = false,
    val privacyScreenEnabled: Boolean = false
)

data class PrivacyReport(
    val facts: List<PrivacyFact>,
    /** Statements that are directly contradicted by the current configuration. */
    val warnings: List<String>
)

/**
 * Builds the privacy report from the same switches that drive the real behaviour.
 *
 * There is no second source of truth: if a feature is off, its network fact is marked as
 * not leaving the device, and a test asserts that the report follows the flags.
 */
class PrivacyReportBuilder {

    fun build(snapshot: PrivacySnapshot): PrivacyReport {
        val facts = buildList {
            addAll(storageFacts())
            addAll(networkFacts(snapshot))
            addAll(identifierFacts(snapshot))
            addAll(permissionFacts())
            addAll(telemetryFacts())
        }
        val warnings = buildList {
            if (snapshot.hwidEnabled && !snapshot.deviceMetadataConsent) {
                // Should be impossible: the HWID is only sent with consent. Say it out loud.
                add("Идентификатор устройства включён без согласия — проверьте настройки.")
            }
        }
        return PrivacyReport(facts = facts, warnings = warnings)
    }

    private fun storageFacts(): List<PrivacyFact> = listOf(
        PrivacyFact(
            id = "storage-profiles",
            kind = PrivacyKind.STORAGE,
            title = "Профили и настройки",
            detail = "Хранятся в приватном хранилище приложения и не отправляются.",
            source = PrivacySource.SUBSCRIPTION_REPOSITORY,
            dataLeavesDevice = false
        ),
        PrivacyFact(
            id = "storage-secrets",
            kind = PrivacyKind.STORAGE,
            title = "Секреты профилей и идентификатор",
            detail = "Пароли и идентификатор устройства лежат в зашифрованном хранилище (AES-256).",
            source = PrivacySource.DEVICE_ID_MANAGER,
            dataLeavesDevice = false
        ),
        PrivacyFact(
            id = "storage-log",
            kind = PrivacyKind.STORAGE,
            title = "Журнал приложения",
            detail = "Пишется в приватную папку приложения; адреса и ключи маскируются.",
            source = PrivacySource.LOGGING,
            dataLeavesDevice = false
        ),
        PrivacyFact(
            id = "storage-crash",
            kind = PrivacyKind.STORAGE,
            title = "Файлы сбоев",
            detail = "Сохраняются локально для разбора; автоматической отправки нет.",
            source = PrivacySource.CRASH_HANDLER,
            dataLeavesDevice = false
        )
    )

    private fun networkFacts(snapshot: PrivacySnapshot): List<PrivacyFact> = listOf(
        PrivacyFact(
            id = "network-subscriptions",
            kind = PrivacyKind.NETWORK,
            title = "Обновление подписок",
            detail = "Запрос идёт на адрес подписки, который вы добавили сами. Адрес не попадает в отчёт.",
            source = PrivacySource.SUBSCRIPTION_REPOSITORY,
            dataLeavesDevice = true
        ),
        PrivacyFact(
            id = "network-connectivity",
            kind = PrivacyKind.NETWORK,
            title = "Проверка связи",
            detail = "Короткий запрос к публичным проверочным адресам, чтобы отличить «нет сети» от «сервер не отвечает».",
            source = PrivacySource.CONNECTIVITY_PROBES,
            dataLeavesDevice = true
        ),
        PrivacyFact(
            id = "network-updates",
            kind = PrivacyKind.NETWORK,
            title = "Проверка обновлений",
            detail = if (snapshot.selfUpdateEnabled) {
                "Запрос к GitHub Releases за номером новой версии. Отправляется только версия приложения."
            } else {
                "Отключена в этой сборке."
            },
            source = PrivacySource.UPDATE_CHECKER,
            dataLeavesDevice = snapshot.selfUpdateEnabled
        ),
        PrivacyFact(
            id = "network-geo-rules",
            kind = PrivacyKind.NETWORK,
            title = "Правила маршрутизации",
            detail = if (snapshot.geoRuleSetsEnabled) {
                "Скачиваются публичные наборы правил с GitHub при включённых geo-правилах."
            } else {
                "Наборы правил не запрашиваются."
            },
            source = PrivacySource.RULE_SET_STORE,
            dataLeavesDevice = snapshot.geoRuleSetsEnabled
        )
    )

    private fun identifierFacts(snapshot: PrivacySnapshot): List<PrivacyFact> = listOf(
        PrivacyFact(
            id = "identifier-hwid",
            kind = PrivacyKind.IDENTIFIER,
            title = "Идентификатор устройства для панели подписки",
            detail = "Производный код на основе системного идентификатора с солью приложения; исходный идентификатор не передаётся.",
            source = PrivacySource.DEVICE_ID_MANAGER,
            dataLeavesDevice = snapshot.hwidEnabled && snapshot.deviceMetadataConsent,
            requiresOptIn = true
        ),
        PrivacyFact(
            id = "identifier-device-meta",
            kind = PrivacyKind.IDENTIFIER,
            title = "Модель и версия Android",
            detail = if (snapshot.deviceMetadataConsent) {
                "Передаются панели подписки вместе с идентификатором, потому что вы это разрешили."
            } else {
                "Не передаются: согласие на метаданные устройства выключено."
            },
            source = PrivacySource.SUBSCRIPTION_REPOSITORY,
            dataLeavesDevice = snapshot.deviceMetadataConsent,
            requiresOptIn = true
        )
    )

    private fun permissionFacts(): List<PrivacyFact> = listOf(
        permission("perm-internet", "Интернет", "Нужен для VPN и обновления подписок.", PrivacySource.MANIFEST),
        permission("perm-network-state", "Состояние сети", "Нужно, чтобы замечать смену Wi-Fi и LTE.", PrivacySource.MANIFEST),
        permission("perm-foreground", "Работа в фоне", "Держит VPN включённым при свёрнутом приложении.", PrivacySource.MANIFEST),
        permission("perm-notifications", "Уведомления", "Показывает состояние VPN и просит отключить экономию батареи.", PrivacySource.MANIFEST),
        permission("perm-boot", "Автозапуск", "Возвращает VPN после перезагрузки, если вы это включили.", PrivacySource.MANIFEST),
        permission("perm-install", "Установка обновлений", "Позволяет установить скачанный APK вручную.", PrivacySource.MANIFEST)
    )

    private fun permission(id: String, title: String, detail: String, source: PrivacySource) =
        PrivacyFact(
            id = id,
            kind = PrivacyKind.PERMISSION,
            title = title,
            detail = detail,
            source = source,
            dataLeavesDevice = false
        )

    private fun telemetryFacts(): List<PrivacyFact> = listOf(
        PrivacyFact(
            id = "telemetry-sdk",
            kind = PrivacyKind.TELEMETRY,
            title = "Сторонние SDK аналитики",
            detail = "В сборку не подключены: нет Firebase, Crashlytics, Sentry и рекламных сетей.",
            source = PrivacySource.BUILD_CONFIG,
            dataLeavesDevice = false
        ),
        PrivacyFact(
            id = "telemetry-upload",
            kind = PrivacyKind.TELEMETRY,
            title = "Отправка отчётов о сбоях",
            detail = "Автоматической отправки нет: файл сбоя остаётся на устройстве, пока вы сами его не выгрузите.",
            source = PrivacySource.CRASH_HANDLER,
            dataLeavesDevice = false
        )
    )
}
