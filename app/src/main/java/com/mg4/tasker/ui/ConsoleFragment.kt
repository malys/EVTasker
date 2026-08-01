package com.mg4.tasker.ui

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
import com.mg4.tasker.debug.PrivateBin
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

    /**
     * Uploads the full report to a PrivateBin paste and puts the link in the log.
     *
     * The car has no share target worth the name and no way to read a URL off a toast that
     * has already gone. Writing the link into the app log is what makes it recoverable: it
     * survives on the very screen the user is already looking at, and lands in the next
     * exported report too.
     *
     * Confirmed first, every time. It leaves the vehicle for a public server — encrypted and
     * password-protected, but it leaves — and that is not a decision to make on the user's
     * behalf because they tapped a button labelled "share".
     */
    private fun share() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.console_share)
            .setMessage(getString(R.string.console_share_confirm, PasteConfig.HOST))
            .setNegativeButton(R.string.editor_cancel, null)
            .setPositiveButton(R.string.console_share_send) { _, _ -> upload() }
            .show()
    }

    private fun upload() {
        Toast.makeText(requireContext(), R.string.console_share_running, Toast.LENGTH_SHORT).show()
        val appCtx = requireContext().applicationContext
        // The probe blocks and the upload is a network call: neither belongs on the main thread.
        // Built here, not in the worker: getString needs the fragment's configuration, and
        // the header must be in the language the app is displaying, not the system's.
        val header = getString(
            R.string.paste_header,
            PasteConfig.CONFIG.password,
            PasteConfig.HOST
        )
        thread(name = "mg4-tasker-log-share") {
            val markdown = DebugReport.renderMarkdown(appCtx, DiagnosticProbe.run(appCtx), header)
            val outcome = PrivateBin.paste(markdown, PasteConfig.CONFIG)
            val message = when (outcome) {
                is PrivateBin.Outcome.Ok -> {
                    // The log is the delivery mechanism, not a trace of one: this line is how
                    // the user gets the link back after the toast is gone.
                    AppLogger.i(SHARE_TAG, "diagnostic uploaded — ${outcome.url}")
                    AppLogger.i(SHARE_TAG, "password ${PasteConfig.CONFIG.password}, expires in 1 hour")
                    appCtx.getString(R.string.console_share_ok)
                }
                is PrivateBin.Outcome.Failed -> {
                    AppLogger.w(SHARE_TAG, "diagnostic upload failed — ${outcome.reason}")
                    appCtx.getString(R.string.console_share_failed, outcome.reason)
                }
            }
            Handler(Looper.getMainLooper()).post {
                if (_binding == null) return@post
                render()
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * Where a shared report goes.
     *
     * Chapril's PrivateBin instance, run by April, a French non-profit — no account, no
     * tracking, and the server never holds the key. One hour is deliberately short: long
     * enough to send the link to whoever is helping, too short to leave a car's diagnostic
     * lying around, and the paste is password-protected on top.
     */
    private object PasteConfig {
        const val HOST = "paste.chapril.org"

        val CONFIG = PrivateBin.Config(
            baseUrl = "https://$HOST/",
            password = "mg4taskerR0ck\$",
            expire = "1hour",
            formatter = "markdown",
        )
    }

    private companion object {
        const val SHARE_TAG = "MG4Tasker.Share"
    }
}
