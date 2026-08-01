package com.mg4.tasker.vehicle

import android.content.Context
import android.content.Intent
import android.util.Log
import com.mg4.hardware.MG4Hardware
import com.mg4.hardware.VehicleWriteGate
import com.mg4.hardware.model.DriveMode
import com.mg4.hardware.model.RegenLevel
import com.mg4.tasker.bridge.BridgeContract
import com.mg4.tasker.engine.ActionExecutor
import com.mg4.tasker.model.Action
import com.mg4.tasker.model.ActionResult
import com.mg4.hardware.catalog.ActionType
import com.mg4.tasker.util.Notifier
import com.mg4.tasker.util.Speaker

/**
 * Executes actions by calling MG4Hardware **directly** — MG4Tasker's own vehicle access,
 * no bridge. The 0 km/h [VehicleWriteGate] still applies (inside the MG4Hardware write
 * primitives); the verdict reported here mirrors that decision so the history can explain a
 * refusal.
 *
 * The one exception is [ActionType.APPLY_PROFILE]: applying an MG4Control *profile* needs
 * MG4Control, so it goes through the optional [profileBridge]. Everything else works with
 * MG4Control absent.
 */
class DirectExecutor(
    private val context: Context,
    private val profileBridge: ProfileBridge?
) : ActionExecutor {

    override fun execute(action: Action): ActionResult = when (action.type) {
        ActionType.APPLY_PROFILE     -> applyProfile(action)
        ActionType.LAUNCH_APP        -> launchApp(action)
        ActionType.SHOW_NOTIFICATION -> notify(action)
        ActionType.SPEAK_TEXT        -> speak(action)
        else                         -> applyVehicle(action)
    }

    // -------------------------------------------------------------------------
    // Direct vehicle writes
    // -------------------------------------------------------------------------

    private fun applyVehicle(a: Action): ActionResult {
        val gated = a.type.gated
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
            ActionType.SET_SEAT_HEAT_LEFT        -> MG4Hardware.setSeatHeatLeft(i)
            ActionType.SET_SEAT_HEAT_RIGHT       -> MG4Hardware.setSeatHeatRight(i)
            ActionType.SET_STEERING_HEAT         -> MG4Hardware.setSteeringHeat(b)
            ActionType.SET_MEDIA_VOLUME          -> MG4Hardware.setMediaVolume(i)
            ActionType.SET_SCREEN_BRIGHTNESS     -> MG4Hardware.setScreenBrightnessPercent(i)
            ActionType.SET_AUDIO_BALANCE         -> MG4Hardware.setAudioBalance(i)
            ActionType.SET_AUDIO_FADER           -> MG4Hardware.setAudioFader(i)
            ActionType.SET_TONE_CONTROL          -> MG4Hardware.setToneControl(i)
            ActionType.SET_BOSE_SOUND_TYPE       -> MG4Hardware.setBoseSoundType(i)
            ActionType.SET_3D_EFFECT             -> MG4Hardware.set3dEffectType(i)
            ActionType.SET_SPEED_VOLUME          -> MG4Hardware.setSpeedVolumeLevel(i)
            ActionType.SET_LAS_WARNING_SOUND     -> MG4Hardware.setLasWarningSound(b)
            ActionType.SET_LAS_WARNING_VIBRATION -> MG4Hardware.setLasWarningVibration(b)
            // Road behaviour — gated (already verdict-checked in applyVehicle).
            ActionType.SET_DRIVE_MODE       -> MG4Hardware.setDriveMode(DriveMode.fromValue(i))
            ActionType.SET_REGEN_LEVEL      -> MG4Hardware.setRegenLevel(RegenLevel.fromValue(i))
            ActionType.SET_ONE_PEDAL        -> MG4Hardware.setOnePedal(b)
            ActionType.SET_ENERGY_SAVING    -> MG4Hardware.setEnergySavingMode(b)
            ActionType.SET_OVERSPEED_ALARM  -> MG4Hardware.setOverspeedAlarm(b)
            ActionType.SET_SPEED_LIMIT_TONE -> MG4Hardware.setSpeedLimitTone(b)
            ActionType.SET_SOUND_WARNING    -> MG4Hardware.setSoundWarning(b)
            ActionType.SET_AEB_ENABLED      -> MG4Hardware.setAebEnabled(b)
            ActionType.SET_AEB_MODE         -> MG4Hardware.setAebMode(i)
            ActionType.SET_AEB_SENSITIVITY  -> MG4Hardware.setAebSensitivity(i)
            ActionType.SET_ELK_MODE         -> MG4Hardware.setElkMode(i)
            ActionType.SET_ELK_SENSITIVITY  -> MG4Hardware.setElkSensitivity(i)
            ActionType.SET_TSR              -> MG4Hardware.setTsrMode(b)
            ActionType.SET_ACC_TJA_MODE     -> MG4Hardware.setAccTjaMode(i)
            ActionType.SET_LIMITER_MODE     -> MG4Hardware.setSpeedLimiterMode(i)
            // Not vehicle writes — handled by execute() before it ever gets here.
            ActionType.APPLY_PROFILE,
            ActionType.LAUNCH_APP,
            ActionType.SHOW_NOTIFICATION,
            ActionType.SPEAK_TEXT -> null
        }
    }

    private fun gateVerdict(): String = when (VehicleWriteGate.decide(MG4Hardware.getVehicleSpeedKmh())) {
        VehicleWriteGate.Decision.ALLOWED               -> BridgeContract.VERDICT_ALLOWED
        VehicleWriteGate.Decision.REFUSED_MOVING        -> BridgeContract.VERDICT_MOVING
        VehicleWriteGate.Decision.REFUSED_UNKNOWN_SPEED -> BridgeContract.VERDICT_UNKNOWN_SPEED
    }

    // -------------------------------------------------------------------------
    // Profile (needs MG4Control) + local actions
    // -------------------------------------------------------------------------

    private fun applyProfile(a: Action): ActionResult {
        val bridge = profileBridge
            ?: return ActionResult(a.type, false, BridgeContract.VERDICT_NO_BRIDGE, "MG4Control not installed")
        if (a.text.isBlank()) return ActionResult(a.type, false, BridgeContract.VERDICT_UNSUPPORTED, "no profile selected")
        val res = bridge.applyProfile(a.text)
            ?: return ActionResult(a.type, false, BridgeContract.VERDICT_NO_BRIDGE, "MG4Control unreachable")
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
            Log.w("MG4Tasker.Exec", "launchApp(${a.text}): ${e.message}")
            ActionResult(a.type, false, BridgeContract.VERDICT_ERROR, e.message)
        }
    }

    /**
     * A blocked channel makes `notify()` a no-op the platform reports nothing about. Claiming
     * ALLOWED there is how "the message action does not work" became invisible in the
     * history: the rule said applied, the driver saw nothing, and the two never met.
     */
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
