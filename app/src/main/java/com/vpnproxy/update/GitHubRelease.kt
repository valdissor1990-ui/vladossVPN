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

    /**
     * Извлекает versionCode из tag_name формата v1.0.10001
     * или возвращает 0, если распарсить не удалось.
     */
    fun extractVersionCode(): Int {
        val parts = tagName.removePrefix("v").split(".")
        return parts.lastOrNull()?.toIntOrNull() ?: 0
    }

    fun extractVersionName(): String = tagName.removePrefix("v")
}
