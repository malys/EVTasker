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
    private var contacts: List<com.mg4.tasker.util.ContactDirectory.Entry> = emptyList()

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
        binding.triggerGroup.check(
            if (existing?.firesOn == RuleTrigger.IGNITION_OFF) R.id.triggerIgnitionOff
            else R.id.triggerIgnitionOn
        )

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
                withContactsIfNeeded(type) {
                    val fresh = Action(type = type)
                    if (type.spec.kind == ValueKind.NONE) {
                        actions += fresh
                        renderActions()
                    } else {
                        ValueEditorDialog.editAction(
                            context = this,
                            action = fresh,
                            dynamicMax = mediaVolumeMax,
                            profiles = profiles,
                            contacts = contacts,
                            currentValue = currentValue(type)
                        ) { configured ->
                            actions += configured
                            renderActions()
                        }
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
            val loadedContacts = com.mg4.tasker.util.ContactDirectory.entries(applicationContext)

            val fresh = com.mg4.tasker.vehicle.VehicleReader.read(
                btMacs = emptySet(),
                btAvailable = false,
                fix = com.mg4.tasker.util.CarLocation.lastKnown(applicationContext)
            )

            Handler(Looper.getMainLooper()).post {
                profiles = loadedProfiles
                contacts = loadedContacts
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

    private var pendingAfterContacts: (() -> Unit)? = null

    private fun withContactsIfNeeded(
        type: com.mg4.hardware.catalog.ActionType,
        next: () -> Unit
    ) {
        if (type.spec.kind != ValueKind.CONTACT) return next()
        pendingAfterContacts = next
        if (!com.mg4.tasker.util.ContactDirectory.hasPermission(this)) {
            contactsPermission.launch(android.Manifest.permission.READ_CONTACTS)
        } else {
            loadContactsAndContinue()
        }
    }

    private val contactsPermission =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
            loadContactsAndContinue()
        }

    private fun loadContactsAndContinue() {
        thread(name = "mg4-tasker-editor-contacts") {
            val loaded = com.mg4.tasker.util.ContactDirectory.entries(applicationContext)
            Handler(Looper.getMainLooper()).post {
                contacts = loaded
                // The pending step opens a dialog: on an editor the user already left, that
                // is a BadTokenException rather than a value editor nobody asked for.
                if (isFinishing || isDestroyed) {
                    pendingAfterContacts = null
                    return@post
                }
                pendingAfterContacts?.invoke()
                pendingAfterContacts = null
            }
        }
    }

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
        val hasButtonCondition = conditions.any {
            it.type.eventDriven
        }
        binding.triggerLabel.visibility = if (hasButtonCondition) View.GONE else View.VISIBLE
        binding.triggerGroup.visibility = if (hasButtonCondition) View.GONE else View.VISIBLE
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
                    withContactsIfNeeded(action.type) {
                        ValueEditorDialog.editAction(
                            context = this,
                            action = action,
                            dynamicMax = mediaVolumeMax,
                            profiles = profiles,
                            contacts = contacts,
                            currentValue = currentValue(action.type)
                        ) { updated ->
                            actions[index] = updated
                            renderActions()
                        }
                    }
                },
                onRemove = { actions.removeAt(index); renderActions() },
                // Actions run in the order shown, so the order is part of the rule: a wait
                // is only useful where the user puts it.
                reorderable = true,
                canMoveUp = index > 0,
                canMoveDown = index < actions.lastIndex,
                onMoveUp = { move(index, index - 1) },
                onMoveDown = { move(index, index + 1) }
            )
        }
    }

    private fun move(from: Int, to: Int) {
        actions.add(to, actions.removeAt(from))
        renderActions()
    }

    private fun addRow(
        container: android.view.ViewGroup,
        label: String,
        gated: Boolean,
        onEdit: () -> Unit,
        onRemove: () -> Unit,
        reorderable: Boolean = false,
        canMoveUp: Boolean = false,
        canMoveDown: Boolean = false,
        onMoveUp: () -> Unit = {},
        onMoveDown: () -> Unit = {}
    ) {
        val row = ItemEditorRowBinding.inflate(LayoutInflater.from(this), container, false)
        row.rowLabel.text = label
        row.rowGated.visibility = if (gated) View.VISIBLE else View.GONE
        row.rowClickArea.setOnClickListener { onEdit() }
        row.rowRemove.setOnClickListener { onRemove() }
        // Kept in place and disabled at the ends rather than hidden: buttons that disappear
        // move every other row's controls under the finger already reaching for them.
        row.rowUp.visibility = if (reorderable) View.VISIBLE else View.GONE
        row.rowDown.visibility = if (reorderable) View.VISIBLE else View.GONE
        row.rowUp.isEnabled = canMoveUp
        row.rowDown.isEnabled = canMoveDown
        row.rowUp.setOnClickListener { onMoveUp() }
        row.rowDown.setOnClickListener { onMoveDown() }
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
            trigger = if (binding.triggerGroup.checkedButtonId == R.id.triggerIgnitionOff)
                RuleTrigger.IGNITION_OFF else RuleTrigger.IGNITION_ON,
            conditions = conditions.toList(),
            actions = actions.toList()
        )

        // A rule with no condition would apply on every start without being asked.
        if (!rule.isComplete()) {
            toast(getString(R.string.editor_incomplete)); return
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
