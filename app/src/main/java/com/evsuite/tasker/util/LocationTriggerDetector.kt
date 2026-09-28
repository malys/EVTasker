package com.evsuite.tasker.util

import com.evsuite.hardware.catalog.ConditionType
import com.evsuite.tasker.engine.ConditionEvaluator
import com.evsuite.tasker.model.ConditionOutcome
import com.evsuite.tasker.model.Rule
import com.evsuite.tasker.model.Snapshot

/**
 * Detects a "near a place" condition turning true, for the position trigger.
 *
 * Same contract as [ParkTriggerDetector], per condition: the first readable sample is a
 * silent baseline (a drive that starts at home must not "arrive" there), an unreadable one
 * changes nothing, and only a confirmed false → true transition fires. The key carries the
 * condition's own point, radius and sense, so editing a place starts a fresh baseline
 * instead of comparing against the old one.
 */
class LocationTriggerDetector {
    private val lastMatch = HashMap<String, Boolean>()

    /** @return the ids of [rules] one of whose position conditions has just turned true. */
    fun sample(rules: List<Rule>, fix: CarLocation.Fix?): List<String> {
        val snapshot = Snapshot(latitude = fix?.latitude, longitude = fix?.longitude)
        val seen = HashSet<String>()
        val fired = rules.filter { rule ->
            rule.branches.flatMap { it.conditions }
                .filter { it.type == ConditionType.LOCATION_WITHIN }
                .mapIndexed { i, c ->
                    val key = "${rule.id}#$i#${c.text}#${c.number}#${c.flag}"
                    seen += key
                    when (ConditionEvaluator.evaluate(c, snapshot)) {
                        ConditionOutcome.UNAVAILABLE -> false
                        ConditionOutcome.MATCH -> lastMatch.put(key, true) == false
                        else -> { lastMatch[key] = false; false }
                    }
                }
                .any { it }
        }.map { it.id }
        lastMatch.keys.retainAll(seen)
        return fired
    }

    fun reset() {
        lastMatch.clear()
    }
}
