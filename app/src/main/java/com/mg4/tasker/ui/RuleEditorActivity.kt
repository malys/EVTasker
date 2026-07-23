package com.mg4.tasker.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mg4.tasker.R
import com.mg4.tasker.bridge.BridgeClient
import com.mg4.tasker.bridge.BridgeContract
import com.mg4.tasker.databinding.ActivityRuleEditorBinding
import com.mg4.tasker.databinding.ItemEditorRowBinding
import com.mg4.tasker.model.Action
import com.mg4.tasker.model.Condition
import com.mg4.tasker.model.MatchMode
import com.mg4.tasker.model.Rule
import com.mg4.hardware.catalog.ValueKind
import com.mg4.tasker.store.RuleStore
import com.mg4.tasker.util.BtDevices
import kotlin.concurrent.thread

/**
 * Rule editing.
 *
 * A separate activity, not a fragment: editing is a task of its own, and the system back
 * button must abandon it without touching the saved rules.
 */
class RuleEditorActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_RULE_ID = "ruleId"

        fun intentForNew(context: Context) = Intent(context, RuleEditorActivity::class.java)

        fun intentForEdit(context: Context, ruleId: String) =
            Intent(context, RuleEditorActivity::class.java).putExtra(EXTRA_RULE_ID, ruleId)
    }

    private lateinit var binding: ActivityRuleEditorBinding
    private lateinit var store: RuleStore

    private var ruleId: String? = null
    private val conditions = mutableListOf<Condition>()
    private val actions = mutableListOf<Action>()

    /** MG4Control profiles, loaded in the background: the bridge blocks, the editor must not. */
    private var profiles: List<Pair<String, String>> = emptyList()

    /** Real maximum media volume of the vehicle, if MG4Control could read it. */
    private var mediaVolumeMax: Int? = null

    /** Connected car firmware, if reported: drives which catalogue entries are offered. */
    private var firmware: com.mg4.hardware.FirmwareGen? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRuleEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = RuleStore(this)

        ruleId = intent.getStringExtra(EXTRA_RULE_ID)
        val existing = ruleId?.let { store.getById(it) }

        binding.editorTitle.setText(
            if (existing == null) R.string.editor_new_title else R.string.editor_edit_title
        )
        existing?.let {
            binding.nameInput.setText(it.name)
            conditions += it.conditions
            actions += it.actions
        }
        binding.matchGroup.check(
            if (existing?.match == MatchMode.ANY) R.id.matchAny else R.id.matchAll
        )

        binding.addConditionButton.setOnClickListener {
            CatalogSheet.pickCondition(this, firmware) { type ->
                val fresh = Condition(type = type)
                ValueEditorDialog.editCondition(this, fresh, mediaVolumeMax) { configured ->
                    conditions += configured
                    renderConditions()
                }
            }
        }
        binding.addActionButton.setOnClickListener {
            CatalogSheet.pickAction(this, firmware) { type ->
                val fresh = Action(type = type)
                if (type.spec.kind == ValueKind.NONE) {
                    actions += fresh
                    renderActions()
                } else {
                    ValueEditorDialog.editAction(this, fresh, mediaVolumeMax, profiles) { configured ->
                        actions += configured
                        renderActions()
                    }
                }
            }
        }
        binding.cancelButton.setOnClickListener { finish() }
        binding.saveButton.setOnClickListener { save() }

        renderConditions()
        renderActions()
        loadVehicleContext()
    }

    /**
     * Loads what only MG4Control knows: the profile list and the maximum volume.
     * The editor stays usable if the bridge does not answer — the affected actions
     * simply show "no profile reachable".
     */
    private fun loadVehicleContext() {
        thread(name = "mg4-tasker-editor-context") {
            val client = BridgeClient(this)
            try {
                if (!client.connect()) return@thread
                val loadedProfiles = client.listProfiles()
                val snapshot = client.readSnapshot()
                val maxVolume = snapshot.int(BridgeContract.KEY_MEDIA_VOLUME_MAX)
                val gen = com.mg4.hardware.FirmwareSupport.parse(
                    snapshot.string(BridgeContract.KEY_FIRMWARE_GEN)
                )
                Handler(Looper.getMainLooper()).post {
                    profiles = loadedProfiles
                    mediaVolumeMax = maxVolume
                    firmware = gen
                }
            } finally {
                client.disconnect()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Row rendering
    // -------------------------------------------------------------------------

    private fun renderConditions() {
        val labels = Labels(this, btNames = BtDevices.bondedNamesByMac(this))
        binding.conditionContainer.removeAllViews()
        conditions.forEachIndexed { index, condition ->
            addRow(
                container = binding.conditionContainer,
                label = labels.describe(condition),
                gated = false,
                onEdit = {
                    ValueEditorDialog.editCondition(this, condition, mediaVolumeMax) { updated ->
                        conditions[index] = updated
                        renderConditions()
                    }
                },
                onRemove = { conditions.removeAt(index); renderConditions() }
            )
        }
    }

    private fun renderActions() {
        val labels = Labels(this, profileNames = profiles.toMap())
        binding.actionContainer.removeAllViews()
        actions.forEachIndexed { index, action ->
            addRow(
                container = binding.actionContainer,
                label = labels.describe(action),
                gated = action.type.gated,
                onEdit = {
                    ValueEditorDialog.editAction(this, action, mediaVolumeMax, profiles) { updated ->
                        actions[index] = updated
                        renderActions()
                    }
                },
                onRemove = { actions.removeAt(index); renderActions() }
            )
        }
    }

    private fun addRow(
        container: android.view.ViewGroup,
        label: String,
        gated: Boolean,
        onEdit: () -> Unit,
        onRemove: () -> Unit
    ) {
        val row = ItemEditorRowBinding.inflate(LayoutInflater.from(this), container, false)
        row.rowLabel.text = label
        row.rowGated.visibility = if (gated) View.VISIBLE else View.GONE
        row.rowClickArea.setOnClickListener { onEdit() }
        row.rowRemove.setOnClickListener { onRemove() }
        container.addView(row.root)
    }

    // -------------------------------------------------------------------------

    private fun save() {
        val name = binding.nameInput.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            toast(getString(R.string.editor_name_required)); return
        }

        val rule = Rule(
            id = ruleId ?: java.util.UUID.randomUUID().toString(),
            name = name,
            enabled = ruleId?.let { store.getById(it)?.enabled } ?: true,
            match = if (binding.matchGroup.checkedButtonId == R.id.matchAny) MatchMode.ANY else MatchMode.ALL,
            conditions = conditions.toList(),
            actions = actions.toList()
        )

        // A rule with no condition would apply on every start without being asked.
        if (!rule.isComplete()) {
            toast(getString(R.string.editor_incomplete)); return
        }
        if (!store.save(rule)) {
            toast(getString(R.string.rules_quota_reached)); return
        }
        finish()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
