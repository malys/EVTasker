package com.mg4.tasker.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mg4.hardware.VehicleWriteGate
import com.mg4.tasker.R
import com.mg4.tasker.databinding.DialogValueEditorBinding
import com.mg4.tasker.databinding.FragmentConfigBinding
import com.mg4.tasker.model.Rule
import com.mg4.tasker.store.AppState
import com.mg4.tasker.store.LanguageStore
import com.mg4.tasker.store.RuleFiles
import com.mg4.tasker.store.RuleStore
import com.mg4.tasker.store.RuleTransfer
import java.io.File

/**
 * Every parameter of the app: the speed gate, the language, and the rules file.
 *
 * They used to sit in the rules pane, above the list. They are set once and then never
 * looked at again, so they were paying for themselves in list rows on every screen the
 * list is actually read on — and the rules tab reads better as one thing: the rules.
 */
class ConfigFragment : Fragment() {

    private var _binding: FragmentConfigBinding? = null
    private val binding get() = _binding!!

    private lateinit var store: RuleStore

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        store = RuleStore(requireContext())

        binding.thresholdButton.setOnClickListener { editThreshold() }
        renderThreshold()

        renderLanguage()
        binding.languageButton.setOnClickListener { pickLanguage() }

        binding.expertRulesSwitch.isChecked = AppState.areExpertRulesEnabled(requireContext())
        binding.expertRulesSwitch.setOnCheckedChangeListener { _, enabled ->
            AppState.setExpertRulesEnabled(requireContext(), enabled)
        }

        binding.exportButton.setOnClickListener { exportRules() }
        binding.importButton.setOnClickListener { importRules() }
    }

    // ---------- Speed gate ----------

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
        val dialogBinding = DialogValueEditorBinding.inflate(layoutInflater)
        dialogBinding.numberBlock.visibility = View.VISIBLE
        dialogBinding.numberSlider.valueFrom = 0f
        dialogBinding.numberSlider.valueTo = VehicleWriteGate.MAX_ALLOWED_THRESHOLD_KMH
        dialogBinding.numberSlider.stepSize = 5f
        var chosen = AppState.writeThresholdKmh(requireContext())
        // The stored value predates the step, or came from another build: snap it in range.
        chosen = (Math.round(chosen / 5f) * 5f).coerceIn(0f, VehicleWriteGate.MAX_ALLOWED_THRESHOLD_KMH)
        dialogBinding.numberSlider.value = chosen
        // The unit label lives with the catalogue, in the library's resources.
        val unit = " " + getString(com.mg4.hardware.R.string.unit_kmh)
        dialogBinding.numberValue.text = "${chosen.toInt()}$unit"
        dialogBinding.numberSlider.addOnChangeListener { _, value, _ ->
            chosen = value
            dialogBinding.numberValue.text = "${value.toInt()}$unit"
        }
        dialogBinding.gatedExplain.visibility = View.VISIBLE
        dialogBinding.gatedExplain.text = getString(R.string.rules_threshold_explain)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rules_threshold_title)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.editor_cancel, null)
            .setPositiveButton(R.string.value_ok) { _, _ ->
                AppState.setWriteThresholdKmh(requireContext(), chosen)
                renderThreshold()
            }
            .show()
    }

    // ---------- Language ----------

    private fun renderLanguage() {
        val tags = resources.getStringArray(R.array.language_tags)
        val names = resources.getStringArray(R.array.language_names)
        val index = tags.indexOf(LanguageStore.getTag(requireContext())).takeIf { it >= 0 } ?: 0
        binding.languageButton.text = getString(R.string.rules_language, names[index])
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
