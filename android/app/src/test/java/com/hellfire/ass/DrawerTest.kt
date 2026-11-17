package com.hellfire.ass

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.hellfire.ass.data.EventDatabase
import com.hellfire.ass.drawer.*
import com.hellfire.ass.library.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.util.UUID
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class DrawerTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: EventDatabase
    private lateinit var app: HellFireApp
    private lateinit var manager: androidx.work.impl.WorkManagerImpl

    @Before fun setup() {
        androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().setExecutor { }.build())
        manager = androidx.work.WorkManager.getInstance(context) as androidx.work.impl.WorkManagerImpl
        db = Room.inMemoryDatabaseBuilder(context, EventDatabase::class.java).build()
        app = HellFireApp()
        ReflectionHelpers.callInstanceMethod<Unit>(app, "attach", ClassParameter.from(Context::class.java, context))
        ReflectionHelpers.setField(app, "database\$delegate", lazyOf(db))
        ReflectionHelpers.setField(app, "cipher\$delegate", lazyOf(com.hellfire.ass.security.PayloadCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())))
        app.media.directory.deleteRecursively()
    }

    @After fun close() {
        manager.cancelAllWork().result.get()
        manager.closeDatabase()
        androidx.work.impl.WorkManagerImpl.setDelegate(null)
        ReflectionHelpers.setStaticField(androidx.work.impl.WorkManagerImpl::class.java, "sDefaultInstance", null)
        db.close()
        app.media.directory.deleteRecursively()
    }

    @Test fun `detectors find coordinates maps phone email vcard ics code json and mixed content`() {
        val maps = ContentDetect.parse("https://www.google.com/maps?q=-1.2921,36.8219")
        assertEquals(ContentKind.LOCATION, maps.kind)
        assertEquals(-1.2921, maps.places.first().latitude!!, 0.0001)
        assertEquals(36.8219, maps.places.first().longitude!!, 0.0001)
        assertTrue(maps.signals.contains("maps"))

        val geo = ContentDetect.parse("geo:-6.7924,39.2083")
        assertEquals(-6.7924, geo.places.first().latitude!!, 0.0001)
        assertTrue(geo.signals.contains("coordinates"))

        val dms = ContentDetect.parse("""1°17'31.6"S 36°49'18.8"E""")
        assertTrue(dms.places.any { it.latitude != null && it.latitude!! < 0 && it.longitude!! > 0 })

        val contact = ContentDetect.parse("Call +255712345678 or me@example.com")
        assertTrue(contact.entities.any { it.type == "phone" && it.value.contains("255") })
        assertTrue(contact.entities.any { it.type == "email" && it.value == "me@example.com" })

        val card = ContentDetect.parse("BEGIN:VCARD\nFN:Amina Juma\nTEL:+255700000000\nEMAIL:amina@example.com\nEND:VCARD")
        assertEquals(ContentKind.CONTACT, card.kind)
        assertTrue(card.signals.contains("vcard"))
        assertTrue(card.entities.any { it.type == "contact" && it.value.contains("Amina") })

        val event = ContentDetect.parse("BEGIN:VEVENT\nSUMMARY:Site visit\nDTSTART:20261117T090000Z\nLOCATION:Kariakoo\nEND:VEVENT")
        assertEquals(ContentKind.EVENT, event.kind)
        assertTrue(event.signals.contains("ical"))
        assertTrue(event.entities.any { it.type == "event" && it.value.contains("Site visit") })

        val code = ContentDetect.parse("java.lang.IllegalStateException: boom\n\tat com.example.Foo.bar(Foo.kt:10)")
        assertEquals(ContentKind.CODE, code.kind)
        assertTrue(code.signals.contains("error"))
        assertTrue(code.signals.contains("code"))

        val json = ContentDetect.parse("""{"ok":true,"n":1}""")
        assertEquals(ContentKind.CODE, json.kind)
        assertTrue(json.signals.contains("json"))

        val mixed = ContentDetect.combine(listOf(maps, card))
        assertTrue(mixed.mixed)
        assertTrue(mixed.signals.contains("mixed"))
        assertEquals(ContentKind.OTHER, mixed.kind)

        val address = ContentDetect.parse("Meet at 12 Samora Avenue")
        assertTrue(address.entities.any { it.type == "address" && it.uncertain })
        assertTrue(ActionEngine.liveFactsNote(maps)!!.contains("live source"))
        assertFalse(SystemDrawer.implemented)
    }

    @Test fun `actions prioritize errors and receipts and never execute code`() {
        val shot = ContentDetect.parse("java.lang.NullPointerException\n\tat com.example.Foo.bar(Foo.kt:3)", mime = "image/png")
        val shotActions = ActionEngine.recommend(shot, modelInstalled = false)
        assertEquals("troubleshoot", shotActions.first().type)
        assertTrue(shotActions.none { it.type == "execute" || it.type == "run" })

        val receipt = ContentDetect.parse("Receipt\nTotal 1200\nVAT 192", mime = "image/jpeg")
        assertEquals("extract_receipt", ActionEngine.recommend(receipt, false).first().type)

        val place = ContentDetect.parse("https://maps.google.com/?q=-1.2921,36.8219")
        val location = ActionEngine.recommend(place, false)
        assertEquals("open_map", location.first().type)
        assertTrue(location.any { it.type == "navigate" && it.needsConfirmation })
        assertTrue(location.none { it.label.contains("weather", true) || it.label.contains("hours", true) })
        assertEquals("geo:-1.2921,36.8219", ActionEngine.mapUri(place.places.first { it.latitude != null }))
        assertTrue(ActionEngine.navigationUri(place.places.first())!!.startsWith("google.navigation:q="))
    }

    @Test fun `save happens before analysis and a failed capture is an incomplete record`() = runBlocking {
        var analyzedAfterSave = false
        val saved = DrawerPipeline(app, answer = {
            assertNotNull(db.inbox().page(InboxLifecycle.ACTIVE.name, false, Long.MAX_VALUE, "", 5).firstOrNull())
            analyzedAfterSave = true
            "local summary"
        }).ingest(listOf(DropPart.Text("Notes for the drawer https://example.com")))
        assertTrue(saved.steps.indexOf("save") < saved.steps.indexOf("inbox"))
        assertTrue(saved.steps.indexOf("inbox") < saved.steps.indexOf("local-ai"))
        assertTrue(analyzedAfterSave)
        assertEquals(CaptureCompleteness.COMPLETE.name, saved.captureState)
        assertEquals(1, db.inbox().contentsNow(saved.inboxId).size)
        assertEquals(SyncState.PENDING.name, db.inbox().item(saved.inboxId)!!.syncState)
        assertEquals(BackupState.PENDING.name, db.inbox().item(saved.inboxId)!!.backupState)
        assertTrue(saved.summary.contains("No page snapshot was fetched"))

        var called = false
        val missing = DrawerPipeline(app, answer = { called = true; "no" }).ingest(listOf(DropPart.Bytes(ByteArray(0), "empty.bin", "application/octet-stream")))
        assertFalse(called)
        assertEquals(CaptureCompleteness.INCOMPLETE.name, missing.captureState)
        assertTrue(missing.steps.contains("incomplete-record"))
        assertFalse(missing.steps.contains("local-ai"))
        assertNotNull(db.inbox().item(missing.inboxId))
        assertTrue(db.inbox().contentsNow(missing.inboxId).isEmpty())
        assertFalse(missing.captureError.isNullOrBlank())

        val kept = DrawerPipeline(app, answer = { error("model down") }).ingest(listOf(DropPart.Text("keep this original")))
        assertEquals(CaptureCompleteness.COMPLETE.name, kept.captureState)
        assertEquals(1, db.inbox().contentsNow(kept.inboxId).size)
    }

    @Test fun `follow-up keeps context and clearing it does not delete the item`() = runBlocking {
        val id = UUID.randomUUID().toString()
        db.inbox().saveCapture(InboxItem(id, ContentKind.LOCATION.name, "Place", "Coordinates -1.29, 36.82", capturedAt = 1, updatedAt = 1), emptyList())
        val talk = DrawerTalk(db.inbox(), now = { 5 })
        val session = "sess-1"
        talk.ask(id, session, "What is nearby?", "Coordinates -1.29, 36.82") { prompt ->
            assertTrue(prompt.contains("untrusted"))
            assertTrue(prompt.contains("What is nearby?"))
            "needs a live source"
        }
        talk.ask(id, session, "Show hotels", "Coordinates -1.29, 36.82") { prompt ->
            assertTrue(prompt.contains("What is nearby?"))
            "needs a live source"
        }
        assertEquals(4, db.inbox().context(session, 10).size)
        talk.clear(session)
        assertTrue(db.inbox().context(session, 10).isEmpty())
        assertEquals(4, db.inbox().conversation(id).first().size)
        assertEquals(id, db.inbox().item(id)!!.id)
    }

    @Test fun `trash restore and permanent delete keep an honest lifecycle`() = runBlocking {
        val captured = app.media.capture("original".toByteArray(), "note.txt", "text/plain", CaptureOrigin.DRAWER)
        val id = UUID.randomUUID().toString()
        db.inbox().saveCapture(InboxItem(id, ContentKind.TEXT.name, "Note", capturedAt = 1, updatedAt = 1), listOf(captured.id))
        DrawerInbox.trash(app, id, 2)
        assertEquals(InboxLifecycle.TRASHED.name, db.inbox().item(id)!!.lifecycle)
        assertNotNull(db.media().item(captured.id))
        DrawerInbox.restore(app, id, 3)
        assertEquals(InboxLifecycle.ACTIVE.name, db.inbox().item(id)!!.lifecycle)
        DrawerInbox.deletePermanently(app, id, 4)
        assertEquals(InboxLifecycle.DELETED.name, db.inbox().item(id)!!.lifecycle)
        assertNull(db.media().item(captured.id))
        assertEquals(true, db.sync().record("inbox_item", id)!!.deleted)
    }
}
