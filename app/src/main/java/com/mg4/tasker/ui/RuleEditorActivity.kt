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
import com.mg4.tasker.databinding.ActivityRuleEditorBinding
import com.mg4.tasker.databinding.ItemEditorRowBinding
import com.mg4.tasker.model.Action
import com.mg4.tasker.model.Condition
import com.mg4.tasker.model.MatchMode
import com.mg4.tasker.model.RuleTrigger
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

    /**
     * What the car reports right now, read once when the editor opens.
     *
     * Used to open a value control on the present setting rather than on the bottom of its
     * range, and to prefill a location condition with where the car is. Read once and not
     * refreshed: a slider that moved under the user's finger because the car changed would
     * be worse than a value a minute old.
     */
    private var snapshot: com.mg4.tasker.model.Snapshot? = null

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
        binding.triggerGroup.check(when (existing?.firesOn) {
            RuleTrigger.IGNITION_OFF -> R.id.triggerIgnitionOff
            RuleTrigger.PHYSICAL_BUTTON -> R.id.triggerPhysicalButton
            else -> R.id.triggerIgnitionOn
        })

        binding.addConditionButton.setOnClickListener {
            CatalogPicker.pickCondition(this, firmware) { type ->
                withLocationIfNeeded(type) {
                    val fresh = Condition(type = type)
                    ValueEditorDialog.editCondition(this, fresh, mediaVolumeMax, currentPoint()) { configured ->
                        conditions += configured
                        renderConditions()
                    }
                }
            }
        }
        binding.addActionButton.setOnClickListener {
            CatalogPicker.pickAction(this, firmware) { type ->
                val fresh = Action(type = type)
                if (type.spec.kind == ValueKind.NONE) {
                    actions += fresh
                    renderActions()
                } else {
                    ValueEditorDialog.editAction(
                        this, fresh, mediaVolumeMax, profiles, currentValue(type)
                    ) { configured ->
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
     * Firmware and max volume come straight from MG4Hardware; the profile list is the one
     * thing that needs MG4Control (via the optional bridge). The editor stays usable when
     * MG4Control is absent — the "apply profile" action just shows "no profile reachable".
     */
    private fun loadVehicleContext() {
        thread(name = "mg4-tasker-editor-context") {
            com.mg4.hardware.MG4Hardware.init(applicationContext)   // idempotent
            val gen = com.mg4.hardware.FirmwareInfo.getGeneration().let {
                com.mg4.hardware.FirmwareSupport.parse(it.name)
            }
            val maxVolume = com.mg4.hardware.MG4Hardware.getMediaVolumeMax()

            val bridge = com.mg4.tasker.vehicle.ProfileBridge(this)
            val loadedProfiles = try {
                if (bridge.connect()) bridge.listProfiles() else emptyList()
            } finally {
                bridge.disconnect()
            }

            val fresh = com.mg4.tasker.vehicle.VehicleReader.read(
                btMacs = emptySet(),
                btAvailable = false,
                fix = com.mg4.tasker.util.CarLocation.lastKnown(applicationContext)
            )

            Handler(Looper.getMainLooper()).post {
                profiles = loadedProfiles
                mediaVolumeMax = if (maxVolume >= 0) maxVolume else null
                firmware = gen
                snapshot = fresh
            }
        }
    }

    /**
     * Asks for the position permission at the moment a rule first needs it.
     *
     * Not at startup: an app that asks for location before the user has expressed any
     * interest in it has not explained why, and on a car the answer is usually no. Here the
     * request follows the choice that requires it, and a refusal still opens the editor —
     * the condition is saved, the Diagnostic tab reports it as having no position, and
     * granting the permission later makes it work with no change to the rule.
     */
    private fun withLocationIfNeeded(type: com.mg4.hardware.catalog.ConditionType, next: () -> Unit) {
        if (type != com.mg4.hardware.catalog.ConditionType.LOCATION_WITHIN ||
            com.mg4.tasker.util.CarLocation.hasPermission(this)
        ) {
            next()
            return
        }
        pendingAfterLocation = next
        locationPermission.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private var pendingAfterLocation: (() -> Unit)? = null

    private val locationPermission =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                // The fix is what prefills the point; re-read it before the dialog opens.
                thread(name = "mg4-tasker-editor-fix") {
                    val fix = com.mg4.tasker.util.CarLocation.lastKnown(applicationContext)
                    Handler(Looper.getMainLooper()).post {
                        snapshot = snapshot?.copy(latitude = fix?.latitude, longitude = fix?.longitude)
                        pendingAfterLocation?.invoke()
                        pendingAfterLocation = null
                    }
                }
            } else {
                pendingAfterLocation?.invoke()
                pendingAfterLocation = null
            }
        }

    /** The car's present value for what [type] controls, when it reports one. */
    private fun currentValue(type: com.mg4.hardware.catalog.ActionType): Number? {
        val key = type.currentKey ?: return null
        val readings = snapshot?.readings ?: return null
        return readings[key] as? Number
    }

    /** "latitude,longitude" for a fresh location condition, or null with no fix. */
    private fun currentPoint(): String? {
        val current = snapshot ?: return null
        val lat = current.latitude ?: return null
        val lon = current.longitude ?: return null
        return String.format(java.util.Locale.US, "%.6f,%.6f", lat, lon)
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
                    ValueEditorDialog.editCondition(this, condition, mediaVolumeMax, currentPoint()) { updated ->
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
                    ValueEditorDialog.editAction(
                        this, action, mediaVolumeMax, profiles, currentValue(action.type)
                    ) { updated ->
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
            trigger = when (binding.triggerGroup.checkedButtonId) {
                R.id.triggerIgnitionOff -> RuleTrigger.IGNITION_OFF
                R.id.triggerPhysicalButton -> RuleTrigger.PHYSICAL_BUTTON
                else -> RuleTrigger.IGNITION_ON
            },
            conditions = conditions.toList(),
            actions = actions.toList()
        )

        // A rule with no condition would apply on every start without being asked.
        if (!rule.isComplete()) {
            toast(getString(R.string.editor_incomplete)); return
        }
        val buttonTypes = setOf(
            com.mg4.hardware.catalog.ConditionType.STAR_LEFT_SHORT_PRESS,
            com.mg4.hardware.catalog.ConditionType.STAR_LEFT_LONG_PRESS,
            com.mg4.hardware.catalog.ConditionType.STAR_RIGHT_SHORT_PRESS,
            com.mg4.hardware.catalog.ConditionType.STAR_RIGHT_LONG_PRESS
        )
        val hasButtonCondition = rule.conditions.any { it.type in buttonTypes }
        if (rule.firesOn == RuleTrigger.PHYSICAL_BUTTON && !hasButtonCondition) {
            toast(getString(R.string.editor_button_condition_required)); return
        }
        if (rule.firesOn != RuleTrigger.PHYSICAL_BUTTON && hasButtonCondition) {
            toast(getString(R.string.editor_button_trigger_required)); return
        }
        // A write that does not reach disk must say so on screen. Closing the editor on a
        // failed save is what made the rule look like it had been accepted and then lost.
        when (store.save(rule)) {
            RuleStore.SaveResult.OK -> finish()
            RuleStore.SaveResult.QUOTA_REACHED -> toast(getString(R.string.rules_quota_reached))
            RuleStore.SaveResult.WRITE_FAILED,
            RuleStore.SaveResult.NOT_READ_BACK -> toastLong(getString(R.string.editor_save_failed))
            // Saving would overwrite rules that are on disk but unparsed. Import is the way out.
            RuleStore.SaveResult.STORE_UNREADABLE -> toastLong(getString(R.string.editor_store_unreadable))
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    /** A save failure is the one message here worth reading time. */
    private fun toastLong(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
