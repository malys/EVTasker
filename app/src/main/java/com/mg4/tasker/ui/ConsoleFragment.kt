package com.mg4.tasker.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mg4.tasker.R
import com.mg4.tasker.databinding.FragmentConsoleBinding
import com.mg4.hardware.AppLogger
import com.mg4.tasker.debug.CrashLogger
import com.mg4.tasker.debug.DebugReport
import com.mg4.tasker.debug.DiagnosticProbe
import kotlin.concurrent.thread

/**
 * In-app log console.
 *
 * A head unit has no cable and no crash reporter. This screen is how a rule that did not
 * fire, or a bridge that would not bind, gets diagnosed on the car itself.
 */
class ConsoleFragment : Fragment() {

    private var _binding: FragmentConsoleBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentConsoleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.clearButton.setOnClickListener { AppLogger.clear(); render() }
        binding.exportButton.setOnClickListener { export() }
        binding.shareButton.setOnClickListener { share() }
        binding.crashShowButton.setOnClickListener { showCrashReport() }
        binding.crashDismissButton.setOnClickListener {
            CrashLogger.clear(requireContext())
            render()
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val text = AppLogger.dump()
        binding.consoleText.text = text.ifEmpty { getString(R.string.console_empty) }
        // Newest lines are at the bottom: scroll there, that is what someone opening the
        // console after a failed ignition cycle wants to see first.
        binding.consoleScroll.post { binding.consoleScroll.fullScroll(View.FOCUS_DOWN) }

        val hasCrash = CrashLogger.hasReport(requireContext())
        binding.crashBanner.visibility = if (hasCrash) View.VISIBLE else View.GONE
    }

    private fun showCrashReport() {
        val report = CrashLogger.read(requireContext()) ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.console_crash_title)
            .setMessage(report)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * Writes the same file the Diagnostic screen exports — log, crash, rules, history and the
     * diagnostic verdicts. Splitting them would mean receiving half a picture: a log line is
     * rarely enough without knowing what the car could do at the time.
     */
    private fun export() {
        StorageBrowserDialog.pickFolder(requireContext(), R.string.rules_export_pick) { dir ->
            val appCtx = requireContext().applicationContext
            // The probe binds to MG4Control and reads system properties: never on the main thread.
            thread(name = "mg4-tasker-log-export") {
                val file = DebugReport.export(appCtx, DiagnosticProbe.run(appCtx), dir)
                Handler(Looper.getMainLooper()).post {
                    if (_binding == null) return@post
                    Toast.makeText(
                        requireContext(),
                        if (file == null) getString(R.string.diag_export_failed)
                        else getString(R.string.diag_export_ok, file.absolutePath),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun share() {
        val body = buildString {
            CrashLogger.read(requireContext())?.let {
                appendLine(it)
                appendLine()
            }
            append(AppLogger.dump())
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.app_name))
            putExtra(Intent.EXTRA_TEXT, body)
        }
        runCatching { startActivity(Intent.createChooser(intent, getString(R.string.console_share))) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
