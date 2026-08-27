package com.evsuite.tasker.store

import android.content.Context
import com.evsuite.hardware.FirmwareGen
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.FirmwareSupport
import com.evsuite.hardware.EVHardware
import com.evsuite.hardware.catalog.ActionType
import com.evsuite.hardware.catalog.ConditionType
import com.evsuite.hardware.effectProven
import com.evsuite.tasker.util.SpeechEngines

/**
 * Runs the "check support of MG4 properties" step and stores its result (see [SupportStore]).
 *
 * The check walks the whole catalogue and asks the EVHardware firmware matrix
 * ([FirmwareSupport]) whether each condition/action runs on the detected generation. It is
 * run automatically on first launch and after an update ([ensureChecked]) and can be re-run
 * on demand from the Diagnostic screen ([refresh]).
 *
 * The EVHardware calls are blocking (system-property reads, reflection): never call on the
 * main thread.
 */
object SupportChecker {

    /** Runs a check only if none was recorded for the current app version yet. */
    fun ensureChecked(context: Context) {
        if (SupportStore.needsCheck(context)) refresh(context)
    }

    /** Always recomputes the supported set for the current firmware and overwrites the store. */
    fun refresh(context: Context) {
        EVHardware.init(context.applicationContext)   // idempotent
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
    }

    // Pure — no Android, no hardware state. Split out so the matrix filter is unit-testable.
    fun supportedConditionNames(gen: FirmwareGen?): Set<String> =
        ConditionType.entries.filter { FirmwareSupport.isSupported(it, gen) }.map { it.name }.toSet()

    /**
     * [ActionType.writeProven] filters here as well as in the matrix: an unknown generation
     * hides nothing, deliberately, and an action nobody has seen do anything must not slip
     * back into the picker through that door.
     */
    fun supportedActionNames(gen: FirmwareGen?): Set<String> =
        ActionType.entries
            .filter { FirmwareSupport.isSupported(it, gen) && it.effectProven }
            .map { it.name }
            .toSet()
}
