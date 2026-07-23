package com.mg4.tasker.engine

import com.mg4.tasker.bridge.BridgeContract
import com.mg4.tasker.model.CompareOp
import com.mg4.tasker.model.Condition
import com.mg4.tasker.model.ConditionOutcome
import com.mg4.hardware.catalog.ConditionType
import com.mg4.tasker.model.Snapshot
import com.mg4.hardware.catalog.ValueKind
import kotlin.math.abs

/**
 * Pure evaluation of one condition against a snapshot. No Android dependency: all the
 * decision behaviour is testable on the JVM, with no vehicle and no emulator.
 *
 * Core rule: missing data yields [ConditionOutcome.UNAVAILABLE], never
 * [ConditionOutcome.NO_MATCH]. The difference reaches the user — "I could not tell" and
 * "it was false" are not fixed the same way.
 */
object ConditionEvaluator {

    /** Tolerance for float equality comparisons (temperature above all). */
    private const val EPSILON = 0.01f

    fun evaluate(condition: Condition, snapshot: Snapshot): ConditionOutcome =
        when (condition.type.spec.kind) {
            ValueKind.BT_DEVICE  -> evaluateBtDevice(condition, snapshot)
            ValueKind.TIME_RANGE -> evaluateTimeRange(condition, snapshot)
            ValueKind.DAYS       -> evaluateDays(condition, snapshot)
            ValueKind.BOOL       -> evaluateBool(condition, snapshot)
            ValueKind.NUMBER     -> evaluateNumber(condition, snapshot)
            ValueKind.ENUM       -> evaluateEnum(condition, snapshot)
            else                 -> ConditionOutcome.UNAVAILABLE
        }

    // -------------------------------------------------------------------------
    // Context — independent of the vehicle
    // -------------------------------------------------------------------------

    private fun evaluateBtDevice(c: Condition, s: Snapshot): ConditionOutcome {
        // The connected-device list comes from the bridge: with no bridge, we do not know.
        if (!s.bridgeAvailable) return ConditionOutcome.UNAVAILABLE
        if (c.text.isBlank()) return ConditionOutcome.UNAVAILABLE
        val connected = s.btMacs.any { it.equals(c.text, ignoreCase = true) }
        return match(connected == c.flag)
    }

    private fun evaluateTimeRange(c: Condition, s: Snapshot): ConditionOutcome {
        val now = s.minutesOfDay
        // A range crossing midnight (22:00 → 06:00) is legitimate and common ("at
        // night"). The interval is then the union of the two pieces.
        val inRange = if (c.minutesFrom <= c.minutesTo) {
            now in c.minutesFrom..c.minutesTo
        } else {
            now >= c.minutesFrom || now <= c.minutesTo
        }
        return match(inRange)
    }

    private fun evaluateDays(c: Condition, s: Snapshot): ConditionOutcome {
        if (c.days.isEmpty()) return ConditionOutcome.UNAVAILABLE
        return match(s.dayOfWeek in c.days)
    }

    // -------------------------------------------------------------------------
    // Vehicle
    // -------------------------------------------------------------------------

    private fun evaluateBool(c: Condition, s: Snapshot): ConditionOutcome {
        if (c.type == ConditionType.ANY_BT_CONNECTED) {
            if (!s.bridgeAvailable) return ConditionOutcome.UNAVAILABLE
            return match(s.btMacs.isNotEmpty() == c.flag)
        }
        val key = c.type.snapshotKey ?: return ConditionOutcome.UNAVAILABLE
        val actual = s.bool(key) ?: return ConditionOutcome.UNAVAILABLE
        return match(actual == c.flag)
    }

    private fun evaluateNumber(c: Condition, s: Snapshot): ConditionOutcome {
        val key = c.type.snapshotKey ?: return ConditionOutcome.UNAVAILABLE

        // Speed is a special case: the bridge distinguishes "0 km/h" from "unreadable"
        // with a dedicated flag, because a missing speed key is exactly what makes writes
        // be refused. A rule must not read that as 0.
        if (c.type == ConditionType.SPEED && s.bool(BridgeContract.KEY_SPEED_READABLE) == false) {
            return ConditionOutcome.UNAVAILABLE
        }

        val actual = s.number(key) ?: return ConditionOutcome.UNAVAILABLE
        return match(compare(actual, c.number, c.op))
    }

    private fun evaluateEnum(c: Condition, s: Snapshot): ConditionOutcome {
        val key = c.type.snapshotKey ?: return ConditionOutcome.UNAVAILABLE

        // FIRMWARE_GEN is the only enum transmitted as text.
        if (c.type == ConditionType.FIRMWARE_GEN) {
            val actual = s.string(key) ?: return ConditionOutcome.UNAVAILABLE
            val equal = actual.equals(c.text, ignoreCase = true)
            return match(if (c.op == CompareOp.NE) !equal else equal)
        }

        val actual = s.int(key) ?: return ConditionOutcome.UNAVAILABLE
        val equal = actual == c.number.toInt()
        return match(if (c.op == CompareOp.NE) !equal else equal)
    }

    // -------------------------------------------------------------------------

    private fun compare(actual: Float, expected: Float, op: CompareOp): Boolean = when (op) {
        CompareOp.EQ -> abs(actual - expected) < EPSILON
        CompareOp.NE -> abs(actual - expected) >= EPSILON
        CompareOp.LT -> actual < expected
        CompareOp.LE -> actual <= expected
        CompareOp.GT -> actual > expected
        CompareOp.GE -> actual >= expected
    }

    private fun match(value: Boolean) =
        if (value) ConditionOutcome.MATCH else ConditionOutcome.NO_MATCH
}
