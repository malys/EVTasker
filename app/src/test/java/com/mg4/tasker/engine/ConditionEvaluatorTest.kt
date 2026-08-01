package com.mg4.tasker.engine

import com.mg4.tasker.bridge.BridgeContract
import com.mg4.tasker.model.CompareOp
import com.mg4.tasker.model.Condition
import com.mg4.tasker.model.ConditionOutcome
import com.mg4.hardware.catalog.ConditionType
import com.mg4.tasker.model.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class ConditionEvaluatorTest {

    private fun snapshot(vararg readings: Pair<String, Any>) =
        Snapshot(readings = readings.toMap())

    // -------------------------------------------------------------------------
    // La distinction centrale : illisible ≠ faux
    // -------------------------------------------------------------------------

    @Test
    fun `temperature absente est indisponible et non fausse`() {
        // Sur un firmware qui n'expose pas ENV_OUTSIDE_TEMPERATURE, la clé manque.
        // Si on rendait NO_MATCH, l'utilisateur croirait sa règle correcte mais jamais
        // remplie ; UNAVAILABLE permet à l'historique de nommer la donnée manquante.
        val condition = Condition(ConditionType.OUTSIDE_TEMP, op = CompareOp.GT, number = 10f)

        assertEquals(
            ConditionOutcome.UNAVAILABLE,
            ConditionEvaluator.evaluate(condition, snapshot())
        )
    }

    @Test
    fun `temperature presente est comparee normalement`() {
        val condition = Condition(ConditionType.OUTSIDE_TEMP, op = CompareOp.GT, number = 10f)

        assertEquals(
            ConditionOutcome.MATCH,
            ConditionEvaluator.evaluate(condition, snapshot(BridgeContract.KEY_OUTSIDE_TEMP to 15.5f))
        )
        assertEquals(
            ConditionOutcome.NO_MATCH,
            ConditionEvaluator.evaluate(condition, snapshot(BridgeContract.KEY_OUTSIDE_TEMP to 4f))
        )
    }

    @Test
    fun `vitesse illisible est indisponible meme si une valeur trainait`() {
        // Le pont signale l'illisibilité par un drapeau dédié. Sans ce test, une vitesse
        // résiduelle dans l'instantané pourrait être lue comme une mesure valide.
        val condition = Condition(ConditionType.SPEED, op = CompareOp.EQ, number = 0f)

        assertEquals(
            ConditionOutcome.UNAVAILABLE,
            ConditionEvaluator.evaluate(
                condition,
                snapshot(
                    BridgeContract.KEY_SPEED_READABLE to false,
                    BridgeContract.KEY_SPEED_KMH to 0f
                )
            )
        )
    }

    // -------------------------------------------------------------------------
    // Bluetooth
    // -------------------------------------------------------------------------

    @Test
    fun `appareil bluetooth reconnu independamment de la casse du MAC`() {
        val condition = Condition(ConditionType.BT_DEVICE_CONNECTED, text = "aa:bb:cc:dd:ee:ff")
        val connected = Snapshot(btMacs = setOf("AA:BB:CC:DD:EE:FF"))

        assertEquals(ConditionOutcome.MATCH, ConditionEvaluator.evaluate(condition, connected))
    }

    @Test
    fun `appareil bluetooth absent ne matche pas`() {
        val condition = Condition(ConditionType.BT_DEVICE_CONNECTED, text = "AA:BB:CC:DD:EE:FF")
        val other = Snapshot(btMacs = setOf("11:22:33:44:55:66"))

        assertEquals(ConditionOutcome.NO_MATCH, ConditionEvaluator.evaluate(condition, other))
    }

    @Test
    fun `radio eteinte l etat bluetooth est indisponible et non vide`() {
        // btAvailable=false signifie « la radio est coupée ou illisible ». L'ensemble des
        // MAC est vide pour cette raison, pas parce qu'aucun téléphone n'est connecté.
        val condition = Condition(ConditionType.BT_DEVICE_CONNECTED, text = "AA:BB:CC:DD:EE:FF")

        assertEquals(
            ConditionOutcome.UNAVAILABLE,
            ConditionEvaluator.evaluate(condition, Snapshot(btAvailable = false))
        )
    }

    @Test
    fun `radio eteinte aucun peripherique connecte est indisponible et non faux`() {
        val condition = Condition(ConditionType.ANY_BT_CONNECTED, flag = false)

        assertEquals(
            ConditionOutcome.UNAVAILABLE,
            ConditionEvaluator.evaluate(condition, Snapshot(btAvailable = false))
        )
    }

    @Test
    fun `le bluetooth reste evaluable quand la couche vehicule n est pas prete`() {
        // Le Bluetooth est du contexte, pas un signal véhicule : une règle « mon téléphone
        // est connecté » doit répondre même si MG4Hardware n'est pas monté.
        val condition = Condition(ConditionType.BT_DEVICE_CONNECTED, text = "AA:BB:CC:DD:EE:FF")
        val snapshot = Snapshot(btMacs = setOf("AA:BB:CC:DD:EE:FF"), bridgeAvailable = false)

        assertEquals(ConditionOutcome.MATCH, ConditionEvaluator.evaluate(condition, snapshot))
    }

    // -------------------------------------------------------------------------
    // Heure et jours
    // -------------------------------------------------------------------------

    @Test
    fun `plage horaire simple`() {
        val condition = Condition(ConditionType.TIME_OF_DAY, minutesFrom = 7 * 60, minutesTo = 9 * 60)

        assertEquals(
            ConditionOutcome.MATCH,
            ConditionEvaluator.evaluate(condition, Snapshot(minutesOfDay = 8 * 60))
        )
        assertEquals(
            ConditionOutcome.NO_MATCH,
            ConditionEvaluator.evaluate(condition, Snapshot(minutesOfDay = 12 * 60))
        )
    }

    @Test
    fun `plage horaire qui enjambe minuit`() {
        // « La nuit » (22h → 6h) est une plage légitime. Sans traitement spécifique,
        // l'intervalle 22*60..6*60 serait vide et la règle ne partirait jamais.
        val night = Condition(ConditionType.TIME_OF_DAY, minutesFrom = 22 * 60, minutesTo = 6 * 60)

        assertEquals(
            ConditionOutcome.MATCH,
            ConditionEvaluator.evaluate(night, Snapshot(minutesOfDay = 23 * 60))
        )
        assertEquals(
            ConditionOutcome.MATCH,
            ConditionEvaluator.evaluate(night, Snapshot(minutesOfDay = 2 * 60))
        )
        assertEquals(
            ConditionOutcome.NO_MATCH,
            ConditionEvaluator.evaluate(night, Snapshot(minutesOfDay = 14 * 60))
        )
    }

    @Test
    fun `jours de la semaine`() {
        val weekdays = Condition(
            ConditionType.DAY_OF_WEEK,
            days = listOf(Calendar.MONDAY, Calendar.TUESDAY)
        )

        assertEquals(
            ConditionOutcome.MATCH,
            ConditionEvaluator.evaluate(weekdays, Snapshot(dayOfWeek = Calendar.MONDAY))
        )
        assertEquals(
            ConditionOutcome.NO_MATCH,
            ConditionEvaluator.evaluate(weekdays, Snapshot(dayOfWeek = Calendar.SUNDAY))
        )
    }

    @Test
    fun `aucun jour coche est indisponible plutot que toujours faux`() {
        val empty = Condition(ConditionType.DAY_OF_WEEK, days = emptyList())

        assertEquals(
            ConditionOutcome.UNAVAILABLE,
            ConditionEvaluator.evaluate(empty, Snapshot(dayOfWeek = Calendar.MONDAY))
        )
    }

    // -------------------------------------------------------------------------
    // Énumérations et booléens
    // -------------------------------------------------------------------------

    @Test
    fun `mode de conduite compare la valeur vehicule et non l index`() {
        // Sport vaut 4 dans le protocole, pas 2 (son rang dans la liste).
        val sport = Condition(ConditionType.DRIVE_MODE, number = 4f)

        assertEquals(
            ConditionOutcome.MATCH,
            ConditionEvaluator.evaluate(sport, snapshot(BridgeContract.KEY_DRIVE_MODE to 4))
        )
        assertEquals(
            ConditionOutcome.NO_MATCH,
            ConditionEvaluator.evaluate(sport, snapshot(BridgeContract.KEY_DRIVE_MODE to 2))
        )
    }

    @Test
    fun `operateur different inverse le resultat d une enumeration`() {
        val notEco = Condition(ConditionType.DRIVE_MODE, op = CompareOp.NE, number = 2f)

        assertEquals(
            ConditionOutcome.MATCH,
            ConditionEvaluator.evaluate(notEco, snapshot(BridgeContract.KEY_DRIVE_MODE to 4))
        )
    }

    @Test
    fun `condition booleenne attendue a false`() {
        val aebOff = Condition(ConditionType.AEB_ENABLED, flag = false)

        assertEquals(
            ConditionOutcome.MATCH,
            ConditionEvaluator.evaluate(aebOff, snapshot(BridgeContract.KEY_AEB_ENABLED to false))
        )
        assertEquals(
            ConditionOutcome.NO_MATCH,
            ConditionEvaluator.evaluate(aebOff, snapshot(BridgeContract.KEY_AEB_ENABLED to true))
        )
    }
}
