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

    /** Reality public key in URI/query or JSON-like log output. */
    private val REALITY_KEY_REGEX = Regex(
        """(?:pbk|publicKey|public_key)["']?\s*[:=]\s*["']?([A-Za-z0-9_\-]+)""",
        RegexOption.IGNORE_CASE
    )

    /** Short ID in URI query or JSON-like log output. */
    private val SHORT_ID_REGEX = Regex(
        """(?:^|[^A-Za-z0-9_])["']?(?:sid|shortId|short_id)["']?\s*[:=]\s*["']?([a-fA-F0-9]{6,})""",
        RegexOption.IGNORE_CASE
    )

    /** Credential-like key/value pairs, including quoted JSON keys and bearer tokens. */
    private val SECRET_PARAMETER_REGEX = Regex(
        """(?i)["']?(uuid|password|passwd|secret|access[-_]token|refresh[-_]token|client[-_]secret|private[-_]key|authorization|auth|api[-_]key|obfs[-_]password|session(?:_?id)?_?key)["']?\s*([=:]\s*|\s+)(?:Bearer\s+)?(?:"([^"]*)"|'([^']*)'|([^\s,;&}]+))"""
    )

    /** token=XXX / token: XXX. */
    private val TOKEN_REGEX = Regex(
        """(?:token[=:]\s*)([A-Za-z0-9_\-]+)""",
        RegexOption.IGNORE_CASE
    )
    /**
     * Заменяет чувствительные значения на ***.
     * Безопасно вызывается многократно; никаких исключений не бросает.
     */
    fun mask(input: String): String {
        return try {
            var result = input

            // 1. UUID — не трогаем traceId=... (корреляционный ID, не секрет)
            result = UUID_REGEX.replace(result) { m ->
                val start = m.range.first
                val prefix = result.substring(maxOf(0, start - 12), start)
                if (prefix.contains("traceId", ignoreCase = true)) m.value else "***-UUID-***"
            }

            // 2. URL подписок — оставляем схему+host; не маскируем localhost / 127.0.0.1 (proxy=...)
            result = URL_REGEX.replace(result) { m ->
                val url = m.value
                val schemeEnd = url.indexOf("://") + 3
                if (schemeEnd in 1 until url.length) {
                    val afterScheme = url.substring(schemeEnd)
                    val authorityEnd = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
                    val authority = if (authorityEnd > 0) afterScheme.substring(0, authorityEnd) else afterScheme
                    // Never retain URL user-info (user:password@host), even for local proxy URLs.
                    val hostAndPort = authority.substringAfterLast('@')
                    val hostOnly = if (hostAndPort.startsWith("[")) {
                        hostAndPort.substringBefore("]") + "]"
                    } else {
                        hostAndPort.substringBefore(':')
                    }
                    val isBareLocalProxy = (hostOnly == "127.0.0.1" ||
                        hostOnly.equals("localhost", ignoreCase = true)) &&
                        authority == hostAndPort && authorityEnd < 0
                    if (isBareLocalProxy) {
                        // Preserve a bare local proxy endpoint, but never preserve local URL
                        // paths or query strings: those can still carry credentials.
                        url
                    } else {
                        "${url.substring(0, schemeEnd)}$hostAndPort/***"
                    }
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

            // 5. Generic credential-like parameters.
            result = SECRET_PARAMETER_REGEX.replace(result) { m ->
                m.groupValues[1] + m.groupValues[2] + "***SECRET***"
            }

            // 6. token
            result = TOKEN_REGEX.replace(result) { m ->
                val full = m.value
                val prefixLen = full.length - m.groupValues[1].length
                full.substring(0, prefixLen) + "***TOKEN***"
            }

            result
        } catch (t: Throwable) {
            // Fail closed: never return the unmasked input when sanitization fails.
            "[masker-error:${t.javaClass.simpleName}; content redacted]"
        }
    }
}
