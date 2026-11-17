package com.hellfire.ass.sync

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.work.*
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.data.EventDatabase
import com.hellfire.ass.finance.FinanceRepository
import com.hellfire.ass.news.NewsSync
import com.hellfire.ass.security.PayloadCipher
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/**
 * Read-only mirror of selected records on the owner's web server (web/ in this repository).
 * The phone stays the only place data is processed; it pushes the rows that changed since the
 * last pass and the ids that disappeared. Which rows the server holds is tracked on the phone as
 * id → hash per kind, tied to the server's dataset id so a wiped or new server gets a full copy.
 */
object WebSync {
    const val CONFIG = "web-sync"
    const val STATE = "web-sync-state"
    const val PERIODIC = "web-sync"
    const val NOW = "web-sync-now"
    val mutex = Mutex()
    private const val BATCH_ROWS = 300
    private const val BATCH_BYTES = 1_000_000
    private val zone = ZoneId.of("Africa/Dar_es_Salaam")

    /** A server call: method, path and optional JSON body to the parsed JSON reply. */
    fun interface Call { fun send(method: String, path: String, body: JSONObject?): JSONObject }
    class Rejected(val status: Int) : Exception("Web server answered HTTP $status")
    data class Counts(val upserted: Int, val deleted: Int, val bytes: Int = 0)

    private class Kind(val name: String, val sql: String, val at: String, val args: (Long) -> Array<Any> = { emptyArray() },
        val enrich: ((SupportSQLiteDatabase, PayloadCipher, JSONObject) -> Unit)? = null)

    // News: events with coverage in the last 90 days or saved by the owner, and what hangs off them. Public news only.
    private const val NEWS_EVENTS = "SELECT ne.id FROM news_event ne LEFT JOIN news_event_state ns ON ns.eventId = ne.id " +
        "WHERE ne.mergedInto IS NULL AND (ne.lastArticleAt >= ? OR ns.bookmarked = 1 OR ns.readLater = 1)"
    private val newsWindow: (Long) -> Array<Any> = { now -> arrayOf(now - 90 * 86_400_000L) }
    private val mediaFields = listOf("type", "url", "mime", "credit", "durationMs")

    /** Replaces the JSON text in [column] with its parsed value under [name] ([fallback] when missing or invalid). */
    private fun parsed(row: JSONObject, column: String, name: String, fallback: () -> Any) {
        val text = row.opt(column) as? String
        row.remove(column)
        row.put(name, text?.let { runCatching { JSONTokener(it).nextValue() }.getOrNull() }?.takeIf { it is JSONObject || it is JSONArray } ?: fallback())
    }
    private fun booleans(row: JSONObject, vararg names: String) = names.forEach { row.put(it, row.optLong(it) != 0L) }

    // Only the listed columns leave the phone. Rows tied to a security-sensitive source (codes, OTPs) never do.
    private val eventFields = setOf("title", "text", "big_text", "text_lines", "sub_text", "messages", "sender_hint", "conversation_title")
    private val kinds = listOf(
        Kind("account", "SELECT id, label, currency, createdAt FROM finance_account", "createdAt"),
        Kind("ledger", "SELECT id, accountId, kind, amount, category, description, occurredAt, createdAt, groupId, reversesId FROM ledger_entry", "occurredAt"),
        Kind("candidate", "SELECT c.id, c.provider, c.reference, c.kind, c.amount, c.fee, c.merchant, c.evidence, c.occurredAt, c.accountId, c.category " +
            "FROM finance_candidate c JOIN source_event e ON e.id = c.sourceId WHERE c.state = 'REVIEW' AND e.securitySensitive = 0", "occurredAt"),
        Kind("balance", "SELECT provider AS id, provider, amount, observedAt FROM provider_balance", "observedAt"),
        Kind("task", "SELECT t.id, t.title, t.dueAt, t.party, t.evidence, t.state, t.createdAt, t.updatedAt, t.completedAt, t.direction, t.amount, t.kind, t.repeatMonthly " +
            "FROM assistant_task t JOIN source_event e ON e.id = t.sourceId WHERE e.securitySensitive = 0", "dueAt"),
        Kind("message", "SELECT m.id, m.channel, m.threadKey, m.sender, m.recipient, m.subject, m.body, m.occurredAt, m.direction " +
            "FROM communication_message m JOIN source_event e ON e.id = m.sourceId WHERE e.securitySensitive = 0", "occurredAt"),
        Kind("draft", "SELECT id, channel, recipient, subject, body, createdAt, state, approvedAt, error FROM outbound_draft", "createdAt"),
        Kind("media", "SELECT id, name, mime, kind, size, importedAt, recordedAt, state, summary, summaryState, durationMs FROM media_item WHERE storage = 'MANAGED'", "importedAt"),
        Kind("transcript", "SELECT id, mediaId, startMs, endMs, speaker, text FROM transcript_segment WHERE mediaId IN (SELECT id FROM media_item WHERE storage = 'MANAGED')", "startMs"),
        Kind("memory", "SELECT m.id, m.subject, m.text, m.kind, m.confidence, m.createdAt, m.confirmedAt " +
            "FROM memory_item m JOIN source_event e ON e.id = m.sourceId WHERE m.state = 'ACTIVE' AND e.securitySensitive = 0", "createdAt"),
        // SMS, plus notifications the classifier saw as messages or payments. payloadHash makes content changes visible to
        // the local hash; it is replaced by the decrypted text fields only when the row is uploaded.
        Kind("event", "SELECT e.id, e.source, e.packageName, e.occurredAt, e.kind, e.contentRedacted, p.label, e.payloadHash " +
            "FROM source_event e LEFT JOIN event_processing p ON p.eventId = e.id WHERE e.source IN ('android_sms', 'android_notification') " +
            "AND e.contentAvailable = 1 AND e.securitySensitive = 0 AND (e.source = 'android_sms' OR p.label IN ('MESSAGE', 'FINANCE_CANDIDATE'))", "occurredAt") { db, cipher, row ->
            val blob = db.query("SELECT encryptedPayload FROM source_event WHERE id = ?", arrayOf(row.getString("id"))).use { if (it.moveToFirst()) it.getBlob(0) else null }
            val fields = blob?.let { JSONObject(cipher.decrypt(it).toString(Charsets.UTF_8)).optJSONObject("fields") } ?: JSONObject()
            row.remove("payloadHash")
            row.put("fields", JSONObject().apply { eventFields.forEach { name -> fields.optJSONArray(name)?.let { put(name, it) } } })
        },
        Kind("news_event", "SELECT id, headline, eventType, topics, place, status, version, score, level, protectedPublic, independentSources, articleCount, " +
            "verification, firstSeenAt, updatedAt, lastArticleAt, summaryJson, scoreJson FROM news_event WHERE id IN ($NEWS_EVENTS)", "lastArticleAt", newsWindow) { _, _, row ->
            booleans(row, "protectedPublic")
            row.put("topics", JSONArray(row.optString("topics").split(',').map(String::trim).filter(String::isNotEmpty)))
            parsed(row, "summaryJson", "summary") { JSONObject.NULL }
            // `score` stays the 0..100 total; the factor breakdown and reasons go under scoreDetail.
            parsed(row, "scoreJson", "scoreDetail") { JSONObject() }
        },
        Kind("news_version", "SELECT eventId || ':' || version AS id, eventId, version, at, classification, previously, whatsNew, whyItMatters, evidenceJson " +
            "FROM news_event_version WHERE eventId IN ($NEWS_EVENTS)", "at", newsWindow) { _, _, row -> parsed(row, "evidenceJson", "evidence") { JSONArray() } },
        Kind("news_article", "SELECT a.id, a.eventId, a.sourceId, s.name AS publisher, a.title, a.url, a.publishedAt, a.contentType, a.agency, a.language, a.mediaJson " +
            "FROM news_article a LEFT JOIN news_source s ON s.id = a.sourceId WHERE a.eventId IN ($NEWS_EVENTS)", "publishedAt", newsWindow) { _, _, row ->
            parsed(row, "mediaJson", "media") { JSONArray() }
            val media = row.optJSONArray("media") ?: JSONArray()
            row.put("media", JSONArray((0 until media.length()).mapNotNull { i ->
                media.optJSONObject(i)?.let { item -> JSONObject().apply { mediaFields.forEach { if (item.has(it)) put(it, item.get(it)) } } }
            }))
        },
        Kind("news_briefing", "SELECT id, slot, title, scheduledFor, generatedAt, windowStart, windowEnd, itemsJson FROM news_briefing WHERE generatedAt >= ?",
            "generatedAt", { now -> arrayOf(now - 60 * 86_400_000L) }) { _, _, row -> parsed(row, "itemsJson", "items") { JSONArray() } },
        Kind("news_state", "SELECT eventId AS id, eventId, deliveredVersion, seenVersion, readAt, bookmarked, readLater, hidden, followed, fieldTimes, updatedAt " +
            "FROM news_event_state", "updatedAt") { _, _, row ->
            booleans(row, "bookmarked", "readLater", "hidden", "followed")
            parsed(row, "fieldTimes", "fieldTimes") { JSONObject() }
        },
        Kind("news_progress", "SELECT briefingId AS id, briefingId, position, audioMs, updatedAt FROM news_progress", "updatedAt"),
        // Never the X token (kept in secrets), feed validators or connector cursors.
        Kind("news_source", "SELECT id, name, kind, category, state, reliability, lastSuccessAt, failures, lastError, updatedAt FROM news_source", "updatedAt"),
    )

    fun validate(url: String, token: String) {
        val uri = URI(url.trim())
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) { "Use an https:// server address" }
        require(token.length in 32..512 && token.none(Char::isWhitespace)) { "The sync token is 32–512 characters without spaces" }
    }

    private class Local(val hash: String, val at: Long, val row: JSONObject)

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) }

    private fun read(db: SupportSQLiteDatabase, kind: Kind, now: Long): Map<String, Local> {
        val rows = linkedMapOf<String, Local>()
        db.query(kind.sql, kind.args(now)).use { cursor ->
            while (cursor.moveToNext()) {
                val row = JSONObject()
                for (i in 0 until cursor.columnCount) row.put(cursor.getColumnName(i), when (cursor.getType(i)) {
                    android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                    android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                    android.database.Cursor.FIELD_TYPE_STRING -> cursor.getString(i)
                    else -> JSONObject.NULL
                })
                rows[row.getString("id")] = Local(hash(row.toString()), row.optLong(kind.at), row)
            }
        }
        return rows
    }

    /** This and last month's totals plus account balances, computed by the phone's own finance code. */
    private suspend fun summary(db: EventDatabase, now: Long): Map<String, Local> {
        val finances = FinanceRepository(db)
        val start = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), zone).withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS)
        val bounds = listOf(start.minusMonths(1), start, start.plusMonths(1)).map { it.toInstant().toEpochMilli() }
        val previous = finances.summary(bounds[0], bounds[1])
        val current = finances.summary(bounds[1], bounds[2])
        val balances = JSONArray()
        for (account in db.finances().allAccounts())
            balances.put(JSONObject().put("accountId", account.id).put("label", account.label).put("balance", db.finances().balance(account.id)))
        val row = JSONObject().put("id", "current").put("month", start.toLocalDate().toString().take(7)).put("income", current.income).put("expenses", current.expenses)
            .put("previousMonth", start.minusMonths(1).toLocalDate().toString().take(7)).put("previousIncome", previous.income).put("previousExpenses", previous.expenses)
            .put("balances", balances)
        return mapOf("current" to Local(hash(row.toString()), 0, row))
    }

    /**
     * One pass. [saved] is the stored state (null forces a full copy); [save] persists state after each kind,
     * so an interrupted pass resumes without resending finished kinds.
     */
    suspend fun run(db: EventDatabase, cipher: PayloadCipher, saved: JSONObject?, call: Call, save: (JSONObject) -> Unit, now: Long = System.currentTimeMillis()): Counts {
        val remote = call.send("GET", "/api/sync/state", null).getString("dataset")
        val previous = saved?.takeIf { it.optString("dataset") == remote && it.has("rows") }
        // Owner news choices made on the web go into Room first, so this pass pushes the merged state.
        var newsSeq = previous?.optLong("newsActionSeq") ?: 0L
        val actions = newsActions(call, newsSeq)
        if (!actions.isNullOrEmpty()) {
            NewsSync.apply(db.news(), actions)
            newsSeq = maxOf(newsSeq, actions.maxOf { it.seq })
        }
        val state = previous ?: JSONObject()
            .put("dataset", call.send("POST", "/api/sync/reset", JSONObject()).getString("dataset")).put("rows", JSONObject()).also(save)
        state.put("newsActionSeq", newsSeq)
        val dataset = state.getString("dataset")
        val held = state.getJSONObject("rows")
        val sql = db.openHelper.readableDatabase
        var upserted = 0
        var deleted = 0
        var bytes = 0
        for (kind in kinds.map { it.name } + "summary") {
            currentCoroutineContext().ensureActive()
            val spec = kinds.firstOrNull { it.name == kind }
            val local = if (spec == null) summary(db, now) else read(sql, spec, now)
            val server = held.optJSONObject(kind) ?: JSONObject().also { held.put(kind, it) }
            val changed = local.filter { server.optString(it.key) != it.value.hash }
            val gone = server.keys().asSequence().filter { it !in local }.toList()
            var upserts = JSONArray()
            var size = 0
            val pending = mutableListOf<Pair<String, String>>()
            fun flush(deletes: List<String> = emptyList()) {
                if (upserts.length() == 0 && deletes.isEmpty()) return
                call.send("POST", "/api/sync/batch", JSONObject().put("dataset", dataset).put("kind", kind).put("upserts", upserts).put("deletes", JSONArray(deletes)))
                pending.forEach { (id, hash) -> server.put(id, hash) }
                deletes.forEach { server.remove(it) }
                upserted += pending.size; deleted += deletes.size; bytes += size
                upserts = JSONArray(); size = 0; pending.clear()
            }
            for ((id, row) in changed) {
                val data = JSONObject(row.row.toString())
                spec?.enrich?.invoke(sql, cipher, data)
                val item = JSONObject().put("id", id).put("at", row.at).put("data", data)
                val bytes = item.toString().length
                if (upserts.length() == BATCH_ROWS || (size + bytes > BATCH_BYTES && upserts.length() > 0)) flush()
                upserts.put(item); size += bytes; pending += id to row.hash
            }
            flush()
            gone.chunked(500).forEach { flush(it) }
            save(state)
        }
        call.send("POST", "/api/sync/finish", JSONObject().put("dataset", dataset))
        if (actions != null && newsSeq > state.optLong("newsActionAcked")) {
            call.send("POST", "/api/sync/news-actions/ack", JSONObject().put("upTo", newsSeq))
            save(state.put("newsActionAcked", newsSeq))
        }
        return Counts(upserted, deleted, bytes)
    }

    /** Web actions after [after], oldest first; null when the server predates news (404). */
    private fun newsActions(call: Call, after: Long): List<NewsSync.Action>? {
        val all = mutableListOf<NewsSync.Action>()
        var cursor = after
        repeat(20) {
            val page = try { call.send("GET", "/api/sync/news-actions?after=$cursor", null) }
                catch (e: Rejected) { if (e.status == 404) return null else throw e }
            val list = page.optJSONArray("actions") ?: return all
            for (i in 0 until list.length()) all += NewsSync.parse(list.getJSONObject(i))
            if (list.length() < 500 || all.isEmpty()) return all
            cursor = all.last().seq
        }
        return all
    }

    /** JSON body, not gzip: the server parser does not advertise compression. */
    fun https(url: String, token: String) = Call { method, path, body ->
        val connection = URI(url.trim().trimEnd('/') + path).toURL().openConnection() as HttpsURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.requestMethod = method
        connection.setRequestProperty("Authorization", "Bearer $token")
        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            if (connection.responseCode !in 200..299) throw Rejected(connection.responseCode)
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(output.size() + read <= 1024 * 1024) { "Reply too large" }
                    output.write(buffer, 0, read)
                }
            }
            JSONObject(output.toString("UTF-8"))
        } finally { connection.disconnect() }
    }

    private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()
    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WebSyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES).build())
    }
    fun now(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<WebSyncWorker>().setConstraints(constraints()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES).build())
    }
    fun stop(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        WorkManager.getInstance(context).cancelUniqueWork(NOW)
    }

    fun status(context: Context, value: String, success: Boolean = false) {
        val edit = context.getSharedPreferences(CONFIG, Context.MODE_PRIVATE).edit().putString("status", value)
        if (success) edit.putLong("last-success", System.currentTimeMillis())
        edit.apply()
    }
    fun lastSuccess(context: Context): Long = context.getSharedPreferences(CONFIG, Context.MODE_PRIVATE).getLong("last-success", 0)
    fun readStatus(context: Context): String = context.getSharedPreferences(CONFIG, Context.MODE_PRIVATE).getString("status", "Off").orEmpty()

    /** Saves a new server and token; a different server starts from a full copy. */
    fun enable(app: HellFireApp, url: String, token: String) {
        validate(url, token)
        app.secrets.save(CONFIG, JSONObject().put("url", url.trim().trimEnd('/')).put("token", token).put("generation", UUID.randomUUID().toString()))
        schedule(app); now(app)
        status(app, "Waiting for the first sync")
    }

    /** Stops syncing. With [wipe], the server copy is deleted first; failure to reach the server keeps sync on. */
    suspend fun disable(app: HellFireApp, wipe: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val config = app.secrets.read(CONFIG)
            if (wipe && config != null) https(config.getString("url"), config.getString("token")).send("POST", "/api/sync/reset", JSONObject())
            stop(app)
            app.secrets.remove(CONFIG)
            if (wipe) app.secrets.remove(STATE)
            status(app, if (wipe) "Off · web copy deleted" else "Off · web copy kept as of the last sync")
        }
    }
}

class WebSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    /** Web access was turned off or pointed at another server during this pass. */
    private class Superseded : Exception()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        WebSync.mutex.withLock {
            val app = applicationContext as HellFireApp
            try {
                val config = app.secrets.read(WebSync.CONFIG) ?: return@withLock Result.success()
                val generation = config.getString("generation")
                WebSync.status(app, "Syncing")
                val counts = WebSync.run(app.database, app.cipher, app.secrets.read(WebSync.STATE),
                    WebSync.https(config.getString("url"), config.getString("token")),
                    save = { state -> if (app.secrets.read(WebSync.CONFIG)?.optString("generation") == generation) app.secrets.save(WebSync.STATE, state) else throw Superseded() })
                com.hellfire.ass.performance.PerfLog.record("web_sync_rows", counts.upserted.toLong() + counts.deleted)
                com.hellfire.ass.performance.PerfLog.record("web_sync_bytes", counts.bytes.toLong())
                WebSync.status(app, "Synced · ${counts.upserted} updated, ${counts.deleted} removed", success = true)
                Result.success()
            } catch (_: Superseded) {
                Result.success()
            } catch (e: CancellationException) {
                WebSync.status(app, "Sync interrupted; it will continue next time")
                throw e
            } catch (e: WebSync.Rejected) {
                if (e.status == 401) { WebSync.status(app, "The server refused the sync token. Check it in Web access."); Result.failure() }
                else { WebSync.status(app, "Server error (HTTP ${e.status}); will retry"); Result.retry() }
            } catch (_: Exception) {
                WebSync.status(app, "Could not reach the server; will retry")
                Result.retry()
            }
        }
    }
}
