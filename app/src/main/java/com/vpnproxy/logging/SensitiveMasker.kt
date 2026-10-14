package com.vpnproxy.logging

/**
 * Маскировка чувствительных данных: UUID, Reality-ключи, URL подписок, токены.
 */
object SensitiveMasker {

    private val UUID_REGEX = Regex(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    )
    private val URL_REGEX = Regex("https?://[^\\s]+")
    private val REALITY_KEY_REGEX = Regex("(?<=pbk=)[A-Za-z0-9_-]+|(?<=publicKey[\"']?\\s*[:=]\\s*[\"']?)[A-Za-z0-9_-]+")
    private val SHORT_ID_REGEX = Regex("(?<=sid=)[a-fA-F0-9]+|(?<=shortId[\"']?\\s*[:=]\\s*[\"']?)[a-fA-F0-9]+")
    private val TOKEN_REGEX = Regex("(?<=token[=:]\\s*)[A-Za-z0-9_\\-]+")

    fun mask(input: String): String {
        var result = input
        result = UUID_REGEX.replace(result, "***-UUID-***")
        result = URL_REGEX.replace(result) { match ->
            val url = match.value
            if (url.length > 40) url.take(30) + "***" else "***URL***"
        }
        result = REALITY_KEY_REGEX.replace(result, "***PBK***")
        result = SHORT_ID_REGEX.replace(result, "***SID***")
        result = TOKEN_REGEX.replace(result, "***TOKEN***")
        return result
    }
}
