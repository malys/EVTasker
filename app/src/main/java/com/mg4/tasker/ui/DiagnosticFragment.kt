package com.mg4.tasker.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mg4.hardware.MG4Hardware
import com.mg4.tasker.R
import com.mg4.tasker.databinding.FragmentDiagnosticBinding
import com.mg4.tasker.databinding.ItemDiagnosticBinding
import com.mg4.hardware.catalog.ConditionType
import com.mg4.tasker.model.Snapshot
import com.mg4.hardware.catalog.ValueKind
import com.mg4.tasker.vehicle.BtTracker
import com.mg4.tasker.vehicle.VehicleReader
import kotlin.concurrent.thread

/**
 * What MG4Tasker can actually read on THIS vehicle — read directly through MG4Hardware.
 *
 * Rationale: the condition catalogue is wider than what every firmware exposes. Outside
 * temperature in particular has not been verified on a vehicle. This screen lets you see
 * that a signal is unreadable BEFORE writing a rule that will never fire — without it,
 * the only symptom would be "my rule does not work".
 */
class DiagnosticFragment : Fragment() {

    private var _binding: FragmentDiagnosticBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentDiagnosticBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.diagList.layoutManager = LinearLayoutManager(requireContext())
        binding.refreshButton.setOnClickListener { load() }
        load()
    }

    private fun load() {
        binding.bridgeStatus.setText(R.string.diag_reading)
        binding.bridgeHint.visibility = View.GONE

        // MG4Hardware reflection reads are blocking: never on the main thread.
        thread(name = "mg4-tasker-diag") {
            MG4Hardware.init(requireContext().applicationContext)   // idempotent
            val snapshot = VehicleReader.read(BtTracker.snapshot())
            Handler(Looper.getMainLooper()).post {
                if (_binding != null) render(snapshot)
            }
        }
    }

    private fun render(snapshot: Snapshot) {
        binding.bridgeStatus.setText(
            if (snapshot.bridgeAvailable) R.string.diag_bridge_ok else R.string.diag_bridge_ko
        )
        binding.bridgeHint.visibility = if (snapshot.bridgeAvailable) View.GONE else View.VISIBLE

        // The diagnostic walks the catalogue, not a hand-written list: a condition added
        // to the catalogue shows up here with no change.
        val rows = ConditionType.entries
            .filter { it.snapshotKey != null }
            .map { type -> Row(getString(type.labelRes), formatValue(type, snapshot)) }

        binding.diagList.adapter = Adapter(rows)
    }

    private fun formatValue(type: ConditionType, snapshot: Snapshot): String {
        val key = type.snapshotKey ?: return getString(R.string.diag_unavailable)

        return when (type.spec.kind) {
            ValueKind.BOOL -> snapshot.bool(key)
                ?.let { getString(if (it) R.string.value_enabled else R.string.value_disabled) }
                ?: getString(R.string.diag_unavailable)

            ValueKind.NUMBER -> snapshot.number(key)?.let { value ->
                val unit = type.spec.unitRes.takeIf { it != 0 }?.let { " " + getString(it) } ?: ""
                "$value$unit"
            } ?: getString(R.string.diag_unavailable)

            ValueKind.ENUM -> {
                snapshot.string(key)
                    ?: snapshot.int(key)?.let { raw ->
                        val label = type.spec.options.firstOrNull { it.value == raw }
                            ?.let { getString(it.labelRes) }
                        // Raw value kept next to the label: an unexpected integer is the
                        // sign of a firmware that does not use the same codes.
                        if (label != null) "$label ($raw)" else raw.toString()
                    }
                    ?: getString(R.string.diag_unavailable)
            }

            else -> getString(R.string.diag_unavailable)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private data class Row(val label: String, val value: String)

    private class Adapter(private val rows: List<Row>) : RecyclerView.Adapter<Adapter.Holder>() {

        class Holder(val binding: ItemDiagnosticBinding) : RecyclerView.ViewHolder(binding.root)

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            ItemDiagnosticBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.binding.diagLabel.text = rows[position].label
            holder.binding.diagValue.text = rows[position].value
        }
    }
}
