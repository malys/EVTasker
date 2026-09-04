package com.evsuite.tasker.store

import com.evsuite.hardware.catalog.WeatherConditions
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Rules saved when the weather condition compared a typed phrase.
 *
 * The phrase is exactly what the new list replaces, so it is also the only thing left to
 * read the user's intention from: "pluie" was a rule about rain and stays one, in whatever
 * language it was written.
 */
class WeatherRuleMigrationTest {

    private fun migrated(text: String, number: Int? = null): JsonObject {
        val stored = if (number == null) "" else ""","number":$number"""
        val json = """[{"conditions":[{"type":"WEATHER_NOW","text":"$text"$stored}]}]"""
        return JsonParser.parseString(LegacyRuleJson.migrate(json))
            .asJsonArray[0].asJsonObject
            .getAsJsonArray("conditions")[0].asJsonObject
    }

    @Test
    fun `a typed phrase becomes the state it described`() {
        val rain = migrated("pluie")
        assertEquals(WeatherConditions.RAIN, rain.get("number").asInt)
        // The phrase goes: leaving it would show the old wording next to the new list.
        assertEquals("", rain.get("text").asString)
        assertEquals(WeatherConditions.CLEAR, migrated("Sunny").get("number").asInt)
    }

    @Test
    fun `a phrase this build cannot place is left alone`() {
        // No state is invented for it: the rule stops matching and says so, rather than
        // silently becoming a rule about a different sky.
        val unknown = migrated("gribouilli")
        assertEquals("gribouilli", unknown.get("text").asString)
        assertEquals(null, unknown.get("number"))
    }

    @Test
    fun `the migration is idempotent`() {
        // It runs on every load. A condition saved after the change carries no phrase, so
        // there is nothing left to reinterpret.
        val already = migrated("", WeatherConditions.SNOW)
        assertEquals(WeatherConditions.SNOW, already.get("number").asInt)
    }
}
