package com.evsuite.tasker.debug

import android.content.Context
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.FirmwareSupport
import com.evsuite.hardware.EVHardware
import com.evsuite.hardware.VehicleWriteGate
import com.evsuite.hardware.saic.SaicCharging
import com.evsuite.hardware.saic.SaicClimate
import com.evsuite.hardware.saic.SaicPhone
import com.evsuite.hardware.saic.SaicRadio
import com.evsuite.hardware.saic.SaicVehicleControl
import com.evsuite.tasker.bridge.BridgeContract
import com.evsuite.tasker.service.TaskerVehicleService
import com.evsuite.tasker.store.AppState
import com.evsuite.tasker.store.SupportStore
import com.evsuite.tasker.util.BtDevices
import com.evsuite.tasker.util.CarLocation
import com.evsuite.tasker.util.Notifier
import com.evsuite.tasker.util.SpeechEngines
import com.evsuite.tasker.vehicle.BtOnboard
import com.evsuite.tasker.vehicle.BtTracker
import com.evsuite.tasker.vehicle.DeferredWrites
import com.evsuite.tasker.vehicle.VendorServices
import com.evsuite.tasker.vehicle.ProfileBridge
import com.evsuite.tasker.vehicle.VehicleReader

/**
 * Collects the real execution context and runs [Diagnostics] against it.
 *
 * Everything the rule engine depends on is measured the way the engine measures it: the same
 * [VehicleReader] snapshot, the same [VehicleWriteGate] decision, a real bind to EVProfile's
 * bridge rather than a package-installed check. The point is that a green diagnostic and a
 * successful cycle cannot disagree — a probe that took a shortcut here would reintroduce
 * exactly the gap this screen exists to close.
 *
 * ⚠️ Blocking throughout (reflection, system properties, a service bind with a timeout):
 * call off the main thread.
 */
object DiagnosticProbe {

    /** [Env.EVPROFILE] detail when the package is present; anything else means absent. */
    const val EVPROFILE_INSTALLED = "installed"

    /**
     * A prerequisite that is not tied to one catalogue entry but decides whether rules run
     * at all. [detail] is untranslated context for the exported report.
     */
    data class EnvCheck(val id: Env, val ok: Boolean, val detail: String = "")

    enum class Env {
        /** EVHardware ready — without it every vehicle read and write is out. */
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
        /** EVProfile presence: needed by the profile action, and a concurrent writer. */
        EVPROFILE,
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
        /** Fixed standstill policy and current deferred-write count. */
        WRITE_THRESHOLD,
        /**
         * Each window's raw position and the door-lock state, exactly as the vendor service
         * returns them. Printed unrounded on purpose: the 0–100 scale is inferred from the
         * launcher's constants, not documented, and one look at a car with a window half
         * open is what confirms it.
         */
        GLASS_AND_LOCKS,
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
        EVHardware.init(appContext)   // idempotent

        val genName = FirmwareInfo.getGeneration().name
        val gen = FirmwareSupport.parse(genName)
        // Ask for the vendor binds before reading: they are asynchronous, so a diagnostic run
        // right after boot is also what gets them connected for the next rule cycle.
        VendorServices.connect(appContext)
        val fix = CarLocation.lastKnown(appContext)
        // The two Bluetooth sets are read here for the same reason as the rest of the
        // snapshot: left out, they arrived null and the diagnostic reported the "on board"
        // and "hands-free" conditions as blocked on every car, including the ones where a
        // rule using them evaluates perfectly well.
        val snapshot = VehicleReader.read(
            btMacs = BtTracker.snapshot(appContext),
            btAvailable = BtDevices.isAvailable(appContext),
            btOnboardMacs = BtOnboard.onboard(appContext),
            btHandsFreeMacs = BtDevices.activeHandsFree(appContext),
            fix = fix
        )

        // Bound and released here rather than inferred from the package list: an installed
        // EVProfile whose bridge refuses the bind fails the profile action just the same.
        val bridge = ProfileBridge(appContext)
        val bridgeReachable = try { bridge.connect() } finally { bridge.disconnect() }

        val engines = SpeechEngines.describe(appContext)
        val caps = Diagnostics.Capabilities(
            vehicleLayerReady = EVHardware.isCarPropertyManagerReady(),
            gateVerdict = gateVerdict(),
            evprofileInstalled = ProfileBridge.isEVProfileInstalled(appContext),
            profileBridgeReachable = bridgeReachable,
            notificationsEnabled = Notifier.canNotify(appContext),
            ttsEngineAvailable = engines.isNotEmpty(),
            climateService = SaicClimate.isAvailable,
            chargingService = SaicCharging.isAvailable,
            radioService = SaicRadio.isAvailable,
            phoneService = SaicPhone.isAvailable,
            navigationApp = hasNavigationApp(appContext),
        )

        val conditions = Diagnostics.conditions(snapshot, gen)
        val actions = Diagnostics.actions(caps, gen)
        // The editor reads this back, so what the Diagnostic tab calls blocked is no longer
        // offered in the pickers. Only the verdicts that describe the car are kept — see
        // Diagnostics.Reason.describesTheCar.
        SupportStore.saveDiagnostic(
            appContext,
            conditions = structurallyBlocked(conditions),
            actions = structurallyBlocked(actions),
        )

        return Report(
            at = System.currentTimeMillis(),
            firmwareGen = genName,
            appVersion = "${com.evsuite.tasker.BuildConfig.VERSION_NAME} (${com.evsuite.tasker.BuildConfig.VERSION_CODE})",
            capabilities = caps,
            environment = environment(appContext, caps, gen?.name, engines, snapshot, fix),
            conditions = conditions,
            actions = actions,
        )
    }

    private fun structurallyBlocked(entries: List<Diagnostics.Entry>): Set<String> =
        entries.filter { it.status == Diagnostics.Status.BLOCKED && it.reason.describesTheCar }
            .map { it.name }
            .toSet()

    private fun environment(
        context: Context,
        caps: Diagnostics.Capabilities,
        genName: String?,
        ttsEngines: List<String>,
        snapshot: com.evsuite.tasker.model.Snapshot,
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
                Env.EVPROFILE,
                ok = true,
                detail = if (caps.evprofileInstalled) EVPROFILE_INSTALLED else "absent"
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
            EnvCheck(
                Env.WRITE_THRESHOLD,
                ok = true,
                detail = "fixed 0 km/h, fail closed, deferred=${DeferredWrites.size}"
            ),
            EnvCheck(
                Env.GLASS_AND_LOCKS,
                ok = caps.climateService,
                detail = SaicVehicleControl.windowPercents()
                    .joinToString(" ", prefix = "windows[FL FR RL RR]=") { it?.toString() ?: "?" } +
                    " locked=" + (SaicVehicleControl.doorsLocked()?.toString() ?: "?")
            ),
        )
    }

    /** Whether any activity answers a `geo:` intent — what NAVIGATE_TO needs to exist. */
    /**
     * A `geo:` resolver is not the question — the MG4's map app has no such filter, and this
     * reported "no navigation app" on a car whose home screen shows one. Asks [MapApps],
     * which is also what actually opens it.
     */
    private fun hasNavigationApp(context: Context): Boolean =
        com.evsuite.tasker.util.MapApps.isAvailable(context)

    private fun gateVerdict(): String =
        when (VehicleWriteGate.decideNow()) {
            VehicleWriteGate.Decision.ALLOWED -> BridgeContract.VERDICT_ALLOWED
            VehicleWriteGate.Decision.REFUSED_MOVING -> BridgeContract.VERDICT_MOVING
            VehicleWriteGate.Decision.REFUSED_UNKNOWN_SPEED -> BridgeContract.VERDICT_UNKNOWN_SPEED
        }

}
