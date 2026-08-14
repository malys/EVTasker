package com.evsuite.tasker.engine

import com.evsuite.hardware.FirmwareGen
import com.evsuite.hardware.FirmwareSupport
import com.evsuite.hardware.catalog.ActionType

/** One firmware decision shared by diagnostics and runtime execution. */
object ActionCompatibility {

    /**
     * Firmware-specific actions require a positively identified supported generation.
     * Local actions carry no [com.evsuite.hardware.SupportedOn] annotation and work without one.
     */
    fun isConfirmed(type: ActionType, generation: FirmwareGen?): Boolean {
        val declared = FirmwareSupport.gensOf(type) ?: return true
        return generation != null && generation in declared
    }
}
