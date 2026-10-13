package com.evsuite.tasker.debug

import com.evsuite.hardware.probe.VendorSurfaceRules

/**
 * The Diagnostic tab's "Capture before" / "Capture after" pair (CR-045, CR-046), kept for the
 * export. `VendorSurfaceProbe.capture()` calls value getters only — its rules refuse every
 * setter. In memory: a capture pair is taken minutes before its export, never across a restart.
 */
object SurfaceCaptures {

    @Volatile var before: Map<String, String>? = null
    @Volatile var after: Map<String, String>? = null

    /** The export section body, or null when nothing was captured. */
    fun report(): String? {
        val b = before
        val a = after
        return when {
            b == null && a == null -> null
            b == null || a == null -> "only the ${if (b == null) "after" else "before"} capture was taken"
            else -> VendorSurfaceRules.formatDiff(VendorSurfaceRules.diff(b, a), b.size, a.size).trimEnd()
        }
    }
}
