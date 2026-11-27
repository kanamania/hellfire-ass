package com.hellfire.ass

import androidx.test.platform.app.InstrumentationRegistry
import com.hellfire.ass.data.SourceEvent
import com.hellfire.ass.library.CaptureOrigin
import com.hellfire.ass.library.ContentKind
import com.hellfire.ass.security.PortableBackup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * HF-071: a full portable backup and restore through the app's own SQLCipher database, Keystore-wrapped
 * media vault and the real staging database on Android. Runs only in the isolated test packages; it clears
 * that package's database. Synthetic data only.
 */
class NativeBackupRoundTripTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HellFireApp

    @Test fun fullBackupRestoresRowsAndOriginalsThroughNativeSqlcipher() = runBlocking {
        val db = app.database
        val password = "synthetic-roundtrip-passphrase"
        val archive = File(app.cacheDir, "roundtrip-${UUID.randomUUID()}.hfb")
        db.clearAllTables()
        val eventId = "roundtrip-${UUID.randomUUID()}"
        val payload = "synthetic native payload".toByteArray()
        db.events().insert(SourceEvent(eventId, externalId = eventId, packageName = "fixture.invalid", occurredAt = 1,
            receivedAt = 2, kind = "SYNTHETIC", payloadHash = "fixture", encryptedPayload = app.cipher.encrypt(payload),
            contentAvailable = true, contentRedacted = false, securitySensitive = false))
        val original = app.media.capture("synthetic native original".toByteArray(), "fixture.txt", "text/plain",
            CaptureOrigin.DRAWER, contentKind = ContentKind.TEXT)
        try {
            val outcome = PortableBackup(app).export(archive, password.toCharArray())
            assertTrue(outcome.blocked.isEmpty())

            db.clearAllTables()
            app.media.file(original.id).delete()
            assertNull(db.events().find(eventId))

            val report = PortableBackup(app).restoreFiles(listOf(archive), password.toCharArray())
            assertEquals(1, report.restoredOriginals)
            assertTrue(report.missing.isEmpty())
            val event = db.events().find(eventId)!!
            assertArrayEquals(payload, app.cipher.decrypt(event.encryptedPayload))
            val restored = db.library().item(original.id)!!
            assertEquals("synthetic native original", app.media.openStream(restored).use { it.readBytes().toString(Charsets.UTF_8) })
            assertEquals("ok", db.openHelper.writableDatabase.query("PRAGMA integrity_check").use { it.moveToFirst(); it.getString(0) })

            assertTrue(runCatching { PortableBackup(app).restoreFiles(listOf(archive), password.toCharArray()) }.isFailure)
        } finally {
            archive.delete()
            db.clearAllTables()
        }
    }
}
