package com.mg4.tasker.debug

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures uncaught exceptions to a file in `filesDir`, surfaced on the next launch.
 *
 * On a vehicle there is no crash reporter and usually no cable. Without this, a crash at
 * ignition leaves nothing behind: the process dies, the user sees the app "not working",
 * and there is no way to find out why after the fact.
 *
 * The previous handler is always chained, so the system still gets to do its job.
 */
object CrashLogger {

    private const val TAG = "Crash"
    private const val FILE_NAME = "last_crash.txt"

    /**
     * Reports are truncated by keeping the HEAD, not the tail.
     *
     * The exception and its top frames are at the top of the report — that is the part
     * that identifies the bug. Cutting from the start would discard exactly what is
     * needed and keep the least interesting frames.
     */
    private const val MAX_CHARS = 64 * 1024

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(appContext, thread, throwable) }
            // Chain, never swallow: the platform still needs to terminate the process,
            // and swallowing here would leave the app in an undefined state instead.
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun hasReport(context: Context): Boolean = file(context).exists()

    fun read(context: Context): String? =
        file(context).takeIf { it.exists() }?.runCatching { readText() }?.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val stackTrace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        val report = buildString {
            appendLine("MG4Tasker crash report")
            appendLine("time    : $timestamp")
            appendLine("thread  : ${thread.name}")
            appendLine("android : ${android.os.Build.VERSION.SDK_INT}")
            appendLine("device  : ${android.os.Build.DEVICE}")
            appendLine()
            appendLine("── Stack trace ──")
            appendLine(stackTrace)
            appendLine("── Recent log (AppLogger) ──")
            appendLine(AppLogger.dump())
        }.take(MAX_CHARS)

        writeAtomically(file(context), report)
    }

    /**
     * Temp file then rename over the target.
     *
     * Never delete-then-write: that leaves a window with no file at all, and this runs
     * while the process is already dying — a second failure mid-write would destroy the
     * previous report without producing a new one.
     */
    private fun writeAtomically(target: File, content: String) {
        val temp = File(target.parentFile, "${target.name}.${System.nanoTime()}.tmp")
        try {
            temp.writeText(content)
            if (!temp.renameTo(target)) {
                temp.delete()
                AppLogger.w(TAG, "could not rename crash report into place")
            }
        } catch (e: Exception) {
            temp.delete()
            AppLogger.w(TAG, "could not write crash report: ${e.message}")
        }
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
}
