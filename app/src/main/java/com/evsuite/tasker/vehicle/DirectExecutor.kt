package com.evsuite.tasker.vehicle

import android.content.Context
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.FirmwareSupport
import com.evsuite.hardware.GlassEvidence
import com.evsuite.hardware.EVHardware
import com.evsuite.hardware.effectProven
import com.evsuite.hardware.VehicleWriteGate
import com.evsuite.hardware.model.DriveMode
import com.evsuite.hardware.model.RegenLevel
import com.evsuite.hardware.saic.SaicCharging
import com.evsuite.hardware.saic.SaicClimate
import com.evsuite.hardware.saic.RadioFrequency
import com.evsuite.hardware.saic.SaicPhone
import com.evsuite.hardware.saic.SaicMediaPlayer
import com.evsuite.hardware.saic.SaicRadio
import com.evsuite.hardware.saic.SaicVehicleControl
import com.evsuite.tasker.bridge.BridgeContract
import com.evsuite.tasker.engine.ActionCompatibility
import com.evsuite.tasker.engine.ActionExecutor
import com.evsuite.tasker.model.Action
import com.evsuite.tasker.model.ActionResult
import com.evsuite.hardware.catalog.ActionType
import com.evsuite.hardware.catalog.MediaCommand
import com.evsuite.hardware.catalog.VehicleEnums
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
        // An action whose write was never shown to move anything cannot be run at all — see
        // ActionType.writeProven, and GlassEvidence for the one case a probe can settle. It
        // is refused rather than attempted because the service
        // behind such a write accepts the call and drops the value, so attempting it would
        // write "applied" in the history for a car that did not budge. UNSUPPORTED, not
        // ERROR: the engine retries an error three times, and no amount of retrying proves
        // an effect. Reaching here at all means an imported or pre-existing rule still names
        // it; the editor stopped offering it (SupportChecker).
        if (!action.type.effectProven) {
            return ActionResult(
                action.type,
                false,
                BridgeContract.VERDICT_UNSUPPORTED,
                "write effect not established for ${action.type.name}"
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
        ActionType.RADIO_PLAY_PAUSE  -> radioPlayPause(action)
        ActionType.CALL_NUMBER,
        ActionType.CALL_CONTACT      -> placeCall(action)
        else                         -> applyVehicle(action)
        }
    }

    // -------------------------------------------------------------------------
    // Direct vehicle writes
    // -------------------------------------------------------------------------

    private fun applyVehicle(a: Action): ActionResult {
        if (a.type.gated) {
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
            ActionType.ADJUST_MEDIA_VOLUME       -> EVHardware.adjustMediaVolume(i)
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
            // Blocking for about a second: EVHardware reads the state three times before it
            // dares toggle it. The executor already runs off the main thread.
            ActionType.SET_ESC              -> EVHardware.setEsc(b)
            ActionType.SET_DROWSINESS       -> EVHardware.setDrowsiness(b)
            ActionType.SET_DROWSINESS_SENSITIVITY -> EVHardware.setDrowsinessSensitivity(i)
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
            // Glass: the value is a command in 0..7, not a position — see SaicVehicleControl.
            // A command outside that range is refused there rather than sent to be dropped.
            // The rule stores a state; the car takes a command. glassCommand() is where the
            // two meet, and it answers null when this car has no proof of which command is
            // which — which cannot happen here, because effectProven already refused the
            // action above. Belt and braces: a null reports "no write path" rather than
            // sending a command nobody established.
            ActionType.SET_WINDOWS          ->
                glassCommand(i)?.let { SaicVehicleControl.setAllWindows(it) }
            ActionType.SET_WINDOW_DRIVER    ->
                glassCommand(i)?.let { SaicVehicleControl.setWindow(SaicVehicleControl.Window.DRIVER, it) }
            ActionType.SET_WINDOW_PASSENGER ->
                glassCommand(i)?.let { SaicVehicleControl.setWindow(SaicVehicleControl.Window.PASSENGER, it) }
            ActionType.SET_WINDOW_REAR_LEFT ->
                glassCommand(i)?.let { SaicVehicleControl.setWindow(SaicVehicleControl.Window.REAR_LEFT, it) }
            ActionType.SET_WINDOW_REAR_RIGHT ->
                glassCommand(i)?.let { SaicVehicleControl.setWindow(SaicVehicleControl.Window.REAR_RIGHT, it) }
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
            ActionType.PAUSE_RADIO          -> SaicRadio.pause()
            ActionType.RADIO_NEXT_STATION   -> SaicRadio.nextStation()
            ActionType.RADIO_PREV_STATION   -> SaicRadio.previousStation()
            // Gated: applyVehicle has already refused it if the car is moving or its speed
            // could not be read. A radio screen is the only one of the family that takes the
            // driver's eyes rather than their ears.
            ActionType.OPEN_RADIO_SCREEN    -> SaicRadio.openScreen()
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
            // Three outcomes, not two: an unreadable tuner state is neither a success nor a
            // vehicle that refused, and applyVehicle's boolean cannot say which it was.
            ActionType.RADIO_PLAY_PAUSE,
            // A call is never retried — see placeCall.
            ActionType.CALL_NUMBER,
            ActionType.CALL_CONTACT,
            // TUNE_RADIO is a vehicle write, but it carries a frequency the driver typed, and
            // "103,5 FM" that parsed to nothing must be reported as that rather than as a radio
            // that refused. Its band half has three outcomes too: "already on that band" is a
            // success that sent nothing, and an unreadable band is not a refusal.
            ActionType.TUNE_RADIO -> null
            // Waiting between two actions is the engine's business — it is what runs them in
            // sequence. Reaching here would mean an action ran outside a rule.
            ActionType.DELAY -> null
        }
    }

    /**
     * The one tuner action: a band, a frequency, or both.
     *
     * A rule that names no frequency names a band, and that is a complete instruction —
     * `SaicRadio.selectBand` reads where the tuner is and moves it, which is the only form
     * **DAB** has. A rule that names a frequency tunes it, on the band the rule picked or, for
     * everything saved before the band existed, on the band the text implies.
     *
     * Text that names no station is unsupported, not an error: retrying "FM 250" three times
     * with backoff will not make it a station, and the history showing what was typed is what
     * lets the user find the typo.
     *
     * Playback follows [Action.flag], the editor's "enable radio" switch. It has to be
     * decided here rather than by a separate play action after the tune, because the vendor
     * service starts the radio from inside `tune` itself: switching the station always takes
     * the audio focus, so "do not play" is something EVHardware has to undo, not something a
     * caller can achieve by not asking for playback.
     */
    private fun tuneRadio(a: Action): ActionResult {
        // 0 is the band of every rule saved before the merge: it means "read the text".
        val band = a.number.takeIf { it != 0 }
        // DAB has no frequency to type, so the band alone is all a DAB rule can carry.
        if (band != null && (band == SaicRadio.BAND_DAB || a.text.isBlank())) return selectRadioBand(a, band)
        val station = RadioFrequency.parse(a.text, band)
            ?: return ActionResult(a.type, false, BridgeContract.VERDICT_UNSUPPORTED, "not a frequency: ${a.text}")
        val ok = SaicRadio.tune(station.band, station.frequencyKhz, andPlay = a.flag)
        return ActionResult(
            a.type,
            ok,
            if (ok) BridgeContract.VERDICT_ALLOWED else BridgeContract.VERDICT_ERROR,
            "${station.frequencyKhz} kHz"
        )
    }

    /**
     * Toggles the tuner, on the state the tuner reports — never on a guess.
     *
     * The three outcomes are three different things to write in the history, which is why
     * this does not go through the boolean write path. A state that could not be read is an
     * error rather than an unsupported action: the read fails the same way while the radio
     * service's bind has not landed yet, and that one is worth the engine's retry. What it is
     * not is a reason to send a command — [ActionType.PLAY_RADIO] and
     * [ActionType.PAUSE_RADIO] are there for a driver who knows which direction they want.
     */
    private fun radioPlayPause(a: Action): ActionResult = when (SaicRadio.playPause()) {
        SaicRadio.ToggleResult.PLAYED ->
            ActionResult(a.type, true, BridgeContract.VERDICT_ALLOWED, "playing")
        SaicRadio.ToggleResult.PAUSED ->
            ActionResult(a.type, true, BridgeContract.VERDICT_ALLOWED, "silenced")
        SaicRadio.ToggleResult.STATE_UNKNOWN ->
            ActionResult(a.type, false, BridgeContract.VERDICT_ERROR, "radio state unreadable — nothing sent")
        SaicRadio.ToggleResult.REFUSED ->
            ActionResult(a.type, false, BridgeContract.VERDICT_ERROR, "radio refused the command")
    }

    /**
     * Turns the rule's window state into the command this car was observed to obey.
     *
     * The catalogue deliberately does not know which of the eight commands opens and which
     * closes: that is a property of the car, recorded by `GlassProbe` into `GlassEvidence`.
     * Keeping it out of the rule is what lets a rule exported from one car run on another —
     * and it is what stops a rule asking for "7", a value the service accepts, drops, and
     * reports as applied.
     */
    private fun glassCommand(state: Int): Int? {
        val proof = GlassEvidence.proof ?: return null
        return if (state == VehicleEnums.WINDOW_OPEN) proof.openCommand else proof.closeCommand
    }

    /**
     * Puts the tuner on a band, reporting the three outcomes separately.
     *
     * "Already on that band" is a success that sent nothing, and the history should say so
     * rather than claim a switch. An unreadable band sends nothing at all — tuning without
     * knowing where the tuner is would move the driver off their station for an action that
     * may have been a no-op.
     */
    private fun selectRadioBand(a: Action, band: Int): ActionResult =
        when (SaicRadio.selectBand(band, andPlay = a.flag)) {
            SaicRadio.BandResult.SWITCHED ->
                ActionResult(a.type, true, BridgeContract.VERDICT_ALLOWED)
            SaicRadio.BandResult.ALREADY_ON_BAND ->
                ActionResult(a.type, true, BridgeContract.VERDICT_ALLOWED, "already on that band")
            SaicRadio.BandResult.STATE_UNKNOWN ->
                ActionResult(a.type, false, BridgeContract.VERDICT_ERROR, "radio band unreadable — nothing sent")
            SaicRadio.BandResult.UNSUPPORTED_BAND ->
                ActionResult(a.type, false, BridgeContract.VERDICT_UNSUPPORTED, "band $band")
            SaicRadio.BandResult.REFUSED ->
                ActionResult(a.type, false, BridgeContract.VERDICT_ERROR, "radio refused the band")
        }

    /**
     * Places a call through the car's hands-free stack, and **never lets it be retried**.
     *
     * Both failures deliberately report UNSUPPORTED rather than ERROR, because the engine
     * retries an error three times with backoff and neither failure is helped by that:
     *
     *  - text that contains no dialable character will not become a number on the second
     *    attempt, the same reasoning [tuneRadio] already applies to a frequency nobody can
     *    parse — and the history naming what was typed is what lets the user find the typo;
     *  - a refused `placeCall` is worse. The return value says the hands-free stack did not
     *    accept the request, not that the phone did not dial, and those are not the same
     *    thing. Retrying is how one rule places the same call three times to a driver who
     *    asked for it once. [com.evsuite.tasker.util.BtMessaging] refuses to retry a message
     *    on exactly this argument; a call deserves it at least as much, and it did not have
     *    it — reaching `applyVehicle` as a bare boolean, a refusal was an ERROR like any
     *    other.
     *
     * Digits, `+`, `*` and `#` only: anything else is not a number the car can dial.
     */
    private fun placeCall(a: Action): ActionResult {
        val number = a.text.filter { it.isDigit() || it in "+*#" }
        if (number.isBlank()) {
            return ActionResult(a.type, false, BridgeContract.VERDICT_UNSUPPORTED, "not a number: ${a.text}")
        }
        if (SaicPhone.placeCall(number)) {
            return ActionResult(a.type, true, BridgeContract.VERDICT_ALLOWED)
        }
        return ActionResult(
            a.type, false, BridgeContract.VERDICT_UNSUPPORTED,
            "the hands-free stack refused the call — not retried, it may already be ringing"
        )
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
     * Commands the source that owns the audio, through [SaicMediaPlayer].
     *
     * Not a media key: on this head unit that reaches Bluetooth or nothing, and a rule that
     * meant "next track" would change the audio source out from under the driver. The stored
     * value stays the Android key code so existing rules keep their meaning.
     */
    private fun mediaControl(a: Action): ActionResult {
        val code = a.number.toInt()
        val command = when (code) {
            MediaCommand.PLAY_PAUSE -> SaicMediaPlayer.Command.PLAY_PAUSE
            MediaCommand.NEXT -> SaicMediaPlayer.Command.NEXT
            MediaCommand.PREVIOUS -> SaicMediaPlayer.Command.PREVIOUS
            else -> return ActionResult(
                a.type, false, BridgeContract.VERDICT_UNSUPPORTED, "unknown media command: $code"
            )
        }
        return try {
            // False is a real answer here, not an error: it means nothing was playing that
            // could take the command. Reported as unsupported so the engine does not retry
            // three times with backoff — a silent car will still be silent on the third try.
            if (SaicMediaPlayer.command(command)) {
                ActionResult(a.type, true, BridgeContract.VERDICT_ALLOWED)
            } else {
                ActionResult(a.type, false, BridgeContract.VERDICT_UNSUPPORTED, "nothing playing")
            }
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
            // The rule says what its own silence means, and the history says which reading
            // was applied — "no answer" alone would leave the user to remember the setting.
            ConfirmPrompt.Answer.NO_ANSWER ->
                if (a.yesOnNoAnswer)
                    ActionResult(a.type, true, BridgeContract.VERDICT_ALLOWED, "no answer — continued")
                else
                    ActionResult(a.type, false, BridgeContract.VERDICT_DECLINED, "no answer")
            // Never the permissive reading: the question did not reach the driver, so there
            // is no silence to interpret.
            ConfirmPrompt.Answer.NOT_ASKED ->
                ActionResult(a.type, false, BridgeContract.VERDICT_DECLINED, "question not shown")
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
