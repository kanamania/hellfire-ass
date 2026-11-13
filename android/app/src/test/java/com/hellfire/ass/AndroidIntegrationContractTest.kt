package com.hellfire.ass

import android.app.Application
import android.app.Notification
import android.app.Person
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import com.hellfire.ass.assistant.*
import com.hellfire.ass.events.NotificationNormalizer
import com.hellfire.ass.notifications.AndroidNotificationAdapter
import com.hellfire.ass.notifications.HellFireNotificationListener
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidIntegrationContractTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun sbn(notification: Notification) = StatusBarNotification(
        "example.fixture", "example.fixture", 1, "synthetic", 10000, 0, 0,
        notification, UserHandle.getUserHandleForUid(0), 100L,
    )

    @Test fun `Android extras preserve long lines ongoing and removal reason`() {
        val notification = Notification.Builder(context, "fixture-channel")
            .setContentTitle("Fixture title").setContentText("Fixture short text")
            .setStyle(Notification.InboxStyle().addLine("Line one").addLine("Line two"))
            .setOngoing(true).setCategory(Notification.CATEGORY_STATUS).build()
        val raw = AndroidNotificationAdapter.snapshot(sbn(notification), "REMOVED", 2, null)
        assertEquals("fixture-channel", raw.fields["channel_id"]!!.single())
        assertEquals(listOf("Line one", "Line two"), raw.fields["text_lines"])
        assertEquals(2, raw.removedReason)
        assertTrue(raw.ongoing)
        assertTrue(NotificationNormalizer.normalize(raw, "com.hellfire.ass")!!.contentAvailable)
    }
    @Test fun `messaging fields are redacted along with top-level OTP text`() {
        val person = Person.Builder().setName("Synthetic sender").build()
        val notification = Notification.Builder(context, "fixture-channel")
            .setStyle(Notification.MessagingStyle(person).setGroupConversation(true)
                .addMessage("OTP AB9K2Z", 100, person)).build()
        val raw = AndroidNotificationAdapter.snapshot(sbn(notification), "POSTED", null, null)
        assertTrue(raw.groupConversation)
        assertTrue(raw.fields["messages"]!!.first().contains("AB9K2Z"))
        val safe = NotificationNormalizer.normalize(raw, "com.hellfire.ass")!!
        assertTrue(safe.contentRedacted)
        assertFalse(safe.snapshot.fields.toString().contains("AB9K2Z"))
    }
    @Test fun `contentless notification does not fabricate content`() {
        val raw = AndroidNotificationAdapter.snapshot(sbn(Notification.Builder(context, "fixture-channel").build()), "POSTED", null, null)
        assertFalse(NotificationNormalizer.normalize(raw, "com.hellfire.ass")!!.contentAvailable)
    }
    @Test fun `assistant binding metadata names real session and recognition services`() {
        val info = context.packageManager.getServiceInfo(ComponentName(context, HellFireVoiceService::class.java), PackageManager.GET_META_DATA)
        assertEquals("android.permission.BIND_VOICE_INTERACTION", info.permission)
        val parser = info.loadXmlMetaData(context.packageManager, "android.voice_interaction")
        parser.use {
            while (it.next() != org.xmlpull.v1.XmlPullParser.START_TAG) { }
            assertEquals("voice-interaction-service", it.name)
            val ns = "http://schemas.android.com/apk/res/android"
            assertEquals(HellFireSessionService::class.java.name, it.getAttributeValue(ns, "sessionService"))
            assertEquals(HellFireRecognitionService::class.java.name, it.getAttributeValue(ns, "recognitionService"))
            assertEquals("true", it.getAttributeValue(ns, "supportsAssist"))
        }
        val session = context.packageManager.getServiceInfo(ComponentName(context, HellFireSessionService::class.java), 0)
        assertEquals("android.permission.BIND_VOICE_INTERACTION", session.permission)
        val listener = context.packageManager.getServiceInfo(ComponentName(context, HellFireNotificationListener::class.java), 0)
        assertEquals("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", listener.permission)
    }
}
