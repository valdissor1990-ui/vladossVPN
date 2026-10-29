package com.vpnproxy.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.vpnproxy.logging.LogCollector
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Validates the downloaded APK and launches Android's package installer. */
@Singleton
class UpdateInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logCollector: LogCollector
) {
    companion object {
        private const val TAG = "UpdateInstaller"
        private const val APK_MIME = "application/vnd.android.package-archive"

        /** Common package-installer package ids across AOSP / GMS / OEM skins. */
        private val INSTALLER_PACKAGES = listOf(
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.samsung.android.packageinstaller",
            "com.miui.packageinstaller",
            "com.google.android.apps.nbu.files",
            "com.android.vending"
        )
    }

    /**
     * Returns whether the app may request package installs.
     * Must never throw: missing REQUEST_INSTALL_PACKAGES in the manifest causes
     * SecurityException on API 26+ (seen as FATAL CrashHandler in production logs).
     */
    fun canInstallPackages(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return try {
            context.packageManager.canRequestPackageInstalls()
        } catch (e: SecurityException) {
            logCollector.error(
                TAG,
                "canRequestPackageInstalls blocked — declare android.permission.REQUEST_INSTALL_PACKAGES",
                e
            )
            false
        } catch (e: Exception) {
            logCollector.error(TAG, "canInstallPackages failed: ${e.javaClass.simpleName}", e)
            false
        }
    }

    fun openInstallPermissionSettings() {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            logCollector.error(TAG, "Failed to open install settings", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun readVersionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    private fun grantUriToResolvers(intent: Intent, uri: Uri) {
        val flag = Intent.FLAG_GRANT_READ_URI_PERMISSION
        for (pkg in INSTALLER_PACKAGES) {
            try {
                context.grantUriPermission(pkg, uri, flag)
            } catch (_: Exception) {
                // Package not present on this device — ignore.
            }
        }
        try {
            val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
            for (info in resolved) {
                val pkg = info.activityInfo?.packageName ?: continue
                try {
                    context.grantUriPermission(pkg, uri, flag)
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            logCollector.warn(TAG, "queryIntentActivities for APK install failed: ${e.message}")
        }
    }

    fun install(apkFile: File): Boolean {
        return try {
            if (!apkFile.isFile || apkFile.length() <= 0L) {
                logCollector.warn(TAG, "Install rejected: APK file missing or empty")
                return false
            }
            val archiveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            }
            if (archiveInfo == null) {
                logCollector.warn(TAG, "Install rejected: Android cannot parse APK")
                return false
            }
            if (archiveInfo.packageName != context.packageName) {
                logCollector.warn(
                    TAG,
                    "Install rejected: package mismatch expected=${context.packageName} actual=${archiveInfo.packageName}"
                )
                return false
            }
            val currentInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            val newVersion = readVersionCode(archiveInfo)
            val currentVersion = readVersionCode(currentInfo)
            // Only reject when we could read a positive versionCode and it is not newer.
            // Some OEM builds return 0 from getPackageArchiveInfo — do not block install.
            if (newVersion > 0L && newVersion <= currentVersion) {
                logCollector.warn(TAG, "Install rejected: APK version=$newVersion current=$currentVersion")
                return false
            }
            if (newVersion <= 0L) {
                logCollector.warn(
                    TAG,
                    "APK versionCode unreadable (got $newVersion); proceeding with system installer"
                )
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            // ACTION_VIEW + MIME is more reliable across OEMs than deprecated ACTION_INSTALL_PACKAGE.
            // Missing URI grants cause the system installer to open and immediately close.
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME)
                clipData = ClipData.newRawUri(apkFile.name, uri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
                putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, context.packageName)
            }
            grantUriToResolvers(viewIntent, uri)

            try {
                context.startActivity(viewIntent)
                logCollector.info(
                    TAG,
                    "Package installer launched via ACTION_VIEW: ${apkFile.name}, versionCode=$newVersion"
                )
                return true
            } catch (primary: Exception) {
                logCollector.warn(
                    TAG,
                    "ACTION_VIEW install failed (${primary.javaClass.simpleName}): ${primary.message}; trying ACTION_INSTALL_PACKAGE"
                )
            }

            @Suppress("DEPRECATION")
            val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                clipData = ClipData.newRawUri(apkFile.name, uri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
                putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, context.packageName)
            }
            grantUriToResolvers(installIntent, uri)
            context.startActivity(installIntent)
            logCollector.info(
                TAG,
                "Package installer launched via ACTION_INSTALL_PACKAGE: ${apkFile.name}, versionCode=$newVersion"
            )
            true
        } catch (e: Exception) {
            logCollector.error(TAG, "Install failed: ${e.javaClass.simpleName}: ${e.message}", e)
            false
        }
    }
}
