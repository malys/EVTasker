package com.evsuite.tasker.util

import android.os.Build

/**
 * Detects an Android emulator (`mise run emulator-car` / `emulator-screen`), as opposed to a
 * real head unit.
 *
 * Used by [com.evsuite.tasker.vehicle.DirectExecutor] to bypass the firmware-confirmation gate:
 * no emulator image runs SAIC firmware, so [com.evsuite.hardware.FirmwareInfo] never resolves a
 * generation there and every gated action would otherwise be refused before it even reaches
 * EVHardware. Bypassing the gate lets the underlying getters/setters run for real — on the
 * `emulator-car` AVD (AAOS, has a `CarPropertyManager`) standard signals such as speed and
 * ignition genuinely work; vendor-only ones fail closed on their own, same as a real car with
 * that service unbound. `emulator-screen` has no car service at all, so vehicle actions and
 * conditions stay unavailable there regardless — only the AAOS `emulator-car` profile can
 * exercise them.
 */
object EmulatorDetector {

    fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.MODEL.contains("google_sdk") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for x86") ||
            Build.MANUFACTURER.contains("Genymotion") ||
            Build.PRODUCT.contains("sdk") ||
            Build.HARDWARE.contains("goldfish") ||
            Build.HARDWARE.contains("ranchu")
}
