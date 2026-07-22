package com.mg4.tasker.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mg4.tasker.R
import com.mg4.tasker.databinding.FragmentRulesBinding
import com.mg4.tasker.model.Rule
import com.mg4.tasker.service.TaskerRunService
import com.mg4.tasker.store.AppState
import com.mg4.tasker.store.RuleStore
import com.mg4.tasker.util.BtDevices

class RulesFragment : Fragment() {

    private var _binding: FragmentRulesBinding? = null
    private val binding get() = _binding!!

    private lateinit var store: RuleStore
    private lateinit var adapter: RuleAdapter
    private var selected: Rule? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentRulesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        store = RuleStore(requireContext())

        binding.automationSwitch.isChecked = AppState.isAutomationEnabled(requireContext())
        binding.automationSwitch.setOnCheckedChangeListener { _, checked ->
            AppState.setAutomationEnabled(requireContext(), checked)
        }

        adapter = RuleAdapter(
            onSelect = { rule -> selected = rule; refresh() },
            onToggle = { rule, enabled -> store.setEnabled(rule.id, enabled) }
        )
        binding.rulesList.layoutManager = LinearLayoutManager(requireContext())
        binding.rulesList.adapter = adapter

        binding.newRuleButton.setOnClickListener {
            if (store.getAll().size >= RuleStore.MAX_RULES) {
                toast(getString(R.string.rules_quota_reached))
            } else {
                startActivity(RuleEditorActivity.intentForNew(requireContext()))
            }
        }
        binding.editButton.setOnClickListener {
            selected?.let { startActivity(RuleEditorActivity.intentForEdit(requireContext(), it.id)) }
        }
        binding.deleteButton.setOnClickListener { confirmDelete() }
        binding.runNowButton.setOnClickListener {
            // The test reuses the run service: testing a rule must take exactly the
            // vehicle-start path, otherwise the test proves nothing.
            TaskerRunService.start(requireContext(), TaskerRunService.TRIGGER_MANUAL)
            toast(getString(R.string.rules_running))
        }
    }

    override fun onResume() {
        super.onResume()
        // The editor is a separate activity: re-read storage on the way back.
        refresh()
    }

    private fun refresh() {
        val rules = store.getAll()
        // The selected rule may have been deleted or renamed in the editor.
        selected = rules.firstOrNull { it.id == selected?.id }
        adapter.submit(rules, selected?.id)

        val current = selected
        if (current == null) {
            binding.emptyState.visibility = View.VISIBLE
            binding.detailScroll.visibility = View.GONE
            binding.emptyTitle.setText(if (rules.isEmpty()) R.string.rules_empty_title else R.string.rules_select_hint)
            binding.emptyBody.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
            return
        }

        binding.emptyState.visibility = View.GONE
        binding.detailScroll.visibility = View.VISIBLE

        val labels = Labels(requireContext(), btNames = BtDevices.bondedNamesByMac(requireContext()))
        binding.detailName.text = current.name
        binding.detailConditions.text = current.conditions.joinToString("\n") { "• " + labels.describe(it) }
        binding.detailActions.text = current.actions.joinToString("\n") { "• " + labels.describe(it) }
    }

    private fun confirmDelete() {
        val rule = selected ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(getString(R.string.rules_delete_confirm, rule.name))
            .setNegativeButton(R.string.editor_cancel, null)
            .setPositiveButton(R.string.rules_delete) { _, _ ->
                store.delete(rule.id)
                selected = null
                refresh()
            }
            .show()
    }

    private fun toast(message: String) =
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
