package com.hellfire.ass

import android.app.Application
import androidx.room.Room
import com.hellfire.ass.communications.CommunicationMessage
import com.hellfire.ass.data.*
import com.hellfire.ass.finance.*
import com.hellfire.ass.memory.MemoryItem
import com.hellfire.ass.security.PayloadCipher
import com.hellfire.ass.sync.WebSync
import com.hellfire.ass.tasks.AssistantTask
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import javax.crypto.KeyGenerator

/** In-memory stand-in for web/api's sync routes. */
private class FakeServer : WebSync.Call {
    var dataset = "first"
    val rows = mutableMapOf<String, MutableMap<String, JSONObject>>()
    var batches = 0
    var resets = 0
    var finished = 0
    fun reset() { dataset = UUID.randomUUID().toString(); rows.clear(); resets++ }
    override fun send(method: String, path: String, body: JSONObject?): JSONObject = when (path) {
        "/api/sync/state" -> JSONObject().put("dataset", dataset)
        "/api/sync/reset" -> { reset(); JSONObject().put("dataset", dataset) }
        "/api/sync/batch" -> {
            if (body!!.getString("dataset") != dataset) throw WebSync.Rejected(409)
            batches++
            val kind = rows.getOrPut(body.getString("kind")) { mutableMapOf() }
            val upserts = body.getJSONArray("upserts")
            for (i in 0 until upserts.length()) upserts.getJSONObject(i).let { kind[it.getString("id")] = it }
            val deletes = body.getJSONArray("deletes")
            for (i in 0 until deletes.length()) kind.remove(deletes.getString(i))
            JSONObject()
        }
        "/api/sync/finish" -> { finished++; JSONObject() }
        else -> error("unexpected $method $path")
    }
    fun ids(kind: String) = rows[kind].orEmpty().keys.sorted()
    fun data(kind: String, id: String) = rows.getValue(kind).getValue(id).getJSONObject("data")
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebSyncTest {
    private lateinit var db: EventDatabase
    private val cipher = PayloadCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
    private val now = 1_794_787_200_000L // 2026-11-16 EAT
    private var state: JSONObject? = null

    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), EventDatabase::class.java).build() }
    @After fun close() = db.close()

    private suspend fun event(id: String, source: String, text: String, sensitive: Boolean = false, label: String? = null) {
        val payload = JSONObject().put("fields", JSONObject().put("text", org.json.JSONArray().put(text)).put("sender_hint", org.json.JSONArray().put("M-PESA"))
            .put("action_metadata", org.json.JSONArray().put("not for the web")))
        db.events().insertCaptured(SourceEvent(id, source, "ext-$id", "fixture", now - 5_000, now - 5_000, "POSTED", "hash-$id",
            cipher.encrypt(payload.toString().toByteArray()), contentAvailable = true, contentRedacted = false, securitySensitive = sensitive))
        if (label != null) db.openHelper.writableDatabase.execSQL("UPDATE event_processing SET label = ? WHERE eventId = ?", arrayOf(label, id))
    }

    private fun sync(server: FakeServer) = runBlocking { WebSync.run(db, cipher, state, server, save = { state = JSONObject(it.toString()) }, now = now) }

    private fun seed() = runBlocking {
        event("sms", "android_sms", "Umepokea TSh 5,000")
        event("otp", "android_sms", "Code 482910", sensitive = true)
        event("chat", "android_notification", "Tuonane saa nne", label = "MESSAGE")
        event("system", "android_notification", "Battery optimised", label = "SYSTEM")
        db.finances().addAccount(FinanceAccount("cash", "Cash"))
        db.finances().post(LedgerEntry("lunch", "cash", "k1", "EXPENSE", -7_000, "Food", "Lunch", now - 3_600_000, now, "lunch"))
        db.tasks().insert(AssistantTask("pay", "sms", "Pay Juma", now + 86_400_000, party = "Juma", evidence = "Nitakulipa kesho", state = "ACTIVE", createdAt = now, updatedAt = now))
        db.tasks().insert(AssistantTask("secret", "otp", "Use code", now, party = "Bank", evidence = "Code 482910", createdAt = now, updatedAt = now))
        db.communications().message(CommunicationMessage("mail", "EMAIL_IMAP", "acct", "x1", "sms", "t", "a@example.com", "me@example.com", "Hi", "Habari", now - 1_000, "INCOMING"))
        db.communications().message(CommunicationMessage("otp-mail", "EMAIL_IMAP", "acct", "x2", "otp", "t", "bank@example.com", "me@example.com", "Code", "Code 482910", now, "INCOMING"))
        db.memory().insert(MemoryItem("note", "sms", "Juma", "Prefers M-Pesa", "OWNER_NOTE", 100, now, now))
    }

    @Test fun `first pass copies selected rows and never security-sensitive ones`() {
        seed()
        val server = FakeServer()
        sync(server)
        assertEquals(1, server.resets)
        assertEquals(1, server.finished)
        assertEquals(listOf("chat", "sms"), server.ids("event"))
        assertEquals(listOf("pay"), server.ids("task"))
        assertEquals(listOf("mail"), server.ids("message"))
        assertEquals(listOf("lunch"), server.ids("ledger"))
        assertEquals(listOf("note"), server.ids("memory"))
        val sms = server.data("event", "sms")
        assertEquals("Umepokea TSh 5,000", sms.getJSONObject("fields").getJSONArray("text").getString(0))
        assertFalse(sms.has("payloadHash"))
        assertFalse(sms.getJSONObject("fields").has("action_metadata"))
        assertFalse(server.data("ledger", "lunch").has("dedupeKey"))
        val summary = server.data("summary", "current")
        assertEquals("2026-11", summary.getString("month"))
        assertEquals(7_000L, summary.getLong("expenses"))
        assertEquals(-7_000L, summary.getJSONArray("balances").getJSONObject(0).getLong("balance"))
        assertFalse(server.rows.toString().contains("482910"))
    }

    @Test fun `contact picks and person keys stay out of the web copy`() = runBlocking {
        seed()
        val server = FakeServer()
        sync(server)
        val links = com.hellfire.ass.contacts.ContactLinks(db)
        val key = links.save(com.hellfire.ass.contacts.ContactChoice("Private contact fixture", "private-lookup", listOf("0691234567", "private@example.invalid")))
        links.ledger("lunch", key); links.task("pay", key)
        sync(server)
        assertFalse(server.data("ledger", "lunch").has("personKey"))
        assertFalse(server.data("task", "pay").has("personKey"))
        for (privateValue in listOf("Private contact fixture", "private-lookup", "private@example.invalid", "691234567", "person_contact", "person_alias"))
            assertFalse(privateValue, server.rows.toString().contains(privateValue))
        assertEquals(1, db.contacts().all().size)
    }

    @Test fun `later passes send only changes and deletions`() = runBlocking {
        seed()
        val server = FakeServer()
        sync(server)
        val batches = server.batches
        assertEquals(WebSync.Counts(0, 0), sync(server))
        assertEquals(batches, server.batches)
        db.tasks().close("pay", "DONE", now)
        db.memory().forget("note")
        assertEquals(WebSync.Counts(1, 1), sync(server))
        assertEquals("DONE", server.data("task", "pay").getString("state"))
        assertEquals(emptyList<String>(), server.ids("memory"))
        assertEquals(1, server.resets)
    }

    @Test fun `a wiped or replaced server gets a full copy`() {
        seed()
        val server = FakeServer()
        sync(server)
        server.reset()
        sync(server)
        assertEquals(3, server.resets)
        assertEquals(listOf("chat", "sms"), server.ids("event"))
        assertEquals(listOf("lunch"), server.ids("ledger"))
    }

    @Test fun `large kinds are split into bounded batches`() = runBlocking {
        event("sms", "android_sms", "x")
        repeat(650) { db.memory().insert(MemoryItem("m$it", "sms", "S$it", "t".repeat(2_000), "OWNER_NOTE", 100, now + it, null)) }
        val server = FakeServer()
        assertEquals(651 + 1, sync(server).upserted) // memory + event + summary
        assertEquals(650, server.ids("memory").size)
        assertTrue(server.batches >= 4)
    }

    @Test fun `only https servers and long tokens are accepted`() {
        val token = "t".repeat(32)
        WebSync.validate("https://hellfire.example.com", token)
        for (url in listOf("http://hellfire.example.com", "https://user@hellfire.example.com", "https://hellfire.example.com/?x=1", "hellfire.example.com"))
            assertThrows(IllegalArgumentException::class.java) { WebSync.validate(url, token) }
        assertThrows(IllegalArgumentException::class.java) { WebSync.validate("https://hellfire.example.com", "short") }
        assertThrows(IllegalArgumentException::class.java) { WebSync.validate("https://hellfire.example.com", "t".repeat(31) + " ") }
    }
}
