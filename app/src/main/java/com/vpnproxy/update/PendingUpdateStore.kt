package com.vpnproxy.update

import android.content.Context
import com.vpnproxy.BuildConfig
import java.io.File

/**
 * Remembers the newest successfully downloaded APK.
 * When a newer release is fetched, older APK files in cacheDir/updates are deleted.
 */
class PendingUpdateStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Pending(
        val versionCode: Int,
        val versionName: String,
        val tagName: String,
        val fileName: String,
        val releaseBody: String
    ) {
        fun file(context: Context): File =
            File(File(context.cacheDir, "updates"), fileName)
    }

    fun read(): Pending? {
        val code = prefs.getInt(KEY_CODE, 0)
        val name = prefs.getString(KEY_NAME, null) ?: return null
        val tag = prefs.getString(KEY_TAG, null) ?: return null
        val fileName = prefs.getString(KEY_FILE, null) ?: return null
        if (code <= 0 || fileName.isBlank()) return null
        return Pending(
            versionCode = code,
            versionName = name,
            tagName = tag,
            fileName = fileName,
            releaseBody = prefs.getString(KEY_BODY, "").orEmpty()
        )
    }

    /** Pending APK that is still newer than the installed build and exists on disk. */
    fun readyFile(): File? {
        val pending = read() ?: return null
        if (pending.versionCode <= BuildConfig.VERSION_CODE) {
            clear()
            return null
        }
        val file = pending.file(appContext)
        if (!file.isFile || file.length() <= 0L) {
            clear()
            return null
        }
        return file
    }

    fun save(release: GitHubRelease, apk: File) {
        prefs.edit()
            .putInt(KEY_CODE, release.extractVersionCode())
            .putString(KEY_NAME, release.extractVersionName())
            .putString(KEY_TAG, release.tagName)
            .putString(KEY_FILE, apk.name)
            .putString(KEY_BODY, release.body)
            .apply()
        deleteOtherApks(keep = apk)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    /** Keep only [keep]; remove other .apk / .part files under cacheDir/updates. */
    fun deleteOtherApks(keep: File? = null) {
        val dir = File(appContext.cacheDir, "updates")
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { file ->
            if (keep != null && file.absolutePath == keep.absolutePath) return@forEach
            if (file.name.endsWith(".apk", ignoreCase = true) ||
                file.name.endsWith(".part", ignoreCase = true)
            ) {
                runCatching { file.delete() }
            }
        }
    }

    companion object {
        private const val PREFS = "pending_update"
        private const val KEY_CODE = "version_code"
        private const val KEY_NAME = "version_name"
        private const val KEY_TAG = "tag"
        private const val KEY_FILE = "file_name"
        private const val KEY_BODY = "body"
    }
}
