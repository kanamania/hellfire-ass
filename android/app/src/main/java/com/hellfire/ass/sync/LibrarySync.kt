package com.hellfire.ass.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.hellfire.ass.security.BackupCrypto
import com.hellfire.ass.security.PayloadCipher
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Client-side library sync. The server stores ciphertext only: record bodies, originals and the key check are
 * AES-GCM sealed with a passphrase-derived key, and record ids and blob dedupe keys are HMACs under a key derived
 * from the same passphrase, so the server never sees local ids, tag names or content hashes. Drawer and share
 * captures are in scope; MediaStore rows are not, unless the user has marked them for managed sync.
 */
object LibrarySync {
    const val CHUNK = 256 * 1024
    const val PASS = "library-sync-pass"
    const val DEVICE = "library-sync-device"
    const val CURSOR = "library-sync-cursor"
    const val BASE = "library-sync-base"
    const val PREFS = "library-sync"
    const val PERIODIC = "library-sync"
    const val NOW = "library-sync-now"
    const val PROTOCOL = 2
    /** The server refuses envelopes over 300 000 characters; stay under it with room for JSON framing. */
    const val MAX_ENVELOPE = 290_000
    private const val KEY_CHECK = "hellfire-library-key-check-v1"
    val mutex = Mutex()
    val TYPES = setOf(
        "media_item", "media_analysis", "media_segment", "media_bookmark", "media_category", "media_item_category",
        "media_item_tag", "media_collection", "collection_member", "inbox_item", "inbox_member", "drawer_message",
        "saved_location", "action_record", "transcript_segment",
    )

    data class Field(val value: String, val updatedAt: Long)
    data class Doc(val fields: Map<String, Field> = emptyMap(), val deleted: Boolean = false, val updatedAt: Long = 0)
    data class Merge(val doc: Doc, val conflicts: List<String>, val delete: Boolean)
    data class Clock(val hash: String, val updatedAt: Long)

    /** AES-GCM record/chunk cipher plus the HMAC key for server-visible ids. Both come from one PBKDF2 derivation. */
    class Keys(val cipher: PayloadCipher, private val idKey: ByteArray) {
        /** Opaque server id for a local entity: never reveals the local id, tag text or join keys. */
        fun remoteId(type: String, localId: String) = hmacHex(idKey, "record:$type:$localId")
        /** Server-side dedupe key for an original; the plaintext SHA-256 stays inside the encrypted record. */
        fun blobKey(contentHash: String) = hmacHex(idKey, "blob:$contentHash")
    }

    class Quota : Exception("Storage quota exceeded")

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun wifiOnly(context: Context) = prefs(context).getBoolean("wifi", false)
    /** The single, persistent pause switch. Local edits keep queueing while paused; nothing is sent until resumed. */
    fun isPaused(context: Context) = prefs(context).getBoolean("paused", false)
    fun setWifiOnly(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("wifi", enabled).apply()
        schedule(context)
    }
    fun setPaused(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("paused", value).commit()
        if (value) stop(context) else { schedule(context); now(context) }
    }
    private fun constraints(context: Context) = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly(context)) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true).build()
    fun schedule(context: Context) {
        if (isPaused(context)) return
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<LibrarySyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints(context))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES).build())
    }
    fun now(context: Context) {
        if (isPaused(context)) return
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<LibrarySyncWorker>().setConstraints(constraints(context))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES).build())
    }
    fun stop(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        WorkManager.getInstance(context).cancelUniqueWork(NOW)
    }
    fun status(context: Context, value: String) {
        prefs(context).edit().putString("status", value).apply()
    }
    fun readStatus(context: Context) = prefs(context).getString("status", "Off").orEmpty()

    fun includeMedia(syncEnabled: Boolean, storage: String) = syncEnabled && storage == "MANAGED"

    fun backoffMs(attempts: Int): Long {
        val shift = (attempts.coerceAtLeast(1) - 1).coerceAtMost(6)
        return (5L * 60_000) shl shift
    }

    fun chunkCount(size: Long): Int {
        require(size > 0)
        return ((size + CHUNK - 1) / CHUNK).toInt()
    }

    fun missing(received: Collection<Int>, count: Int): List<Int> = (0 until count).filter { it !in received }

    fun bytesDone(received: Collection<Int>, size: Long): Long {
        if (size <= 0) return 0
        val count = chunkCount(size)
        var done = 0L
        for (index in received) {
            if (index !in 0 until count) continue
            val start = index.toLong() * CHUNK
            done += minOf(CHUNK.toLong(), size - start)
        }
        return done
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))

    /** Hash of a row's synced values only (no clocks), used to notice local changes since the last sync. */
    fun fieldsHash(fields: Map<String, String>): String =
        sha256(fields.toSortedMap().entries.joinToString("\u0000") { (name, value) -> "$name\u0001$value" })

    /** PBKDF2-SHA256 (same parameters as backups and the website) → AES key; HKDF-Expand style HMAC → id key. */
    fun keys(passphrase: CharArray, salt: ByteArray): Keys {
        require(passphrase.size in 12..1024) { "Use a passphrase of at least 12 characters" }
        require(salt.size == 16)
        val spec = PBEKeySpec(passphrase, salt, BackupCrypto.ITERATIONS, 256)
        val master = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
        return try {
            Keys(PayloadCipher(SecretKeySpec(master, "AES")), hmac(master, "hellfire-library-ids-v1".toByteArray(Charsets.UTF_8) + byteArrayOf(1)))
        } finally { master.fill(0) }
    }

    fun cipher(passphrase: CharArray, salt: ByteArray): PayloadCipher = keys(passphrase, salt).cipher

    private fun hmac(key: ByteArray, message: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(message)

    private fun hmacHex(key: ByteArray, message: String) = hmac(key, message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Sealed known value the phone stores on the server; clients must open it before reading or writing. */
    fun keyCheck(cipher: PayloadCipher): String =
        Base64.getEncoder().encodeToString(cipher.encrypt(KEY_CHECK.toByteArray(Charsets.UTF_8), aad("keycheck", "v1")))

    fun checkKey(cipher: PayloadCipher, sealed: String): Boolean = runCatching {
        cipher.decrypt(Base64.getDecoder().decode(sealed), aad("keycheck", "v1")).toString(Charsets.UTF_8) == KEY_CHECK
    }.getOrDefault(false)

    fun stamp(current: Map<String, String>, base: Map<String, Clock>, rowUpdatedAt: Long): Map<String, Field> =
        current.mapValues { (name, value) ->
            val hash = sha256(value)
            val previous = base[name]
            if (previous != null && previous.hash == hash) Field(value, previous.updatedAt) else Field(value, rowUpdatedAt)
        }

    fun clocks(fields: Map<String, Field>): Map<String, Clock> =
        fields.mapValues { (_, field) -> Clock(sha256(field.value), field.updatedAt) }

    fun doc(fields: Map<String, Field>, deleted: Boolean = false, updatedAt: Long = fields.values.maxOfOrNull { it.updatedAt } ?: 0) =
        Doc(fields, deleted, updatedAt)

    /**
     * Three-way merge per field. A side "changed" a field when its value differs from the base value; clocks only
     * pick the winner. Conflicts are recorded only against a known base: with no common ancestor (new device,
     * restore, server reset) a differing value cannot be told apart from a stale copy, so the later clock wins quietly.
     */
    fun merge(base: Doc, local: Doc, remote: Doc): Merge {
        val known = base.fields.isNotEmpty() || base.deleted || base.updatedAt != 0L
        val names = (base.fields.keys + local.fields.keys + remote.fields.keys + setOf("*deleted")).sorted()
        val localFields = local.fields + ("*deleted" to Field(if (local.deleted) "1" else "0", local.updatedAt))
        val remoteFields = remote.fields + ("*deleted" to Field(if (remote.deleted) "1" else "0", remote.updatedAt))
        val baseFields = base.fields + ("*deleted" to Field(if (base.deleted) "1" else "0", base.updatedAt))
        val out = linkedMapOf<String, Field>()
        val conflicts = mutableListOf<String>()
        for (name in names) {
            val b = baseFields[name]
            val l = localFields[name]
            val r = remoteFields[name]
            val left = l?.value != b?.value
            val right = r?.value != b?.value
            when {
                left && right && l?.value != r?.value -> {
                    if (known) conflicts += name
                    val winner = when {
                        l == null -> r
                        r == null -> l
                        r.updatedAt > l.updatedAt -> r
                        else -> l
                    }
                    if (winner != null) out[name] = winner
                }
                // A field missing on one side means "not sent", never "erased".
                right && r != null -> out[name] = r
                left && l != null -> out[name] = l
                l != null -> out[name] = l
                r != null -> out[name] = r
            }
        }
        val deleted = out["*deleted"]?.value == "1"
        out.remove("*deleted")
        val at = out.values.maxOfOrNull { it.updatedAt } ?: maxOf(local.updatedAt, remote.updatedAt)
        return Merge(Doc(out, deleted, at), conflicts, deleted)
    }

    fun sameValues(a: Doc, b: Doc) = a.deleted == b.deleted && a.fields.mapValues { it.value.value } == b.fields.mapValues { it.value.value }

    fun canonical(doc: Doc): String {
        val body = doc.fields.toSortedMap().entries.joinToString(",") { (name, field) ->
            "${jsonString(name)}:{\"t\":${field.updatedAt},\"v\":${jsonString(field.value)}}"
        }
        return "{\"deleted\":${doc.deleted},\"fields\":{$body}}"
    }

    /** Sealed record body: the canonical document plus the local id, which only key holders can read. */
    fun body(id: String, doc: Doc): String = canonical(doc).dropLast(1) + ",\"id\":${jsonString(id)}}"

    /** Local id and document from a decrypted record body. */
    fun parseBody(json: String): Pair<String, Doc> = JSONObject(json).getString("id") to parse(json)

    fun parse(json: String): Doc {
        val obj = JSONObject(json)
        val fields = linkedMapOf<String, Field>()
        val raw = obj.optJSONObject("fields") ?: JSONObject()
        for (name in raw.keys()) {
            val field = raw.getJSONObject(name)
            fields[name] = Field(field.getString("v"), field.getLong("t"))
        }
        return Doc(fields, obj.optBoolean("deleted"), fields.values.maxOfOrNull { it.updatedAt } ?: 0)
    }

    fun aad(kind: String, id: String): ByteArray = "library-$kind-v1:$id".toByteArray(Charsets.UTF_8)

    fun open(cipher: PayloadCipher, envelope: ByteArray, aad: ByteArray): ByteArray = cipher.decrypt(envelope, aad)

    fun envelope(salt: ByteArray, sealed: ByteArray): JSONObject = JSONObject()
        .put("v", PROTOCOL)
        .put("salt", Base64.getEncoder().encodeToString(salt))
        .put("ct", Base64.getEncoder().encodeToString(sealed))

    interface BlobApi {
        /** [contentHash] is the server-visible dedupe key ([Keys.blobKey]), not the plaintext hash. */
        fun open(contentHash: String, size: Long, chunkSize: Int, chunkCount: Int): JSONObject
        fun putChunk(blobId: String, index: Int, sha256: String, bytes: ByteArray): JSONObject
    }

    /**
     * Uploads missing chunks of one original. Chunks are sealed with AAD bound to the plaintext [hash]; the server
     * only sees [blobKey]. [progress] runs after every stored chunk with the bytes the server now holds.
     */
    suspend fun upload(api: BlobApi, cipher: PayloadCipher, size: Long, hash: String, blobKey: String = hash,
        progress: suspend (blobId: String, bytesDone: Long) -> Unit = { _, _ -> }, slice: (Int) -> ByteArray): String {
        val count = chunkCount(size)
        val opened = api.open(blobKey, size, CHUNK, count)
        if (opened.optBoolean("complete") && opened.optString("contentHash") == blobKey) return opened.getString("blobId")
        val blobId = opened.getString("blobId")
        val received = opened.optJSONArray("received") ?: JSONArray()
        val have = (0 until received.length()).map { received.getInt(it) }.toMutableSet()
        progress(blobId, bytesDone(have, size))
        for (index in missing(have, count)) {
            val sealed = cipher.encrypt(slice(index), aad("chunk", "$hash:$index"))
            val digest = sha256(sealed)
            val reply = api.putChunk(blobId, index, digest, sealed)
            if (reply.optInt("status") == 413 || reply.optString("error") == "quota") throw Quota()
            require(reply.optString("sha256", digest) == digest) { "Chunk hash mismatch" }
            have += index
            progress(blobId, bytesDone(have, size))
        }
        return blobId
    }

    private fun jsonString(value: String): String = "\"${escape(value)}\""

    private fun escape(value: String): String = buildString {
        for (ch in value) when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
        }
    }
}
