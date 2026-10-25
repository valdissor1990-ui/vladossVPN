package com.vpnproxy.logging

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Перехватывает фатальные исключения до смерти процесса.
 * Пишет стек в:
 *   1. filesDir/crashes/last_crash.txt   (persistent, доступно после перезапуска)
 *   2. LogCollector                       (для DebugScreen)
 *   3. Logcat (для Android Studio)
 *
 * ВАЖНО: не глушит краш — приложение всё равно упадёт, но стек сохранится.
 */
object CrashHandler {

    private const val TAG = "CrashHandler"
    private const val DIR = "crashes"
    private const val FILE = "last_crash.txt"

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true

        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                persist(appContext, thread, throwable)
            } catch (_: Throwable) {
                // Ничего не делаем — не должны усугубить падение
            } finally {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun persist(context: Context, thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stack = sw.toString()

        val header = buildString {
            append("=== CRASH ===\n")
            append("time:  ")
            append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date()))
            append("\nthread: ")
            append(thread.name)
            append("\nclass:  ")
            append(throwable.javaClass.name)
            append("\nmsg:    ")
            append(throwable.message ?: "-")
            append("\n\n")
        }

        // Crash messages and stack traces can contain subscription URLs or credentials.
        val full = SensitiveMasker.mask(header + stack)

        try {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            File(dir, FILE).writeText(full)
            File(dir, "crash_${System.currentTimeMillis()}.txt").writeText(full)
            pruneOldCrashes(dir)
        } catch (_: Throwable) {
        }

        try {
            LogCollectorHolder.get()?.fatal(TAG, full.take(2000))
        } catch (_: Throwable) {
        }

        try {
            android.util.Log.e(TAG, full)
        } catch (_: Throwable) {
        }
    }

    private fun pruneOldCrashes(dir: File) {
        try {
            val files = dir.listFiles { _, name -> name.startsWith("crash_") }
                ?.sortedByDescending { it.lastModified() }
                ?: return
            files.drop(5).forEach { it.delete() }
        } catch (_: Throwable) {
        }
    }

    /** Возвращает последний краш, если был. */
    fun readLastCrash(context: Context): String? {
        return try {
            val f = File(File(context.filesDir, DIR), FILE)
            if (f.exists()) f.readText() else null
        } catch (_: Throwable) {
            null
        }
    }

    /** Удалить историю крашей (для кнопки в DebugScreen). */
    fun clearCrashes(context: Context) {
        try {
            File(context.filesDir, DIR).deleteRecursively()
        } catch (_: Throwable) {
        }
    }
}
