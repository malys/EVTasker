package com.evsuite.tasker.ui

import com.evsuite.hardware.catalog.ActionType
import com.evsuite.tasker.model.Action

/** Expands editor conveniences into the explicit sequence stored and executed by a rule. */
internal object ActionBundles {
    private const val RADIO_SETTLE_SECONDS = 1

    /**
     * The play tail is opt-out ([Action.flag], on by default): tuning a station without
     * starting playback is a real need, and rules saved before the switch existed carry the
     * default and keep expanding as they did.
     */
    fun expand(action: Action): List<Action> = when {
        action.type == ActionType.TUNE_RADIO && action.flag -> listOf(
            action,
            Action(type = ActionType.DELAY, number = RADIO_SETTLE_SECONDS),
            Action(type = ActionType.PLAY_RADIO)
        )
        else -> listOf(action)
    }

    /**
     * Puts the play tail back in step with the switch after an existing tune action is
     * edited: the tail is made of ordinary rows the user can also move or delete, so it is
     * only touched when it still sits right behind the action being edited.
     */
    fun resync(actions: MutableList<Action>, index: Int) {
        val tune = actions[index]
        if (tune.type != ActionType.TUNE_RADIO) return
        val tail = expand(tune).drop(1)
        val hasTail = actions.getOrNull(index + 1)?.type == ActionType.DELAY &&
            actions.getOrNull(index + 2)?.type == ActionType.PLAY_RADIO
        when {
            tail.isEmpty() && hasTail -> repeat(2) { actions.removeAt(index + 1) }
            tail.isNotEmpty() && !hasTail -> actions.addAll(index + 1, tail)
        }
    }
}
