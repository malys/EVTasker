package com.mg4.tasker.store

import android.content.Context
import com.mg4.hardware.FirmwareGen
import com.mg4.hardware.FirmwareInfo
import com.mg4.hardware.FirmwareSupport
import com.mg4.hardware.MG4Hardware
import com.mg4.hardware.catalog.ActionType
import com.mg4.hardware.catalog.ConditionType
import com.mg4.tasker.util.Notifier
import com.mg4.tasker.util.SpeechEngines

/**
 * Runs the "check support of MG4 properties" step and stores its result (see [SupportStore]).
 *
 * The check walks the whole catalogue and asks the MG4Hardware firmware matrix
 * ([FirmwareSupport]) whether each condition/action runs on the detected generation. It is
 * run automatically on first launch and after an update ([ensureChecked]) and can be re-run
 * on demand from the Diagnostic screen ([refresh]).
 *
 * The MG4Hardware calls are blocking (system-property reads, reflection): never call on the
 * main thread.
 */
object SupportChecker {

    /** Runs a check only if none was recorded for the current app version yet. */
    fun ensureChecked(context: Context) {
        if (SupportStore.needsCheck(context)) refresh(context)
    }

    /** Always recomputes the supported set for the current firmware and overwrites the store. */
    fun refresh(context: Context) {
        MG4Hardware.init(context.applicationContext)   // idempotent
        // UNKNOWN parses to null, which the matrix treats as "hide nothing": everything is
        // stored as supported rather than guessing a generation and filtering wrongly.
        val gen = FirmwareSupport.parse(FirmwareInfo.getGeneration().name)
        SupportStore.save(
            context,
            gen = gen?.name,
            conditions = supportedConditionNames(gen),
            actions = supportedActionNames(gen) - unavailableLocalActions(context),
        )
    }

    /**
     * Local actions the head unit cannot perform, whatever the firmware says.
     *
     * The matrix only knows about vehicle capabilities, so it counts "speak" as supported on
     * a car with no speech engine — and the Diagnostic summary then advertises an action the
     * same screen reports as blocked two rows below.
     */
    private fun unavailableLocalActions(context: Context): Set<String> = buildSet {
        if (!SpeechEngines.any(context)) add(ActionType.SPEAK_TEXT.name)
        if (!Notifier.canNotify(context)) add(ActionType.SHOW_NOTIFICATION.name)
    }

    // Pure — no Android, no hardware state. Split out so the matrix filter is unit-testable.
    fun supportedConditionNames(gen: FirmwareGen?): Set<String> =
        ConditionType.entries.filter { FirmwareSupport.isSupported(it, gen) }.map { it.name }.toSet()

    fun supportedActionNames(gen: FirmwareGen?): Set<String> =
        ActionType.entries.filter { FirmwareSupport.isSupported(it, gen) }.map { it.name }.toSet()
}
