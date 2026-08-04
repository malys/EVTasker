package com.mg4.tasker.engine

import com.mg4.hardware.FirmwareGen
import com.mg4.hardware.FirmwareSupport
import com.mg4.hardware.catalog.ActionType

/** One firmware decision shared by diagnostics and runtime execution. */
object ActionCompatibility {

    /**
     * Firmware-specific actions require a positively identified supported generation.
     * Local actions carry no [com.mg4.hardware.SupportedOn] annotation and work without one.
     */
    fun isConfirmed(type: ActionType, generation: FirmwareGen?): Boolean {
        val declared = FirmwareSupport.gensOf(type) ?: return true
        return generation != null && generation in declared
    }
}
