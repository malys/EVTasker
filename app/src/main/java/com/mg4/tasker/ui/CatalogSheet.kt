package com.mg4.tasker.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.mg4.tasker.R
import com.mg4.tasker.databinding.ItemCatalogEntryBinding
import com.mg4.tasker.databinding.SheetCatalogBinding
import com.mg4.tasker.model.ActionType
import com.mg4.tasker.model.ConditionType
import com.mg4.tasker.model.FirmwareGen
import com.mg4.tasker.model.FirmwareSupport

/**
 * Condition or action picker, grouped by theme.
 *
 * A sheet rather than a new screen: picking an entry is a short step in the middle of
 * editing, and switching activity would hide the rule being built.
 *
 * [firmware] is the connected car's generation, when known. Entries not supported on it
 * are hidden — but a null firmware (no bridge, or MG4Control did not report one) hides
 * nothing: filtering on a guess would be worse than offering an entry that later refuses.
 */
object CatalogSheet {

    fun pickCondition(context: Context, firmware: FirmwareGen?, onPick: (ConditionType) -> Unit) {
        val entries = mutableListOf<Entry>()
        ConditionType.byGroup().forEach { (group, types) ->
            val supported = types.filter { FirmwareSupport.isSupported(it, firmware) }
            if (supported.isEmpty()) return@forEach
            entries += Entry.Header(context.getString(group.labelRes))
            supported.forEach { entries += Entry.Item(context.getString(it.labelRes), null) { onPick(it) } }
        }
        show(context, context.getString(R.string.editor_pick_condition), entries)
    }

    fun pickAction(context: Context, firmware: FirmwareGen?, onPick: (ActionType) -> Unit) {
        val entries = mutableListOf<Entry>()
        ActionType.byGroup().forEach { (group, types) ->
            val supported = types.filter { FirmwareSupport.isSupported(it, firmware) }
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
