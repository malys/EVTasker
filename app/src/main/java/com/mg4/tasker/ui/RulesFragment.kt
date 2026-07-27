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
import com.mg4.tasker.store.LanguageStore
import com.mg4.tasker.store.RuleFiles
import com.mg4.tasker.store.RuleStore
import com.mg4.tasker.store.RuleTransfer
import com.mg4.tasker.util.BtDevices
import java.io.File

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

        binding.languageButton.text = getString(R.string.rules_language, currentLanguageName())
        binding.languageButton.setOnClickListener { pickLanguage() }

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
        binding.exportButton.setOnClickListener { exportRules() }
        binding.importButton.setOnClickListener { importRules() }
        binding.deleteButton.setOnClickListener { confirmDelete() }
        binding.runNowButton.setOnClickListener {
            // The test reuses the run service: testing a rule must take exactly the
            // vehicle-start path, otherwise the test proves nothing.
            TaskerRunService.start(requireContext())
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

    /** Display name of the current language choice, for the button label. */
    private fun currentLanguageName(): String {
        val tags = resources.getStringArray(R.array.language_tags)
        val names = resources.getStringArray(R.array.language_names)
        val index = tags.indexOf(LanguageStore.getTag(requireContext())).takeIf { it >= 0 } ?: 0
        return names[index]
    }

    private fun pickLanguage() {
        val tags = resources.getStringArray(R.array.language_tags)
        val names = resources.getStringArray(R.array.language_names)
        val current = tags.indexOf(LanguageStore.getTag(requireContext())).takeIf { it >= 0 } ?: 0
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rules_language_title)
            // Picking a language re-applies the per-app locale, which recreates the activity
            // with the new resources — no manual restart, the button label reflects it.
            .setSingleChoiceItems(names, current) { dialog, which ->
                dialog.dismiss()
                LanguageStore.setTag(requireContext(), tags[which])
            }
            .setNegativeButton(R.string.editor_cancel, null)
            .show()
    }

    // ---------- Import / export ----------

    private fun exportRules() {
        val rules = store.getAll()
        if (rules.isEmpty()) {
            toast(getString(R.string.rules_export_none))
            return
        }
        StorageBrowserDialog.pickFolder(requireContext(), R.string.rules_export_pick) { dir ->
            val file = RuleFiles.export(rules, dir)
            toastLong(
                if (file == null) getString(R.string.rules_export_failed)
                else getString(R.string.rules_export_ok, file.absolutePath)
            )
        }
    }

    private fun importRules() {
        StorageBrowserDialog.pickFile(
            requireContext(),
            RuleTransfer.FILE_EXTENSION,
            R.string.rules_import_pick
        ) { file ->
            when (val result = RuleFiles.read(file)) {
                is RuleTransfer.Result.Ok -> confirmImport(file, result.rules)
                // The user pointed at this exact file, so name what is wrong with it.
                else -> toastLong(rejectionMessage(file, result))
            }
        }
    }

    /** Import replaces the whole set, so it is confirmed the same way a delete is. */
    private fun confirmImport(file: File, rules: List<Rule>) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rules_import_title)
            .setMessage(getString(R.string.rules_import_confirm, file.name))
            .setNegativeButton(R.string.editor_cancel, null)
            .setPositiveButton(R.string.rules_import) { _, _ ->
                store.replaceAll(rules)
                selected = null
                refresh()
                toast(getString(R.string.rules_import_ok))
            }
            .show()
    }

    private fun rejectionMessage(file: File, result: RuleTransfer.Result): String {
        val invalid = result as? RuleTransfer.Result.Invalid
            ?: return getString(R.string.rules_import_err_malformed, file.name)
        return when (invalid.reason) {
            RuleTransfer.Reason.VERSION -> getString(R.string.rules_import_err_version, file.name)
            RuleTransfer.Reason.UNKNOWN_ENTRY ->
                getString(R.string.rules_import_err_unknown, file.name, invalid.detail)
            RuleTransfer.Reason.TOO_MANY ->
                getString(R.string.rules_import_err_too_many, file.name, RuleStore.MAX_RULES)
            RuleTransfer.Reason.EMPTY -> getString(R.string.rules_import_err_empty, file.name)
            RuleTransfer.Reason.MALFORMED -> getString(R.string.rules_import_err_malformed, file.name)
        }
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

    /** Storage paths and import refusals need reading time; a short toast is gone too soon. */
    private fun toastLong(message: String) =
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
