package com.evsuite.tasker.engine

import com.evsuite.tasker.bridge.BridgeContract
import com.evsuite.tasker.model.CompareOp
import com.evsuite.tasker.model.Condition
import com.evsuite.tasker.model.ConditionOutcome
import com.evsuite.hardware.catalog.ConditionType
import com.evsuite.hardware.catalog.SnapshotKeys
import com.evsuite.tasker.model.Snapshot
import com.evsuite.hardware.PhysicalButtonEventDecoder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class ConditionEvaluatorTest {

    private fun snapshot(vararg readings: Pair<String, Any>) =
        Snapshot(readings = readings.toMap())

    @Test
    fun `physical button condition is unavailable outside a button event`() {
        val condition = Condition(ConditionType.PHYSICAL_BUTTON, number =
            PhysicalButtonEventDecoder.Button.STAR_LEFT.codes.first().toFloat(), text = "LONG")
        assertEquals(ConditionOutcome.UNAVAILABLE, ConditionEvaluator.evaluate(condition, snapshot()))
    }

    @Test
    fun `only the matching physical button condition matches`() {
        val event = PhysicalButtonEventDecoder.Event(
            PhysicalButtonEventDecoder.Button.STAR_LEFT,
            PhysicalButtonEventDecoder.Press.LONG
        )
        val current = snapshot(*event.readings().map { it.key to it.value }.toTypedArray())
        assertEquals(
            ConditionOutcome.MATCH,
            ConditionEvaluator.evaluate(Condition(ConditionType.PHYSICAL_BUTTON,
                number = PhysicalButtonEventDecoder.Button.STAR_LEFT.codes.first().toFloat(), text = "LONG"), current)
        )
        assertEquals(
            ConditionOutcome.NO_MATCH,
            ConditionEvaluator.evaluate(Condition(ConditionType.PHYSICAL_BUTTON,
                number = PhysicalButtonEventDecoder.Button.STAR_RIGHT.codes.first().toFloat(), text = "LONG"), current)
        )
    }

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
        // est connecté » doit répondre même si EVHardware n'est pas monté.
        val condition = Condition(ConditionType.BT_DEVICE_CONNECTED, text = "AA:BB:CC:DD:EE:FF")
        val snapshot = Snapshot(btMacs = setOf("AA:BB:CC:DD:EE:FF"), bridgeAvailable = false)

        assertEquals(ConditionOutcome.MATCH, ConditionEvaluator.evaluate(condition, snapshot))
    }

    @Test
    fun `le telephone reste a la maison il est connecte mais pas a bord`() {
        // Le cas qui a motivé la condition : voiture garée devant la maison, le téléphone
        // resté à l'intérieur est bien connecté, mais il n'a pas fait le trajet.
        val mac = "AA:BB:CC:DD:EE:FF"
        val snapshot = Snapshot(btMacs = setOf(mac), btOnboardMacs = setOf("11:22:33:44:55:66"))

        assertEquals(
            ConditionOutcome.MATCH,
            ConditionEvaluator.evaluate(Condition(ConditionType.BT_DEVICE_CONNECTED, text = mac), snapshot)
        )
        assertEquals(
            ConditionOutcome.NO_MATCH,
            ConditionEvaluator.evaluate(Condition(ConditionType.BT_DEVICE_ONBOARD, text = mac), snapshot)
        )
    }

    @Test
    fun `deux telephones a portee seul celui embarque est a bord`() {
        val inCar = "AA:BB:CC:DD:EE:FF"
        val atHome = "11:22:33:44:55:66"
        val snapshot = Snapshot(btMacs = setOf(inCar, atHome), btOnboardMacs = setOf(inCar))

        assertEquals(
            ConditionOutcome.MATCH,
            ConditionEvaluator.evaluate(Condition(ConditionType.BT_DEVICE_ONBOARD, text = inCar), snapshot)
        )
        assertEquals(
            ConditionOutcome.NO_MATCH,
            ConditionEvaluator.evaluate(Condition(ConditionType.BT_DEVICE_ONBOARD, text = atHome), snapshot)
        )
    }

    @Test
    fun `avant d avoir roule le telephone a bord est indisponible et non faux`() {
        // btOnboardMacs=null : la voiture n'a pas bougé, la question n'a pas encore de
        // réponse. Répondre « non » ferait tirer toute règle « mon téléphone n'est pas là ».
        val condition = Condition(ConditionType.BT_DEVICE_ONBOARD, text = "AA:BB:CC:DD:EE:FF")

        assertEquals(
            ConditionOutcome.UNAVAILABLE,
            ConditionEvaluator.evaluate(condition, Snapshot(btMacs = setOf("AA:BB:CC:DD:EE:FF")))
        )
    }

    @Test
    fun `personne a bord apres avoir roule est faux et non indisponible`() {
        // L'ensemble vide est une vraie réponse : la voiture a roulé, aucun téléphone n'a
        // suivi. À distinguer du null ci-dessus.
        val condition = Condition(ConditionType.BT_DEVICE_ONBOARD, text = "AA:BB:CC:DD:EE:FF")

        assertEquals(
            ConditionOutcome.NO_MATCH,
            ConditionEvaluator.evaluate(condition, Snapshot(btOnboardMacs = emptySet()))
        )
    }

    @Test
    fun `le telephone mains-libres est celui que la voiture a choisi`() {
        val driver = "AA:BB:CC:DD:EE:FF"
        val passenger = "11:22:33:44:55:66"
        val snapshot = Snapshot(
            btMacs = setOf(driver, passenger),
            btHandsFreeMacs = setOf(driver)
        )

        assertEquals(
            ConditionOutcome.MATCH,
            ConditionEvaluator.evaluate(Condition(ConditionType.BT_DEVICE_HANDSFREE, text = driver), snapshot)
        )
        assertEquals(
            ConditionOutcome.NO_MATCH,
            ConditionEvaluator.evaluate(Condition(ConditionType.BT_DEVICE_HANDSFREE, text = passenger), snapshot)
        )
    }

    @Test
    fun `mains-libres illisible est indisponible et non faux`() {
        val condition = Condition(ConditionType.BT_DEVICE_HANDSFREE, text = "AA:BB:CC:DD:EE:FF")

        assertEquals(
            ConditionOutcome.UNAVAILABLE,
            ConditionEvaluator.evaluate(condition, Snapshot(btMacs = setOf("AA:BB:CC:DD:EE:FF")))
        )
    }

    @Test
    fun `radio eteinte le telephone a bord est indisponible meme avec un trajet connu`() {
        val mac = "AA:BB:CC:DD:EE:FF"
        val condition = Condition(ConditionType.BT_DEVICE_ONBOARD, text = mac)

        assertEquals(
            ConditionOutcome.UNAVAILABLE,
            ConditionEvaluator.evaluate(
                condition,
                Snapshot(btAvailable = false, btOnboardMacs = setOf(mac))
            )
        )
    }

    // -------------------------------------------------------------------------
    // Position
    // -------------------------------------------------------------------------

    @Test
    fun `dans le rayon la condition est remplie`() {
        // Tour Eiffel → Champ-de-Mars, ~300 m.
        val condition = Condition(
            ConditionType.LOCATION_WITHIN, text = "48.858370,2.294481", number = 500f
        )
        val snapshot = Snapshot(latitude = 48.855800, longitude = 2.298600)

        assertEquals(ConditionOutcome.MATCH, ConditionEvaluator.evaluate(condition, snapshot))
    }

    @Test
    fun `hors du rayon la condition n est pas remplie`() {
        val condition = Condition(
            ConditionType.LOCATION_WITHIN, text = "48.858370,2.294481", number = 200f
        )
        val snapshot = Snapshot(latitude = 48.873800, longitude = 2.295000)   // Arc de Triomphe

        assertEquals(ConditionOutcome.NO_MATCH, ConditionEvaluator.evaluate(condition, snapshot))
    }

    @Test
    fun `sans fix la position est indisponible et non hors zone`() {
        // Sans ça, une règle « quand je ne suis PAS à la maison » se déclencherait dans
        // l'allée, le temps que le GPS accroche.
        val condition = Condition(
            ConditionType.LOCATION_WITHIN, text = "48.858370,2.294481", number = 500f
        )

        assertEquals(
            ConditionOutcome.UNAVAILABLE,
            ConditionEvaluator.evaluate(condition, Snapshot())
        )
    }

    @Test
    fun `un point illisible rend la condition indisponible`() {
        val snapshot = Snapshot(latitude = 48.85, longitude = 2.29)

        listOf("", "48.85", "abc,def", "91.0,2.0").forEach { text ->
            assertEquals(
                "« $text » ne décrit pas un point",
                ConditionOutcome.UNAVAILABLE,
                ConditionEvaluator.evaluate(
                    Condition(ConditionType.LOCATION_WITHIN, text = text, number = 500f),
                    snapshot
                )
            )
        }
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

    @Test
    fun `date precise correspond uniquement au jour configure`() {
        val condition = Condition(ConditionType.DATE, text = "2026-07-22")

        assertEquals(
            ConditionOutcome.MATCH,
            ConditionEvaluator.evaluate(condition, Snapshot(localDate = "2026-07-22"))
        )
        assertEquals(
            ConditionOutcome.NO_MATCH,
            ConditionEvaluator.evaluate(condition, Snapshot(localDate = "2026-07-23"))
        )
    }

    @Test
    fun `date absente ou invalide est indisponible`() {
        assertEquals(
            ConditionOutcome.UNAVAILABLE,
            ConditionEvaluator.evaluate(
                Condition(ConditionType.DATE, text = "2026-02-30"),
                Snapshot(localDate = "2026-07-22")
            )
        )
        assertEquals(
            ConditionOutcome.UNAVAILABLE,
            ConditionEvaluator.evaluate(
                Condition(ConditionType.DATE, text = "2026-07-22"),
                Snapshot(localDate = "")
            )
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

    // ── Platform context ─────────────────────────────────────────────────────

    @Test
    fun `a network name matches whatever its case and spacing`() {
        // The name is typed by hand into the rule; "Home " failing to match "Home" would
        // read as a broken condition rather than as a stray space.
        val atHome = Condition(ConditionType.WIFI_SSID, text = " home ")

        assertEquals(
            ConditionOutcome.MATCH,
            ConditionEvaluator.evaluate(atHome, snapshot(SnapshotKeys.KEY_WIFI_SSID to "Home"))
        )
        assertEquals(
            ConditionOutcome.NO_MATCH,
            ConditionEvaluator.evaluate(atHome, snapshot(SnapshotKeys.KEY_WIFI_SSID to "Office"))
        )
    }

    @Test
    fun `an unnamed network is unavailable, not a different network`() {
        // The platform withholds the name when it will not say, and a rule that acts because
        // "it is not the home network" would then act in the garage.
        val atHome = Condition(ConditionType.WIFI_SSID, text = "Home")
        assertEquals(ConditionOutcome.UNAVAILABLE, ConditionEvaluator.evaluate(atHome, snapshot()))
    }

    @Test
    fun `an empty network name is unavailable rather than matching everything`() {
        val unset = Condition(ConditionType.WIFI_SSID, text = "")
        assertEquals(
            ConditionOutcome.UNAVAILABLE,
            ConditionEvaluator.evaluate(unset, snapshot(SnapshotKeys.KEY_WIFI_SSID to "Home"))
        )
    }

    @Test
    fun `the drive duration is unavailable before a drive is seen`() {
        // A service that started on a car already running does not know when the drive began;
        // answering 0 would make "driving for over 20 minutes" false for the whole trip.
        val long = Condition(ConditionType.DRIVE_DURATION, op = CompareOp.GT, number = 20f)
        assertEquals(ConditionOutcome.UNAVAILABLE, ConditionEvaluator.evaluate(long, snapshot()))
        assertEquals(
            ConditionOutcome.MATCH,
            ConditionEvaluator.evaluate(long, snapshot(SnapshotKeys.KEY_DRIVE_MINUTES to 21))
        )
    }

    @Test
    fun `the chance condition is decided by the draw, at both ends`() {
        // Injected rather than sampled: running the rule a thousand times and hoping proves
        // nothing about the boundaries, which is where an off-by-one would live.
        val oneInFive = Condition(ConditionType.RANDOM_CHANCE, number = 20f)
        try {
            ConditionEvaluator.random = { 19 }
            assertEquals(ConditionOutcome.MATCH, ConditionEvaluator.evaluate(oneInFive, snapshot()))
            ConditionEvaluator.random = { 20 }
            assertEquals(ConditionOutcome.NO_MATCH, ConditionEvaluator.evaluate(oneInFive, snapshot()))

            // 100% must never come out false, and 1% must not come out true on a zero draw
            // by accident of the comparison.
            ConditionEvaluator.random = { 99 }
            assertEquals(
                ConditionOutcome.MATCH,
                ConditionEvaluator.evaluate(Condition(ConditionType.RANDOM_CHANCE, number = 100f), snapshot())
            )
            assertEquals(
                ConditionOutcome.NO_MATCH,
                ConditionEvaluator.evaluate(Condition(ConditionType.RANDOM_CHANCE, number = 1f), snapshot())
            )
        } finally {
            ConditionEvaluator.random = { kotlin.random.Random.nextInt(it) }
        }
    }

    @Test
    fun `the chance condition never asks the snapshot for anything`() {
        // It has no key. If it ever gained one it would start coming back UNAVAILABLE on a
        // car that answers nothing, which is not what a coin toss does.
        assertEquals(null, ConditionType.RANDOM_CHANCE.snapshotKey)
    }
}
