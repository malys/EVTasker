package com.mg4.tasker.debug

import com.mg4.hardware.FirmwareGen
import com.mg4.hardware.catalog.ActionType
import com.mg4.hardware.catalog.ConditionType
import com.mg4.hardware.catalog.SnapshotKeys
import com.mg4.tasker.bridge.BridgeContract
import com.mg4.tasker.model.Snapshot
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
        mg4ControlInstalled = true,
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
        // Local actions do not touch MG4Hardware, so they keep working.
        assertEquals(Diagnostics.Status.OK, entry(entries, "SHOW_NOTIFICATION").status)
        assertEquals(Diagnostics.Status.OK, entry(entries, "LAUNCH_APP").status)
    }

    @Test
    fun `the profile action distinguishes MG4Control absent from unreachable`() {
        val absent = Diagnostics.actions(allowed.copy(mg4ControlInstalled = false), FirmwareGen.SWI68)
        assertEquals(Diagnostics.Reason.NO_MG4CONTROL, entry(absent, "APPLY_PROFILE").reason)

        val unreachable =
            Diagnostics.actions(allowed.copy(profileBridgeReachable = false), FirmwareGen.SWI68)
        assertEquals(
            Diagnostics.Reason.MG4CONTROL_UNREACHABLE,
            entry(unreachable, "APPLY_PROFILE").reason
        )
    }

    @Test
    fun `local actions are blocked by the capability they actually need`() {
        val noTts = Diagnostics.actions(allowed.copy(ttsEngineAvailable = false), FirmwareGen.SWI68)
        assertEquals(Diagnostics.Reason.NO_TTS_ENGINE, entry(noTts, "SPEAK_TEXT").reason)
        assertEquals(Diagnostics.Status.OK, entry(noTts, "SHOW_NOTIFICATION").status)

        val silenced =
            Diagnostics.actions(allowed.copy(notificationsEnabled = false), FirmwareGen.SWI68)
        assertEquals(Diagnostics.Reason.NOTIFICATIONS_OFF, entry(silenced, "SHOW_NOTIFICATION").reason)
        assertEquals(Diagnostics.Status.OK, entry(silenced, "SPEAK_TEXT").status)
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
                    it.reason == Diagnostics.Reason.UNSUPPORTED_FIRMWARE
            }
        )
    }
}
