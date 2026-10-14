package com.vpnproxy.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.vpnproxy.logging.LogCollector
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Установка APK:
 * 1. Проверяет разрешение REQUEST_INSTALL_PACKAGES
 * 2. Если не дано — открывает настройки
 * 3. Иначе — запускает системный установщик через ACTION_VIEW + FileProvider
 */
@Singleton
class UpdateInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logCollector: LogCollector
) {
    companion object { private const val TAG = "UpdateInstaller" }

    /** Можно ли устанавливать пакеты без запроса пользователя. */
    fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else true
    }

    /** Открывает экран настроек «Установка неизвестных приложений». */
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

    /**
     * Запускает установку APK. Вызывать только если canInstallPackages() == true.
     */
    fun install(apkFile: File): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            logCollector.info(TAG, "Install intent launched for ${apkFile.name}")
            true
        } catch (e: Exception) {
            logCollector.error(TAG, "Install failed", e)
            false
        }
    }
}
