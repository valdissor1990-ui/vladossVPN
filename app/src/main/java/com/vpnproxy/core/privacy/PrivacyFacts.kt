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
    /** True when this feature can send data off the device under the current configuration. It is not a claim that a request is in flight right now. */
    val dataLeavesDevice: Boolean,
    /** True when sending requires explicit user consent. */
    val requiresOptIn: Boolean = false
)

/**
 * The real switches the report is derived from.
 *
 * Every field names its production source, and [AndroidPrivacyInputsProvider] is the single
 * place that reads them. Do not build this by hand in a screen: a hand-built copy becomes a
 * second source of truth that can silently disagree with the app.
 *
 * There is deliberately no `hwidEnabled`: the derived identifier is only ever sent with
 * [deviceMetadataConsent], so a separate switch would describe a configuration that cannot
 * exist. There is also no `privacyScreenEnabled`: the report does not decide whether the
 * screen itself is shown.
 */
data class PrivacyInputs(
    /** `BuildConfig.ENABLE_SELF_UPDATE` of the current flavor. */
    val selfUpdateBuildEnabled: Boolean,
    /** User toggle `check_updates` in the settings DataStore (default on). */
    val updateChecksEnabled: Boolean,
    /** `SubscriptionRepository.deviceMetadataConsent` (default off). */
    val deviceMetadataConsent: Boolean,
    /** `RoutingSettings.preset(context) == RoutingPreset.BYPASS_RU`. */
    val ruRuleSetsRequested: Boolean,
    /** `RuleSetStore.isReady(context)`: the rule-set files are valid and on disk. */
    val ruRuleSetsReady: Boolean,
    /** `BuildConfig.ENABLE_BATTERY_EXEMPTION`: the play flavor removes this permission. */
    val batteryExemptionOffered: Boolean,
    /** `!BuildConfig.IS_PLAY_STORE`: the play flavor removes REQUEST_INSTALL_PACKAGES. */
    val installPackagesAllowed: Boolean
)

data class PrivacyReport(
    val facts: List<PrivacyFact>,
    /** Statements that are directly contradicted by the current configuration. */
    val warnings: List<String>
)

/**
 * Builds the privacy report from the same switches that drive the real behaviour.
 *
 * The builder has no defaults on [PrivacyInputs]: a caller must pass the real state, so an
 * all-off report cannot be produced by accident.
 */
class PrivacyReportBuilder {

    fun build(inputs: PrivacyInputs): PrivacyReport {
        val facts = buildList {
            addAll(storageFacts())
            addAll(networkFacts(inputs))
            addAll(identifierFacts(inputs))
            addAll(permissionFacts(inputs))
            addAll(telemetryFacts())
        }
        val warnings = buildList {
            if (inputs.ruRuleSetsRequested && !inputs.ruRuleSetsReady) {
                // Truthful, reachable state: the preset is on but the files are missing or
                // invalid. The wording covers both, because a corrupt file is not "missing"
                // yet the routing preset still references it.
                add("Наборы правил не готовы или повреждены: часть правил маршрутизации может не применяться.")
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
            detail = "Сохранённые профили, включая пароли, и производный идентификатор лежат в зашифрованном хранилище приложения (AES-256).",
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

    private fun networkFacts(inputs: PrivacyInputs): List<PrivacyFact> {
        val updatesEnabled = inputs.selfUpdateBuildEnabled && inputs.updateChecksEnabled
        return listOf(
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
            detail = when {
                !inputs.selfUpdateBuildEnabled ->
                    "Отключена в этой сборке."
                !inputs.updateChecksEnabled ->
                    "Отключена в настройках приложения."
                else ->
                    "Запрос к GitHub Releases за номером новой версии. Отправляется только версия приложения."
            },
            source = PrivacySource.UPDATE_CHECKER,
            dataLeavesDevice = updatesEnabled
        ),
        PrivacyFact(
            id = "network-geo-rules",
            kind = PrivacyKind.NETWORK,
            title = "Правила маршрутизации",
            detail = when {
                !inputs.ruRuleSetsRequested ->
                    "Наборы правил не запрашиваются: выбран пресет без geo-правил."
                inputs.ruRuleSetsReady ->
                    "Публичные наборы правил скачаны с GitHub при выборе пресета и обновляются вручную кнопкой «Обновить»."
                else ->
                    "Публичные наборы правил скачиваются с GitHub при выборе пресета «Обход локальной сети и сайтов РФ». Пока файлов нет или они не проверены, часть правил может не применяться."
            },
            source = PrivacySource.RULE_SET_STORE,
            dataLeavesDevice = inputs.ruRuleSetsRequested
        )
        )
    }

    private fun identifierFacts(inputs: PrivacyInputs): List<PrivacyFact> = listOf(
        PrivacyFact(
            id = "identifier-hwid",
            kind = PrivacyKind.IDENTIFIER,
            title = "Идентификатор устройства для панели подписки",
            detail = if (inputs.deviceMetadataConsent) {
                "Передаётся панели подписки производный код на основе системного идентификатора с солью приложения; исходный идентификатор не передаётся."
            } else {
                "Не передаётся: согласие на метаданные устройства выключено. Код строится только на устройстве."
            },
            source = PrivacySource.DEVICE_ID_MANAGER,
            dataLeavesDevice = inputs.deviceMetadataConsent,
            requiresOptIn = true
        ),
        PrivacyFact(
            id = "identifier-device-meta",
            kind = PrivacyKind.IDENTIFIER,
            title = "Модель и версия Android",
            detail = if (inputs.deviceMetadataConsent) {
                "Передаются панели подписки вместе с идентификатором, потому что вы это разрешили."
            } else {
                "Не передаются: согласие на метаданные устройства выключено."
            },
            source = PrivacySource.SUBSCRIPTION_REPOSITORY,
            dataLeavesDevice = inputs.deviceMetadataConsent,
            requiresOptIn = true
        )
    )

    private fun permissionFacts(inputs: PrivacyInputs): List<PrivacyFact> = buildList {
        add(permission("perm-internet", "Интернет", "Нужен для VPN и обновления подписок.", PrivacySource.MANIFEST))
        add(permission("perm-network-state", "Состояние сети", "Нужно, чтобы замечать смену Wi-Fi и LTE.", PrivacySource.MANIFEST))
        add(permission("perm-change-network", "Изменение состояния сети", "Нужно, чтобы переподключать туннель при смене сети.", PrivacySource.MANIFEST))
        add(permission("perm-foreground", "Работа в фоне", "Держит VPN включённым при свёрнутом приложении.", PrivacySource.MANIFEST))
        add(permission("perm-foreground-special", "Специальный тип фоновой службы", "Обязательное объявление для VPN-службы на современных Android.", PrivacySource.MANIFEST))
        add(permission("perm-wake-lock", "Не давать устройству засыпать", "Нужно, чтобы туннель не обрывался во время работы.", PrivacySource.MANIFEST))
        add(permission("perm-notifications", "Уведомления", "Показывает состояние VPN.", PrivacySource.MANIFEST))
        // The play flavor removes this permission and disables the request.
        if (inputs.batteryExemptionOffered) {
            add(permission("perm-battery", "Исключение из экономии батареи", "Приложение может предложить отключить оптимизацию, чтобы VPN не останавливался. Решение остаётся за вами.", PrivacySource.MANIFEST))
        }
        add(permission("perm-boot", "Автозапуск", "Возвращает VPN после перезагрузки, если вы это включили.", PrivacySource.MANIFEST))
        // The play flavor removes REQUEST_INSTALL_PACKAGES: updates come from the store.
        if (inputs.installPackagesAllowed) {
            add(permission("perm-install", "Установка обновлений", "Позволяет установить скачанный APK вручную.", PrivacySource.MANIFEST))
        }
    }

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
