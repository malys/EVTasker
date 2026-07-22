package com.mg4.tasker.model

/**
 * Reads [SupportedOn] off catalogue entries and answers "does this run on that car?".
 *
 * The reflection is done once per entry and memoised: enum entries are a closed set, and
 * re-reading annotations on every editor keystroke would be waste.
 */
object FirmwareSupport {

    private val conditionCache = HashMap<ConditionType, Set<FirmwareGen>?>()
    private val actionCache = HashMap<ActionType, Set<FirmwareGen>?>()

    /** Supported generations, or null when the entry is firmware-independent (works on all). */
    fun gensOf(type: ConditionType): Set<FirmwareGen>? =
        conditionCache.getOrPut(type) { read(ConditionType::class.java, type.name) }

    fun gensOf(type: ActionType): Set<FirmwareGen>? =
        actionCache.getOrPut(type) { read(ActionType::class.java, type.name) }

    /**
     * Is [type] usable on [gen]? A null [gen] means the firmware is unknown (no bridge, or
     * MG4Control did not report it) — in that case nothing is filtered out, because hiding
     * entries on a guess would be worse than showing one that later refuses.
     */
    fun isSupported(type: ConditionType, gen: FirmwareGen?): Boolean =
        supported(gensOf(type), gen)

    fun isSupported(type: ActionType, gen: FirmwareGen?): Boolean =
        supported(gensOf(type), gen)

    /** Parses the snapshot firmware string; null when absent or unrecognised. */
    fun parse(gen: String?): FirmwareGen? =
        gen?.let { runCatching { FirmwareGen.valueOf(it.uppercase()) }.getOrNull() }

    private fun supported(gens: Set<FirmwareGen>?, gen: FirmwareGen?): Boolean =
        gens == null || gen == null || gen in gens

    private fun <T> read(enumClass: Class<T>, name: String): Set<FirmwareGen>? =
        enumClass.getField(name).getAnnotation(SupportedOn::class.java)?.gens?.toSet()
}
