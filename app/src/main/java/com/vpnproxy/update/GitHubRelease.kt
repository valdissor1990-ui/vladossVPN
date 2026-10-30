package com.vpnproxy.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Модель ответа GitHub API /repos/{owner}/{repo}/releases/latest */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("name") val name: String = "",
    @SerialName("body") val body: String = "",
    @SerialName("published_at") val publishedAt: String = "",
    @SerialName("prerelease") val prerelease: Boolean = false,
    @SerialName("draft") val draft: Boolean = false,
    val assets: List<Asset> = emptyList()
) {
    @Serializable
    data class Asset(
        val name: String,
        @SerialName("browser_download_url") val downloadUrl: String,
        val size: Long = 0L
    )

    /** Ищет APK-ассет, подходящий под flavor (direct/play). */
    fun findApkAsset(flavor: String): Asset? =
        assets.firstOrNull { it.name.contains(flavor, ignoreCase = true) && it.name.endsWith(".apk") }
            ?: assets.firstOrNull { it.name.endsWith(".apk") }

    /** Находит version.json ассет, если CI его приложил. */
    fun findVersionJsonAsset(): Asset? =
        assets.firstOrNull { it.name == "version.json" }

    /**
     * Извлекает versionCode из tag_name.
     *
     * Формула должна совпадать с .github/workflows/release.yml:
     * 10000 + major * 1000 + minor * 100 + patch.
     * Например, v1.0.797 соответствует versionCode 11797.
     *
     * Возвращает 0 для некорректного тега или переполнения Int.
     */
    fun extractVersionCode(): Int {
        val cleaned = tagName.removePrefix("v").trim()
        val parts = cleaned.split(".")
        if (parts.size != 3) return 0

        val major = parts[0].toLongOrNull() ?: return 0
        val minor = parts[1].toLongOrNull() ?: return 0
        val patch = parts[2].toLongOrNull() ?: return 0
        if (major < 0 || minor < 0 || patch <= 0) return 0

        val code = 10_000L + major * 1_000L + minor * 100L + patch
        return code.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt() ?: 0
    }

    fun extractVersionName(): String = tagName.removePrefix("v")
}
