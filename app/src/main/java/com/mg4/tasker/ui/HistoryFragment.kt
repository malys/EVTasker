package com.mg4.tasker.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mg4.tasker.R
import com.mg4.tasker.databinding.FragmentHistoryBinding
import com.mg4.tasker.databinding.ItemHistoryRunBinding
import com.mg4.tasker.model.EngineRun
import com.mg4.tasker.model.RuleOutcome
import com.mg4.tasker.model.RuleStatus
import com.mg4.tasker.model.RuleTrigger
import com.mg4.tasker.store.HistoryStore
import com.mg4.tasker.vehicle.DeferredWrites
import com.mg4.tasker.vehicle.RuleCycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * History of triggers.
 *
 * This is where the difference reads between "the rule did not match", "MG4Control
 * refused because the car was moving" and "the data was unreadable". All three produce
 * the same visible effect — nothing changes in the car — and without this screen the
 * user could not tell them apart.
 */
class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val store = HistoryStore(requireContext())
        binding.historyList.layoutManager = LinearLayoutManager(requireContext())
        binding.clearButton.setOnClickListener { store.clear(); render(store) }
        render(store)
    }

    override fun onResume() {
        super.onResume()
        render(HistoryStore(requireContext()))
    }

    private fun render(store: HistoryStore) {
        val runs = store.getAll()
        binding.emptyState.visibility = if (runs.isEmpty()) View.VISIBLE else View.GONE
        binding.historyList.visibility = if (runs.isEmpty()) View.GONE else View.VISIBLE
        binding.clearButton.visibility = if (runs.isEmpty()) View.GONE else View.VISIBLE
        binding.historyList.adapter = Adapter(runs, Labels(requireContext()))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class Adapter(
        private val runs: List<EngineRun>,
        private val labels: Labels
    ) : RecyclerView.Adapter<Adapter.Holder>() {

        private val timeFormat = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())

        class Holder(val binding: ItemHistoryRunBinding) : RecyclerView.ViewHolder(binding.root)

        override fun getItemCount() = runs.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            ItemHistoryRunBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val run = runs[position]
            val context = holder.itemView.context

            // Named individually now that a run has four possible origins. "Vehicle start"
            // over a switch-off cycle, or over a write applied later at a red light, would
            // put the reader on the wrong end of the drive.
            val trigger = context.getString(
                when (run.trigger) {
                    RuleCycle.MANUAL -> R.string.history_trigger_manual
                    DeferredWrites.TRIGGER -> R.string.history_trigger_deferred
                    RuleTrigger.IGNITION_OFF.name -> R.string.history_trigger_ignition_off
                    RuleTrigger.PHYSICAL_BUTTON.name -> R.string.history_trigger_button
                    else -> R.string.history_trigger_ignition
                }
            )
            holder.binding.runHeader.text = "${timeFormat.format(Date(run.timestamp))} — $trigger"

            holder.binding.runWarning.visibility = if (run.bridgeAvailable) View.GONE else View.VISIBLE
            holder.binding.runWarning.setText(R.string.history_no_bridge)

            holder.binding.runDetail.text = run.ruleRuns.joinToString("\n") { ruleRun ->
                val outcome = context.getString(
                    when (ruleRun.outcome) {
                        RuleOutcome.FIRED         ->
                            if (ruleRun.status == RuleStatus.FAILED) R.string.outcome_failed else R.string.outcome_fired
                        RuleOutcome.NOT_MATCHED   -> R.string.outcome_not_matched
                        RuleOutcome.NOT_EVALUABLE -> R.string.outcome_not_evaluable
                        RuleOutcome.DISABLED      -> R.string.outcome_disabled
                    }
                )
                buildString {
                    append("• ${ruleRun.ruleName} : $outcome")
                    // Name the missing data: "cannot be evaluated" alone does not help fix it.
                    if (ruleRun.unavailableConditions.isNotEmpty()) {
                        val names = ruleRun.unavailableConditions
                            .joinToString(", ") { context.getString(it.labelRes) }
                        append("\n    ")
                        append(context.getString(R.string.outcome_unavailable_detail, names))
                    }
                    ruleRun.actionResults.forEach { append("\n    – ${labels.describe(it)}") }
                }
            }
        }
    }
}
