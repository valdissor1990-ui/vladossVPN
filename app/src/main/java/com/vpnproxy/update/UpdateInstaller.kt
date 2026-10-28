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
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Validates the downloaded APK and launches Android's package installer. */
@Singleton
class UpdateInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logCollector: LogCollector
) {
    companion object { private const val TAG = "UpdateInstaller" }

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

    private fun certificateSha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Suppress("DEPRECATION")
    private fun currentSignerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        } ?: return emptySet()
        return signatures.map { certificateSha256(it.toByteArray()) }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun archiveSignerDigests(info: PackageInfo): Set<String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptySet()
            // Include the signing lineage so a legitimate single-signer key rotation
            // can be accepted when the new APK declares the currently installed signer.
            val history = signingInfo.signingCertificateHistory
            val certificates = history?.takeIf { it.isNotEmpty() }
                ?: signingInfo.apkContentsSigners
            return certificates.map { certificateSha256(it.toByteArray()) }.toSet()
        }
        return info.signatures?.map { certificateSha256(it.toByteArray()) }?.toSet().orEmpty()
    }

    private fun hasCompatibleSigningIdentity(installedSigners: Set<String>, archiveSigners: Set<String>): Boolean =
        signerSetsCompatible(installedSigners, archiveSigners)

    fun install(apkFile: File): Boolean {
        return try {
            if (!apkFile.isFile || apkFile.length() <= 0L) {
                logCollector.warn(TAG, "Install rejected: APK file missing or empty")
                return false
            }
            val archiveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.GET_SIGNATURES
                )
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
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            }
            if (!hasCompatibleSigningIdentity(currentSignerDigests(currentInfo), archiveSignerDigests(archiveInfo))) {
                logCollector.warn(TAG, "Install rejected: APK signing identity does not match installed publisher")
                return false
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


/**
 * Pure signer policy so mismatch and key-rotation behavior can be regression-tested
 * without constructing Android PackageInfo objects.
 */
internal fun signerSetsCompatible(installedSigners: Set<String>, archiveSigners: Set<String>): Boolean {
    if (installedSigners.isEmpty() || archiveSigners.isEmpty()) return false

    // Single-signer updates may use a declared signing lineage; multi-signer
    // packages must match exactly.
    if (installedSigners.size == 1) return installedSigners.single() in archiveSigners
    return installedSigners == archiveSigners
}
