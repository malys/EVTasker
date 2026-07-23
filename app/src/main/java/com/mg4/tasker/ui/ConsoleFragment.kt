package com.mg4.tasker.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mg4.tasker.R
import com.mg4.tasker.databinding.FragmentConsoleBinding
import com.mg4.hardware.AppLogger
import com.mg4.tasker.debug.CrashLogger

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
