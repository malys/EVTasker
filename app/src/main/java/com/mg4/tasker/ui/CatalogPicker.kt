package com.mg4.tasker.ui

import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mg4.tasker.R
import com.mg4.tasker.databinding.DialogCatalogPickerBinding
import com.mg4.tasker.databinding.ItemCatalogEntryBinding
import com.mg4.tasker.databinding.ItemCatalogGroupBinding
import com.mg4.hardware.catalog.ActionType
import com.mg4.hardware.catalog.ActionGroup
import com.mg4.hardware.catalog.ConditionType
import com.mg4.hardware.catalog.ValueKind
import com.mg4.hardware.FirmwareGen
import com.mg4.hardware.FirmwareSupport
import com.mg4.tasker.store.SupportStore
import com.mg4.tasker.util.BtDevices
import com.mg4.tasker.util.SpeechEngines
import com.mg4.tasker.vehicle.ProfileBridge
import java.util.Locale

/**
 * Condition or action picker, grouped by theme.
 *
 * Full screen and two panes: the group rail on the left never moves, the entry grid on the
 * right changes. It replaces a bottom sheet, which on a landscape vehicle screen gave one
 * column over a third of the width — every catalogue past the fifth entry needed a scroll,
 * which is the worst gesture to ask for at the wheel. Here a group sits in the same place
 * on every opening, so the choice becomes muscle memory: one tap on the group, one tap on
 * the entry, no scrolling for five groups out of six.
 *
 * Still a dialog rather than an activity: picking an entry is a short step in the middle
 * of editing, and the rule being built must survive it untouched.
 *
 * Entries not supported on this car are hidden. The source is the stored support state
 * computed by the "check support" step ([SupportStore]); until that has ever run it falls
 * back to the live matrix filter on [firmware]. A null firmware (no bridge, or MG4Control
 * did not report one) hides nothing: filtering on a guess would be worse than offering an
 * entry that later refuses.
 */
object CatalogPicker {

    fun pickCondition(context: Context, firmware: FirmwareGen?, onPick: (ConditionType) -> Unit) {
        val allowed = SupportStore.supportedConditions(context)
        // Nothing paired means the device picker would be empty, and the rule would carry a
        // blank MAC that can never match. Same reasoning as the profile action below.
        val hasPairedDevice = BtDevices.bonded(context).isNotEmpty()
        val groups = ConditionType.byGroup().mapNotNull { (group, types) ->
            val supported = types
                .filter { hasPairedDevice || it.spec.kind != ValueKind.BT_DEVICE }
                .filter { type ->
                    allow(allowed, type.name) { FirmwareSupport.isSupported(type, firmware) }
                }
            if (supported.isEmpty()) return@mapNotNull null
            Group(
                label = context.getString(group.labelRes),
                entries = supported.map { type ->
                    Entry(context.getString(type.labelRes), null) { onPick(type) }
                }
            )
        }
        show(context, context.getString(R.string.editor_pick_condition), groups)
    }

    fun pickAction(context: Context, firmware: FirmwareGen?, onPick: (ActionType) -> Unit) {
        val allowed = SupportStore.supportedActions(context)
        // The profile actions are the ones that cannot work without MG4Control. Offering them
        // with an empty profile list only produces a rule that fails at ignition.
        val hasProfiles = ProfileBridge.isMG4ControlInstalled(context)
        val visible = ActionType.entries.filterNot { it in CatalogVisibility.hiddenActions }
        val grouped = visible.groupBy { type ->
            when (type) {
                ActionType.APPLY_PROFILE, ActionType.SHOW_PROFILE_PICKER,
                ActionType.WEBHOOK_GET, ActionType.WEBHOOK_POST -> IntegrationGroup
                else -> type.group
            }
        }
        val groups = grouped.mapNotNull { (group, types) ->
            val supported = types
                .filter { hasProfiles || it.group != ActionGroup.PROFILE }
                .filter { runnableHere(context, it) }
                .filter { type -> allow(allowed, type.name) { FirmwareSupport.isSupported(type, firmware) } }
            if (supported.isEmpty()) return@mapNotNull null
            Group(
                label = when (group) {
                    IntegrationGroup -> context.getString(R.string.group_integration)
                    is ActionGroup -> context.getString(group.labelRes)
                    else -> error("Unknown action group")
                },
                entries = supported.map { type ->
                    // The "when stopped only" badge shows BEFORE the choice, not after: the
                    // user must know an action will not apply while moving at the moment they
                    // pick it.
                    val note = when {
                        type == ActionType.TUNE_RADIO -> context.getString(R.string.editor_radio_sequence_hint)
                        type.gated -> context.getString(R.string.editor_gated_hint)
                        else -> null
                    }
                    val label = if (type == ActionType.TUNE_RADIO)
                        context.getString(R.string.editor_tune_and_play_radio)
                    else context.getString(type.labelRes)
                    Entry(label, note) { onPick(type) }
                }
            )
        }
        show(context, context.getString(R.string.editor_pick_action), groups)
    }

    // -------------------------------------------------------------------------

    /** Stored support wins when a check has run; otherwise fall back to the live matrix. */
    private inline fun allow(allowed: Set<String>?, name: String, live: () -> Boolean): Boolean =
        allowed?.contains(name) ?: live()

    /**
     * The local actions depend on the head unit, not on the firmware matrix, so the matrix
     * cannot filter them.
     *
     * Speech was the visible case: the Diagnostic tab reported "no speech engine" while the
     * editor kept offering the action, and picking it produced a rule that could only ever
     * fail. Both now ask [SpeechEngines] the same question — a package query, no vehicle
     * read, nothing that blocks.
     *
     * The message action is not filtered: it shows on screen whatever the notification
     * channel is doing, so it always has a way to reach the driver.
     */
    private fun runnableHere(context: Context, type: ActionType): Boolean = when (type) {
        ActionType.SPEAK_TEXT -> SpeechEngines.any(context)
        else -> true
    }

    private class Group(val label: String, val entries: List<Entry>)

    /** UI-only group: the shared catalogue remains the source of execution semantics. */
    private data object IntegrationGroup

    private class Entry(val label: String, val note: String?, val onClick: () -> Unit)

    private fun show(context: Context, title: String, groups: List<Group>) {
        val binding = DialogCatalogPickerBinding.inflate(LayoutInflater.from(context))
        val dialog = Dialog(context, R.style.Theme_MG4_Picker)
        dialog.setContentView(binding.root)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        binding.pickerTitle.text = title
        binding.pickerClose.setOnClickListener { dialog.dismiss() }

        val adapter = EntryAdapter { dialog.dismiss() }
        binding.pickerGrid.layoutManager = GridLayoutManager(context, spanCount(context))
        binding.pickerGrid.adapter = adapter
        // Every tile is the same height by construction, so the RecyclerView can skip
        // re-measuring itself each time the rail switches group.
        binding.pickerGrid.setHasFixedSize(true)

        // A support check that filtered everything leaves nothing to pick. Saying so beats
        // an empty grid that reads as a failure to load.
        binding.pickerEmpty.visibility = if (groups.isEmpty()) View.VISIBLE else View.GONE

        val rows = groups.map { group ->
            ItemCatalogGroupBinding.inflate(
                LayoutInflater.from(context), binding.pickerRail, false
            ).also { row ->
                row.groupLabel.text = group.label
                row.groupCount.text = String.format(Locale.getDefault(), "%d", group.entries.size)
                binding.pickerRail.addView(row.root)
            }
        }

        fun select(index: Int) {
            rows.forEachIndexed { position, row -> row.root.isSelected = position == index }
            adapter.submit(groups[index].entries)
            binding.pickerGrid.scrollToPosition(0)
        }

        rows.forEachIndexed { index, row -> row.root.setOnClickListener { select(index) } }
        if (groups.isNotEmpty()) select(0)

        dialog.show()
    }

    /**
     * Columns that fit next to the rail, from the screen the app is actually on rather than
     * a fixed count: the vehicle screen is wide, a bench emulator may not be.
     */
    private fun spanCount(context: Context): Int {
        val resources = context.resources
        val railDp = resources.getDimension(R.dimen.pane_rail_width) / resources.displayMetrics.density
        val tileDp = resources.getDimension(R.dimen.catalog_tile_min_width) / resources.displayMetrics.density
        val gridDp = resources.configuration.screenWidthDp - railDp
        return (gridDp / tileDp).toInt().coerceIn(1, 4)
    }

    private class EntryAdapter(private val onPicked: () -> Unit) :
        RecyclerView.Adapter<EntryAdapter.Holder>() {

        private var entries: List<Entry> = emptyList()

        /** At most thirteen tiles per group: a full rebind costs less than diffing them. */
        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun submit(next: List<Entry>) {
            entries = next
            notifyDataSetChanged()
        }

        override fun getItemCount() = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemCatalogEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = entries[position]
            holder.binding.entryLabel.text = entry.label
            holder.binding.entryNote.visibility = if (entry.note == null) View.GONE else View.VISIBLE
            holder.binding.entryNote.text = entry.note
            holder.binding.root.setOnClickListener { entry.onClick(); onPicked() }
        }

        class Holder(val binding: ItemCatalogEntryBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
