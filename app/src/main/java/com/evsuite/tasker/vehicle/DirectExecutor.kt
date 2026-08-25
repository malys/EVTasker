package com.evsuite.tasker.vehicle

import android.content.Context
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.FirmwareSupport
import com.evsuite.hardware.EVHardware
import com.evsuite.hardware.VehicleWriteGate
import com.evsuite.hardware.model.DriveMode
import com.evsuite.hardware.model.RegenLevel
import com.evsuite.hardware.saic.SaicCharging
import com.evsuite.hardware.saic.SaicClimate
import com.evsuite.hardware.saic.RadioFrequency
import com.evsuite.hardware.saic.SaicPhone
import com.evsuite.hardware.saic.SaicRadio
import com.evsuite.hardware.saic.SaicVehicleControl
import com.evsuite.tasker.bridge.BridgeContract
import com.evsuite.tasker.engine.ActionCompatibility
import com.evsuite.tasker.engine.ActionExecutor
import com.evsuite.tasker.model.Action
import com.evsuite.tasker.model.ActionResult
import com.evsuite.hardware.catalog.ActionType
import com.evsuite.hardware.catalog.MediaCommand
import com.evsuite.tasker.store.RuleStore
import com.evsuite.tasker.ui.ConfirmPrompt
import com.evsuite.tasker.util.EmulatorDetector
import com.evsuite.tasker.util.BtMessaging
import com.evsuite.tasker.util.ContactDirectory
import com.evsuite.tasker.util.Notifier
import com.evsuite.tasker.util.Speaker
import com.evsuite.tasker.util.WebhookClient

/**
 * Executes actions by calling EVHardware **directly** — EVTasker's own vehicle access,
 * no bridge. The 0 km/h [VehicleWriteGate] still applies (inside the EVHardware write
 * primitives); the verdict reported here mirrors that decision so the history can explain a
 * refusal.
 *
 * The exceptions are [ActionType.APPLY_PROFILE] and [ActionType.SHOW_PROFILE_PICKER]: an
 * EVProfile *profile* — applied outright or offered to the driver — needs EVProfile, so
 * both go through the optional [profileBridge]. Everything else works with EVProfile
 * absent.
 */
class DirectExecutor(
    private val context: Context,
    private val profileBridge: ProfileBridge?
) : ActionExecutor {

    override fun execute(action: Action): ActionResult {
        val generationName = FirmwareInfo.getGeneration().name
        val generation = FirmwareSupport.parse(generationName)
        // No emulator image runs SAIC firmware, so `generation` never resolves there and this
        // gate would refuse every gated action before EVHardware even gets a chance to run.
        // Bypass it on an emulator so the action reaches EVHardware for real — see
        // EmulatorDetector's doc for what that actually exercises on each AVD profile.
        if (!EmulatorDetector.isEmulator() && !ActionCompatibility.isConfirmed(action.type, generation)) {
            return ActionResult(
                action.type,
                false,
                BridgeContract.VERDICT_UNSUPPORTED,
                "firmware support not confirmed: $generationName"
            )
        }
        return when (action.type) {
        ActionType.APPLY_PROFILE     -> applyProfile(action)
        ActionType.SHOW_PROFILE_PICKER -> showProfilePicker(action)
        ActionType.LAUNCH_APP        -> launchApp(action)
        ActionType.SHOW_NOTIFICATION -> notify(action)
        ActionType.SPEAK_TEXT        -> speak(action)
        ActionType.NAVIGATE_TO       -> navigate(action)
        ActionType.ASK_CONFIRM       -> askConfirm(action)
        ActionType.WEBHOOK           -> webhook(action, if (action.flag) "POST" else "GET")
        ActionType.SEND_SMS          -> sendSms(action)
        ActionType.ENABLE_RULE       -> setRuleEnabled(action, true)
        ActionType.DISABLE_RULE      -> setRuleEnabled(action, false)
        ActionType.MEDIA_CONTROL     -> mediaControl(action)
        ActionType.SET_BLUETOOTH     -> setBluetooth(action)
        ActionType.SET_WIFI          -> setWifi(action)
        ActionType.TUNE_RADIO        -> tuneRadio(action)
        else                         -> applyVehicle(action)
        }
    }

    // -------------------------------------------------------------------------
    // Direct vehicle writes
    // -------------------------------------------------------------------------

    private fun applyVehicle(a: Action): ActionResult {
        // Two ways to be gated: the action always is, or it is in the direction this
        // particular write happens to go — see ActionType.gatedWhenOpening.
        val gated = a.type.gated || (a.type.gatedWhenOpening && opensGlass(a))
        if (gated) {
            val verdict = gateVerdict()
            if (verdict != BridgeContract.VERDICT_ALLOWED) return ActionResult(a.type, false, verdict)
        }
        // null = catalogued but with no write path here. Reported as unsupported rather than
        // as an error: an error is retried three times with backoff, and no amount of
        // retrying adds a missing branch.
        val ok = write(a)
            ?: return ActionResult(a.type, false, BridgeContract.VERDICT_UNSUPPORTED, "no write path")
        return ActionResult(a.type, ok, if (ok) BridgeContract.VERDICT_ALLOWED else BridgeContract.VERDICT_ERROR)
    }

    /**
     * Exhaustive on purpose — no `else`.
     *
     * A vehicle action whose branch is missing used to fall through to `false` and be
     * reported as a transient failure, which is how [ActionType.SET_TONE_CONTROL] shipped
     * catalogued, selectable, and silently inert. Listing every constant makes the compiler
     * refuse the next one.
     */
    private fun write(a: Action): Boolean? {
        val i = a.number
        val b = a.flag
        return when (a.type) {
            // Comfort / audio — not gated.
            ActionType.SET_SEAT_HEAT_LEFT        -> EVHardware.setSeatHeatLeft(i)
            ActionType.SET_SEAT_HEAT_RIGHT       -> EVHardware.setSeatHeatRight(i)
            ActionType.SET_STEERING_HEAT         -> EVHardware.setSteeringHeat(b)
            ActionType.SET_MEDIA_VOLUME          -> EVHardware.setMediaVolume(i)
            ActionType.SET_SCREEN_BRIGHTNESS     -> EVHardware.setScreenBrightnessPercent(i)
            ActionType.SET_AUDIO_BALANCE         -> EVHardware.setAudioBalance(i)
            ActionType.SET_AUDIO_FADER           -> EVHardware.setAudioFader(i)
            ActionType.SET_TONE_CONTROL          -> EVHardware.setToneControl(i)
            ActionType.SET_BOSE_SOUND_TYPE       -> EVHardware.setBoseSoundType(i)
            ActionType.SET_3D_EFFECT             -> EVHardware.set3dEffectType(i)
            ActionType.SET_SPEED_VOLUME          -> EVHardware.setSpeedVolumeLevel(i)
            ActionType.SET_LAS_WARNING_SOUND     -> EVHardware.setLasWarningSound(b)
            ActionType.SET_LAS_WARNING_VIBRATION -> EVHardware.setLasWarningVibration(b)
            // Road behaviour — gated (already verdict-checked in applyVehicle).
            ActionType.SET_DRIVE_MODE       -> EVHardware.setDriveMode(DriveMode.fromValue(i))
            ActionType.SET_REGEN_LEVEL      -> EVHardware.setRegenLevel(RegenLevel.fromValue(i))
            ActionType.SET_ONE_PEDAL        -> EVHardware.setOnePedal(b)
            ActionType.SET_ENERGY_SAVING    -> EVHardware.setEnergySavingMode(b)
            ActionType.SET_OVERSPEED_ALARM  -> EVHardware.setOverspeedAlarm(b)
            ActionType.SET_SPEED_LIMIT_TONE -> EVHardware.setSpeedLimitTone(b)
            ActionType.SET_SOUND_WARNING    -> EVHardware.setSoundWarning(b)
            ActionType.SET_AEB_ENABLED      -> EVHardware.setAebEnabled(b)
            ActionType.SET_AEB_MODE         -> EVHardware.setAebMode(i)
            ActionType.SET_AEB_SENSITIVITY  -> EVHardware.setAebSensitivity(i)
            ActionType.SET_ELK_MODE         -> EVHardware.setElkMode(i)
            ActionType.SET_ELK_SENSITIVITY  -> EVHardware.setElkSensitivity(i)
            ActionType.SET_TSR              -> EVHardware.setTsrMode(b)
            ActionType.SET_ACC_TJA_MODE     -> EVHardware.setAccTjaMode(i)
            ActionType.SET_LIMITER_MODE     -> EVHardware.setSpeedLimiterMode(i)
            // Climate — vendor service, not gated (see the catalogue).
            ActionType.SET_CLIMATE_POWER    -> SaicClimate.setPower(b)
            ActionType.SET_CABIN_TEMP       -> SaicClimate.setDriverTemp(i)
            ActionType.SET_PASSENGER_TEMP   -> SaicClimate.setPassengerTemp(i)
            ActionType.SET_AC               -> SaicClimate.setAc(b)
            ActionType.SET_ECON             -> SaicClimate.setEcon(b)
            ActionType.SET_CLIMATE_AUTO     -> SaicClimate.setAuto(b)
            ActionType.SET_RECIRCULATION    -> SaicClimate.setRecirculation(b)
            ActionType.SET_FAN_LEVEL        -> SaicClimate.setFanLevel(i)
            ActionType.SET_FRONT_DEFROST    -> SaicClimate.setFrontDefrost(b)
            ActionType.SET_REAR_DEFROST     -> SaicClimate.setRearDefrost(b)
            // Glass and locks: the vehicle applies its own speed limit, so these are not
            // standstill-gated here — a refusal it makes is reported, not pre-empted.
            ActionType.SET_WINDOWS          -> SaicVehicleControl.setAllWindows(i)
            ActionType.SET_WINDOW_DRIVER    ->
                SaicVehicleControl.setWindow(SaicVehicleControl.Window.DRIVER, i)
            ActionType.SET_WINDOW_PASSENGER ->
                SaicVehicleControl.setWindow(SaicVehicleControl.Window.PASSENGER, i)
            ActionType.SET_WINDOW_REAR_LEFT ->
                SaicVehicleControl.setWindow(SaicVehicleControl.Window.REAR_LEFT, i)
            ActionType.SET_WINDOW_REAR_RIGHT ->
                SaicVehicleControl.setWindow(SaicVehicleControl.Window.REAR_RIGHT, i)
            ActionType.SET_DOOR_LOCK        -> SaicVehicleControl.setDoorsLocked(b)
            // Energy — vendor charging service.
            ActionType.SET_CHARGE_LIMIT      -> SaicCharging.setChargeLimitPercent(i)
            ActionType.SET_CHARGING_ENABLED  -> SaicCharging.setChargingEnabled(b)
            ActionType.SET_CHARGE_SCHEDULE   -> SaicCharging.setScheduleEnabled(b)
            ActionType.SET_CHARGE_WINDOW     ->
                SaicCharging.setScheduleStart(a.minutesFrom) && SaicCharging.setScheduleStop(a.minutesTo)
            ActionType.SET_BATTERY_PREHEAT   -> SaicCharging.setBatteryPreheat(b)
            // Media and telephony — vendor services too.
            ActionType.PLAY_RADIO           -> SaicRadio.play()
            ActionType.CALL_NUMBER,
            ActionType.CALL_CONTACT         -> callNumber(a)
            // Handled by execute() before it ever gets here: none of these is a vehicle write,
            // so a failure is the app's own (no such profile, no such app, webhook refused) and
            // must be reported as that rather than as a vehicle that would not take the value.
            ActionType.APPLY_PROFILE,
            ActionType.SHOW_PROFILE_PICKER,
            ActionType.LAUNCH_APP,
            ActionType.SHOW_NOTIFICATION,
            ActionType.SPEAK_TEXT,
            ActionType.NAVIGATE_TO,
            ActionType.WEBHOOK,
            ActionType.ASK_CONFIRM,
            ActionType.SEND_SMS,
            ActionType.ENABLE_RULE,
            ActionType.DISABLE_RULE,
            ActionType.MEDIA_CONTROL,
            ActionType.SET_BLUETOOTH,
            ActionType.SET_WIFI,
            // TUNE_RADIO is a vehicle write, but it carries a frequency the driver typed, and
            // "103,5 FM" that parsed to nothing must be reported as that rather than as a radio
            // that refused.
            ActionType.TUNE_RADIO -> null
            // Waiting between two actions is the engine's business — it is what runs them in
            // sequence. Reaching here would mean an action ran outside a rule.
            ActionType.DELAY -> null
        }
    }

    /**
     * A frequency the driver typed. Text that names no station is unsupported, not an error:
     * retrying "FM 250" three times with backoff will not make it a station, and the history
     * showing what was typed is what lets the user find the typo.
     *
     * Playback follows [Action.flag], the editor's "enable radio" switch. It has to be
     * decided here rather than by a separate play action after the tune, because the vendor
     * service starts the radio from inside `tune` itself: switching the station always takes
     * the audio focus, so "do not play" is something EVHardware has to undo, not something a
     * caller can achieve by not asking for playback.
     */
    private fun tuneRadio(a: Action): ActionResult {
        val station = RadioFrequency.parse(a.text)
            ?: return ActionResult(a.type, false, BridgeContract.VERDICT_UNSUPPORTED, "not a frequency: ${a.text}")
        val ok = SaicRadio.tune(station.band, station.frequencyKhz, andPlay = a.flag)
        return ActionResult(
            a.type,
            ok,
            if (ok) BridgeContract.VERDICT_ALLOWED else BridgeContract.VERDICT_ERROR,
            "${station.frequencyKhz} kHz"
        )
    }

    /** Digits, `+`, `*` and `#` only: anything else is not a number the car can dial. */
    private fun callNumber(a: Action): Boolean {
        val number = a.text.filter { it.isDigit() || it in "+*#" }
        if (number.isBlank()) return false
        return SaicPhone.placeCall(number)
    }

    /**
     * Whether this write would open glass rather than close it.
     *
     * True when the current position cannot be read: an unknown direction is not a safe one,
     * and the gate this feeds is built on failing closed. Equal positions are not an opening —
     * a write that changes nothing needs no gate.
     */
    private fun opensGlass(a: Action): Boolean {
        val current = when (a.type) {
            // Moving all four to a position opens whichever sits below it, so the write is
            // measured against the least-open window, not the widest.
            ActionType.SET_WINDOWS -> SaicVehicleControl.narrowestWindowPercent()
            ActionType.SET_WINDOW_DRIVER ->
                SaicVehicleControl.windowPercent(SaicVehicleControl.Window.DRIVER)
            ActionType.SET_WINDOW_PASSENGER ->
                SaicVehicleControl.windowPercent(SaicVehicleControl.Window.PASSENGER)
            ActionType.SET_WINDOW_REAR_LEFT ->
                SaicVehicleControl.windowPercent(SaicVehicleControl.Window.REAR_LEFT)
            ActionType.SET_WINDOW_REAR_RIGHT ->
                SaicVehicleControl.windowPercent(SaicVehicleControl.Window.REAR_RIGHT)
            else -> null
        } ?: return true
        return a.number > current
    }

    private fun gateVerdict(): String = when (VehicleWriteGate.decideNow()) {
        VehicleWriteGate.Decision.ALLOWED               -> BridgeContract.VERDICT_ALLOWED
        VehicleWriteGate.Decision.REFUSED_MOVING        -> BridgeContract.VERDICT_MOVING
        VehicleWriteGate.Decision.REFUSED_UNKNOWN_SPEED -> BridgeContract.VERDICT_UNKNOWN_SPEED
    }

    // -------------------------------------------------------------------------
    // Profile (needs EVProfile) + local actions
    // -------------------------------------------------------------------------

    private fun applyProfile(a: Action): ActionResult {
        val bridge = profileBridge
            ?: return ActionResult(a.type, false, BridgeContract.VERDICT_NO_BRIDGE, "EVProfile not installed")
        if (a.text.isBlank()) return ActionResult(a.type, false, BridgeContract.VERDICT_UNSUPPORTED, "no profile selected")
        val res = bridge.applyProfile(a.text)
            ?: return ActionResult(a.type, false, BridgeContract.VERDICT_NO_BRIDGE, "EVProfile unreachable")
        return ActionResult(
            a.type,
            ok = res.getBoolean(BridgeContract.KEY_OK, false),
            verdict = res.getString(BridgeContract.KEY_VERDICT) ?: BridgeContract.VERDICT_ERROR,
            detail = res.getString(BridgeContract.KEY_DETAIL)
        )
    }

    /**
     * The gate, the profile list and the overlay all live on EVProfile's side, so the
     * verdict is whatever it answers — nothing is decided here beyond "no bridge".
     */
    private fun showProfilePicker(a: Action): ActionResult {
        val bridge = profileBridge
            ?: return ActionResult(a.type, false, BridgeContract.VERDICT_NO_BRIDGE, "EVProfile not installed")
        val res = bridge.showProfilePicker()
            ?: return ActionResult(a.type, false, BridgeContract.VERDICT_NO_BRIDGE, "EVProfile unreachable")
        return ActionResult(
            a.type,
            ok = res.getBoolean(BridgeContract.KEY_OK, false),
            verdict = res.getString(BridgeContract.KEY_VERDICT) ?: BridgeContract.VERDICT_ERROR,
            detail = res.getString(BridgeContract.KEY_DETAIL)
        )
    }

    private fun launchApp(a: Action): ActionResult {
        val intent = context.packageManager.getLaunchIntentForPackage(a.text)
            ?: return ActionResult(a.type, false, BridgeContract.VERDICT_UNSUPPORTED, "app not found: ${a.text}")
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ActionResult(a.type, true, BridgeContract.VERDICT_ALLOWED, a.text)
        } catch (e: Exception) {
            Log.w("EVTasker.Exec", "launchApp(${a.text}): ${e.message}")
            ActionResult(a.type, false, BridgeContract.VERDICT_ERROR, e.message)
        }
    }

    /**
     * A blocked channel makes `notify()` a no-op the platform reports nothing about. Claiming
     * ALLOWED there is how "the message action does not work" became invisible in the
     * history: the rule said applied, the driver saw nothing, and the two never met.
     */
    /**
     * Hands the destination to whatever navigation app the head unit has.
     *
     * `geo:` first — the standard every Android navigation app registers — then
     * `google.navigation:`, which starts guidance rather than only showing the place. A car
     * with neither is reported as unsupported with the destination in the detail, so the
     * history says which rule found no navigator rather than just "failed".
     */
    private fun navigate(a: Action): ActionResult {
        if (a.text.isBlank()) {
            return ActionResult(a.type, false, BridgeContract.VERDICT_UNSUPPORTED, "no destination")
        }
        // The URI intents come first and carry the destination; the head unit's own map app
        // has no geo: filter, so MapApps ends with an explicit component. See MapApps.
        val opened = com.evsuite.tasker.util.MapApps.open(context, a.text)
        return if (opened != null) {
            ActionResult(a.type, true, BridgeContract.VERDICT_ALLOWED, opened)
        } else {
            ActionResult(a.type, false, BridgeContract.VERDICT_UNSUPPORTED, "no navigation app")
        }
    }

    /**
     * Switches another rule on or off.
     *
     * The change lands in the store, and the store is read at the start of a cycle — so a
     * rule enabled here runs from the *next* trigger, not from this one. That is the point
     * rather than a limitation: a rule that could enable another and have it fire in the same
     * pass would make the order of a cycle part of what the user has to reason about.
     *
     * A target that no longer exists is unsupported, with its id in the detail. Silence would
     * leave a chain whose middle link was deleted looking like it still worked.
     */
    private fun setRuleEnabled(a: Action, enabled: Boolean): ActionResult {
        if (a.text.isBlank()) {
            return ActionResult(a.type, false, BridgeContract.VERDICT_UNSUPPORTED, "no rule selected")
        }
        val store = RuleStore(context)
        val target = store.getById(a.text)
            ?: return ActionResult(a.type, false, BridgeContract.VERDICT_UNSUPPORTED, "no such rule: ${a.text}")
        if (target.enabled == enabled) {
            return ActionResult(a.type, true, BridgeContract.VERDICT_ALLOWED, "${target.name} unchanged")
        }
        store.setEnabled(a.text, enabled)
        return ActionResult(a.type, true, BridgeContract.VERDICT_ALLOWED, target.name)
    }

    /**
     * Sends the media key, down then up, exactly as the steering control does.
     *
     * Both halves or nothing: an app that receives a key-down and never the matching key-up
     * can sit on a held key. `dispatchMediaKeyEvent` answers nothing, so what is reported is
     * that the key was sent — not that anything was playing to receive it.
     */
    private fun mediaControl(a: Action): ActionResult {
        val code = a.number.toInt()
        if (code !in MediaCommand.OPTIONS.map { it.value }) {
            return ActionResult(a.type, false, BridgeContract.VERDICT_UNSUPPORTED, "unknown media key: $code")
        }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ActionResult(a.type, false, BridgeContract.VERDICT_UNSUPPORTED, "no audio service")
        return try {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            ActionResult(a.type, true, BridgeContract.VERDICT_ALLOWED)
        } catch (e: Exception) {
            Log.w("EVTasker.Exec", "mediaControl($code): ${e.message}")
            ActionResult(a.type, false, BridgeContract.VERDICT_ERROR, e.message)
        }
    }

    /**
     * The head unit's own radios.
     *
     * Reported as already-in-that-state rather than as a write when nothing had to change: a
     * rule that switches Wi-Fi off at every ignition should not fill the history with writes
     * it never made.
     */
    private fun setBluetooth(a: Action): ActionResult {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return ActionResult(a.type, false, BridgeContract.VERDICT_UNSUPPORTED, "no Bluetooth adapter")
        // The catch in radioResult would cover a refusal, but the check is made here as well:
        // the app is granted these by the platform signature, so a missing one means the APK
        // is not installed as a system app — worth saying in the history rather than
        // surfacing as an opaque SecurityException.
        val missing = bluetoothSwitchPermission()
        if (missing != null) {
            return ActionResult(a.type, false, BridgeContract.VERDICT_UNSUPPORTED, "not granted: $missing")
        }
        return radioResult(a, adapter.isEnabled) {
            @Suppress("DEPRECATION", "MissingPermission")
            if (a.flag) adapter.enable() else adapter.disable()
        }
    }

    /**
     * The permission the switch needs on this platform, or null when it is held.
     *
     * Two names, because the platform moved: up to API 30 turning the adapter on or off is
     * `BLUETOOTH_ADMIN`, from API 31 it is `BLUETOOTH_CONNECT`. Both are declared, and which
     * one is enforced is the system's business, not the rule's.
     */
    private fun bluetoothSwitchPermission(): String? {
        val needed =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT
            else Manifest.permission.BLUETOOTH_ADMIN
        val granted = ContextCompat.checkSelfPermission(context, needed) ==
            PackageManager.PERMISSION_GRANTED
        return if (granted) null else needed
    }

    private fun setWifi(a: Action): ActionResult {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return ActionResult(a.type, false, BridgeContract.VERDICT_UNSUPPORTED, "no Wi-Fi service")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CHANGE_WIFI_STATE) !=
            PackageManager.PERMISSION_GRANTED) {
            return ActionResult(
                a.type, false, BridgeContract.VERDICT_UNSUPPORTED,
                "not granted: ${Manifest.permission.CHANGE_WIFI_STATE}"
            )
        }
        return radioResult(a, wifi.isWifiEnabled) {
            @Suppress("DEPRECATION")
            wifi.setWifiEnabled(a.flag)
        }
    }

    private fun radioResult(a: Action, current: Boolean, write: () -> Boolean): ActionResult {
        if (current == a.flag) {
            return ActionResult(a.type, true, BridgeContract.VERDICT_ALLOWED, "already")
        }
        return try {
            val ok = write()
            ActionResult(a.type, ok, if (ok) BridgeContract.VERDICT_ALLOWED else BridgeContract.VERDICT_ERROR)
        } catch (e: SecurityException) {
            // A platform that refuses the switch will refuse it on the third attempt too, so
            // this is not the transient failure ERROR would have retried.
            ActionResult(a.type, false, BridgeContract.VERDICT_UNSUPPORTED, e.message)
        }
    }

    private fun webhook(a: Action, method: String): ActionResult {
        if (a.text.isBlank()) {
            return ActionResult(a.type, false, BridgeContract.VERDICT_UNSUPPORTED, "no URL")
        }
        val response = WebhookClient.call(method, a.text, a.payload)
        return ActionResult(
            a.type,
            response.ok,
            if (response.ok) BridgeContract.VERDICT_ALLOWED else BridgeContract.VERDICT_ERROR,
            response.detail
        )
    }

    /**
     * Hands a message to the paired phone.
     *
     * Never [BridgeContract.VERDICT_ERROR], whatever went wrong. That verdict is retried three
     * times with backoff, and a send call that failed after the message left the car would
     * then deliver it twice — the one outcome worse than not sending it at all. Every failure
     * is reported as unsupported, with the reason, and stops there.
     */
    private fun sendSms(a: Action): ActionResult {
        val number = ContactDirectory.normalize(a.text)
        if (number.isBlank()) {
            return ActionResult(a.type, false, BridgeContract.VERDICT_UNSUPPORTED, "no recipient")
        }
        val message = a.payload?.trim().orEmpty()
        if (message.isBlank()) {
            return ActionResult(a.type, false, BridgeContract.VERDICT_UNSUPPORTED, "no message")
        }
        val result = BtMessaging.send(context, number, message)
        return ActionResult(
            a.type,
            result.ok,
            if (result.ok) BridgeContract.VERDICT_ALLOWED else BridgeContract.VERDICT_UNSUPPORTED,
            result.detail
        )
    }

    /**
     * Puts the rule's question on screen and waits for the driver, for as long as the action
     * says.
     *
     * The three answers are three different things and the history says which:
     *   • yes — allowed, and the rule carries on;
     *   • no — declined. `ok` is true because the action did exactly what it exists to do;
     *     the rule stopping afterwards is the answer being honoured, not a failure;
     *   • nothing — declined too, but `ok` is false. A rule left half-applied because
     *     nobody was there to answer is worth seeing in red; a rule the driver deliberately
     *     stopped is not.
     */
    private fun askConfirm(a: Action): ActionResult {
        if (a.text.isBlank()) {
            return ActionResult(a.type, false, BridgeContract.VERDICT_UNSUPPORTED, "no question")
        }
        return when (ConfirmPrompt.ask(context, a.text, ConfirmPrompt.timeoutMsFor(a.number))) {
            ConfirmPrompt.Answer.YES ->
                ActionResult(a.type, true, BridgeContract.VERDICT_ALLOWED, "confirmed")
            ConfirmPrompt.Answer.NO ->
                ActionResult(a.type, true, BridgeContract.VERDICT_DECLINED, "declined")
            ConfirmPrompt.Answer.NO_ANSWER ->
                ActionResult(a.type, false, BridgeContract.VERDICT_DECLINED, "no answer")
        }
    }

    private fun notify(a: Action): ActionResult {
        if (a.text.isBlank()) {
            return ActionResult(a.type, false, BridgeContract.VERDICT_UNSUPPORTED, "no message")
        }
        if (!Notifier.canNotify(context)) {
            return ActionResult(a.type, false, BridgeContract.VERDICT_UNSUPPORTED, "notifications off")
        }
        Notifier.showRuleMessage(context, a.text)
        return ActionResult(a.type, true, BridgeContract.VERDICT_ALLOWED)
    }

    /**
     * A vehicle without a TTS engine (or without the voice data for the current language)
     * is a real case: it is reported as a failure with a reason rather than as a silent
     * success, otherwise the history would claim the driver was told something.
     */
    private fun speak(a: Action): ActionResult {
        if (a.text.isBlank()) {
            return ActionResult(a.type, false, BridgeContract.VERDICT_UNSUPPORTED, "no message")
        }
        val failure = Speaker.speak(context, a.text)
            ?: return ActionResult(a.type, true, BridgeContract.VERDICT_ALLOWED)
        // A missing engine will still be missing on the third try, so it is not an ERROR the
        // rule engine should retry with backoff — only a refused utterance is worth another go.
        val verdict =
            if (failure == Speaker.Failure.REFUSED) BridgeContract.VERDICT_ERROR
            else BridgeContract.VERDICT_UNSUPPORTED
        return ActionResult(a.type, false, verdict, failure.name)
    }
}
