package com.mg4.tasker.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mg4.tasker.R
import com.mg4.hardware.VehicleWriteGate
import com.mg4.tasker.databinding.DialogValueEditorBinding
import com.mg4.tasker.databinding.FragmentRulesBinding
import com.mg4.tasker.model.EngineRun
import com.mg4.tasker.model.Rule
import com.mg4.tasker.service.TaskerRunService
import com.mg4.tasker.store.AppState
import com.mg4.tasker.store.LanguageStore
import com.mg4.tasker.store.RuleFiles
import com.mg4.tasker.store.RuleStore
import com.mg4.tasker.store.RuleTransfer
import com.mg4.tasker.util.BtDevices
import com.mg4.tasker.vehicle.CycleReporter
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

        // The button sits in a third of the list pane: only a code fits without being
        // truncated. The full "Language: X" wording stays as the accessibility label.
        binding.languageButton.text = currentLanguageCode()
        binding.languageButton.contentDescription =
            getString(R.string.rules_language, currentLanguageName())
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
        binding.runNowButton.setOnClickListener { runNow() }
        binding.thresholdButton.setOnClickListener { editThreshold() }
        renderThreshold()
    }

    private fun renderThreshold() {
        val kmh = AppState.writeThresholdKmh(requireContext()).toInt()
        binding.thresholdButton.text =
            if (kmh == 0) getString(R.string.rules_threshold_standstill)
            else getString(R.string.rules_threshold_value, kmh)
    }

    /**
     * Chooses the speed up to which a gated action may still be applied.
     *
     * Zero is the default and the safe answer. Raising it does not make the vehicle accept a
     * drive-mode change at 40 km/h — the car refuses what it refuses — it only stops
     * MG4Tasker from being the one that declines first, which is what makes a rule fail on a
     * car that is merely creeping forward. The dialog says so before the slider.
     */
    private fun editThreshold() {
        val binding = DialogValueEditorBinding.inflate(layoutInflater)
        binding.numberBlock.visibility = View.VISIBLE
        binding.numberSlider.valueFrom = 0f
        binding.numberSlider.valueTo = VehicleWriteGate.MAX_ALLOWED_THRESHOLD_KMH
        binding.numberSlider.stepSize = 5f
        var chosen = AppState.writeThresholdKmh(requireContext())
        // The stored value predates the step, or came from another build: snap it in range.
        chosen = (Math.round(chosen / 5f) * 5f).coerceIn(0f, VehicleWriteGate.MAX_ALLOWED_THRESHOLD_KMH)
        binding.numberSlider.value = chosen
        // The unit label lives with the catalogue, in the library's resources.
        val unit = " " + getString(com.mg4.hardware.R.string.unit_kmh)
        binding.numberValue.text = "${chosen.toInt()}$unit"
        binding.numberSlider.addOnChangeListener { _, value, _ ->
            chosen = value
            binding.numberValue.text = "${value.toInt()}$unit"
        }
        binding.gatedExplain.visibility = View.VISIBLE
        binding.gatedExplain.text = getString(R.string.rules_threshold_explain)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rules_threshold_title)
            .setView(binding.root)
            .setNegativeButton(R.string.editor_cancel, null)
            .setPositiveButton(R.string.value_ok) { _, _ ->
                AppState.setWriteThresholdKmh(requireContext(), chosen)
                renderThreshold()
            }
            .show()
    }

    /**
     * Runs one cycle and shows what it decided.
     *
     * The button used to end at "test running", which answers nothing: the point of testing
     * a rule is to learn whether it would fire. The cycle happens in a service, so the result
     * comes back through [CycleReporter] and lands in a dialog naming every rule and its
     * outcome — including the action verdicts behind a failure, which is where "applied" and
     * "refused, vehicle moving" part company.
     */
    private fun runNow() {
        toast(getString(R.string.rules_running))
        val handler = Handler(Looper.getMainLooper())
        CycleReporter.listener = { run ->
            handler.post {
                CycleReporter.listener = null
                if (_binding != null) showRunResult(run)
            }
        }
        // The test reuses the run service: testing a rule must take exactly the
        // vehicle-start path, otherwise the test proves nothing.
        TaskerRunService.start(requireContext())
    }

    private fun showRunResult(run: EngineRun) {
        val labels = Labels(requireContext(), btNames = BtDevices.bondedNamesByMac(requireContext()))
        val body = if (run.ruleRuns.isEmpty()) {
            getString(R.string.rules_result_none)
        } else {
            run.ruleRuns.joinToString("\n\n") { ruleRun ->
                buildString {
                    append("${ruleRun.ruleName} — ${labels.outcomeLabel(ruleRun)}")
                    // Only for a rule that fired: for the others the outcome is the whole
                    // story, and listing actions nobody attempted would suggest otherwise.
                    ruleRun.actionResults.forEach { append("\n   • " + labels.describe(it)) }
                    if (ruleRun.unavailableConditions.isNotEmpty()) {
                        val names = ruleRun.unavailableConditions.joinToString(", ") {
                            getString(it.labelRes)
                        }
                        append("\n   " + getString(R.string.outcome_unavailable_detail, names))
                    }
                }
            }
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rules_result_title)
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
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
        binding.detailName.text = current.name + "  ·  " + getString(
            if (current.firesOn == com.mg4.tasker.model.RuleTrigger.IGNITION_OFF)
                R.string.editor_trigger_off else R.string.editor_trigger_on
        )
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

    /** Two-letter code for the button ("FR"), or the localized "auto" for "follow the OS". */
    private fun currentLanguageCode(): String {
        val tag = LanguageStore.getTag(requireContext())
        return if (tag == LanguageStore.SYSTEM) getString(R.string.rules_language_auto)
        else tag.uppercase()
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
            val file = RuleFiles.export(requireContext(), rules, dir)
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
        // The listener holds this fragment; a test still running when the screen goes away
        // must not deliver into a dead binding.
        CycleReporter.listener = null
        _binding = null
    }
}
