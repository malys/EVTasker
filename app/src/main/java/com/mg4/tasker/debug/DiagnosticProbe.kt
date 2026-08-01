package com.mg4.tasker.debug

import android.content.Context
import com.mg4.hardware.FirmwareInfo
import com.mg4.hardware.FirmwareSupport
import com.mg4.hardware.MG4Hardware
import com.mg4.hardware.VehicleWriteGate
import com.mg4.hardware.saic.SaicCharging
import com.mg4.hardware.saic.SaicClimate
import com.mg4.hardware.saic.SaicPhone
import com.mg4.hardware.saic.SaicRadio
import com.mg4.tasker.bridge.BridgeContract
import com.mg4.tasker.service.TaskerVehicleService
import com.mg4.tasker.store.AppState
import com.mg4.tasker.store.SupportStore
import com.mg4.tasker.util.BtDevices
import com.mg4.tasker.util.CarLocation
import com.mg4.tasker.util.Notifier
import com.mg4.tasker.util.SpeechEngines
import com.mg4.tasker.vehicle.BtTracker
import com.mg4.tasker.vehicle.VendorServices
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
        /** The engine behind the "speak" action; the detail names it, for the report. */
        TTS,
        /**
         * Radio state and what is connected right now. A Bluetooth rule that never fires is
         * the most reported symptom, and the connected list is the one fact that explains it.
         */
        BLUETOOTH,
        /**
         * The SAIC vendor services behind climate, charging, radio and calls. One row, four
         * binds: the detail names the ones that answered, which is what says whether a
         * firmware this build has never seen supports them.
         */
        VENDOR_SERVICES,
        /** Position: the permission, and whether there is a fix recent enough to use. */
        LOCATION,
        /** Standstill threshold in force — 0 means writes only when stopped. */
        WRITE_THRESHOLD,
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
        // Ask for the vendor binds before reading: they are asynchronous, so a diagnostic run
        // right after boot is also what gets them connected for the next rule cycle.
        VendorServices.connect(appContext)
        val fix = CarLocation.lastKnown(appContext)
        val snapshot = VehicleReader.read(
            btMacs = BtTracker.snapshot(appContext),
            btAvailable = BtDevices.isAvailable(appContext),
            fix = fix
        )

        // Bound and released here rather than inferred from the package list: an installed
        // MG4Control whose bridge refuses the bind fails the profile action just the same.
        val bridge = ProfileBridge(appContext)
        val bridgeReachable = try { bridge.connect() } finally { bridge.disconnect() }

        val engines = SpeechEngines.describe(appContext)
        val caps = Diagnostics.Capabilities(
            vehicleLayerReady = MG4Hardware.isCarPropertyManagerReady(),
            gateVerdict = gateVerdict(),
            mg4ControlInstalled = ProfileBridge.isMG4ControlInstalled(appContext),
            profileBridgeReachable = bridgeReachable,
            notificationsEnabled = Notifier.canNotify(appContext),
            ttsEngineAvailable = engines.isNotEmpty(),
            climateService = SaicClimate.isAvailable,
            chargingService = SaicCharging.isAvailable,
            radioService = SaicRadio.isAvailable,
            phoneService = SaicPhone.isAvailable,
            navigationApp = hasNavigationApp(appContext),
        )

        return Report(
            at = System.currentTimeMillis(),
            firmwareGen = genName,
            appVersion = "${com.mg4.tasker.BuildConfig.VERSION_NAME} (${com.mg4.tasker.BuildConfig.VERSION_CODE})",
            capabilities = caps,
            environment = environment(appContext, caps, gen?.name, engines, snapshot, fix),
            conditions = Diagnostics.conditions(snapshot, gen),
            actions = Diagnostics.actions(caps, gen),
        )
    }

    private fun environment(
        context: Context,
        caps: Diagnostics.Capabilities,
        genName: String?,
        ttsEngines: List<String>,
        snapshot: com.mg4.tasker.model.Snapshot,
        fix: CarLocation.Fix?
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
            // The engine names go in the detail rather than the row: "no speech engine" on a
            // car that talks is a report nobody can act on without knowing what was looked at.
            EnvCheck(
                Env.TTS,
                ok = ttsEngines.isNotEmpty(),
                detail = ttsEngines.joinToString(", ").ifEmpty { "none" }
            ),
            // The MACs, not just a count: matching them against the one a rule names is what
            // turns "my Bluetooth rule never fires" into an answer.
            EnvCheck(
                Env.BLUETOOTH,
                ok = snapshot.btAvailable,
                detail = snapshot.btMacs.sorted().joinToString(", ").ifEmpty { "none connected" }
            ),
            // Named individually: "climate works but charging does not" is a real state, and
            // a single yes/no would hide which half of the catalogue is out.
            EnvCheck(
                Env.VENDOR_SERVICES,
                ok = caps.climateService || caps.chargingService ||
                    caps.radioService || caps.phoneService,
                detail = listOf(
                    "climate" to caps.climateService,
                    "charging" to caps.chargingService,
                    "radio" to caps.radioService,
                    "btcall" to caps.phoneService,
                ).joinToString(" ") { (name, bound) -> if (bound) name else "$name:no" }
            ),
            EnvCheck(
                Env.LOCATION,
                ok = fix != null,
                detail = when {
                    !CarLocation.hasPermission(context) -> "permission denied"
                    fix == null -> "no recent fix"
                    else -> "${fix.latitude},${fix.longitude}"
                }
            ),
            // Reported, never judged: zero is the safe value and any other one was chosen.
            EnvCheck(
                Env.WRITE_THRESHOLD,
                ok = true,
                detail = "${VehicleWriteGate.allowUpToKmh.toInt()} km/h"
            ),
        )
    }

    /** Whether any activity answers a `geo:` intent — what NAVIGATE_TO needs to exist. */
    private fun hasNavigationApp(context: Context): Boolean {
        val intent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("geo:0,0?q=test")
        )
        return intent.resolveActivity(context.packageManager) != null
    }

    private fun gateVerdict(): String =
        when (VehicleWriteGate.decide(MG4Hardware.getVehicleSpeedKmh())) {
            VehicleWriteGate.Decision.ALLOWED -> BridgeContract.VERDICT_ALLOWED
            VehicleWriteGate.Decision.REFUSED_MOVING -> BridgeContract.VERDICT_MOVING
            VehicleWriteGate.Decision.REFUSED_UNKNOWN_SPEED -> BridgeContract.VERDICT_UNKNOWN_SPEED
        }

}
