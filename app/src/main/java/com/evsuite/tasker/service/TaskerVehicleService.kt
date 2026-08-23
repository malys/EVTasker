package com.evsuite.tasker.service

import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
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
import com.evsuite.tasker.util.Notifier
import com.evsuite.tasker.vehicle.BtOnboard
import com.evsuite.tasker.vehicle.BtTracker
import com.evsuite.tasker.vehicle.ProfileBridge
import com.evsuite.tasker.vehicle.RuleCycle
import com.evsuite.tasker.vehicle.VendorServices
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

    /** Last transition acted on, so a re-asserted ignition state does not re-run a cycle. */
    @Volatile
    private var lastTrigger: RuleTrigger? = null

    /**
     * Off the main thread: sampling reads the car and the Bluetooth stack over binder, and
     * this service runs in the same process as the UI.
     */
    private val onboardThread = HandlerThread("mg4-tasker-onboard")
    private lateinit var onboardHandler: Handler
    private val onboardSampler = object : Runnable {
        override fun run() {
            BtOnboard.sample(this@TaskerVehicleService)
            onboardHandler.postDelayed(this, ONBOARD_SAMPLE_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(Notifier.FOREGROUND_NOTIFICATION_ID, Notifier.buildForegroundNotification(this))
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
        }
        // No "EVProfile is also installed" notification here any more. It fired on every
        // service start on the cars where the two apps are meant to run together, and it said
        // less than the rule editor already does: the profile actions carry the coexistence
        // warning at the moment it matters, when one is picked.
        logEVProfilePresence()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        ignitionListener?.let { EVHardware.unregisterVehicleConditionListener(it) }
        btReceiver?.let { runCatching { unregisterReceiver(it) } }
        hardkeyReceiver?.let { runCatching { unregisterReceiver(it) } }
        onboardHandler.removeCallbacks(onboardSampler)
        onboardThread.quitSafely()
    }

    private fun startOnboardSampling() {
        onboardHandler.removeCallbacks(onboardSampler)
        onboardHandler.post(onboardSampler)
    }

    private fun stopOnboardSampling() {
        onboardHandler.removeCallbacks(onboardSampler)
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
                    BtOnboard.reset()
                    startOnboardSampling()
                } else {
                    stopOnboardSampling()
                }
                if (AppState.isAutomationEnabled(this)) {
                    AppLogger.i(TAG, "Ignition $state → evaluating ${trigger.name} rules")
                    thread(name = "mg4-tasker-cycle") { RuleCycle.run(this, trigger.name) }
                } else {
                    AppLogger.i(TAG, "Ignition $state but automation disabled — ignored")
                }
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
                if (AppState.isAutomationEnabled(this@TaskerVehicleService)) {
                    thread(name = "mg4-tasker-button-cycle") {
                        RuleCycle.run(
                            this@TaskerVehicleService,
                            RuleCycle.PHYSICAL_BUTTON,
                            event.readings()
                        )
                    }
                }
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
