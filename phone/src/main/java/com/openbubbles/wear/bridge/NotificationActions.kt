package com.openbubbles.wear.bridge

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Thin wrapper around the notification's own actions. We deliberately avoid
 * re-implementing message sending: the pending intents belong to OpenBubbles and
 * its receiver performs the real backend work.
 */
object NotificationActions {
    private const val TAG = "OBWearActions"

    fun replyAction(notification: Notification): Notification.Action? =
        notification.actions?.firstOrNull { action ->
            !action.remoteInputs.isNullOrEmpty()
        }

    fun markReadAction(notification: Notification): Notification.Action? {
        val actions = notification.actions ?: return null
        return actions.firstOrNull { action ->
            action.title?.toString()?.contains("read", ignoreCase = true) == true
        } ?: actions.firstOrNull { action ->
            action.semanticAction == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ
        }
    }

    fun sendReply(context: Context, action: Notification.Action, text: String): Boolean {
        val inputs = action.remoteInputs
        if (inputs.isNullOrEmpty() || text.isBlank()) return false
        return try {
            val input = inputs.firstOrNull { it.allowFreeFormInput } ?: inputs.first()
            val results = Bundle().apply {
                putCharSequence(input.resultKey, text)
            }
            val fillIn = Intent()
            RemoteInput.addResultsToIntent(inputs, fillIn, results)
            action.actionIntent.send(context, 0, fillIn)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to send notification reply", t)
            false
        }
    }

    fun sendMarkRead(context: Context, action: Notification.Action): Boolean = try {
        action.actionIntent.send(context, 0, null)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "Unable to send mark-read action", t)
        false
    }

    fun openContentIntent(context: Context, notification: Notification): Boolean {
        val intent: PendingIntent = notification.contentIntent ?: return false
        return try {
            intent.send(context, 0, null)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to open chat on phone", t)
            false
        }
    }

    fun launchOpenBubbles(context: Context, packageName: String?): Boolean {
        val pkg = packageName ?: BridgeInfo.openBubblesPackage(context) ?: return false
        val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(launch)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to launch OpenBubbles", t)
            false
        }
    }
}
