package com.evsuite.tasker.ui

import com.evsuite.hardware.catalog.ActionType
import com.evsuite.tasker.model.Action

/** Expands editor conveniences into the explicit sequence stored and executed by a rule. */
internal object ActionBundles {
    private const val RADIO_SETTLE_SECONDS = 1

    fun expand(action: Action): List<Action> = when (action.type) {
        ActionType.TUNE_RADIO -> listOf(
            action,
            Action(type = ActionType.DELAY, number = RADIO_SETTLE_SECONDS),
            Action(type = ActionType.PLAY_RADIO)
        )
        else -> listOf(action)
    }
}
