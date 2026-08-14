package com.evsuite.tasker.ui

import com.evsuite.hardware.catalog.ActionType

/**
 * Catalogue entries the user never picks, and therefore never has to read about.
 *
 * Both are duplicates of an entry that IS offered, with the same verdict and the same
 * vendor service behind them, so leaving them in produced two radio entries and two call
 * entries — in the picker, and again in the Diagnostic list.
 *
 * - [ActionType.PLAY_RADIO] is not a choice: "Tune and play radio" expands into it
 *   ([ActionBundles]), so it always appears in a rule that needs it.
 * - `CALL_CONTACT` is the deprecated alias kept only so rules saved by older releases still
 *   load; `CALL_NUMBER`'s editor already accepts a contact.
 *
 * Hidden from the two screens the driver reads, not from the engine or the exported debug
 * report: a rule imported from another car can still carry either one, and whoever debugs
 * it needs to see the verdict.
 */
internal object CatalogVisibility {

    @Suppress("DEPRECATION")
    val hiddenActions: Set<ActionType> = setOf(ActionType.PLAY_RADIO, ActionType.CALL_CONTACT)
}
