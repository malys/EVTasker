package com.evsuite.tasker.service

import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.evsuite.hardware.AppLogger
import com.evsuite.hardware.EVHardware
import com.evsuite.hardware.PhysicalButtonEventDecoder
import com.evsuite.hardware.VehicleWriteGate
import com.evsuite.tasker.model.RuleTrigger
import com.evsuite.tasker.store.AppState
import com.evsuite.tasker.util.BtDevices
import com.evsuite.tasker.util.BtMessaging
import com.evsuite.tasker.util.CarLocation
import com.evsuite.tasker.util.DriveClock
import com.evsuite.tasker.util.Notifier
import com.evsuite.tasker.util.ParkTriggerDetector
import com.evsuite.tasker.vehicle.BtOnboard
import com.evsuite.tasker.vehicle.BtTracker
import com.evsuite.tasker.vehicle.ProfileBridge
import com.evsuite.tasker.vehicle.RuleCycle
import com.evsuite.tasker.vehicle.VendorServices
import com.evsuite.hardware.catalog.ValueKind
import com.evsuite.tasker.store.RuleStore
import kotlin.concurrent.thread

/**
 * EVTasker's own persistent vehicle service — this is what makes the app independent.
 *
 * It initialises EVHardware, listens for ignition directly (no EVProfile broadcast), and
 * on each RUN evaluates the rules and applies actions straight through EVHardware. It also
 * tracks connected Bluetooth devices for the context conditions.
 *
 * EVProfile is optional. When it is present the service warns once that two apps can write
 * the car (accepted concurrency risk), and the "apply profile" action becomes usable.
 */
class TaskerVehicleService : Service() {

    companion object {
        private const val TAG = "EVTasker.Vehicle"
        /**
         * How often the moving car is asked which phones are still with it.
         *
         * Two binder reads (speed, connected devices) — cheap enough at this pace that the
         * drive is sampled several times before the first junction, and rare enough that a
         * long motorway leg costs nothing measurable.
         */
        private const val ONBOARD_SAMPLE_MS = 15_000L
        /** Gear has no portable push callback on every supported generation. Poll only in RUN. */
        private const val PARK_SAMPLE_MS = 500L
        private const val HARDKEY_ACTION = "com.saic.keyevent.hardkey.report"
        private const val SYSTEMUI_HARDKEY_ACTION = "com.android.systemui.ACTION_HARD_KEY_EVENT"
        const val HARDKEY_PERMISSION = "com.evsuite.tasker.permission.RECEIVE_HARDKEY"

        /**
         * Whether the ignition listener is live, for the diagnostic screen.
         *
         * Read from a flag rather than from ActivityManager: since API 26 the running-service
         * list only ever contains our own services anyway, and a flag set in the lifecycle
         * callbacks cannot report a service the system has already torn down.
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TaskerVehicleService::class.java))
        }
    }

    private var ignitionListener: ((Int) -> Unit)? = null
    private var btReceiver: BroadcastReceiver? = null
    private var hardkeyReceiver: BroadcastReceiver? = null
    private val physicalButtons = PhysicalButtonEventDecoder()

    /** Last ignition transition acted on, so a re-asserted state does not re-run a cycle. */
    @Volatile
    private var lastTrigger: RuleTrigger? = null

    private val parkTrigger = ParkTriggerDetector()

    /** Stops an in-flight park sample from scheduling itself again after ignition-off. */
    @Volatile
    private var vehicleRunning = false

    /**
     * Off the main thread: sampling reads the car and the Bluetooth stack over binder, and
     * this service runs in the same process as the UI.
     */
    private val onboardThread = HandlerThread("mg4-tasker-onboard")
    private lateinit var onboardHandler: Handler
    private val onboardSampler = object : Runnable {
        override fun run() {
            BtOnboard.sample(this@TaskerVehicleService)
            // Same watchdog the Bluetooth sampling gets: a GPS subscription placed once at
            // boot dies with a provider that was off at the time, and nothing else would ever
            // ask again — the fix would stay null for the rest of the drive.
            CarLocation.ensureTracking(applicationContext)
            onboardHandler.postDelayed(this, ONBOARD_SAMPLE_MS)
        }
    }

    private val parkSampler = object : Runnable {
        override fun run() {
            if (!vehicleRunning) return
            if (parkTrigger.sample(EVHardware.isVehicleInPark())) {
                runRulesFor(RuleTrigger.GEAR_PARK, "Gear entered P")
            }
            if (vehicleRunning) onboardHandler.postDelayed(this, PARK_SAMPLE_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.startInForeground(this)
        AppLogger.i(TAG, "onCreate — initialising vehicle layer")

        // The application context, captured deliberately. `getString` here would resolve on
        // the Service, and messageProvider is a field on a singleton in the shared library —
        // it would hold this Service, and everything it references, for the life of the
        // process. START_STICKY means the Service is destroyed and recreated; each
        // generation would be retained.
        val strings = applicationContext
        VehicleWriteGate.messageProvider = { decision ->
            when (decision) {
                VehicleWriteGate.Decision.REFUSED_MOVING        -> strings.getString(com.evsuite.tasker.R.string.verdict_moving)
                VehicleWriteGate.Decision.REFUSED_UNKNOWN_SPEED -> strings.getString(com.evsuite.tasker.R.string.verdict_unknown_speed)
                VehicleWriteGate.Decision.ALLOWED               -> null
            }
        }
        EVHardware.init(applicationContext)
        // The vendor audio helper is a separate bind, and without it every balance / fader /
        // tone / Bose / 3D / speed-volume write returns false with nothing in the log. It was
        // simply never requested here, which is why those six actions had never once worked
        // from a rule. A no-op on firmware that has no such helper.
        EVHardware.initAudio(applicationContext)
        // The vendor climate/charging/radio/telephony services, bound here so the first
        // ignition cycle already has them.
        VendorServices.connect(applicationContext)
        onboardThread.start()
        onboardHandler = Handler(onboardThread.looper)
        // The Bluetooth profile proxies answer on a callback, so the first question asked of
        // them cannot be answered. Asked for here, they are ready long before the first rule
        // cycle — which is the cycle that decides whose phone is on board.
        BtDevices.warmUp(applicationContext)
        // The message profile answers on the same kind of callback, and the rule that sends a
        // message is as unwilling to wait for it as the one that reads which phone is aboard.
        BtMessaging.warmUp(applicationContext)
        // Position, on the same footing as the Bluetooth proxies: a rule cycle reads a cached
        // fix and cannot wait for a lock, so the cache has to already be full when it runs.
        CarLocation.startTracking(applicationContext)
        registerBtReceiver()
        registerHardkeyReceiver()
        registerIgnitionListener()
        // START_STICKY means this service can be recreated mid-drive. Waiting for the next
        // IGNITION_ON would then leave the whole drive unsampled, and every "phone on board"
        // rule unevaluable until the car had been switched off and on again.
        // IgnitionState, not CarIgnitionItem: getCurrentIgnitionState() answers on the AAOS
        // scale, where 2 means OFF — the very value CarIgnitionItem calls RUN. The mistake
        // was invisible while the property was unreadable on SWI68 and the comparison could
        // only ever be false; now that the reader falls back to Katman5, it would have
        // started sampling on a car that had just been switched off.
        if (EVHardware.getCurrentIgnitionState() == EVHardware.IgnitionState.ON) {
            startOnboardSampling()
            startParkSampling()
        }
        // No "EVProfile is also installed" notification here any more. It fired on every
        // service start on the cars where the two apps are meant to run together, and it said
        // less than the rule editor already does: the profile actions carry the coexistence
        // warning at the moment it matters, when one is picked.
        logEVProfilePresence()
        isRunning = true
    }

    /**
     * Re-asserted on every start, because a permission granted after boot changes what this
     * service may hold. MainActivity starts it again once position is granted: the foreground
     * types are re-declared with `location` in them, and the subscription that fills the fix
     * cache is armed without waiting for the next ignition.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifier.startInForeground(this)
        CarLocation.startTracking(applicationContext)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        ignitionListener?.let { EVHardware.unregisterVehicleConditionListener(it) }
        btReceiver?.let { runCatching { unregisterReceiver(it) } }
        hardkeyReceiver?.let { runCatching { unregisterReceiver(it) } }
        CarLocation.stopTracking(applicationContext)
        vehicleRunning = false
        onboardHandler.removeCallbacks(onboardSampler)
        onboardHandler.removeCallbacks(parkSampler)
        onboardThread.quitSafely()
    }

    private fun startOnboardSampling() {
        onboardHandler.removeCallbacks(onboardSampler)
        onboardHandler.post(onboardSampler)
    }

    private fun stopOnboardSampling() {
        onboardHandler.removeCallbacks(onboardSampler)
    }

    private fun startParkSampling() {
        if (vehicleRunning) return
        vehicleRunning = true
        onboardHandler.removeCallbacks(parkSampler)
        onboardHandler.post {
            parkTrigger.reset()
            if (vehicleRunning) parkSampler.run()
        }
    }

    private fun stopParkSampling() {
        if (!vehicleRunning) return
        vehicleRunning = false
        onboardHandler.removeCallbacks(parkSampler)
        // Keep detector mutation on its sampling thread.
        onboardHandler.post { parkTrigger.reset() }
    }

    private fun runRulesFor(trigger: RuleTrigger, event: String) {
        if (AppState.isAutomationEnabled(this)) {
            AppLogger.i(TAG, "$event → evaluating ${trigger.name} rules")
            thread(name = "mg4-tasker-cycle") { RuleCycle.run(this, trigger.name) }
        } else {
            AppLogger.i(TAG, "$event but automation disabled — ignored")
        }
    }

    /**
     * One listener, both transitions.
     *
     * The stream already carried OFF; the service simply stopped reading at RUN. Acting on
     * the other end costs no second listener, no bind and no polling — which is why the
     * switch-off trigger is free where a geofence or a battery threshold would not be.
     *
     * Repeats are ignored. The vehicle re-asserts its ignition state, and a rule that locked
     * the doors must not run four times because the bus said OFF four times.
     */
    private fun registerIgnitionListener() {
        val listener: (Int) -> Unit = { state ->
            // Park reads exist only while the vehicle is genuinely running. ACC/CRANK are
            // neither a new ignition-rule event nor permission to keep polling the gear.
            if (state == EVHardware.CarIgnitionItem.RUN) startParkSampling()
            else stopParkSampling()

            val trigger = when (state) {
                EVHardware.CarIgnitionItem.RUN -> RuleTrigger.IGNITION_ON
                EVHardware.CarIgnitionItem.OFF -> RuleTrigger.IGNITION_OFF
                else -> null
            }
            if (trigger != null && trigger != lastTrigger) {
                lastTrigger = trigger
                // The onboard set is cleared when the next drive starts, not when this one
                // ends: the IGNITION_OFF rules are precisely the ones that want to know
                // whose phone made the trip, and they evaluate on a thread of their own.
                if (trigger == RuleTrigger.IGNITION_ON) {
                    // Same transition, no second listener: the drive's clock starts where the
                    // onboard sampling does.
                    DriveClock.start()
                    BtOnboard.reset()
                    startOnboardSampling()
                } else {
                    stopOnboardSampling()
                }
                runRulesFor(trigger, "Ignition $state")
            }
        }
        ignitionListener = listener
        EVHardware.registerVehicleConditionListener(listener)
    }

    private fun registerBtReceiver() {
        btReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                @Suppress("DEPRECATION")
                val mac = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)?.address ?: return
                when (intent.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> BtTracker.add(mac)
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> BtTracker.remove(mac)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        ContextCompat.registerReceiver(this, btReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** A single press waiting to see whether a second one follows, per button. */
    private val pendingShortPresses =
        mutableMapOf<PhysicalButtonEventDecoder.Button, Runnable>()

    private val buttonHandler = Handler(Looper.getMainLooper())

    /**
     * Runs the rules a press addresses — after a pause, when a double tap is still possible.
     *
     * The press is released before anyone can know whether a second one is coming, so a
     * double tap necessarily produces a SHORT and then a DOUBLE. Firing both would run the
     * single-press rule every time the driver taps twice, which is not what either rule says.
     *
     * So a SHORT waits out the double-tap window — but **only when a rule is actually armed on
     * that button's double tap**. Delaying every press to serve a rule nobody wrote would make
     * every button in the car feel slow, and the delay is only worth its cost where it prevents
     * a wrong rule from running.
     */
    private fun dispatchButton(event: PhysicalButtonEventDecoder.Event) {
        if (event.press == PhysicalButtonEventDecoder.Press.DOUBLE) {
            pendingShortPresses.remove(event.button)?.let { buttonHandler.removeCallbacks(it) }
            runButtonCycle(event)
            return
        }
        if (event.press == PhysicalButtonEventDecoder.Press.SHORT && isDoubleTapArmed(event.button)) {
            pendingShortPresses.remove(event.button)?.let { buttonHandler.removeCallbacks(it) }
            val pending = Runnable {
                pendingShortPresses.remove(event.button)
                runButtonCycle(event)
            }
            pendingShortPresses[event.button] = pending
            buttonHandler.postDelayed(pending, PhysicalButtonEventDecoder.DOUBLE_TAP_MS)
            return
        }
        runButtonCycle(event)
    }

    /**
     * Whether any enabled rule waits on a double tap of [button].
     *
     * Read per press rather than cached: rules change while the service lives, and a stale
     * answer here either delays a press for nothing or defeats a rule the user just wrote.
     */
    private fun isDoubleTapArmed(button: PhysicalButtonEventDecoder.Button): Boolean =
        RuleStore(this).getAll().any { rule ->
            rule.enabled && rule.branches.any { branch ->
                branch.conditions.any {
                    it.type.spec.kind == ValueKind.PHYSICAL_BUTTON &&
                        it.text == PhysicalButtonEventDecoder.Press.DOUBLE.name &&
                        it.number.toInt() in button.codes
                }
            }
        }

    private fun runButtonCycle(event: PhysicalButtonEventDecoder.Event) {
        thread(name = "mg4-tasker-button-cycle") {
            RuleCycle.run(this, RuleCycle.PHYSICAL_BUTTON, event.readings())
        }
    }

    /**
     * The R69 OEM apps read these exact extras from this broadcast. The action itself is
     * unprotected, so the receiver requires a signature permission from its sender.
     */
    private fun registerHardkeyReceiver() {
        hardkeyReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val keyCode = intent.getIntExtra("android.intent.extra.hardkey.keycode", -1)
                    .takeIf { it >= 0 }
                    ?: intent.getIntExtra("KEY_CODE", -1).takeIf { it >= 0 }
                    ?: intent.getIntExtra("keycode", -1).takeIf { it >= 0 }
                    ?: intent.getIntExtra("keyCode", -1)
                val down = intent.getBooleanExtra("android.intent.extra.hardkey.down", false) ||
                    intent.getBooleanExtra("DOWN", false) ||
                    intent.getBooleanExtra("down", false)
                val longPress = intent.getBooleanExtra("android.intent.extra.hardkey.longpress", false) ||
                    intent.getBooleanExtra("LONG_PRESS", false) ||
                    intent.getBooleanExtra("longpress", false)
                val event = physicalButtons.accept(keyCode, down, longPress) ?: return
                AppLogger.i(TAG, "Physical button ${event.button} ${event.press}")
                if (AppState.isAutomationEnabled(this@TaskerVehicleService)) dispatchButton(event)
            }
        }
        ContextCompat.registerReceiver(
            this,
            hardkeyReceiver,
            IntentFilter().apply { addAction(HARDKEY_ACTION); addAction(SYSTEMUI_HARDKEY_ACTION) },
            HARDKEY_PERMISSION,
            null,
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    /** Kept in the log — the exported debug report still has to say which apps share the car. */
    private fun logEVProfilePresence() {
        val pkg = ProfileBridge.installedPackage(this) ?: return
        AppLogger.i(TAG, "EVProfile present ($pkg) — both apps can write the vehicle")
    }
}
