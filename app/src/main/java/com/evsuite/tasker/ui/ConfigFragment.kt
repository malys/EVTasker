package com.evsuite.tasker.ui

import android.os.Bundle
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.evsuite.tasker.R
import com.evsuite.tasker.databinding.FragmentConfigBinding
import com.evsuite.tasker.model.Rule
import com.evsuite.tasker.store.AppState
import com.evsuite.tasker.store.LanguageStore
import com.evsuite.tasker.store.RuleFiles
import com.evsuite.tasker.store.RuleStore
import com.evsuite.tasker.store.RuleTransfer
import java.io.File

/**
 * Every user-configurable parameter of the app: language, editor and rule files.
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

        renderLanguage()
        binding.languageButton.setOnClickListener { pickLanguage() }

        binding.expertRulesSwitch.isChecked = AppState.areExpertRulesEnabled(requireContext())
        binding.expertRulesSwitch.setOnCheckedChangeListener { _, enabled ->
            AppState.setExpertRulesEnabled(requireContext(), enabled)
        }

        binding.exportButton.setOnClickListener { exportRules() }
        binding.importButton.setOnClickListener { importRules() }
        binding.aboutButton.setOnClickListener { showAbout() }
    }

    private fun showAbout() {
        val version = try {
            requireContext().packageManager
                .getPackageInfo(requireContext().packageName, 0).versionName
                ?: getString(R.string.about_version_unknown)
        } catch (_: Exception) {
            getString(R.string.about_version_unknown)
        }
        val repositoryUrl = "https://github.com/malys/EVTasker"
        val content = layoutInflater.inflate(R.layout.dialog_about, null)
        content.findViewById<TextView>(R.id.about_version).text = getString(R.string.about_version, version)
        QrCode.generate(repositoryUrl, 416)?.let {
            content.findViewById<ImageView>(R.id.about_qr_code).setImageBitmap(it)
        }
        content.findViewById<View>(R.id.about_repository).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(repositoryUrl)))
        }
        val dialog = MaterialAlertDialogBuilder(requireContext()).setView(content).create()
        content.findViewById<MaterialButton>(R.id.about_close).setOnClickListener { dialog.dismiss() }
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
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
