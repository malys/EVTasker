package com.mg4.tasker.model

/**
 * Declares which firmware generations a catalogue entry works on.
 *
 * Placed on the entries of [ConditionType] and [ActionType]. It is the single source of
 * truth for three things:
 *   • self-documenting source — the support set sits next to the entry
 *   • the README compatibility matrix — generated from these annotations, never
 *     hand-maintained (see `FirmwareMatrix`)
 *   • the runtime filter — the editor hides an entry on a car whose firmware is not
 *     listed (see `FirmwareSupport`)
 *
 * A catalogue entry with NO `@SupportedOn` is firmware-independent (time, day, Bluetooth,
 * launch app, notify): it works everywhere and is never filtered out.
 *
 * RUNTIME retention because the filter and the generator read it by reflection.
 * FIELD target because Kotlin enum entries are compiled to fields.
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class SupportedOn(vararg val gens: FirmwareGen)
