package com.evsuite.tasker.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.evsuite.tasker.R
import com.evsuite.tasker.databinding.ActivityBranchEditorBinding
import com.evsuite.tasker.databinding.ItemEditorRowBinding
import com.evsuite.tasker.model.Action
import com.evsuite.tasker.model.Branch
import com.evsuite.tasker.model.Condition
import com.evsuite.tasker.model.MatchMode
import com.evsuite.hardware.catalog.ActionGroup
import com.evsuite.hardware.catalog.ActionType
import com.evsuite.hardware.catalog.ValueKind
import com.evsuite.tasker.util.BtDevices
import kotlin.concurrent.thread

/**
 * One case of a rule — the "if", an "else if", or the "else" — edited in its own window.
 *
 * A window rather than another section of the rule screen. A rule with three cases is three
 * condition lists and three action lists; stacked in one column they are six sections, and
 * the one being worked on is never the one on screen. Here the case is the whole screen and
 * its two halves sit side by side: what is checked on the left, what is done on the right,
 * each with its own scroll and its own "add" button. Adding a third action never pushes the
 * condition that justifies it out of view.
 *
 * The vehicle context (firmware, profiles, contacts, current readings) is loaded here rather
 * than passed in: it is what the pickers and the value dialogs need, and this is where they
 * open. The rule screen does not open any of them and needs none of it.
 */
class BranchEditorActivity : AppCompatActivity() {

    /** Which case is being edited — the only difference is whether it has conditions. */
    enum class Kind { IF, ELSE_IF, ELSE }

    companion object {
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_POSITION = "position"
        private const val EXTRA_MATCH = "match"
        private const val EXTRA_CONDITIONS = "conditions"
        private const val EXTRA_ACTIONS = "actions"
        private const val STATE_PROFILE_WARNING_ACTION = "profile_warning_action"

        /**
         * @param position 1-based rank of an "else if" among its peers, for the title. Ignored
         *   by the other kinds.
         */
        fun intent(context: Context, kind: Kind, position: Int, branch: Branch): Intent =
            Intent(context, BranchEditorActivity::class.java)
                .putExtra(EXTRA_KIND, kind.name)
                .putExtra(EXTRA_POSITION, position)
                .putExtra(EXTRA_MATCH, branch.match.name)
                // Gson through Array<T>, never a TypeToken: R8 has already deleted anonymous
                // TypeToken subclasses on a release build. Same rule as RuleStore.
                .putExtra(EXTRA_CONDITIONS, Gson().toJson(branch.conditions.toTypedArray()))
                .putExtra(EXTRA_ACTIONS, Gson().toJson(branch.actions.toTypedArray()))

        /** The edited case, or null when the window was left without confirming. */
        fun resultBranch(data: Intent?): Branch? {
            val intent = data ?: return null
            val match = intent.getStringExtra(EXTRA_MATCH)
                ?.let { name -> MatchMode.entries.firstOrNull { it.name == name } }
                ?: MatchMode.ALL
            return Branch(
                match = match,
                conditions = decode(intent.getStringExtra(EXTRA_CONDITIONS), Array<Condition>::class.java),
                actions = decode(intent.getStringExtra(EXTRA_ACTIONS), Array<Action>::class.java)
            )
        }

        private fun <T> decode(json: String?, type: Class<Array<T>>): List<T> =
            if (json.isNullOrEmpty()) emptyList()
            else Gson().fromJson(json, type)?.toList().orEmpty()
    }

    private lateinit var binding: ActivityBranchEditorBinding

    private lateinit var kind: Kind
    private val conditions = mutableListOf<Condition>()
    private val actions = mutableListOf<Action>()

    /** EVProfile profiles, loaded in the background: the bridge blocks, the editor must not. */
    private var profiles: List<Pair<String, String>> = emptyList()
    private var evprofileDetected = false
    private var contacts: List<com.evsuite.tasker.util.ContactDirectory.Entry> = emptyList()

    /** Real maximum media volume of the vehicle, if EVProfile could read it. */
    private var mediaVolumeMax: Int? = null

    /** Connected car firmware, if reported: drives which catalogue entries are offered. */
    private var firmware: com.evsuite.hardware.FirmwareGen? = null

    /**
     * What the car reports right now, read once when the editor opens.
     *
     * Used to open a value control on the present setting rather than on the bottom of its
     * range, and to prefill a location condition with where the car is. Read once and not
     * refreshed: a slider that moved under the user's finger because the car changed would
     * be worse than a value a minute old.
     */
    private var snapshot: com.evsuite.tasker.model.Snapshot? = null

    /**
     * The warning is a full-screen activity, so keep only the catalogue choice while it is
     * visible. Rebuilding the edit lambda after the result also survives activity recreation.
     */
    private var actionAwaitingProfileWarning: ActionType? = null

    private val profileWarning =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val type = actionAwaitingProfileWarning
            actionAwaitingProfileWarning = null
            if (result.resultCode == Activity.RESULT_OK && type != null) editNewAction(type)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBranchEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        actionAwaitingProfileWarning = savedInstanceState
            ?.getString(STATE_PROFILE_WARNING_ACTION)
            ?.let { name -> ActionType.entries.firstOrNull { it.name == name } }

        kind = intent.getStringExtra(EXTRA_KIND)
            ?.let { name -> Kind.entries.firstOrNull { it.name == name } }
            ?: Kind.IF
        conditions += decode(intent.getStringExtra(EXTRA_CONDITIONS), Array<Condition>::class.java)
        actions += decode(intent.getStringExtra(EXTRA_ACTIONS), Array<Action>::class.java)

        binding.branchTitle.text = when (kind) {
            Kind.IF -> getString(R.string.branch_if)
            Kind.ELSE_IF -> getString(R.string.branch_else_if, intent.getIntExtra(EXTRA_POSITION, 1))
            Kind.ELSE -> getString(R.string.branch_else)
        }

        // The "else" is reached precisely because nothing matched, so it has no conditions to
        // show. Its pane goes away instead of standing empty, the actions take the width, and
        // the sentence that would have been the pane's title moves under the heading.
        val conditional = kind != Kind.ELSE
        binding.conditionPane.visibility = if (conditional) View.VISIBLE else View.GONE
        binding.matchSpacer.visibility = if (conditional) View.VISIBLE else View.GONE
        binding.branchSubtitle.visibility = if (conditional) View.GONE else View.VISIBLE

        val match = intent.getStringExtra(EXTRA_MATCH)
        binding.matchGroup.check(
            if (match == MatchMode.ANY.name) R.id.matchAny else R.id.matchAll
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
                if (type.group == ActionGroup.PROFILE && evprofileDetected) {
                    actionAwaitingProfileWarning = type
                    profileWarning.launch(ProfileAutomationWarningActivity.intent(this))
                } else {
                    editNewAction(type)
                }
            }
        }
        binding.branchCancelButton.setOnClickListener { finish() }
        binding.branchDoneButton.setOnClickListener { done() }

        renderConditions()
        renderActions()
        loadVehicleContext()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        actionAwaitingProfileWarning?.let {
            outState.putString(STATE_PROFILE_WARNING_ACTION, it.name)
        }
        super.onSaveInstanceState(outState)
    }

    private fun editNewAction(type: ActionType) {
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
                    currentValue = currentValue(type),
                    currentPoint = currentPoint()
                ) { configured ->
                    actions += ActionBundles.expand(configured)
                    renderActions()
                }
            }
        }
    }

    /**
     * Said here rather than at rule save: the case is on screen, so the missing half is one
     * tap away. A case with no condition would swallow every case written after it, and one
     * that only waits holds the cycle without changing anything.
     */
    private fun done() {
        val conditional = kind != Kind.ELSE
        val complete = (!conditional || conditions.isNotEmpty()) &&
            actions.any { it.type != ActionType.DELAY }
        if (!complete) {
            Toast.makeText(this, R.string.editor_case_incomplete, Toast.LENGTH_LONG).show()
            return
        }
        val match =
            if (binding.matchGroup.checkedButtonId == R.id.matchAny) MatchMode.ANY else MatchMode.ALL
        setResult(
            Activity.RESULT_OK,
            Intent()
                .putExtra(EXTRA_MATCH, match.name)
                .putExtra(EXTRA_CONDITIONS, Gson().toJson(conditions.toTypedArray()))
                .putExtra(EXTRA_ACTIONS, Gson().toJson(actions.toTypedArray()))
        )
        finish()
    }

    /**
     * Firmware and max volume come straight from EVHardware; the profile list is the one
     * thing that needs EVProfile (via the optional bridge). The editor stays usable when
     * EVProfile is absent — the "apply profile" action just shows "no profile reachable".
     */
    private fun loadVehicleContext() {
        thread(name = "mg4-tasker-editor-context") {
            com.evsuite.hardware.EVHardware.init(applicationContext)   // idempotent
            val gen = com.evsuite.hardware.FirmwareInfo.getGeneration().let {
                com.evsuite.hardware.FirmwareSupport.parse(it.name)
            }
            val maxVolume = com.evsuite.hardware.EVHardware.getMediaVolumeMax()

            val bridge = com.evsuite.tasker.vehicle.ProfileBridge(this)
            var bridgeConnected = false
            val loadedProfiles = try {
                bridgeConnected = bridge.connect()
                if (bridgeConnected) bridge.listProfiles() else emptyList()
            } finally {
                bridge.disconnect()
            }
            val loadedContacts = com.evsuite.tasker.util.ContactDirectory.entries(applicationContext)

            val fresh = com.evsuite.tasker.vehicle.VehicleReader.read(
                btMacs = emptySet(),
                btAvailable = false,
                fix = com.evsuite.tasker.util.CarLocation.lastKnown(applicationContext)
            )

            Handler(Looper.getMainLooper()).post {
                profiles = loadedProfiles
                evprofileDetected = bridgeConnected
                contacts = loadedContacts
                mediaVolumeMax = if (maxVolume >= 0) maxVolume else null
                firmware = gen
                snapshot = fresh
                // Profile ids became profile names while this ran.
                renderActions()
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
    private fun withLocationIfNeeded(type: com.evsuite.hardware.catalog.ConditionType, next: () -> Unit) {
        if (type != com.evsuite.hardware.catalog.ConditionType.LOCATION_WITHIN ||
            com.evsuite.tasker.util.CarLocation.hasPermission(this)
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
        type: com.evsuite.hardware.catalog.ActionType,
        next: () -> Unit
    ) {
        if (type.spec.kind != ValueKind.CONTACT) return next()
        pendingAfterContacts = next
        if (!com.evsuite.tasker.util.ContactDirectory.hasPermission(this)) {
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
            val loaded = com.evsuite.tasker.util.ContactDirectory.entries(applicationContext)
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
                    val fix = com.evsuite.tasker.util.CarLocation.lastKnown(applicationContext)
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
    private fun currentValue(type: com.evsuite.hardware.catalog.ActionType): Number? {
        val key = type.currentKey ?: return null
        val readings = snapshot?.readings ?: return null
        return readings[key] as? Number
    }

    /** "latitude,longitude" for the point and destination editors, or null with no fix. */
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
                    withContactsIfNeeded(action.type) {
                        ValueEditorDialog.editAction(
                            context = this,
                            action = action,
                            dynamicMax = mediaVolumeMax,
                            profiles = profiles,
                            contacts = contacts,
                            currentValue = currentValue(action.type),
                            currentPoint = currentPoint()
                        ) { updated ->
                            actions[index] = updated
                            ActionBundles.resync(actions, index)
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
}
