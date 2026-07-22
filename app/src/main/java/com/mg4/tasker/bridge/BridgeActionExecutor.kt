package com.mg4.tasker.bridge

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.mg4.tasker.debug.AppLogger
import com.mg4.tasker.engine.ActionExecutor
import com.mg4.tasker.model.Action
import com.mg4.tasker.model.ActionResult
import com.mg4.tasker.model.ActionType
import com.mg4.tasker.model.ValueKind
import com.mg4.tasker.util.Notifier

/**
 * Translates a catalogue action into a bridge call, or into a local effect.
 *
 * No vehicle write happens here: everything goes to MG4Control, which stays the only
 * process that touches the car and applies the speed gate.
 */
class BridgeActionExecutor(
    private val context: Context,
    private val client: BridgeClient
) : ActionExecutor {

    companion object {
        private const val TAG = "MG4Tasker.Exec"
    }

    override fun execute(action: Action): ActionResult = when (action.type) {
        ActionType.APPLY_PROFILE     -> applyProfile(action)
        ActionType.LAUNCH_APP        -> launchApp(action)
        ActionType.SHOW_NOTIFICATION -> showNotification(action)
        else                         -> applyViaBridge(action)
    }

    private fun applyProfile(action: Action): ActionResult {
        if (action.text.isBlank()) {
            return ActionResult(action.type, false, BridgeContract.VERDICT_UNSUPPORTED, "no profile selected")
        }
        val response = client.applyProfile(action.text)
            ?: return noBridge(action)
        return response.toResult(action.type)
    }

    private fun applyViaBridge(action: Action): ActionResult {
        val bridgeAction = action.type.bridgeAction
            ?: return ActionResult(action.type, false, BridgeContract.VERDICT_UNSUPPORTED, "action has no target")

        val params = Bundle().apply {
            when (action.type.spec.kind) {
                ValueKind.BOOL -> putBoolean(BridgeContract.PARAM_VALUE, action.flag)
                else           -> putInt(BridgeContract.PARAM_VALUE, action.number)
            }
        }

        val response = client.applyAction(bridgeAction, params) ?: return noBridge(action)
        return response.toResult(action.type)
    }

    // -------------------------------------------------------------------------
    // Local actions — these never reach the vehicle
    // -------------------------------------------------------------------------

    private fun launchApp(action: Action): ActionResult {
        val intent = context.packageManager.getLaunchIntentForPackage(action.text)
            ?: return ActionResult(action.type, false, BridgeContract.VERDICT_UNSUPPORTED,
                "application not found: ${action.text}")
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ActionResult(action.type, true, BridgeContract.VERDICT_ALLOWED, action.text)
        } catch (e: Exception) {
            AppLogger.w(TAG, "launchApp(${action.text}): ${e.message}")
            ActionResult(action.type, false, BridgeContract.VERDICT_ERROR, e.message)
        }
    }

    private fun showNotification(action: Action): ActionResult {
        Notifier.showRuleMessage(context, action.text)
        return ActionResult(action.type, true, BridgeContract.VERDICT_ALLOWED)
    }

    // -------------------------------------------------------------------------

    private fun noBridge(action: Action) =
        ActionResult(action.type, false, BridgeContract.VERDICT_NO_BRIDGE, "MG4Control unreachable")

    private fun Bundle.toResult(type: ActionType) = ActionResult(
        actionType = type,
        ok = getBoolean(BridgeContract.KEY_OK, false),
        verdict = getString(BridgeContract.KEY_VERDICT) ?: BridgeContract.VERDICT_ERROR,
        detail = getString(BridgeContract.KEY_DETAIL)
    )
}
