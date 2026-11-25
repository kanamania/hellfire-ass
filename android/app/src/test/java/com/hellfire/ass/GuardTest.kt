package com.hellfire.ass

import android.app.Application
import androidx.room.Room
import com.hellfire.ass.data.EventDatabase
import com.hellfire.ass.guard.*
import com.hellfire.ass.security.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.*
import java.util.zip.*
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GuardTest {
    private lateinit var db: EventDatabase
    @Before fun open() { db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), EventDatabase::class.java).build() }
    @After fun close() { db.close() }
    private fun cipher() = PayloadCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())

    @Test fun `triggers only escalate an armed session and preserve first reason`() = runBlocking {
        val dao = db.guard()
        assertEquals(0, dao.activate("sim_changed", 1))
        dao.arm(10)
        assertEquals(1, dao.activate("unlock_failures", 11))
        assertEquals(0, dao.activate("connectivity_lost", 12))
        assertEquals("unlock_failures", dao.state()!!.trigger)
        assertTrue(GuardLogic.capture(dao.state()))
        assertEquals(1, dao.disarm(13))
        assertFalse(GuardLogic.capture(dao.state()))
        assertEquals(0, dao.activate("manual_test", 14))
    }
    @Test fun `rearm waits for disarm acknowledgment and starts a new identity`() = runBlocking {
        val dao = db.guard()
        dao.arm(10)
        val first = dao.nextHeartbeat()!!
        assertEquals(1L, first.sequence)
        dao.disarm(11)
        try { dao.arm(12); fail("Rearmed while cancellation pending") } catch (_: IllegalStateException) { }
        val cancel = dao.nextHeartbeat()!!
        assertFalse(cancel.armed); assertEquals(2L, cancel.sequence)
        dao.acknowledged(first.sessionId, first.sequence, 13)
        assertTrue(dao.state()!!.pendingDisarm)
        dao.acknowledged(cancel.sessionId, cancel.sequence, 14)
        assertFalse(dao.state()!!.pendingDisarm)
        dao.arm(15)
        assertNotEquals(first.sessionId, dao.state()!!.sessionId)
        assertEquals(0L, dao.state()!!.sequence)
    }
    @Test fun `boot never resumes camera and requires background access for location`() {
        assertEquals("OFF", GuardLogic.bootMode(false, true))
        assertEquals("TAP_TO_RESUME", GuardLogic.bootMode(true, false))
        assertEquals("LOCATION_ONLY", GuardLogic.bootMode(true, true))
    }
    @Test fun `SIM and unlock thresholds refuse absence and disarmed signals`() {
        assertFalse(GuardLogic.simChanged(true, null, "card"))
        assertFalse(GuardLogic.simChanged(true, "card", null))
        assertFalse(GuardLogic.simChanged(false, "old", "new"))
        assertFalse(GuardLogic.simChanged(true, "card", "card"))
        assertTrue(GuardLogic.simChanged(true, "old", "new"))
        assertFalse(GuardLogic.unlockTrigger(false, 9))
        assertFalse(GuardLogic.unlockTrigger(true, 2))
        assertTrue(GuardLogic.unlockTrigger(true, 3))
    }
    @Test fun `encrypted evidence backup rekeys and revokes armed consent`() = runBlocking {
        val old = cipher(); val fresh = cipher()
        val payload = "Synthetic guard sensor bytes".toByteArray()
        db.guard().arm(1)
        val session = db.guard().state()!!.sessionId
        db.guard().evidence(GuardEvidence("synthetic", session, 2, old.encrypt(payload)))
        val archive = ByteArrayOutputStream()
        ZipOutputStream(archive).use { BackupTables.write(db.openHelper.writableDatabase, it, old) }
        val target = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), EventDatabase::class.java).build()
        try {
            ZipInputStream(ByteArrayInputStream(archive.toByteArray())).use { zip ->
                for (table in BackupTables.tables) {
                    assertEquals("tables/$table.jsonl", zip.nextEntry!!.name)
                    BackupTables.readTable(target.openHelper.writableDatabase, zip, table, fresh)
                }
            }
            BackupTables.revokeRestoredApprovals(target.openHelper.writableDatabase)
            val restored = target.guard().state()!!
            assertFalse(restored.armed); assertFalse(restored.pendingDisarm)
            val row = target.guard().pending(session).single()
            assertArrayEquals(payload, fresh.decrypt(row.encryptedPayload))
            assertTrue(runCatching { old.decrypt(row.encryptedPayload) }.isFailure)
            assertEquals(20, BackupTables.SCHEMA)
            assertFalse("guard_state" in BackupTables.tablesFor(19))
            assertTrue("guard_state" in BackupTables.tablesFor(20))
        } finally { target.close() }
    }
    @Test fun `relay refuses cleartext URLs credentials queries and weak keys`() {
        val key = android.util.Base64.encodeToString(ByteArray(32), android.util.Base64.NO_WRAP)
        val token = "synthetic-device-token-at-least-32-characters"
        assertEquals(32, GuardRelay.validate("https://relay.example.invalid", token, key).size)
        for (url in listOf("http://relay.example.invalid", "https://user:pass@relay.example.invalid", "https://relay.example.invalid/path", "https://relay.example.invalid?token=secret")) {
            assertThrows(IllegalArgumentException::class.java) { GuardRelay.validate(url, token, key) }
        }
        assertThrows(IllegalArgumentException::class.java) { GuardRelay.validate("https://relay.example.invalid", "short", key) }
        assertThrows(IllegalArgumentException::class.java) { GuardRelay.validate("https://relay.example.invalid", token, "c2hvcnQ=") }
    }
    @Test fun `owner policy helper reports unprovisioned installation honestly`() {
        val context = RuntimeEnvironment.getApplication()
        assertFalse(GuardPolicies.isOwner(context))
    }
}
