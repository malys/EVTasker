package com.mg4.tasker.debug

import android.content.Context
import com.google.gson.GsonBuilder
import com.mg4.hardware.AppLogger
import com.mg4.hardware.diag.CrashLogger
import com.mg4.tasker.store.HistoryStore
import com.mg4.tasker.store.RuleStore
import com.mg4.tasker.store.RuleTransfer
import com.mg4.tasker.store.StorageBrowser
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The whole debugging picture as one text file: diagnostic verdicts, the rules that produced
 * them, the history of what actually ran, the in-app log and the last crash.
 *
 * A head unit has no cable, no logcat and no crash reporter, and the existing "share" hands
 * the text to an app the car usually does not have. Writing to the USB stick the user
 * already uses for rules is the one path that works on the vehicle itself.
 *
 * Deliberately untranslated: the reader is whoever debugs the app, and enum constants are
 * what they will search the source for. Translating `NOT_READABLE` would break that.
 */
object DebugReport {

    private const val NAME_PREFIX = "mg4tasker-diagnostic-"
    private const val FILE_EXTENSION = "txt"

    /**
     * The log and the history are the unbounded parts (500 log lines, 30 runs). The cap sits
     * far above both so a normal report is never truncated, and stops a pathological one
     * from filling a stick.
     */
    private const val MAX_CHARS = 512 * 1024

    private const val SEPARATOR = "────────────────────────────────────────"

    /** @return the file written, or null when [dir] was not writable. */
    fun export(context: Context, report: DiagnosticProbe.Report, dir: File): File? {
        // A stick whose root refuses writes still takes the app-specific folder on the same
        // volume: the file lands on the stick the user chose either way.
        val into = StorageBrowser.writableTarget(context, dir) ?: return null
        val target = File(into, fileName())
        val temp = File(into, target.name + ".tmp")
        return try {
            // Temp then rename, like the rules export: a stick pulled mid-write leaves the
            // .tmp behind rather than a half-written report that reads as complete.
            temp.writeText(render(context, report))
            if (temp.renameTo(target)) target else null
        } catch (_: IOException) {
            null
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    fun render(context: Context, report: DiagnosticProbe.Report): String = buildString {
        appendLine("MG4Tasker diagnostic report")
        appendLine("generated : ${timestamp(report.at)}")
        appendLine("app       : ${report.appVersion}")
        appendLine("android   : ${android.os.Build.VERSION.SDK_INT}")
        appendLine("device    : ${android.os.Build.DEVICE} (${android.os.Build.MODEL})")
        appendLine("firmware  : ${report.firmwareGen}")
        appendLine()

        section("Environment")
        report.environment.forEach { check ->
            appendLine(row(check.ok, check.id.name, check.detail))
        }
        appendLine()

        section("Conditions — ${report.blockedConditions} blocked of ${report.conditions.size}")
        report.conditions.forEach { appendLine(entryRow(it)) }
        appendLine()

        section("Actions — ${report.blockedActions} blocked of ${report.actions.size}")
        report.actions.forEach { appendLine(entryRow(it)) }
        appendLine()

        section("Rules")
        // The export format, not a bespoke dump: the rules can be pasted straight back into
        // an import to reproduce the problem on another car.
        appendLine(RuleStore(context).getAll().let { rules ->
            if (rules.isEmpty()) "(none)" else RuleTransfer.encode(rules)
        })
        appendLine()

        section("Storage")
        // What the browser offered and where the app may write. A stick the car mounts
        // somewhere the app never looks is invisible from the UI but obvious here.
        appendLine(storage(context))
        appendLine()

        section("History")
        val runs = HistoryStore(context).getAll()
        appendLine(if (runs.isEmpty()) "(none)" else GsonBuilder().setPrettyPrinting().create().toJson(runs))
        appendLine()

        section("Log")
        appendLine(AppLogger.dump().ifEmpty { "(empty)" })
        appendLine()

        CrashLogger.read(context)?.let {
            section("Previous crash")
            appendLine(it)
        }
    }.take(MAX_CHARS)

    /**
     * The same report as [render], as Markdown.
     *
     * Only the frame changes: the body of each section is a fixed-width table and stays in a
     * fenced block, because the alignment is what makes a 40-row verdict list readable at a
     * glance. Reflowing it as prose would lose exactly that.
     */
    fun renderMarkdown(
        context: Context,
        report: DiagnosticProbe.Report,
        /** Front matter for a shared paste: how to open it, and who is hosting it. */
        header: String? = null,
    ): String = buildString {
        appendLine("# MG4Tasker diagnostic report")
        appendLine()
        header?.let {
            appendLine(it.trim())
            appendLine()
            appendLine("---")
            appendLine()
        }
        appendLine("| | |")
        appendLine("|---|---|")
        appendLine("| generated | ${timestamp(report.at)} |")
        appendLine("| app | ${report.appVersion} |")
        appendLine("| android | ${android.os.Build.VERSION.SDK_INT} |")
        appendLine("| device | ${android.os.Build.DEVICE} (${android.os.Build.MODEL}) |")
        appendLine("| firmware | ${report.firmwareGen} |")
        appendLine()

        fenced("Environment") {
            report.environment.forEach { appendLine(row(it.ok, it.id.name, it.detail)) }
        }
        fenced("Conditions — ${report.blockedConditions} blocked of ${report.conditions.size}") {
            report.conditions.forEach { appendLine(entryRow(it)) }
        }
        fenced("Actions — ${report.blockedActions} blocked of ${report.actions.size}") {
            report.actions.forEach { appendLine(entryRow(it)) }
        }
        fenced("Rules", language = "json") {
            appendLine(RuleStore(context).getAll().let { rules ->
                if (rules.isEmpty()) "(none)" else RuleTransfer.encode(rules)
            })
        }
        fenced("Storage") { appendLine(storage(context)) }
        fenced("History", language = "json") {
            val runs = HistoryStore(context).getAll()
            appendLine(
                if (runs.isEmpty()) "(none)"
                else GsonBuilder().setPrettyPrinting().create().toJson(runs)
            )
        }
        fenced("Log") { appendLine(AppLogger.dump().ifEmpty { "(empty)" }) }
        CrashLogger.read(context)?.let { crash -> fenced("Previous crash") { appendLine(crash) } }
    }.take(MAX_CHARS)

    private fun StringBuilder.fenced(title: String, language: String = "", body: StringBuilder.() -> Unit) {
        appendLine("## $title")
        appendLine()
        appendLine("```$language")
        body()
        appendLine("```")
        appendLine()
    }

    private fun storage(context: Context): String = buildString {
        context.getExternalFilesDirs(null).forEachIndexed { index, dir ->
            appendLine("externalFilesDir[$index] = ${dir?.absolutePath ?: "(null)"}")
        }
        StorageBrowser.roots(context).forEach { root ->
            appendLine(
                "root ${root.dir.absolutePath} removable=${root.removable} " +
                    "entries=${root.dir.listFiles()?.size ?: -1} " +
                    "writableTarget=${StorageBrowser.writableTarget(context, root.dir)?.absolutePath ?: "(none)"}"
            )
        }
    }.trimEnd()

    private fun StringBuilder.section(title: String) {
        appendLine(SEPARATOR)
        appendLine(title)
        appendLine(SEPARATOR)
    }

    private fun entryRow(entry: Diagnostics.Entry): String {
        val note = buildList {
            if (entry.reason != Diagnostics.Reason.NONE) add(entry.reason.name)
            entry.value?.let { add("= $it") }
            if (entry.hidden) add("hidden in the editor on this firmware")
        }.joinToString("  ")
        return row(entry.status == Diagnostics.Status.OK, entry.name, note)
    }

    /** Fixed-width so the file reads as a table in any editor. */
    private fun row(ok: Boolean, name: String, note: String): String {
        val mark = if (ok) "[ OK ]" else "[FAIL]"
        return "$mark ${name.padEnd(26)} $note".trimEnd()
    }

    /** Timestamped: a report is evidence, and overwriting the previous one loses the before. */
    private fun fileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "$NAME_PREFIX$stamp.$FILE_EXTENSION"
    }

    private fun timestamp(at: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(at))
}
