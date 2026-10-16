package com.vpnproxy.logging

/**
 * Маскировка чувствительных данных: UUID, Reality-ключи, URL подписок, токены.
 *
 * ⚠️ Java/Kotlin Regex НЕ поддерживает variable-length lookbehind.
 * Поэтому вместо `(?<=prefix)value` используем capture-group + replace с лямбдой:
 *     Regex("prefix(value)") → match.groupValues[1]
 *
 * Это работает на всех API и не бросает PatternSyntaxException.
 */
object SensitiveMasker {

    private val UUID_REGEX = Regex(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    )

    /** https?://... — обрезаем до host, маскируем query/path. */
    private val URL_REGEX = Regex("https?://[^\\s\"']+")

    /** pbk=XXXX или publicKey=XXXX / "publicKey": "XXXX" — захватываем значение в группу. */
    private val REALITY_KEY_REGEX = Regex(
        """(?:pbk=|publicKey["']?\\s*[:=]\\s*["']?)([A-Za-z0-9_\\-]+)""",
        RegexOption.IGNORE_CASE
    )

    /** sid=XXXX или shortId=XXXX / "shortId": "XXXX". */
    private val SHORT_ID_REGEX = Regex(
        """(?:^|[^A-Za-z0-9_])sid=([a-fA-F0-9]{6,})""",
        RegexOption.IGNORE_CASE
    )

    /** token=XXX / token: XXX. */
    private val TOKEN_REGEX = Regex(
        """(?:token[=:]\\s*)([A-Za-z0-9_\\-]+)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Заменяет чувствительные значения на ***.
     * Безопасно вызывается многократно; никаких исключений не бросает.
     */
    fun mask(input: String): String {
        return try {
            var result = input

            // 1. UUID
            result = UUID_REGEX.replace(result, "***-UUID-***")

            // 2. Реальные URL подписок — оставляем только схему+host
            result = URL_REGEX.replace(result) { m ->
                val url = m.value
                val schemeEnd = url.indexOf("://") + 3
                if (schemeEnd in 1 until url.length) {
                    val afterScheme = url.substring(schemeEnd)
                    val hostEnd = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
                    val host = if (hostEnd > 0) afterScheme.substring(0, hostEnd) else afterScheme
                    "${url.substring(0, schemeEnd)}$host/***"
                } else "***URL***"
            }

            // 3. Reality publicKey (pbk / publicKey) — оставляем префикс, значение → ***
            result = REALITY_KEY_REGEX.replace(result) { m ->
                val full = m.value
                val prefixLen = full.length - m.groupValues[1].length
                full.substring(0, prefixLen) + "***PBK***"
            }

            // 4. shortId (sid / shortId)
            result = SHORT_ID_REGEX.replace(result) { m ->
                val full = m.value
                val value = m.groupValues[1]
                val prefixLen = full.length - value.length
                full.substring(0, prefixLen) + "***SID***"
            }

            // 5. token
            result = TOKEN_REGEX.replace(result) { m ->
                val full = m.value
                val prefixLen = full.length - m.groupValues[1].length
                full.substring(0, prefixLen) + "***TOKEN***"
            }

            result
        } catch (t: Throwable) {
            // Маскировщик НИКОГДА не должен ронять приложение — лучше вернуть оригинал
            "[masker-error:${t.javaClass.simpleName}] $input"
        }
    }
}
