package io.github.hagbard235.ringnotes.diagnostics

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last crash on disk so it can be shared after a restart, and builds a
 * diagnosis report (last crash, app log, this process's logcat) for sharing.
 * Settings and keys are never included.
 */
class CrashReporter(private val context: Context) {
    private val crashFile = File(context.filesDir, "last-crash.txt")
    private val seenFile = File(context.filesDir, "last-crash.seen")

    /** Install once, early in Application.onCreate. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                crashFile.writeText(describe(thread, error))
                seenFile.delete()
            } catch (_: Throwable) {
                // Never let the reporter itself hide the original crash.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The last crash that the user has not dismissed yet, if any. */
    fun unseenCrash(): String? = if (crashFile.exists() && !seenFile.exists()) crashFile.readText() else null

    fun markSeen() {
        seenFile.writeText("1")
    }

    /** Last crash + the given in-app log lines + this process's recent logcat. */
    fun diagnosis(appLog: List<String>): String = buildString {
        appendLine("Ring Notes – Diagnose ${timestamp()}")
        appendLine(environment())
        appendLine()
        appendLine("== Letzter Absturz ==")
        appendLine(if (crashFile.exists()) crashFile.readText() else "keiner gespeichert")
        appendLine()
        appendLine("== App-Log (neueste zuerst) ==")
        appLog.forEach(::appendLine)
        appendLine()
        appendLine("== Systemmeldungen der App (logcat) ==")
        appendLine(ownLogcat())
    }

    private fun describe(thread: Thread, error: Throwable): String {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        return "Zeit: ${timestamp()}\n${environment()}\nThread: ${thread.name}\n\n$trace"
    }

    private fun environment(): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return "App ${info.versionName} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
            "${Build.MANUFACTURER} ${Build.MODEL}"
    }

    /** Apps may read their own log lines; the last 800 of this process. */
    private fun ownLogcat(): String = try {
        val proc = ProcessBuilder("logcat", "-d", "-t", "800", "--pid=${Process.myPid()}")
            .redirectErrorStream(true)
            .start()
        proc.inputStream.bufferedReader().use { it.readText() }.ifBlank { "(leer)" }
    } catch (e: Exception) {
        "(nicht lesbar: ${e.message})"
    }

    private fun timestamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.GERMANY).format(Date())

    companion object {
        fun share(context: Context, text: String) {
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Ring Notes Diagnose")
                .putExtra(Intent.EXTRA_TEXT, text.take(MAX_SHARE_CHARS))
            context.startActivity(Intent.createChooser(send, "Diagnose teilen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        /** Share targets reject very large texts; the newest part matters most. */
        private const val MAX_SHARE_CHARS = 90_000
    }
}
