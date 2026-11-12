package io.github.yyan115.opentune

import android.app.Application
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Keeps the report of the last crash in a file, so that it can be read and shared the next time the app opens. This matters on phones where
 * the log of the system is hard to reach. The report holds the app version, the model of the phone, its Android version and the stack trace,
 * and nothing else. It is only shared when the listener chooses to.
 */
object CrashReporter {
    private const val FILE = "last_crash.txt"
    private const val MAX = 60_000

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
                val report = buildString {
                    append("MyTidal ").append(version).append('\n')
                    append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                    append(", Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
                    append("Thread: ").append(thread.name).append("\n\n")
                    append(Log.getStackTraceString(error))
                }
                File(app.filesDir, FILE).writeText(report.take(MAX))
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The report of the last crash, or null when there is none. */
    fun pending(app: Application): String? = runCatching { File(app.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear(app: Application) { runCatching { File(app.filesDir, FILE).delete() } }
}
