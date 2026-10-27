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
    companion object { private const val TAG = "UpdateInstaller" }

    fun canInstallPackages(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.packageManager.canRequestPackageInstalls() else true

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

    fun install(apkFile: File): Boolean {
        return try {
            if (!apkFile.isFile || apkFile.length() <= 0L) {
                logCollector.warn(TAG, "Install rejected: APK file missing or empty")
                return false
            }
            val archiveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            }
            if (archiveInfo == null) {
                logCollector.warn(TAG, "Install rejected: Android cannot parse APK")
                return false
            }
            if (archiveInfo.packageName != context.packageName) {
                logCollector.warn(TAG, "Install rejected: package mismatch expected=${context.packageName} actual=${archiveInfo.packageName}")
                return false
            }
            val currentInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            val newVersion = readVersionCode(archiveInfo)
            val currentVersion = readVersionCode(currentInfo)
            if (newVersion <= currentVersion) {
                logCollector.warn(TAG, "Install rejected: APK version=$newVersion current=$currentVersion")
                return false
            }

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                clipData = ClipData.newRawUri(apkFile.name, uri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            logCollector.info(TAG, "Package installer launched: ${apkFile.name}, versionCode=$newVersion")
            true
        } catch (e: Exception) {
            logCollector.error(TAG, "Install failed: ${e.javaClass.simpleName}: ${e.message}", e)
            false
        }
    }
}
