package com.evsuite.tasker.store

import com.evsuite.hardware.saic.SaicRadio
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rules saved when the band was an action of its own.
 *
 * Band, frequency and playback were one instruction split across two entries, and the split
 * was the wrong seam: a band could not carry a station, a station could not reach DAB. They
 * merged into `TUNE_RADIO`, which reads the band out of the same `number` the band action
 * already stored — so the rewrite is the name, an emptied frequency, and nothing else.
 *
 * Without it, Gson reads `SELECT_RADIO_BAND` as an unknown name, hands [RuleStore] a null
 * type, and the whole rule disappears on update.
 */
class RadioBandRuleMigrationTest {

    private fun migrate(json: String): JsonObject =
        JsonParser.parseString(LegacyRuleJson.migrate(json))
            .asJsonArray[0].asJsonObject
            .getAsJsonArray("actions")[0].asJsonObject

    private fun band(value: Int): JsonObject =
        migrate("""[{"actions":[{"type":"SELECT_RADIO_BAND","number":$value}]}]""")

    @Test
    fun `the band action becomes the merged one, keeping its band`() {
        val action = band(SaicRadio.BAND_DAB)
        assertEquals("TUNE_RADIO", action.get("type").asString)
        assertEquals(SaicRadio.BAND_DAB, action.get("number").asInt)
    }

    @Test
    fun `the migrated rule names no frequency`() {
        // The old action had none, and the merged one reads an empty frequency as "put the
        // tuner on this band" — which is what the rule asked for. A stale number here would
        // tune a station nobody wrote.
        assertEquals("", band(SaicRadio.BAND_FM).get("text").asString)
    }

    @Test
    fun `the migrated rule still plays, as the band switch always did`() {
        // SaicRadio.selectBand tuned with andPlay = true unconditionally, so the rule played
        // the radio whether or not anyone wrote that down. Dropping it would silence a rule
        // that used to make a sound.
        assertTrue(band(SaicRadio.BAND_AM).get("flag").asBoolean)
    }

    @Test
    fun `every band survives the rewrite`() {
        listOf(SaicRadio.BAND_AM, SaicRadio.BAND_FM, SaicRadio.BAND_DAB).forEach {
            assertEquals("band $it", it, band(it).get("number").asInt)
        }
    }

    @Test
    fun `a rule already on the merged action is left alone`() {
        // The migration runs on every load and on every import: a rule saved after the merge
        // must survive being read again, frequency included.
        val action = migrate(
            """[{"actions":[{"type":"TUNE_RADIO","number":2,"text":"103.5","flag":false}]}]"""
        )
        assertEquals("TUNE_RADIO", action.get("type").asString)
        assertEquals("103.5", action.get("text").asString)
        assertEquals(false, action.get("flag").asBoolean)
    }

    @Test
    fun `a band action inside an else branch is migrated too`() {
        // The walk is recursive: the same action shape is reached through "elseIf" and
        // "elseActions", and a rule is dropped whole when any one of its actions is unknown.
        val rule = JsonParser.parseString(
            LegacyRuleJson.migrate(
                """[{"actions":[],"elseIf":[{"actions":[{"type":"SELECT_RADIO_BAND","number":4}]}],
                   "elseActions":[{"type":"SELECT_RADIO_BAND","number":1}]}]"""
            )
        ).asJsonArray[0].asJsonObject

        assertEquals(
            "TUNE_RADIO",
            rule.getAsJsonArray("elseIf")[0].asJsonObject
                .getAsJsonArray("actions")[0].asJsonObject.get("type").asString
        )
        assertEquals(
            "TUNE_RADIO",
            rule.getAsJsonArray("elseActions")[0].asJsonObject.get("type").asString
        )
    }
}
