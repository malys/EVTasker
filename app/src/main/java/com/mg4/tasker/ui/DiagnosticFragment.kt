package com.mg4.tasker.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mg4.hardware.catalog.ActionType
import com.mg4.hardware.catalog.ConditionType
import com.mg4.hardware.catalog.ValueKind
import com.mg4.tasker.R
import com.mg4.tasker.bridge.BridgeContract
import com.mg4.tasker.databinding.FragmentDiagnosticBinding
import com.mg4.tasker.databinding.ItemDiagnosticBinding
import com.mg4.tasker.databinding.ItemDiagnosticHeaderBinding
import com.mg4.tasker.debug.DebugReport
import com.mg4.tasker.debug.DiagnosticProbe
import com.mg4.tasker.debug.Diagnostics
import com.mg4.tasker.store.SupportChecker
import com.mg4.tasker.store.SupportStore
import java.text.DateFormatSymbols
import kotlin.concurrent.thread

/**
 * What MG4Tasker can actually do on THIS vehicle, right now.
 *
 * The catalogue is wider than any single firmware, and several things outside the catalogue
 * also stop a rule: the vehicle service not running, notifications silenced, no speech
 * engine, the standstill gate closed because speed is unreadable. Every one of those used to
 * surface only as "my rule does not work".
 *
 * So the screen reports one verdict per condition and per action, produced by [Diagnostics]
 * from the same evaluator and the same pre-write checks the engine uses — an entry marked OK
 * here is one the engine will not refuse. The whole picture, plus the rules, the history, the
 * log and any crash, exports to a file for debugging off the car.
 */
class DiagnosticFragment : Fragment() {

    private var _binding: FragmentDiagnosticBinding? = null
    private val binding get() = _binding!!

    /** Kept so Export writes the picture the user is looking at, not a second, different one. */
    private var report: DiagnosticProbe.Report? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentDiagnosticBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.diagList.layoutManager = LinearLayoutManager(requireContext())
        binding.refreshButton.setOnClickListener { load() }
        binding.exportButton.setOnClickListener { export() }
        binding.checkSupportButton.setOnClickListener { checkSupport() }
        load()
        showSupport()
    }

    /** Shows the stored support summary; the check itself runs off the main thread. */
    private fun showSupport() {
        val ctx = requireContext()
        val conds = SupportStore.supportedConditions(ctx)
        if (conds == null) {
            binding.supportStatus.setText(R.string.diag_support_never)
            return
        }
        val acts = SupportStore.supportedActions(ctx) ?: emptySet()
        val gen = SupportStore.lastCheck(ctx)?.gen ?: getString(R.string.diag_support_unknown)
        binding.supportStatus.text =
            getString(R.string.diag_support_summary, conds.size, acts.size) +
                " · " + getString(R.string.diag_support_gen, gen)
    }

    private fun checkSupport() {
        binding.supportStatus.setText(R.string.diag_support_checking)
        val appCtx = requireContext().applicationContext
        thread(name = "mg4-tasker-support-check") {
            SupportChecker.refresh(appCtx)
            // The cache is one of the things the diagnostic judges, so the whole probe is
            // re-run: leaving the screen contradicting the cache it just rewrote is exactly
            // the kind of mismatch this screen exists to remove.
            val fresh = DiagnosticProbe.run(appCtx)
            Handler(Looper.getMainLooper()).post {
                if (_binding != null) { showSupport(); render(fresh) }
            }
        }
    }

    private fun load() {
        binding.summaryStatus.setText(R.string.diag_reading)
        binding.bridgeHint.visibility = View.GONE
        binding.exportButton.isEnabled = false

        // The probe blocks: reflection, system properties, and a bind to MG4Control with a
        // timeout. Never on the main thread.
        val appCtx = requireContext().applicationContext
        thread(name = "mg4-tasker-diag") {
            val fresh = DiagnosticProbe.run(appCtx)
            Handler(Looper.getMainLooper()).post { if (_binding != null) render(fresh) }
        }
    }

    private fun render(fresh: DiagnosticProbe.Report) {
        report = fresh
        binding.exportButton.isEnabled = true

        val blocked = fresh.blockedConditions + fresh.blockedActions
        binding.summaryStatus.text =
            if (blocked == 0) getString(R.string.diag_summary_ok)
            else getString(R.string.diag_summary_blocked, fresh.blockedConditions, fresh.blockedActions)
        binding.summaryStatus.setTextColor(color(blocked == 0))

        binding.bridgeHint.visibility =
            if (fresh.capabilities.vehicleLayerReady) View.GONE else View.VISIBLE

        val rows = buildList {
            add(Row.Header(getString(R.string.diag_section_env)))
            fresh.environment.forEach { add(Row.Item(envLabel(it.id), envValue(it), it.ok)) }

            add(Row.Header(getString(R.string.diag_section_conditions)))
            fresh.conditions.forEach { entry ->
                val type = ConditionType.valueOf(entry.name)
                add(Row.Item(getString(type.labelRes), conditionValue(type, entry), entry.ok))
            }

            add(Row.Header(getString(R.string.diag_section_actions)))
            fresh.actions.forEach { entry ->
                val type = ActionType.valueOf(entry.name)
                add(Row.Item(getString(type.labelRes), actionValue(entry), entry.ok))
            }
        }
        binding.diagList.adapter = Adapter(rows)
    }

    // -------------------------------------------------------------------------
    // Export
    // -------------------------------------------------------------------------

    private fun export() {
        val shown = report ?: return
        StorageBrowserDialog.pickFolder(requireContext(), R.string.rules_export_pick) { dir ->
            val appCtx = requireContext().applicationContext
            // Rules, history and the log are all read while building the file: keep the
            // whole thing off the main thread.
            thread(name = "mg4-tasker-diag-export") {
                val file = DebugReport.export(appCtx, shown, dir)
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

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    private val Diagnostics.Entry.ok get() = status == Diagnostics.Status.OK

    private fun envLabel(id: DiagnosticProbe.Env): String = getString(
        when (id) {
            DiagnosticProbe.Env.VEHICLE_LAYER -> R.string.diag_env_layer
            DiagnosticProbe.Env.VEHICLE_SERVICE -> R.string.diag_env_service
            DiagnosticProbe.Env.AUTOMATION -> R.string.diag_env_automation
            DiagnosticProbe.Env.NOTIFICATIONS -> R.string.diag_env_notifications
            DiagnosticProbe.Env.STANDSTILL_GATE -> R.string.diag_env_gate
            DiagnosticProbe.Env.SUPPORT_CACHE -> R.string.diag_env_cache
            DiagnosticProbe.Env.MG4CONTROL -> R.string.diag_env_mg4control
            DiagnosticProbe.Env.TTS -> R.string.diag_env_tts
            DiagnosticProbe.Env.BLUETOOTH -> R.string.diag_env_bluetooth
            DiagnosticProbe.Env.VENDOR_SERVICES -> R.string.diag_env_vendor
            DiagnosticProbe.Env.LOCATION -> R.string.diag_env_location
            DiagnosticProbe.Env.WRITE_THRESHOLD -> R.string.diag_env_threshold
            DiagnosticProbe.Env.GLASS_AND_LOCKS -> R.string.diag_env_glass
        }
    )

    private fun envValue(check: DiagnosticProbe.EnvCheck): String {
        // The connected MACs are the value, not a decoration: comparing them with the one a
        // rule names is the whole point of the row.
        if (check.id == DiagnosticProbe.Env.BLUETOOTH) {
            return if (!check.ok) getString(R.string.diag_bt_off) else check.detail
        }
        // These three carry their answer in the detail — a bound-service list, a position,
        // a speed. Mapping them to a yes/no string would throw away what makes them useful.
        if (check.id in DETAIL_ROWS) return check.detail
        return getString(envValueRes(check))
    }

    private fun envValueRes(check: DiagnosticProbe.EnvCheck): Int = (
        when (check.id) {
            DiagnosticProbe.Env.VEHICLE_LAYER ->
                if (check.ok) R.string.diag_state_ready else R.string.diag_state_not_ready
            DiagnosticProbe.Env.VEHICLE_SERVICE ->
                if (check.ok) R.string.diag_state_running else R.string.diag_state_stopped
            DiagnosticProbe.Env.AUTOMATION, DiagnosticProbe.Env.NOTIFICATIONS ->
                if (check.ok) R.string.value_enabled else R.string.value_disabled
            DiagnosticProbe.Env.STANDSTILL_GATE -> gateLabel(check.detail)
            DiagnosticProbe.Env.SUPPORT_CACHE ->
                if (check.ok) R.string.diag_cache_ok else R.string.diag_cache_stale
            // MG4Control is reported, never judged: absent is the normal, supported case.
            DiagnosticProbe.Env.MG4CONTROL ->
                if (check.detail == DiagnosticProbe.MG4CONTROL_INSTALLED) R.string.diag_installed
                else R.string.diag_absent
            DiagnosticProbe.Env.TTS ->
                if (check.ok) R.string.diag_installed else R.string.diag_absent
            // Rendered by envValue() from the detail — never reached.
            DiagnosticProbe.Env.BLUETOOTH,
            DiagnosticProbe.Env.VENDOR_SERVICES,
            DiagnosticProbe.Env.LOCATION,
            DiagnosticProbe.Env.WRITE_THRESHOLD,
            DiagnosticProbe.Env.GLASS_AND_LOCKS -> R.string.diag_bt_off
        }
    )

    private fun gateLabel(verdict: String) = when (verdict) {
        BridgeContract.VERDICT_ALLOWED -> R.string.verdict_allowed
        BridgeContract.VERDICT_MOVING -> R.string.verdict_moving
        else -> R.string.verdict_unknown_speed
    }

    private fun conditionValue(type: ConditionType, entry: Diagnostics.Entry): String {
        if (!entry.ok) return reason(entry)
        val raw = entry.value ?: return getString(R.string.diag_state_ready)
        val text = when {
            type == ConditionType.TIME_OF_DAY ->
                (raw as? Int)?.let { "%02d:%02d".format(it / 60, it % 60) }
            type == ConditionType.DAY_OF_WEEK ->
                (raw as? Int)?.let { DateFormatSymbols().weekdays.getOrNull(it) }
            type == ConditionType.ANY_BT_CONNECTED ->
                getString(if (raw == true) R.string.diag_value_yes else R.string.diag_value_no)
            type == ConditionType.BT_DEVICE_CONNECTED ->
                (raw as? String)?.ifBlank { getString(R.string.diag_value_none) }
            type.spec.kind == ValueKind.BOOL ->
                getString(if (raw == true) R.string.value_enabled else R.string.value_disabled)
            type.spec.kind == ValueKind.NUMBER -> {
                val unit = type.spec.unitRes.takeIf { it != 0 }?.let { " " + getString(it) } ?: ""
                "$raw$unit"
            }
            type.spec.kind == ValueKind.ENUM -> enumLabel(type, raw)
            else -> raw.toString()
        } ?: raw.toString()
        return text + hiddenSuffix(entry)
    }

    /**
     * The raw code is kept next to the label: an unexpected integer is the sign of a firmware
     * that does not use the same values, which is exactly what someone comes here to find.
     */
    private fun enumLabel(type: ConditionType, raw: Any): String? {
        if (raw is String) return raw
        val value = raw as? Int ?: return null
        val label = type.spec.options.firstOrNull { it.value == value }?.let { getString(it.labelRes) }
        return if (label != null) "$label ($value)" else value.toString()
    }

    private fun actionValue(entry: Diagnostics.Entry): String =
        if (entry.ok) getString(R.string.diag_action_ready) + hiddenSuffix(entry) else reason(entry)

    /** Usable but not offered in the editor: only an imported rule can still reach it. */
    private fun hiddenSuffix(entry: Diagnostics.Entry): String =
        if (entry.hidden) " · " + getString(R.string.diag_hidden) else ""

    private fun reason(entry: Diagnostics.Entry): String = getString(
        when (entry.reason) {
            Diagnostics.Reason.NOT_READABLE -> R.string.diag_reason_not_readable
            Diagnostics.Reason.BLUETOOTH_OFF -> R.string.diag_reason_bluetooth_off
            Diagnostics.Reason.NO_VENDOR_SERVICE -> R.string.diag_reason_vendor
            Diagnostics.Reason.NO_NAVIGATION_APP -> R.string.diag_reason_no_navigation
            Diagnostics.Reason.NO_LOCATION -> R.string.diag_reason_no_location
            Diagnostics.Reason.LAYER_NOT_READY -> R.string.diag_reason_layer
            Diagnostics.Reason.GATE_MOVING -> R.string.verdict_moving
            Diagnostics.Reason.GATE_UNKNOWN_SPEED -> R.string.verdict_unknown_speed
            Diagnostics.Reason.UNSUPPORTED_FIRMWARE -> R.string.diag_reason_firmware
            Diagnostics.Reason.NO_MG4CONTROL -> R.string.diag_reason_no_mg4control
            Diagnostics.Reason.MG4CONTROL_UNREACHABLE -> R.string.verdict_no_bridge
            Diagnostics.Reason.NO_TTS_ENGINE -> R.string.diag_reason_no_tts
            Diagnostics.Reason.NOTIFICATIONS_OFF -> R.string.diag_reason_notifications
            Diagnostics.Reason.NONE -> R.string.diag_action_ready
        }
    )

    private fun color(ok: Boolean) =
        ContextCompat.getColor(requireContext(), if (ok) R.color.mg4_ok else R.color.mg4_error)

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // -------------------------------------------------------------------------

    private sealed interface Row {
        data class Header(val title: String) : Row
        data class Item(val label: String, val value: String, val ok: Boolean) : Row
    }

    private inner class Adapter(private val rows: List<Row>) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        inner class HeaderHolder(val binding: ItemDiagnosticHeaderBinding) :
            RecyclerView.ViewHolder(binding.root)

        inner class ItemHolder(val binding: ItemDiagnosticBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) =
            if (rows[position] is Row.Header) TYPE_HEADER else TYPE_ITEM

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_HEADER) {
                HeaderHolder(ItemDiagnosticHeaderBinding.inflate(inflater, parent, false))
            } else {
                ItemHolder(ItemDiagnosticBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as HeaderHolder).binding.diagHeader.text = row.title
                is Row.Item -> (holder as ItemHolder).binding.let {
                    it.diagLabel.text = row.label
                    it.diagValue.text = row.value
                    // Colour is never the only carrier: the value text always says what happened.
                    it.diagValue.setTextColor(color(row.ok))
                }
            }
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ITEM = 1

        /** Environment rows whose value is the detail string itself. */
        val DETAIL_ROWS = setOf(
            DiagnosticProbe.Env.VENDOR_SERVICES,
            DiagnosticProbe.Env.LOCATION,
            DiagnosticProbe.Env.WRITE_THRESHOLD,
            DiagnosticProbe.Env.GLASS_AND_LOCKS,
        )
    }
}
