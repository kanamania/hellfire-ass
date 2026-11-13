package com.hellfire.ass

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.Telephony
import com.hellfire.ass.events.NotificationNormalizer
import com.hellfire.ass.sms.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SmsCaptureTest {
    @Test fun `incoming and sent provider versions have stable distinct source identities`() {
        val row = SmsRecord(9, "SyntheticBank", "Umepokea TZS 15000", 100, 90, Telephony.Sms.MESSAGE_TYPE_INBOX, 1)
        val first = NotificationNormalizer.normalize(row.snapshot(200), "com.hellfire.ass")!!
        val replay = NotificationNormalizer.normalize(row.snapshot(999), "com.hellfire.ass")!!
        val outgoing = NotificationNormalizer.normalize(row.copy(type = Telephony.Sms.MESSAGE_TYPE_SENT).snapshot(200), "com.hellfire.ass")!!
        assertEquals(first.id, replay.id)
        assertEquals("android_sms", first.snapshot.source)
        assertEquals("INCOMING", first.snapshot.kind)
        assertEquals("OUTGOING", outgoing.snapshot.kind)
        assertNotEquals(first.id, outgoing.id)
        assertNotEquals(first.id, NotificationNormalizer.normalize(row.copy(rowId = 10).snapshot(200), "com.hellfire.ass")!!.id)
    }
    @Test fun `OTP in provider body is redacted before persistence`() {
        val raw = SmsRecord(1, "FixtureAuth", "Msimbo wako ni AB7K8Z", 1, 1, Telephony.Sms.MESSAGE_TYPE_INBOX, 0)
        val event = NotificationNormalizer.normalize(raw.snapshot(2), "com.hellfire.ass")!!
        assertTrue(event.securitySensitive)
        assertFalse(event.snapshot.fields.toString().contains("AB7K8Z"))
    }
    @Test(expected = IllegalArgumentException::class) fun `drafts are not reported as sent messages`() {
        SmsRecord(1, "Fixture", "Draft", 1, 0, Telephony.Sms.MESSAGE_TYPE_DRAFT, 0).snapshot(2)
    }
    @Test fun `SMS capture starts disabled and owner preference persists`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("capture", 0).edit().clear().commit()
        assertFalse(SmsCaptureSettings.enabled(context))
        SmsCaptureSettings.setEnabled(context, true)
        assertTrue(SmsCaptureSettings.enabled(context))
        SmsCaptureSettings.setEnabled(context, false)
        assertFalse(SmsCaptureSettings.enabled(context))
    }
    @Test fun `provider paging is bounded ordered parameterized and closes its cursor`() = runBlocking {
        val cursor = MatrixCursor(SmsProviderReader.projection)
        for (id in 51..560) cursor.addRow(arrayOf<Any>(id, "Fixture", "Synthetic body", 100L, 90L, 1, 0))
        var observedArgs: Array<out String>? = null
        var observedOrder: String? = null
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
                observedArgs = selectionArgs
                observedOrder = sortOrder
                assertEquals("_id > ? AND type IN (?, ?)", selection)
                return cursor
            }
            override fun getType(uri: Uri): String? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read-only adapter")
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Read-only adapter")
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Read-only adapter")
        }
        ShadowContentResolver.registerProviderInternal("sms", provider)
        val rows = SmsProviderReader.readPage(RuntimeEnvironment.getApplication().contentResolver, 50)
        assertEquals(500, rows.size)
        assertEquals(550L, rows.last().rowId)
        assertArrayEquals(arrayOf("50", "1", "2"), observedArgs)
        assertEquals("_id ASC", observedOrder)
        assertTrue(cursor.isClosed)
    }
    @Test fun `SMS receiver accepts only the platform protected action`() {
        val info = RuntimeEnvironment.getApplication().packageManager.getReceiverInfo(
            android.content.ComponentName(RuntimeEnvironment.getApplication(), SmsReceivedReceiver::class.java), 0)
        assertEquals("android.permission.BROADCAST_SMS", info.permission)
        assertTrue(info.exported)
    }
}
