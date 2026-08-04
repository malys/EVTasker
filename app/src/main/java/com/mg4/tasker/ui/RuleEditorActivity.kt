package com.mg4.tasker.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.mg4.tasker.R
import com.mg4.tasker.databinding.ActivityRuleEditorBinding
import com.mg4.tasker.databinding.ItemBranchRowBinding
import com.mg4.tasker.model.Action
import com.mg4.tasker.model.Branch
import com.mg4.tasker.model.MAX_ELSE_IF
import com.mg4.tasker.model.MatchMode
import com.mg4.tasker.model.RuleTrigger
import com.mg4.tasker.model.Rule
import com.mg4.tasker.store.RuleStore
import com.mg4.tasker.store.AppState

/**
 * Rule editing: the name, what addresses the rule, and which cases it has.
 *
 * A separate activity, not a fragment: editing is a task of its own, and the system back
 * button must abandon it without touching the saved rules.
 *
 * The conditions and actions of a case are not edited here — [BranchEditorActivity] owns
 * them, one window per case. What is left on this screen is the shape of the rule, which is
 * what has to be readable at a glance: the cases in the order they are tried, each with the
 * size of what it checks and of what it does.
 */
class RuleEditorActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_RULE_ID = "ruleId"

        /** [editing] when no case window is open. */
        private const val NO_TARGET = Int.MIN_VALUE

        /** [editing] for the "else", which is not an index into [branches]. */
        private const val ELSE_TARGET = -1

        fun intentForNew(context: Context) = Intent(context, RuleEditorActivity::class.java)

        fun intentForEdit(context: Context, ruleId: String) =
            Intent(context, RuleEditorActivity::class.java).putExtra(EXTRA_RULE_ID, ruleId)
    }

    private lateinit var binding: ActivityRuleEditorBinding
    private lateinit var store: RuleStore

    private var ruleId: String? = null

    /** The conditional cases, in evaluation order. Index 0 is the "if" and always exists. */
    private val branches = mutableListOf(Branch())

    /** The "else" actions, or null while the rule has none — an absent case, not an empty one. */
    private var elseActions: List<Action>? = null

    /** Which card the open case window belongs to; [NO_TARGET] when none is open. */
    private var editing: Int = NO_TARGET

    /** A presentation preference only; existing advanced branches always remain visible. */
    private var expertRulesEnabled: Boolean = false

    private val branchEditor =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val target = editing
            editing = NO_TARGET
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val edited = BranchEditorActivity.resultBranch(result.data)
                ?: return@registerForActivityResult
            when {
                target == ELSE_TARGET -> elseActions = edited.actions
                target in branches.indices -> branches[target] = edited
                // A new "else if" joins the rule only once its window was confirmed:
                // cancelling out of it must leave the rule as it was.
                target == branches.size -> branches += edited
            }
            renderBranches()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRuleEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = RuleStore(this)
        expertRulesEnabled = AppState.areExpertRulesEnabled(this)

        ruleId = intent.getStringExtra(EXTRA_RULE_ID)
        val existing = ruleId?.let { store.getById(it) }

        binding.editorTitle.setText(
            if (existing == null) R.string.editor_new_title else R.string.editor_edit_title
        )
        existing?.let {
            binding.nameInput.setText(it.name)
            branches.clear()
            branches += it.branches
            elseActions = it.elseActions
        }
        binding.triggerGroup.check(
            if (existing?.firesOn == RuleTrigger.IGNITION_OFF) R.id.triggerIgnitionOff
            else R.id.triggerIgnitionOn
        )

        binding.addElseIfButton.setOnClickListener {
            if (branches.size > MAX_ELSE_IF) {
                toastLong(getString(R.string.editor_max_else_if, MAX_ELSE_IF))
            } else {
                openBranch(branches.size, BranchEditorActivity.Kind.ELSE_IF, Branch())
            }
        }
        binding.addElseButton.setOnClickListener {
            openBranch(
                ELSE_TARGET,
                BranchEditorActivity.Kind.ELSE,
                Branch(actions = elseActions.orEmpty())
            )
        }
        binding.cancelButton.setOnClickListener { finish() }
        binding.saveButton.setOnClickListener { save() }

        renderBranches()
    }

    private fun openBranch(target: Int, kind: BranchEditorActivity.Kind, branch: Branch) {
        editing = target
        branchEditor.launch(BranchEditorActivity.intent(this, kind, target, branch))
    }

    // -------------------------------------------------------------------------
    // Case cards
    // -------------------------------------------------------------------------

    private fun renderBranches() {
        binding.branchContainer.removeAllViews()

        branches.forEachIndexed { index, branch ->
            val elseIf = index > 0
            addBranchCard(
                kind = if (elseIf) getString(R.string.branch_else_if, index)
                else getString(R.string.branch_if),
                summary = conditionalSummary(branch),
                isElse = false,
                // The "if" is where the rule starts: it has no rank to change, and removing
                // it would leave a rule that tests nothing.
                canMoveUp = index > 1,
                canMoveDown = elseIf && index < branches.lastIndex,
                canRemove = elseIf,
                onEdit = {
                    openBranch(
                        index,
                        if (elseIf) BranchEditorActivity.Kind.ELSE_IF else BranchEditorActivity.Kind.IF,
                        branch
                    )
                },
                onRemove = { branches.removeAt(index); renderBranches() },
                onMoveUp = { moveBranch(index, index - 1) },
                onMoveDown = { moveBranch(index, index + 1) }
            )
        }

        elseActions?.let { actions ->
            addBranchCard(
                kind = getString(R.string.branch_else),
                summary = resources.getQuantityString(
                    R.plurals.rule_summary_actions, actions.size, actions.size
                ),
                isElse = true,
                canMoveUp = false,
                canMoveDown = false,
                canRemove = true,
                onEdit = {
                    openBranch(ELSE_TARGET, BranchEditorActivity.Kind.ELSE, Branch(actions = actions))
                },
                onRemove = { elseActions = null; renderBranches() },
                onMoveUp = {},
                onMoveDown = {}
            )
        }

        // One "else" is all there is to have, and the chain has a reading limit.
        binding.addElseButton.isEnabled = elseActions == null
        binding.addElseIfButton.isEnabled = branches.size <= MAX_ELSE_IF
        binding.advancedBranchControls.visibility =
            if (expertRulesEnabled) View.VISIBLE else View.GONE
        val hasStoredAlternatives = branches.size > 1 || elseActions != null
        binding.expertBranchesNotice.visibility =
            if (!expertRulesEnabled && hasStoredAlternatives) View.VISIBLE else View.GONE

        // A physical button addresses the rule itself, whichever case names it.
        val eventDriven = branches.any { branch -> branch.conditions.any { it.type.eventDriven } }
        binding.triggerLabel.visibility = if (eventDriven) View.GONE else View.VISIBLE
        binding.triggerGroup.visibility = if (eventDriven) View.GONE else View.VISIBLE
    }

    /** "all: 2 conditions → 3 actions" — the shape of the case, not its content. */
    private fun conditionalSummary(branch: Branch): String {
        val conditions = resources.getQuantityString(
            R.plurals.rule_summary_conditions, branch.conditions.size, branch.conditions.size
        )
        val actions = resources.getQuantityString(
            R.plurals.rule_summary_actions, branch.actions.size, branch.actions.size
        )
        val checked = getString(
            if (branch.match == MatchMode.ANY) R.string.editor_summary_any
            else R.string.editor_summary_all,
            conditions
        )
        return "$checked → $actions"
    }

    private fun moveBranch(from: Int, to: Int) {
        branches.add(to, branches.removeAt(from))
        renderBranches()
    }

    private fun addBranchCard(
        kind: String,
        summary: String,
        isElse: Boolean,
        canMoveUp: Boolean,
        canMoveDown: Boolean,
        canRemove: Boolean,
        onEdit: () -> Unit,
        onRemove: () -> Unit,
        onMoveUp: () -> Unit,
        onMoveDown: () -> Unit
    ) {
        val card = ItemBranchRowBinding.inflate(
            LayoutInflater.from(this), binding.branchContainer, false
        )
        card.branchKind.text = kind
        card.branchSummary.text = summary
        card.branchHint.visibility = if (isElse) View.VISIBLE else View.GONE
        card.branchClickArea.setOnClickListener { onEdit() }
        // Disabled rather than hidden: controls that come and go move the next card's
        // buttons under a finger already reaching for them.
        card.branchUp.isEnabled = canMoveUp
        card.branchDown.isEnabled = canMoveDown
        card.branchRemove.isEnabled = canRemove
        card.branchUp.setOnClickListener { onMoveUp() }
        card.branchDown.setOnClickListener { onMoveDown() }
        card.branchRemove.setOnClickListener { onRemove() }
        binding.branchContainer.addView(card.root)
    }

    // -------------------------------------------------------------------------

    private fun save() {
        val name = binding.nameInput.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            toast(getString(R.string.editor_name_required)); return
        }

        val first = branches.first()
        val rule = Rule(
            id = ruleId ?: java.util.UUID.randomUUID().toString(),
            name = name,
            enabled = ruleId?.let { store.getById(it)?.enabled } ?: true,
            match = first.match,
            trigger = if (binding.triggerGroup.checkedButtonId == R.id.triggerIgnitionOff)
                RuleTrigger.IGNITION_OFF else RuleTrigger.IGNITION_ON,
            conditions = first.conditions,
            actions = first.actions,
            // Absent, not empty: a rule with no other case stays exactly what earlier
            // versions wrote, in the store and in an export.
            elseIf = branches.drop(1).takeIf { it.isNotEmpty() },
            elseActions = elseActions
        )

        // A rule with no condition would apply on every start without being asked.
        if (!rule.isComplete()) {
            toastLong(
                getString(
                    if (rule.hasAlternatives) R.string.editor_case_incomplete
                    else R.string.editor_incomplete
                )
            )
            return
        }
        // A button press addresses the whole rule, so a case that does not name a button
        // would run on presses it never mentioned.
        if (!rule.buttonAddressingIsSound) {
            toastLong(getString(R.string.editor_button_cases)); return
        }
        // Said now rather than through a wait cut short in the history after the drive.
        if (rule.totalDelayMs > com.mg4.tasker.model.DELAY_BUDGET_MS) {
            toastLong(
                getString(
                    R.string.editor_delay_budget,
                    (com.mg4.tasker.model.DELAY_BUDGET_MS / 1000).toInt()
                )
            )
            return
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
