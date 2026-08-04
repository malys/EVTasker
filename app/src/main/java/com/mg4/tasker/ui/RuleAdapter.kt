package com.mg4.tasker.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mg4.tasker.databinding.ItemRuleBinding
import com.mg4.tasker.model.Rule

class RuleAdapter(
    private val onSelect: (Rule) -> Unit,
    private val onToggle: (Rule, Boolean) -> Unit
) : RecyclerView.Adapter<RuleAdapter.Holder>() {

    private var rules: List<Rule> = emptyList()
    private var selectedId: String? = null

    fun submit(newRules: List<Rule>, selectedRuleId: String?) {
        rules = newRules
        selectedId = selectedRuleId
        notifyDataSetChanged()
    }

    class Holder(val binding: ItemRuleBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemRuleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun getItemCount() = rules.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val rule = rules[position]
        val context = holder.itemView.context

        holder.binding.ruleName.text = rule.name
        // A branched rule counts cases, not conditions: only one case runs, so adding up the
        // conditions of all of them would describe a rule that does not exist. The detail
        // pane is where each case is spelled out.
        holder.binding.ruleSummary.text = if (rule.hasAlternatives) {
            val cases = rule.branches.size + if (rule.otherwise.isEmpty()) 0 else 1
            context.resources.getQuantityString(
                com.mg4.tasker.R.plurals.rule_summary_cases, cases, cases
            )
        } else {
            context.resources.getQuantityString(
                com.mg4.tasker.R.plurals.rule_summary_conditions, rule.conditions.size, rule.conditions.size
            ) + " → " + context.resources.getQuantityString(
                com.mg4.tasker.R.plurals.rule_summary_actions, rule.actions.size, rule.actions.size
            )
        }

        // setOnCheckedChangeListener(null) before setChecked: without it, view recycling
        // fires the previous rule's listener and toggles the wrong rule.
        holder.binding.ruleEnabled.setOnCheckedChangeListener(null)
        holder.binding.ruleEnabled.isChecked = rule.enabled
        holder.binding.ruleEnabled.setOnCheckedChangeListener { _, checked -> onToggle(rule, checked) }

        (holder.binding.root as com.google.android.material.card.MaterialCardView).isChecked =
            rule.id == selectedId
        holder.itemView.setOnClickListener { onSelect(rule) }
    }
}
