package com.hellfire.ass

import android.app.Application
import androidx.room.Room
import com.hellfire.ass.communications.CommunicationMessage
import com.hellfire.ass.data.*
import com.hellfire.ass.finance.*
import com.hellfire.ass.memory.MemoryItem
import com.hellfire.ass.news.*
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
    /** Pending web news actions; false makes the news routes answer 404 like a server from before news. */
    val actions = mutableListOf<JSONObject>()
    var newsSupported = true
    val acks = mutableListOf<Long>()
    val calls = mutableListOf<String>()
    fun reset() { dataset = UUID.randomUUID().toString(); rows.clear(); actions.clear(); resets++ }
    override fun send(method: String, path: String, body: JSONObject?): JSONObject = news(path, body) ?: when (path) {
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
    private fun news(path: String, body: JSONObject?): JSONObject? {
        calls += path.substringBefore('?')
        if (!path.startsWith("/api/sync/news-actions")) return null
        if (!newsSupported) throw WebSync.Rejected(404)
        if (path == "/api/sync/news-actions/ack") {
            val upTo = body!!.getLong("upTo")
            acks += upTo
            actions.removeAll { it.getLong("seq") <= upTo }
            return JSONObject().put("deleted", 0)
        }
        val after = path.substringAfter("after=").toLong()
        return JSONObject().put("actions", org.json.JSONArray(actions.filter { it.getLong("seq") > after }.take(500)))
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

    private suspend fun news() {
        val news = db.news()
        news.saveSource(NewsSource("daily", "Daily News", "RSS", "https://news.example/feed", "TANZANIA", "en", 70, etag = "etag-private",
            cursor = "cursor-private", createdAt = now, updatedAt = now))
        news.saveEvent(NewsEvent("e1", "Floods in Dar", "DISASTER", "tanzania, east_africa", signature = "DISASTER|dar", firstSeenAt = now - 9_000,
            updatedAt = now - 2_000, lastArticleAt = now - 1_000, version = 2, level = "HIGH", score = 72, protectedPublic = true,
            summaryJson = """{"headline":"Floods in Dar","quick":["Roads closed"]}""", scoreJson = """{"total":72,"reasons":["Public safety"]}"""))
        news.saveEvent(NewsEvent("saved-old", "Old saved", "POLITICS", "tanzania", signature = "x", firstSeenAt = 1, updatedAt = 1, lastArticleAt = 1))
        news.saveEvent(NewsEvent("stale", "Old unsaved", "POLITICS", "tanzania", signature = "y", firstSeenAt = 1, updatedAt = 1, lastArticleAt = 1))
        news.saveState(NewsEventState("saved-old", bookmarked = true, updatedAt = 1))
        news.saveState(NewsEventState("e1", deliveredVersion = 1, seenVersion = 1, updatedAt = now - 10_000))
        news.saveArticle(NewsArticle("a1", "daily", "https://news.example/a1", "news.example/a1", "Floods", "Full excerpt text", language = "en",
            publishedAt = now - 1_000, fetchedAt = now, fingerprint = "0", eventId = "e1",
            mediaJson = """[{"type":"IMAGE","url":"https://img.example/1.jpg","credit":"Daily News","bytes":"not for the web"}]"""))
        news.insertVersion(NewsEventVersion("e1", 2, now - 2_000, "MAJOR", "{}", "Rain", "More rain", "Roads closed", """["a1"]"""))
        news.saveBriefing(NewsBriefing("MORNING:2026-11-16", "MORNING", "Morning", now, now, now - 86_400_000, now, """[{"eventId":"e1","version":2,"section":"TOP"}]"""))
    }

    private fun webAction(seq: Long, at: Long, field: String, value: Any, target: String = "e1") =
        JSONObject().put("seq", seq).put("at", at).put("target", target).put("field", field).put("value", value)

    @Test fun `web news choices are applied before the push and then acknowledged`() = runBlocking {
        news()
        val server = FakeServer()
        server.actions += webAction(7, now - 5_000, "bookmarked", true)
        server.actions += webAction(8, now - 4_000, "seen", 2)
        server.actions += webAction(9, now - 3_000, "position", JSONObject().put("position", 1).put("audioMs", 0), target = "MORNING:2026-11-16")
        sync(server)
        val local = db.news().state("e1")!!
        assertTrue(local.bookmarked)
        assertEquals(2, local.seenVersion)
        assertEquals(1, db.news().progress("MORNING:2026-11-16")!!.position)
        // The pushed state already carries the web's choices, so the web shows them once the actions are gone.
        val pushed = server.data("news_state", "e1")
        assertTrue(pushed.getBoolean("bookmarked"))
        assertEquals(2, pushed.getInt("seenVersion"))
        assertEquals(now - 5_000, pushed.getJSONObject("fieldTimes").getLong("bookmarked"))
        assertEquals(1, server.data("news_progress", "MORNING:2026-11-16").getInt("position"))
        assertEquals(listOf(9L), server.acks)
        assertTrue(server.actions.isEmpty())
        assertTrue(server.calls.indexOf("/api/sync/news-actions") < server.calls.indexOf("/api/sync/batch"))
        assertTrue(server.calls.indexOf("/api/sync/news-actions/ack") > server.calls.lastIndexOf("/api/sync/batch"))
        assertEquals(9L, state!!.getLong("newsActionSeq"))

        assertEquals(listOf("e1", "saved-old"), server.ids("news_event"))
        val event = server.data("news_event", "e1")
        assertEquals("Roads closed", event.getJSONObject("summary").getJSONArray("quick").getString(0))
        assertEquals("Public safety", event.getJSONObject("scoreDetail").getJSONArray("reasons").getString(0))
        assertEquals(72, event.getInt("score"))
        assertTrue(event.getBoolean("protectedPublic"))
        assertEquals("east_africa", event.getJSONArray("topics").getString(1))
        assertTrue(server.data("news_event", "saved-old").isNull("summary"))
        val article = server.data("news_article", "a1")
        assertEquals("Daily News", article.getString("publisher"))
        assertEquals("https://img.example/1.jpg", article.getJSONArray("media").getJSONObject(0).getString("url"))
        assertEquals("a1", server.data("news_version", "e1:2").getJSONArray("evidence").getString(0))
        assertEquals("TOP", server.data("news_briefing", "MORNING:2026-11-16").getJSONArray("items").getJSONObject(0).getString("section"))
        assertEquals("Daily News", server.data("news_source", "daily").getString("name"))
        for (private in listOf("etag-private", "cursor-private", "not for the web", "Full excerpt text", "summaryJson", "featuresJson", "signature"))
            assertFalse(private, server.rows.toString().contains(private))

        // Nothing new: no ack and nothing resent.
        val batches = server.batches
        assertEquals(WebSync.Counts(0, 0), sync(server))
        assertEquals(batches, server.batches)
        assertEquals(listOf(9L), server.acks)
        // A later web read moves the phone on and is acknowledged.
        server.actions += webAction(10, now + 1_000, "readLater", true)
        sync(server)
        assertTrue(db.news().state("e1")!!.readLater)
        assertEquals(listOf(9L, 10L), server.acks)
    }

    @Test fun `a server without the news actions route still syncs`() = runBlocking {
        seed()
        news()
        val server = FakeServer().apply { newsSupported = false }
        sync(server)
        assertEquals(listOf("chat", "sms"), server.ids("event"))
        assertEquals(listOf("e1", "saved-old"), server.ids("news_event"))
        assertEquals(1, server.finished)
        assertEquals(emptyList<Long>(), server.acks)
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
