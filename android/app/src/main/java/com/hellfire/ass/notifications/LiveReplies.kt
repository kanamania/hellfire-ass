package com.hellfire.ass.notifications

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import com.hellfire.ass.events.NotificationNormalizer

/**
 * Reply actions of notifications that are still showing, so the owner can answer a WhatsApp/Telegram/SMS chat from
 * HellFire through the app's own reply action (audit HF-043). Kept in memory only: Android's reply intents cannot be
 * saved, and they stop working once the notification is gone or this process ends.
 */
object LiveReplies {
    class Reply(val action: Notification.Action, val input: RemoteInput, val app: String)
    private val replies = object : LinkedHashMap<String, Reply>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Reply>?) = size > 200
    }

    /** The notification's free-text reply action, if it has one. */
    fun replyAction(notification: Notification): Pair<Notification.Action, RemoteInput>? =
        notification.actions.orEmpty().firstNotNullOfOrNull { action ->
            action.remoteInputs?.firstOrNull { it.allowFreeFormInput }?.let { action to it }
        }

    /** The fill-in intent that carries [text] as the reply. */
    fun replyIntent(action: Notification.Action, input: RemoteInput, text: String): Intent = Intent().also { intent ->
        RemoteInput.addResultsToIntent(action.remoteInputs, intent, Bundle().apply { putCharSequence(input.resultKey, text) })
    }

    @Synchronized fun remember(sbn: StatusBarNotification, label: String) {
        val found = replyAction(sbn.notification) ?: return
        replies[NotificationNormalizer.sha256(sbn.key)] = Reply(found.first, found.second, label)
    }
    @Synchronized fun forget(sbn: StatusBarNotification) { replies.remove(NotificationNormalizer.sha256(sbn.key)) }
    /** [keyHash] is the "notification_key_hash" stored with the captured event. */
    @Synchronized fun find(keyHash: String): Reply? = replies[keyHash]

    /** Hands [text] to the other app's reply action. Returns false when the notification is no longer live. */
    fun send(context: Context, keyHash: String, text: String): Boolean {
        val reply = find(keyHash) ?: return false
        return try { reply.action.actionIntent.send(context, 0, replyIntent(reply.action, reply.input, text)); true }
        catch (_: android.app.PendingIntent.CanceledException) { synchronized(this) { replies.remove(keyHash) }; false }
    }
}
