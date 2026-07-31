package com.mg4.tasker.debug

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import com.mg4.hardware.FirmwareInfo
import com.mg4.hardware.FirmwareSupport
import com.mg4.hardware.MG4Hardware
import com.mg4.hardware.VehicleWriteGate
import com.mg4.tasker.bridge.BridgeContract
import com.mg4.tasker.service.TaskerVehicleService
import com.mg4.tasker.store.AppState
import com.mg4.tasker.store.SupportStore
import com.mg4.tasker.util.Notifier
import com.mg4.tasker.vehicle.BtTracker
import com.mg4.tasker.vehicle.ProfileBridge
import com.mg4.tasker.vehicle.VehicleReader

/**
 * Collects the real execution context and runs [Diagnostics] against it.
 *
 * Everything the rule engine depends on is measured the way the engine measures it: the same
 * [VehicleReader] snapshot, the same [VehicleWriteGate] decision, a real bind to MG4Control's
 * bridge rather than a package-installed check. The point is that a green diagnostic and a
 * successful cycle cannot disagree — a probe that took a shortcut here would reintroduce
 * exactly the gap this screen exists to close.
 *
 * ⚠️ Blocking throughout (reflection, system properties, a service bind with a timeout):
 * call off the main thread.
 */
object DiagnosticProbe {

    /** [Env.MG4CONTROL] detail when the package is present; anything else means absent. */
    const val MG4CONTROL_INSTALLED = "installed"

    /**
     * A prerequisite that is not tied to one catalogue entry but decides whether rules run
     * at all. [detail] is untranslated context for the exported report.
     */
    data class EnvCheck(val id: Env, val ok: Boolean, val detail: String = "")

    enum class Env {
        /** MG4Hardware ready — without it every vehicle read and write is out. */
        VEHICLE_LAYER,
        /** The service that listens for ignition. Not running means no rule ever triggers. */
        VEHICLE_SERVICE,
        /** Master switch; off means ignition is observed and deliberately ignored. */
        AUTOMATION,
        /** The channel behind the "notify" action and the foreground service. */
        NOTIFICATIONS,
        /** Standstill gate as it stands now — it decides every road-behaviour write. */
        STANDSTILL_GATE,
        /**
         * The cached supported-entry set the pickers are built from. A cache written by an
         * older build, or for another firmware, hides entries this build can actually run.
         */
        SUPPORT_CACHE,
        /** MG4Control presence: needed by the profile action, and a concurrent writer. */
        MG4CONTROL,
    }

    data class Report(
        val at: Long,
        /** Firmware as reported now, `UNKNOWN` included — not the cached one. */
        val firmwareGen: String,
        val appVersion: String,
        val capabilities: Diagnostics.Capabilities,
        val environment: List<EnvCheck>,
        val conditions: List<Diagnostics.Entry>,
        val actions: List<Diagnostics.Entry>,
    ) {
        val blockedConditions: Int get() = conditions.count { it.status == Diagnostics.Status.BLOCKED }
        val blockedActions: Int get() = actions.count { it.status == Diagnostics.Status.BLOCKED }
    }

    fun run(context: Context): Report {
        val appContext = context.applicationContext
        MG4Hardware.init(appContext)   // idempotent

        val genName = FirmwareInfo.getGeneration().name
        val gen = FirmwareSupport.parse(genName)
        val snapshot = VehicleReader.read(BtTracker.snapshot())

        // Bound and released here rather than inferred from the package list: an installed
        // MG4Control whose bridge refuses the bind fails the profile action just the same.
        val bridge = ProfileBridge(appContext)
        val bridgeReachable = try { bridge.connect() } finally { bridge.disconnect() }

        val caps = Diagnostics.Capabilities(
            vehicleLayerReady = MG4Hardware.isCarPropertyManagerReady(),
            gateVerdict = gateVerdict(),
            mg4ControlInstalled = ProfileBridge.isMG4ControlInstalled(appContext),
            profileBridgeReachable = bridgeReachable,
            notificationsEnabled = Notifier.canNotify(appContext),
            ttsEngineAvailable = hasTtsEngine(appContext),
        )

        return Report(
            at = System.currentTimeMillis(),
            firmwareGen = genName,
            appVersion = "${com.mg4.tasker.BuildConfig.VERSION_NAME} (${com.mg4.tasker.BuildConfig.VERSION_CODE})",
            capabilities = caps,
            environment = environment(appContext, caps, gen?.name),
            conditions = Diagnostics.conditions(snapshot, gen),
            actions = Diagnostics.actions(caps, gen),
        )
    }

    private fun environment(
        context: Context,
        caps: Diagnostics.Capabilities,
        genName: String?
    ): List<EnvCheck> {
        val cached = SupportStore.lastCheck(context)
        val cacheStale = SupportStore.needsCheck(context) || cached?.gen != genName
        return listOf(
            EnvCheck(Env.VEHICLE_LAYER, caps.vehicleLayerReady),
            EnvCheck(Env.VEHICLE_SERVICE, TaskerVehicleService.isRunning),
            EnvCheck(Env.AUTOMATION, AppState.isAutomationEnabled(context)),
            EnvCheck(Env.NOTIFICATIONS, caps.notificationsEnabled),
            EnvCheck(
                Env.STANDSTILL_GATE,
                caps.gateVerdict == BridgeContract.VERDICT_ALLOWED,
                caps.gateVerdict
            ),
            EnvCheck(Env.SUPPORT_CACHE, !cacheStale, cached?.gen ?: "never checked"),
            // Presence is not a failure: it is a second app able to write the same car.
            EnvCheck(
                Env.MG4CONTROL,
                ok = true,
                detail = if (caps.mg4ControlInstalled) MG4CONTROL_INSTALLED else "absent"
            ),
        )
    }

    private fun gateVerdict(): String =
        when (VehicleWriteGate.decide(MG4Hardware.getVehicleSpeedKmh())) {
            VehicleWriteGate.Decision.ALLOWED -> BridgeContract.VERDICT_ALLOWED
            VehicleWriteGate.Decision.REFUSED_MOVING -> BridgeContract.VERDICT_MOVING
            VehicleWriteGate.Decision.REFUSED_UNKNOWN_SPEED -> BridgeContract.VERDICT_UNKNOWN_SPEED
        }

    /**
     * Queried instead of instantiated: creating a [TextToSpeech] to test it would take audio
     * focus, and on a car that means talking over whatever is playing.
     */
    private fun hasTtsEngine(context: Context): Boolean =
        context.packageManager
            .queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            .isNotEmpty()
}
