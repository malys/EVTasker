package com.evsuite.tasker.debug

import android.content.Context
import android.content.pm.PackageManager

/**
 * The exported activities of the car's own `com.saicmotor.*` apps (CR-030): the screens
 * another app could open. Read from the package manager only — nothing is launched.
 */
object OemScreens {

    private const val PREFIX = "com.saicmotor."

    /** Bounds the Environment row; the report says when it was reached. */
    const val CAP = 150

    /** Package → exported activity names, or the read error for that package. */
    fun read(context: Context): Map<String, List<String>> {
        val pm = context.packageManager
        val installed = try {
            pm.getInstalledPackages(0)
        } catch (e: Exception) {
            return mapOf("getInstalledPackages" to listOf("ERR ${e.javaClass.simpleName}"))
        }
        return installed
            .map { it.packageName }
            .filter { it.startsWith(PREFIX) }
            .sorted()
            .associateWith { pkg ->
                try {
                    pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES).activities
                        ?.filter { it.exported }?.map { it.name }.orEmpty()
                } catch (e: Exception) {
                    listOf("ERR ${e.javaClass.simpleName}")
                }
            }
    }

    /**
     * One line per activity, `package/.Activity` when the class sits under its package. Stops
     * at [cap] lines and names how many were left out.
     */
    fun format(screens: Map<String, List<String>>, cap: Int = CAP): String {
        val lines = screens.flatMap { (pkg, names) ->
            if (names.isEmpty()) listOf("$pkg: no exported activity")
            else names.map { "$pkg/${if (it.startsWith("$pkg.")) it.removePrefix(pkg) else it}" }
        }
        if (lines.isEmpty()) return "no $PREFIX* package"
        if (lines.size <= cap) return lines.joinToString("\n")
        return (lines.take(cap) + "… ${lines.size - cap} more not listed (cap $cap)").joinToString("\n")
    }
}
