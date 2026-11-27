package com.hellfire.ass

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import com.hellfire.ass.notifications.LiveReplies
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveRepliesTest {
    @Test fun `replyActionRetainedWhileLive - finds the free-text reply and fills it in`() {
        val context = RuntimeEnvironment.getApplication()
        val pending = PendingIntent.getBroadcast(context, 1, Intent("synthetic.REPLY").setPackage(context.packageName), PendingIntent.FLAG_MUTABLE)
        val input = RemoteInput.Builder("reply_text").setLabel("Reply").build()
        val markRead = Notification.Action.Builder(null, "Mark as read", pending).build()
        val reply = Notification.Action.Builder(null, "Reply", pending).addRemoteInput(input).build()
        val notification = Notification.Builder(context, "chat").setContentTitle("Asha").addAction(markRead).addAction(reply).build()
        val (action, found) = LiveReplies.replyAction(notification)!!
        assertEquals("Reply", action.title.toString())
        val filled = LiveReplies.replyIntent(action, found, "Nimeona, asante")
        assertEquals("Nimeona, asante", RemoteInput.getResultsFromIntent(filled)!!.getCharSequence("reply_text").toString())
        assertNull(LiveReplies.replyAction(Notification.Builder(context, "chat").addAction(markRead).build()))
        assertNull(LiveReplies.find("unknown-key"))
        assertFalse(LiveReplies.send(context, "unknown-key", "hi"))
    }
}
