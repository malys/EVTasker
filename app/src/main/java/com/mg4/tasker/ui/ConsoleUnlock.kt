package com.mg4.tasker.ui

/** Reveals the stable/offline support console after three Diagnostic-tab taps. */
internal class ConsoleUnlock(private val requiredTaps: Int = 3) {
    private var taps = 0

    fun onDiagnosticTap(): Boolean {
        if (taps < requiredTaps) taps++
        return taps >= requiredTaps
    }
}
