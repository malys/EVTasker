package com.mg4.tasker.debug

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app ring buffer of log lines, so a car can be diagnosed without ADB.
 *
 * A head unit is not a development machine: there is no cable, and the interesting
 * failures happen at ignition, minutes before anyone opens the app. Keeping the last
 * [CAPACITY] lines in memory is what makes the Console screen worth having.
 *
 * Fixed capacity, oldest dropped first. An unbounded buffer would grow for as long as the
 * car is on, which on a vehicle means "until the process is killed for memory".
 */
object AppLogger {

    private const val TAG_PREFIX = "MG4T"
    private const val CAPACITY = 500

    data class Entry(val time: String, val level: String, val tag: String, val msg: String)

    private val buffer = ArrayDeque<Entry>(CAPACITY)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Snapshot for the console. Copied under lock: the writer runs on other threads. */
    val entries: List<Entry>
        get() = synchronized(buffer) { buffer.toList() }

    fun d(tag: String, msg: String) = log("D", tag, msg) { Log.d(prefixed(tag), msg) }
    fun i(tag: String, msg: String) = log("I", tag, msg) { Log.i(prefixed(tag), msg) }
    fun w(tag: String, msg: String) = log("W", tag, msg) { Log.w(prefixed(tag), msg) }
    fun e(tag: String, msg: String, t: Throwable? = null) =
        log("E", tag, msg) { Log.e(prefixed(tag), msg, t) }

    fun clear() = synchronized(buffer) { buffer.clear() }

    /** Full buffer as text, for sharing or for attaching to a crash report. */
    fun dump(): String = entries.joinToString("\n") { "[${it.time}] ${it.level}/${it.tag}: ${it.msg}" }

    private inline fun log(level: String, tag: String, msg: String, systemLog: () -> Unit) {
        systemLog()
        val entry = Entry(timeFormat.format(Date()), level, tag, msg)
        synchronized(buffer) {
            if (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast(entry)
        }
    }

    private fun prefixed(tag: String) = "$TAG_PREFIX.$tag"
}
