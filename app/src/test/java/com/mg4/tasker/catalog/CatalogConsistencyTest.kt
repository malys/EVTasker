package com.mg4.tasker.catalog

import com.mg4.tasker.model.ActionType
import com.mg4.tasker.model.ConditionType
import com.mg4.tasker.model.ValueKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Garde-fous sur le catalogue. Les erreurs visées ne cassent pas la compilation : elles
 * produisent une entrée d'interface qui ne fait rien, ou pire, une écriture véhicule qui
 * contourne le verrou de vitesse.
 */
class CatalogConsistencyTest {

    @Test
    fun `toute action vehicule cible une action du pont`() {
        // bridgeAction=null est réservé aux actions locales (lancer une app, notifier).
        // Une action véhicule sans cible serait silencieusement ignorée à l'exécution.
        val localOnly = setOf(ActionType.LAUNCH_APP, ActionType.SHOW_NOTIFICATION)

        ActionType.entries.filterNot { it in localOnly }.forEach { type ->
            assertNotNull("${type.name} n'a pas de bridgeAction", type.bridgeAction)
        }
    }

    @Test
    fun `les actions locales ne passent pas par le pont`() {
        listOf(ActionType.LAUNCH_APP, ActionType.SHOW_NOTIFICATION).forEach { type ->
            assertTrue(
                "${type.name} ne touche pas le véhicule et ne doit pas avoir de bridgeAction",
                type.bridgeAction == null
            )
        }
    }

    @Test
    fun `les ecritures de comportement routier sont marquees gated`() {
        // Le marquage ne remplace PAS le verrou côté MG4Control, qui reste l'autorité.
        // Il conditionne l'avertissement affiché à l'utilisateur au moment du choix :
        // une action gated non marquée se présenterait comme applicable en roulant.
        val mustBeGated = setOf(
            ActionType.APPLY_PROFILE,
            ActionType.SET_DRIVE_MODE, ActionType.SET_REGEN_LEVEL, ActionType.SET_ONE_PEDAL,
            ActionType.SET_ENERGY_SAVING,
            ActionType.SET_AEB_ENABLED, ActionType.SET_AEB_MODE, ActionType.SET_AEB_SENSITIVITY,
            ActionType.SET_ELK_MODE, ActionType.SET_ELK_SENSITIVITY,
            ActionType.SET_ACC_TJA_MODE, ActionType.SET_LIMITER_MODE,
            ActionType.SET_TSR, ActionType.SET_OVERSPEED_ALARM,
            ActionType.SET_SPEED_LIMIT_TONE, ActionType.SET_SOUND_WARNING
        )

        mustBeGated.forEach { type ->
            assertTrue("${type.name} modifie le comportement routier et doit être gated", type.gated)
        }
    }

    @Test
    fun `couper le vehicule n est pas automatisable`() {
        // Une règle ne doit jamais pouvoir éteindre la voiture : c'est irréversible pour
        // le conducteur, et ça reste réservé à un geste humain dans MG4Control.
        assertFalse(
            "aucune action du catalogue ne doit couper le véhicule",
            ActionType.entries.any {
                it.bridgeAction?.contains("POWER_OFF", ignoreCase = true) == true
            }
        )
    }

    @Test
    fun `toute condition vehicule lit une cle de l instantane`() {
        // Les conditions calculées localement (heure, jour, Bluetooth) n'ont pas de clé ;
        // toutes les autres en ont besoin, sinon elles seraient toujours indisponibles.
        val localOnly = setOf(
            ConditionType.BT_DEVICE_CONNECTED,
            ConditionType.ANY_BT_CONNECTED,
            ConditionType.TIME_OF_DAY,
            ConditionType.DAY_OF_WEEK
        )

        ConditionType.entries.filterNot { it in localOnly }.forEach { type ->
            assertNotNull("${type.name} n'a pas de clé d'instantané", type.snapshotKey)
        }
    }

    @Test
    fun `every catalogue entry carries a string label`() {
        // A zero labelRes would crash getString at display time on a device, invisible
        // to these JVM tests — so assert the id is set rather than resolve it.
        ConditionType.entries.forEach { assertTrue("${it.name} has no label", it.labelRes != 0) }
        ActionType.entries.forEach { assertTrue("${it.name} has no label", it.labelRes != 0) }
    }

    @Test
    fun `les enumerations ont des options`() {
        // Une ENUM sans option produit une liste déroulante vide : l'utilisateur ne peut
        // rien choisir et la règle reste inutilisable.
        ConditionType.entries
            .filter { it.spec.kind == ValueKind.ENUM && it != ConditionType.FIRMWARE_GEN }
            .forEach { assertTrue("${it.name} : ENUM sans option", it.spec.options.isNotEmpty()) }

        ActionType.entries
            .filter { it.spec.kind == ValueKind.ENUM }
            .forEach { assertTrue("${it.name} : ENUM sans option", it.spec.options.isNotEmpty()) }
    }

    @Test
    fun `les bornes numeriques sont coherentes`() {
        val specs = ConditionType.entries.map { it.name to it.spec } +
                ActionType.entries.map { it.name to it.spec }

        specs.filter { it.second.kind == ValueKind.NUMBER }.forEach { (name, spec) ->
            if (spec.max >= 0) {
                assertTrue("$name : max doit dépasser min", spec.max > spec.min)
            } else {
                // max = -1 : borne résolue à l'exécution. Le repli doit rester utilisable
                // même si le véhicule ne répond pas.
                assertTrue("$name : fallbackMax doit dépasser min", spec.fallbackMax > spec.min)
            }
        }
    }
}
