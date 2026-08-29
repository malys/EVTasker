package com.evsuite.tasker.store

import com.evsuite.hardware.catalog.VehicleEnums
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Rules saved when the glass actions carried a raw vendor command.
 *
 * The capture that prompted the change is the argument for it: a rule sending command `7`
 * ran five times, reported ALLOWED five times, and left the driver's window at 100 % open
 * throughout — the service accepts a value it does not implement and drops it. So there is
 * no correct behaviour to preserve here, only an intention to recover, and the intention is
 * read the way the numbers were evidently typed: as percentages.
 */
class GlassRuleMigrationTest {

    private fun migratedNumber(type: String, number: Int): Int {
        val json = """[{"actions":[{"type":"$type","number":$number}]}]"""
        val obj = JsonParser.parseString(LegacyRuleJson.migrate(json))
            .asJsonArray[0].asJsonObject
            .getAsJsonArray("actions")[0].asJsonObject
        return obj.get("number").asInt
    }

    @Test
    fun `a command typed as a percentage keeps the intention behind it`() {
        // 7 was someone reaching for the top of the 0..7 command range to mean "close"; 100
        // came from a rule written against the percentage control that preceded it.
        assertEquals(VehicleEnums.WINDOW_CLOSE, migratedNumber("SET_WINDOWS", 7))
        assertEquals(VehicleEnums.WINDOW_OPEN, migratedNumber("SET_WINDOW_DRIVER", 100))
        assertEquals(VehicleEnums.WINDOW_CLOSE, migratedNumber("SET_WINDOW_PASSENGER", 0))
    }

    @Test
    fun `the migration is idempotent`() {
        // It runs on every load, so a rule saved after the change must survive being read
        // again. Both states are already valid values and must be left exactly as they are.
        assertEquals(VehicleEnums.WINDOW_OPEN, migratedNumber("SET_WINDOWS", VehicleEnums.WINDOW_OPEN))
        assertEquals(VehicleEnums.WINDOW_CLOSE, migratedNumber("SET_WINDOWS", VehicleEnums.WINDOW_CLOSE))
    }

    @Test
    fun `every glass action is migrated, and nothing else is touched`() {
        listOf(
            "SET_WINDOWS", "SET_WINDOW_DRIVER", "SET_WINDOW_PASSENGER",
            "SET_WINDOW_REAR_LEFT", "SET_WINDOW_REAR_RIGHT"
        ).forEach {
            assertEquals("$it must be migrated", VehicleEnums.WINDOW_OPEN, migratedNumber(it, 100))
        }
        // A neighbouring action whose number is a real value, not a glass command: rewriting
        // it would set the cabin to 1 °C.
        assertEquals(21, migratedNumber("SET_CABIN_TEMP", 21))
        assertEquals(7, migratedNumber("SET_FAN_LEVEL", 7))
    }

    @Test
    fun `a glass action inside an else-if branch is migrated too`() {
        // The walk is recursive for the same reason the rename is: an action reaches the
        // reader through the same object shape wherever it sits, and a rule whose alternative
        // branch closed the windows would otherwise keep a command the service ignores.
        val json = """
            [{"actions":[],"elseIf":[{"actions":[{"type":"SET_WINDOWS","number":7}]}],
              "elseActions":[{"type":"SET_WINDOW_DRIVER","number":100}]}]
        """.trimIndent()
        val root = JsonParser.parseString(LegacyRuleJson.migrate(json)).asJsonArray[0].asJsonObject
        val inBranch = root.getAsJsonArray("elseIf")[0].asJsonObject
            .getAsJsonArray("actions")[0].asJsonObject.get("number").asInt
        val inElse = root.getAsJsonArray("elseActions")[0].asJsonObject.get("number").asInt
        assertEquals(VehicleEnums.WINDOW_CLOSE, inBranch)
        assertEquals(VehicleEnums.WINDOW_OPEN, inElse)
    }
}
