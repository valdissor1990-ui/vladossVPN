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
     * Формат тега: v<major>.<minor>.<run_number>   (например, v1.0.73)
     * Формула versionCode в build.gradle.kts:      10000 + run_number
     * Поэтому:                                     versionCode = 10000 + patch
     *
     * Возвращает 0, если распарсить не удалось — тогда UpdateChecker
     * просто не покажет ложное обновление.
     */
    fun extractVersionCode(): Int {
        val cleaned = tagName.removePrefix("v").trim()
        val parts = cleaned.split(".")
        val patch = parts.lastOrNull()?.toIntOrNull() ?: return 0
        return if (patch > 0) 10000 + patch else 0
    }

    fun extractVersionName(): String = tagName.removePrefix("v")
}
