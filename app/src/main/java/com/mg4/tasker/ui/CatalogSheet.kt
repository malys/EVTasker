package com.mg4.tasker.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.mg4.tasker.R
import com.mg4.tasker.databinding.ItemCatalogEntryBinding
import com.mg4.tasker.databinding.SheetCatalogBinding
import com.mg4.hardware.catalog.ActionType
import com.mg4.hardware.catalog.ConditionType
import com.mg4.hardware.catalog.ValueKind
import com.mg4.hardware.FirmwareGen
import com.mg4.hardware.FirmwareSupport
import com.mg4.tasker.store.SupportStore
import com.mg4.tasker.vehicle.ProfileBridge

/**
 * Condition or action picker, grouped by theme.
 *
 * A sheet rather than a new screen: picking an entry is a short step in the middle of
 * editing, and switching activity would hide the rule being built.
 *
 * Entries not supported on this car are hidden. The source is the stored support state
 * computed by the "check support" step ([SupportStore]); until that has ever run it falls
 * back to the live matrix filter on [firmware]. A null firmware (no bridge, or MG4Control
 * did not report one) hides nothing: filtering on a guess would be worse than offering an
 * entry that later refuses.
 */
object CatalogSheet {

    fun pickCondition(context: Context, firmware: FirmwareGen?, onPick: (ConditionType) -> Unit) {
        val allowed = SupportStore.supportedConditions(context)
        val entries = mutableListOf<Entry>()
        ConditionType.byGroup().forEach { (group, types) ->
            val supported = types.filter { allow(allowed, it.name) { FirmwareSupport.isSupported(it, firmware) } }
            if (supported.isEmpty()) return@forEach
            entries += Entry.Header(context.getString(group.labelRes))
            supported.forEach { entries += Entry.Item(context.getString(it.labelRes), null) { onPick(it) } }
        }
        show(context, context.getString(R.string.editor_pick_condition), entries)
    }

    fun pickAction(context: Context, firmware: FirmwareGen?, onPick: (ActionType) -> Unit) {
        val allowed = SupportStore.supportedActions(context)
        // Applying a profile is the one action that cannot work without MG4Control. Offering
        // it with an empty profile list only produces a rule that fails at ignition.
        val hasProfiles = ProfileBridge.isMG4ControlInstalled(context)
        val entries = mutableListOf<Entry>()
        ActionType.byGroup().forEach { (group, types) ->
            val supported = types
                .filter { hasProfiles || it.spec.kind != ValueKind.PROFILE }
                .filter { allow(allowed, it.name) { FirmwareSupport.isSupported(it, firmware) } }
            if (supported.isEmpty()) return@forEach
            entries += Entry.Header(context.getString(group.labelRes))
            supported.forEach { type ->
                // The "when stopped only" badge shows BEFORE the choice, not after: the
                // user must know an action will not apply while moving at the moment they
                // pick it.
                val note = if (type.gated) context.getString(R.string.editor_gated_hint) else null
                entries += Entry.Item(context.getString(type.labelRes), note) { onPick(type) }
            }
        }
        show(context, context.getString(R.string.editor_pick_action), entries)
    }

    // -------------------------------------------------------------------------

    /** Stored support wins when a check has run; otherwise fall back to the live matrix. */
    private inline fun allow(allowed: Set<String>?, name: String, live: () -> Boolean): Boolean =
        allowed?.contains(name) ?: live()

    private sealed interface Entry {
        data class Header(val label: String) : Entry
        data class Item(val label: String, val note: String?, val onClick: () -> Unit) : Entry
    }

    private fun show(context: Context, title: String, entries: List<Entry>) {
        val binding = SheetCatalogBinding.inflate(LayoutInflater.from(context))
        val dialog = BottomSheetDialog(context)
        binding.catalogTitle.text = title
        binding.catalogList.layoutManager = LinearLayoutManager(context)
        binding.catalogList.adapter = Adapter(entries) { dialog.dismiss() }
        dialog.setContentView(binding.root)

        // The app is locked to landscape, and a bottom sheet in landscape opens COLLAPSED
        // at a peek height computed from the screen ratio — on the vehicle screen that is a
        // 64 dp strip showing the title and none of the list. The picker then looks broken
        // and no condition can be chosen, which makes the rule unsaveable.
        // Opening expanded (and full height) is the only usable state here; there is
        // nothing behind the sheet worth peeking at.
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?: return@setOnShowListener
            sheet.layoutParams = sheet.layoutParams.apply {
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
            dialog.behavior.apply {
                skipCollapsed = true
                state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        dialog.show()
    }

    private class Adapter(
        private val entries: List<Entry>,
        private val onPicked: () -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            private const val TYPE_HEADER = 0
            private const val TYPE_ITEM = 1
        }

        override fun getItemCount() = entries.size

        override fun getItemViewType(position: Int) =
            if (entries[position] is Entry.Header) TYPE_HEADER else TYPE_ITEM

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_HEADER) {
                object : RecyclerView.ViewHolder(
                    inflater.inflate(R.layout.item_catalog_header, parent, false)
                ) {}
            } else {
                ItemHolder(ItemCatalogEntryBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val entry = entries[position]) {
                is Entry.Header ->
                    (holder.itemView as android.widget.TextView).text = entry.label

                is Entry.Item -> {
                    val binding = (holder as ItemHolder).binding
                    binding.entryLabel.text = entry.label
                    binding.entryNote.visibility = if (entry.note == null) View.GONE else View.VISIBLE
                    binding.entryNote.text = entry.note
                    binding.root.setOnClickListener { entry.onClick(); onPicked() }
                }
            }
        }

        private class ItemHolder(val binding: ItemCatalogEntryBinding) :
            RecyclerView.ViewHolder(binding.root)
    }
}
