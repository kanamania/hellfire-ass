package com.hellfire.ass.notifications

import androidx.core.app.NotificationCompat
import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.hellfire.ass.events.NotificationSnapshot

object AndroidNotificationAdapter {
    fun snapshot(sbn: StatusBarNotification, kind: String, reason: Int?,
        ranks: NotificationListenerService.RankingMap?): NotificationSnapshot {
        val n = sbn.notification
        val extras = n.extras
        val fields = linkedMapOf<String, List<String>>()
        fun field(name: String, value: CharSequence?) {
            if (value != null) fields[name] = listOf(value.toString())
        }
        field("title", extras.getCharSequence(Notification.EXTRA_TITLE))
        field("text", extras.getCharSequence(Notification.EXTRA_TEXT))
        field("big_text", extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        field("sub_text", extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.let {
            fields["text_lines"] = it.map(CharSequence::toString)
        }
        field("category", n.category)
        field("channel_id", n.channelId)
        field("conversation_id", n.shortcutId)
        NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)?.let { style ->
            val messages = style.messages
            fields["messages"] = messages.map { it.text?.toString().orEmpty() }
            fields["sender_hint"] = messages.map { it.person?.name?.toString().orEmpty() }
        }
        val actions = n.actions.orEmpty()
        fields["action_metadata"] = actions.map {
            "${it.title?.toString().orEmpty()} (semantic=${it.semanticAction}, freeForm=${it.remoteInputs?.any { input -> input.allowFreeFormInput } == true})"
        }
        val ranking = NotificationListenerService.Ranking().takeIf { ranks?.getRanking(sbn.key, it) == true }
        return NotificationSnapshot(
            key = sbn.key, packageName = sbn.packageName,
            postedAt = sbn.postTime, receivedAt = System.currentTimeMillis(), kind = kind,
            fields = fields, ongoing = sbn.isOngoing, removedReason = reason,
            rank = ranking?.rank, importance = ranking?.importance,
            groupConversation = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION),
            remoteInputCount = actions.sumOf { it.remoteInputs?.size ?: 0 },
        )
    }
}
