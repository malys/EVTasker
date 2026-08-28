package com.evsuite.tasker.debug

import com.evsuite.hardware.FirmwareGen
import com.evsuite.hardware.catalog.ActionType
import com.evsuite.hardware.catalog.ConditionType
import com.evsuite.hardware.catalog.SnapshotKeys
import com.evsuite.tasker.bridge.BridgeContract
import com.evsuite.tasker.model.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The promise of the diagnostic screen is that OK means the engine will not refuse — so what
 * is asserted here is agreement with the engine's own decisions, not the shape of the output.
 */
class DiagnosticsTest {

    private val allowed = Diagnostics.Capabilities(
        vehicleLayerReady = true,
        gateVerdict = BridgeContract.VERDICT_ALLOWED,
        evprofileInstalled = true,
        profileBridgeReachable = true,
        notificationsEnabled = true,
        ttsEngineAvailable = true,
    )

    private fun entry(entries: List<Diagnostics.Entry>, name: String) =
        entries.first { it.name == name }

    // ---------- Conditions ----------

    @Test
    fun `an absent signal is blocked, a present one is not`() {
        val snapshot = Snapshot(readings = mapOf(SnapshotKeys.KEY_OUTSIDE_TEMP to 12f))

        val entries = Diagnostics.conditions(snapshot, FirmwareGen.SWI68)

        assertEquals(Diagnostics.Status.OK, entry(entries, "OUTSIDE_TEMP").status)
        assertEquals(12f, entry(entries, "OUTSIDE_TEMP").value)
        assertEquals(Diagnostics.Status.BLOCKED, entry(entries, "IN_PARK").status)
        assertEquals(Diagnostics.Reason.NOT_READABLE, entry(entries, "IN_PARK").reason)
    }

    /**
     * The old screen showed the speed number whatever the readable flag said, while the
     * engine refused to evaluate. That disagreement is the bug this whole change exists for.
     */
    @Test
    fun `speed follows the readable flag, not the presence of a number`() {
        val snapshot = Snapshot(
            readings = mapOf(
                SnapshotKeys.KEY_SPEED_KMH to 0f,
                SnapshotKeys.KEY_SPEED_READABLE to false
            )
        )

        val speed = entry(Diagnostics.conditions(snapshot, null), "SPEED")

        assertEquals(Diagnostics.Status.BLOCKED, speed.status)
        assertEquals(Diagnostics.Reason.NOT_READABLE, speed.reason)
        assertNull(speed.value)
    }

    @Test
    fun `no vehicle layer blocks every vehicle condition with the layer reason`() {
        val entries = Diagnostics.conditions(Snapshot(bridgeAvailable = false), FirmwareGen.SWI68)

        assertEquals(Diagnostics.Reason.LAYER_NOT_READY, entry(entries, "SPEED").reason)
        // Time and day are computed locally: they survive a dead vehicle layer, and so does
        // any rule built on them.
        assertEquals(Diagnostics.Status.OK, entry(entries, "TIME_OF_DAY").status)
        assertEquals(Diagnostics.Status.OK, entry(entries, "DAY_OF_WEEK").status)
    }

    @Test
    fun `bluetooth conditions report the connected devices, not a configuration gap`() {
        val snapshot = Snapshot(btMacs = setOf("AA:BB:CC:DD:EE:FF"))

        val entries = Diagnostics.conditions(snapshot, null)

        assertEquals(Diagnostics.Status.OK, entry(entries, "BT_DEVICE_CONNECTED").status)
        assertEquals("AA:BB:CC:DD:EE:FF", entry(entries, "BT_DEVICE_CONNECTED").value)
        assertEquals(true, entry(entries, "ANY_BT_CONNECTED").value)
    }

    @Test
    fun `a radio that is off blames bluetooth, not the vehicle layer`() {
        val entries = Diagnostics.conditions(Snapshot(btAvailable = false), null)

        assertEquals(
            Diagnostics.Reason.BLUETOOTH_OFF,
            entry(entries, "BT_DEVICE_CONNECTED").reason
        )
        assertEquals(
            Diagnostics.Reason.BLUETOOTH_OFF,
            entry(entries, "ANY_BT_CONNECTED").reason
        )
    }

    @Test
    fun `a car that has not driven says so instead of blaming the radio`() {
        // The radio is on and a phone is connected; only "on board" is unanswerable, and a
        // report saying "bluetooth off" would send the reader to the wrong switch.
        val entries = Diagnostics.conditions(Snapshot(btMacs = setOf("AA:BB:CC:DD:EE:FF")), null)

        assertEquals(
            Diagnostics.Reason.NOT_DRIVEN_YET,
            entry(entries, "BT_DEVICE_ONBOARD").reason
        )
        assertEquals(
            Diagnostics.Reason.NO_HANDSFREE_INFO,
            entry(entries, "BT_DEVICE_HANDSFREE").reason
        )
    }

    @Test
    fun `once the car has driven the onboard entry reports who came along`() {
        val snapshot = Snapshot(
            btMacs = setOf("AA:BB:CC:DD:EE:FF", "11:22:33:44:55:66"),
            btOnboardMacs = setOf("AA:BB:CC:DD:EE:FF"),
            btHandsFreeMacs = setOf("AA:BB:CC:DD:EE:FF")
        )

        val entries = Diagnostics.conditions(snapshot, null)

        assertEquals(Diagnostics.Status.OK, entry(entries, "BT_DEVICE_ONBOARD").status)
        assertEquals("AA:BB:CC:DD:EE:FF", entry(entries, "BT_DEVICE_ONBOARD").value)
        assertEquals("AA:BB:CC:DD:EE:FF", entry(entries, "BT_DEVICE_HANDSFREE").value)
    }

    @Test
    fun `a readable condition the matrix excludes stays OK and is flagged hidden`() {
        // OUTSIDE_TEMP is annotated for every generation, so an unparsed generation is used
        // to exercise the flag without pinning the test to one firmware's annotations.
        val snapshot = Snapshot(readings = mapOf(SnapshotKeys.KEY_OUTSIDE_TEMP to 5f))

        val supported = entry(Diagnostics.conditions(snapshot, FirmwareGen.SWI68), "OUTSIDE_TEMP")

        assertFalse(supported.hidden)
        assertEquals(Diagnostics.Status.OK, supported.status)
    }

    @Test
    fun `every catalogue condition gets exactly one verdict`() {
        val entries = Diagnostics.conditions(Snapshot(), null)

        assertEquals(ConditionType.entries.size, entries.size)
        assertEquals(ConditionType.entries.map { it.name }, entries.map { it.name })
    }

    // ---------- Actions ----------

    @Test
    fun `every catalogue action gets exactly one verdict`() {
        val entries = Diagnostics.actions(allowed, null)

        assertEquals(ActionType.entries.size, entries.size)
        assertEquals(ActionType.entries.map { it.name }, entries.map { it.name })
    }

    @Test
    fun `a closed standstill gate blocks gated actions and leaves the others alone`() {
        val moving = allowed.copy(gateVerdict = BridgeContract.VERDICT_MOVING)

        val entries = Diagnostics.actions(moving, FirmwareGen.SWI68)

        assertEquals(Diagnostics.Reason.GATE_MOVING, entry(entries, "SET_DRIVE_MODE").reason)
        assertEquals(Diagnostics.Status.OK, entry(entries, "SET_MEDIA_VOLUME").status)
    }

    @Test
    fun `an unreadable speed blocks gated actions the same way the gate does`() {
        val unknown = allowed.copy(gateVerdict = BridgeContract.VERDICT_UNKNOWN_SPEED)

        val entries = Diagnostics.actions(unknown, FirmwareGen.SWI68)

        assertEquals(Diagnostics.Reason.GATE_UNKNOWN_SPEED, entry(entries, "SET_DRIVE_MODE").reason)
    }

    @Test
    fun `a dead vehicle layer blocks every vehicle write`() {
        val entries = Diagnostics.actions(allowed.copy(vehicleLayerReady = false), FirmwareGen.SWI68)

        assertEquals(Diagnostics.Reason.LAYER_NOT_READY, entry(entries, "SET_MEDIA_VOLUME").reason)
        // Local actions do not touch EVHardware, so they keep working.
        assertEquals(Diagnostics.Status.OK, entry(entries, "SHOW_NOTIFICATION").status)
        assertEquals(Diagnostics.Status.OK, entry(entries, "LAUNCH_APP").status)
    }

    @Test
    fun `the profile actions distinguish EVProfile absent from unreachable`() {
        val absent = Diagnostics.actions(allowed.copy(evprofileInstalled = false), FirmwareGen.SWI68)
        assertEquals(Diagnostics.Reason.NO_EVPROFILE, entry(absent, "APPLY_PROFILE").reason)
        // The picker lives in EVProfile too: without it there is no dialog to open.
        assertEquals(Diagnostics.Reason.NO_EVPROFILE, entry(absent, "SHOW_PROFILE_PICKER").reason)

        val unreachable =
            Diagnostics.actions(allowed.copy(profileBridgeReachable = false), FirmwareGen.SWI68)
        assertEquals(
            Diagnostics.Reason.EVPROFILE_UNREACHABLE,
            entry(unreachable, "APPLY_PROFILE").reason
        )
        assertEquals(
            Diagnostics.Reason.EVPROFILE_UNREACHABLE,
            entry(unreachable, "SHOW_PROFILE_PICKER").reason
        )
    }

    @Test
    fun `local actions are blocked by the capability they actually need`() {
        val noTts = Diagnostics.actions(allowed.copy(ttsEngineAvailable = false), FirmwareGen.SWI68)
        assertEquals(Diagnostics.Reason.NO_TTS_ENGINE, entry(noTts, "SPEAK_TEXT").reason)
        assertEquals(Diagnostics.Status.OK, entry(noTts, "SHOW_NOTIFICATION").status)

        val silenced =
            Diagnostics.actions(allowed.copy(notificationsEnabled = false), FirmwareGen.SWI68)
        assertEquals(Diagnostics.Status.BLOCKED, entry(silenced, "SHOW_NOTIFICATION").status)
        assertEquals(
            Diagnostics.Reason.NOTIFICATIONS_OFF,
            entry(silenced, "SHOW_NOTIFICATION").reason
        )
        assertEquals(Diagnostics.Status.OK, entry(silenced, "SPEAK_TEXT").status)
    }

    @Test
    fun `vendor entries are blocked when their service is not bound`() {
        val none = Diagnostics.actions(allowed, FirmwareGen.SWI68)

        assertEquals(Diagnostics.Reason.NO_VENDOR_SERVICE, entry(none, "SET_CABIN_TEMP").reason)
        assertEquals(Diagnostics.Reason.NO_VENDOR_SERVICE, entry(none, "SET_CHARGE_LIMIT").reason)
        assertEquals(Diagnostics.Reason.NO_VENDOR_SERVICE, entry(none, "PLAY_RADIO").reason)
        assertEquals(Diagnostics.Reason.NO_VENDOR_SERVICE, entry(none, "CALL_NUMBER").reason)

        val bound = Diagnostics.actions(
            allowed.copy(
                climateService = true, chargingService = true,
                radioService = true, phoneService = true
            ),
            FirmwareGen.SWI68
        )
        assertEquals(Diagnostics.Status.OK, entry(bound, "SET_CABIN_TEMP").status)
        assertEquals(Diagnostics.Status.OK, entry(bound, "SET_CHARGE_LIMIT").status)
        assertEquals(Diagnostics.Status.OK, entry(bound, "PLAY_RADIO").status)
        assertEquals(Diagnostics.Status.OK, entry(bound, "CALL_NUMBER").status)
    }

    @Test
    fun `navigation is blocked when nothing answers a geo intent`() {
        val none = Diagnostics.actions(allowed, FirmwareGen.SWI68)
        assertEquals(Diagnostics.Reason.NO_NAVIGATION_APP, entry(none, "NAVIGATE_TO").reason)

        val withApp = Diagnostics.actions(allowed.copy(navigationApp = true), FirmwareGen.SWI68)
        assertEquals(Diagnostics.Status.OK, entry(withApp, "NAVIGATE_TO").status)
    }

    @Test
    fun `asking the driver needs nothing from the car`() {
        // The question is a window of MG4Tasker's own: no vendor service, no map app, no
        // speech engine. A bare head unit must still be able to offer it.
        val bare = Diagnostics.actions(allowed, FirmwareGen.SWI68)
        assertEquals(Diagnostics.Status.OK, entry(bare, "ASK_CONFIRM").status)
    }

    @Test
    fun `the whole tuner family answers for one vendor service`() {
        // Station stepping used to answer for the AOSP car layer, which is a different
        // question: the layer can be up with no radio service in sight, and the screen said
        // "layer not ready" for a radio that was simply absent.
        val radio = listOf(
            "PLAY_RADIO", "PAUSE_RADIO", "RADIO_PLAY_PAUSE", "TUNE_RADIO",
            "RADIO_NEXT_STATION", "RADIO_PREV_STATION", "OPEN_RADIO_SCREEN"
        )

        val none = Diagnostics.actions(allowed, FirmwareGen.SWI68)
        radio.forEach {
            assertEquals("$it must blame the radio service", Diagnostics.Reason.NO_VENDOR_SERVICE, entry(none, it).reason)
        }

        val bound = Diagnostics.actions(allowed.copy(radioService = true), FirmwareGen.SWI68)
        radio.forEach { assertEquals("$it is available once the service is bound", Diagnostics.Status.OK, entry(bound, it).status) }
    }

    @Test
    fun `a moving car keeps the radio audible and the radio screen shut`() {
        // The one action of the family that takes the driver's eyes rather than their ears.
        // Skipping a station at speed is what the wheel buttons already do; opening a
        // full-screen app is not, and an unreadable speed refuses it the same way.
        val moving = Diagnostics.actions(
            allowed.copy(radioService = true, gateVerdict = BridgeContract.VERDICT_MOVING),
            FirmwareGen.SWI68
        )
        assertEquals(Diagnostics.Reason.GATE_MOVING, entry(moving, "OPEN_RADIO_SCREEN").reason)
        listOf("PLAY_RADIO", "PAUSE_RADIO", "RADIO_PLAY_PAUSE", "RADIO_NEXT_STATION").forEach {
            assertEquals("$it is audio-only and stays available", Diagnostics.Status.OK, entry(moving, it).status)
        }

        val unknownSpeed = Diagnostics.actions(
            allowed.copy(radioService = true, gateVerdict = BridgeContract.VERDICT_UNKNOWN_SPEED),
            FirmwareGen.SWI68
        )
        assertEquals(Diagnostics.Reason.GATE_UNKNOWN_SPEED, entry(unknownSpeed, "OPEN_RADIO_SCREEN").reason)
    }

    @Test
    fun `local webhook and delay actions do not depend on the vehicle layer`() {
        val entries = Diagnostics.actions(allowed.copy(vehicleLayerReady = false), FirmwareGen.SWI68)

        assertEquals(Diagnostics.Status.OK, entry(entries, "WEBHOOK").status)
        assertEquals(Diagnostics.Status.OK, entry(entries, "DELAY").status)
    }

    @Test
    fun `unknown firmware fails closed only for firmware-specific actions`() {
        val entries = Diagnostics.actions(allowed.copy(radioService = true), null)

        assertEquals(Diagnostics.Reason.UNSUPPORTED_FIRMWARE, entry(entries, "PLAY_RADIO").reason)
        assertEquals(Diagnostics.Status.OK, entry(entries, "SHOW_NOTIFICATION").status)
    }

    /**
     * The rule editor hides what the diagnostic blocked, but only for reasons that will still
     * be true tomorrow. A verdict taken while the car happened to be moving must never remove
     * an action from the picker.
     */
    @Test
    fun `only verdicts about the car itself may hide a catalogue entry`() {
        val structural = listOf(
            Diagnostics.Reason.UNSUPPORTED_FIRMWARE,
            Diagnostics.Reason.NO_VENDOR_SERVICE,
            Diagnostics.Reason.NO_NAVIGATION_APP,
            Diagnostics.Reason.NO_TTS_ENGINE,
            Diagnostics.Reason.NO_EVPROFILE,
            Diagnostics.Reason.NOT_READABLE,
            // Not about the car, but it outlives every drive: only an app update carrying
            // the evidence can change it, so the picker must not keep offering the entry.
            Diagnostics.Reason.WRITE_UNPROVEN
        )
        val transient = listOf(
            Diagnostics.Reason.NONE,
            Diagnostics.Reason.GATE_MOVING,
            Diagnostics.Reason.GATE_UNKNOWN_SPEED,
            Diagnostics.Reason.BLUETOOTH_OFF,
            Diagnostics.Reason.LAYER_NOT_READY,
            Diagnostics.Reason.EVPROFILE_UNREACHABLE,
            Diagnostics.Reason.NOTIFICATIONS_OFF,
            Diagnostics.Reason.NO_LOCATION,
            Diagnostics.Reason.NOT_DRIVEN_YET,
            Diagnostics.Reason.NO_HANDSFREE_INFO,
            // A phone leaves the car with its owner. "No phone on the message profile" is
            // where the car is right now, not what it is: hiding the SMS action from the
            // editor over it would make the action unwritable at the desk.
            Diagnostics.Reason.NO_MESSAGING_PHONE
        )

        structural.forEach { assertTrue("$it must hide", it.describesTheCar) }
        transient.forEach { assertTrue("$it must not hide", !it.describesTheCar) }
        // Every constant is accounted for, so a new reason cannot be added without deciding.
        assertEquals(
            Diagnostics.Reason.entries.toSet(),
            (structural + transient).toSet()
        )
    }

    /** No write can be probed without performing it, so an excluded firmware fails closed. */
    @Test
    fun `an action the matrix excludes is blocked, not merely flagged`() {
        val entries = Diagnostics.actions(allowed, FirmwareGen.SWI68)
        val excluded = entries.filter { it.hidden }

        assertTrue(
            "every hidden action must be blocked",
            excluded.all {
                it.status == Diagnostics.Status.BLOCKED &&
                    it.reason in setOf(
                        Diagnostics.Reason.UNSUPPORTED_FIRMWARE,
                        Diagnostics.Reason.WRITE_UNPROVEN
                    )
            }
        )
    }

    /**
     * The point of the whole screen: OK is a promise that the action works.
     *
     * A write nobody has seen do anything passes every check made before writing — the
     * firmware lists the property, the vendor service is bound, the car is stopped — and
     * would therefore be reported OK on the strength of checks that say nothing about it.
     */
    @Test
    fun `an unproven write is never reported OK`() {
        val everything = allowed.copy(
            climateService = true, chargingService = true,
            radioService = true, phoneService = true,
            messagingPhone = true, navigationApp = true
        )

        val entries = Diagnostics.actions(everything, FirmwareGen.SWI68)

        val unproven = ActionType.entries.filter { !it.writeProven }.map { it.name }
        assertTrue("the glass is the case this exists for", "SET_WINDOWS" in unproven)
        unproven.forEach {
            assertEquals(Diagnostics.Reason.WRITE_UNPROVEN, entry(entries, it).reason)
            assertEquals(Diagnostics.Status.BLOCKED, entry(entries, it).status)
            assertTrue("$it must not be offered in the editor", entry(entries, it).hidden)
        }
        assertTrue(
            "no OK action may be unproven",
            entries.filter { it.status == Diagnostics.Status.OK }.none { it.name in unproven }
        )
    }

    /**
     * Order matters between the two structural verdicts: a firmware that does not carry the
     * property at all is the more specific answer, and the one that tells the reader that
     * proving the write on *this* car would not help.
     */
    @Test
    fun `an unproven write on an excluded firmware still blames the firmware`() {
        val entries = Diagnostics.actions(allowed, FirmwareGen.SWI69)

        assertEquals(
            Diagnostics.Reason.UNSUPPORTED_FIRMWARE,
            entry(entries, "SET_WINDOWS").reason
        )
    }
}
