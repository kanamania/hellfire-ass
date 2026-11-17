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
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Client-side library sync. The server stores ciphertext only. Search text is never sent unless the
 * owner has turned on the plaintext index. Drawer and share captures are in scope; MediaStore rows are
 * not, unless the user has marked them for managed sync.
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
    const val PROTOCOL = 1
    val mutex = Mutex()
    val TYPES = setOf(
        "media_item", "media_analysis", "media_segment", "media_bookmark", "media_category", "media_item_category",
        "media_item_tag", "media_collection", "collection_member", "inbox_item", "inbox_member", "drawer_message",
        "saved_location", "action_record", "transcript_segment",
    )
    private val drawerOrigins = setOf("DRAWER", "SHARE", "SYNC")

    data class Field(val value: String, val updatedAt: Long)
    data class Doc(val fields: Map<String, Field> = emptyMap(), val deleted: Boolean = false, val updatedAt: Long = 0)
    data class Merge(val doc: Doc, val conflicts: List<String>, val delete: Boolean)
    data class Clock(val hash: String, val updatedAt: Long)

    class Quota : Exception("Storage quota exceeded")

    fun wifiOnly(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("wifi", false)
    fun paused(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("paused", false)
    fun setWifiOnly(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("wifi", enabled).apply()
        schedule(context)
    }
    fun setPaused(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("paused", value).apply()
        if (value) stop(context) else { schedule(context); now(context) }
    }
    private fun constraints(context: Context) = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly(context)) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true).build()
    fun schedule(context: Context) {
        if (paused(context)) return
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<LibrarySyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints(context))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES).build())
    }
    fun now(context: Context) {
        if (paused(context)) return
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<LibrarySyncWorker>().setConstraints(constraints(context))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES).build())
    }
    fun stop(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        WorkManager.getInstance(context).cancelUniqueWork(NOW)
    }
    fun status(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("status", value).apply()
    }
    fun readStatus(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("status", "Off").orEmpty()

    fun includeMedia(syncEnabled: Boolean, storage: String) = syncEnabled && storage == "MANAGED"

    fun includeInbox(origin: String, syncState: String, lifecycle: String) =
        origin in drawerOrigins && syncState != "LOCAL_ONLY" && lifecycle != "DELETED"

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

    fun merge(base: Doc, local: Doc, remote: Doc): Merge {
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
            val left = l != b
            val right = r != b
            when {
                left && right && l != r -> {
                    conflicts += name
                    val winner = when {
                        l == null -> r
                        r == null -> l
                        r.updatedAt > l.updatedAt -> r
                        else -> l
                    }
                    if (winner != null) out[name] = winner
                }
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

    fun canonical(doc: Doc): String {
        val body = doc.fields.toSortedMap().entries.joinToString(",") { (name, field) ->
            "${jsonString(name)}:{\"t\":${field.updatedAt},\"v\":${jsonString(field.value)}}"
        }
        return "{\"deleted\":${doc.deleted},\"fields\":{$body}}"
    }

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

    fun seal(cipher: PayloadCipher, plain: ByteArray, aad: ByteArray, iv: ByteArray? = null): ByteArray {
        if (iv == null) return cipher.encrypt(plain, aad)
        require(iv.size == 12)
        val aes = Cipher.getInstance("AES/GCM/NoPadding")
        aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes(cipher), "AES"), GCMParameterSpec(128, iv))
        if (aad.isNotEmpty()) aes.updateAAD(aad)
        return byteArrayOf(1) + iv + aes.doFinal(plain)
    }

    fun open(cipher: PayloadCipher, envelope: ByteArray, aad: ByteArray): ByteArray = cipher.decrypt(envelope, aad)

    fun envelope(salt: ByteArray, sealed: ByteArray): JSONObject = JSONObject()
        .put("v", PROTOCOL)
        .put("salt", Base64.getEncoder().encodeToString(salt))
        .put("ct", Base64.getEncoder().encodeToString(sealed))

    fun cipher(passphrase: CharArray, salt: ByteArray): PayloadCipher = BackupCrypto.derive(passphrase, salt)

    interface BlobApi {
        fun open(contentHash: String, size: Long, chunkSize: Int, chunkCount: Int): JSONObject
        fun putChunk(blobId: String, index: Int, sha256: String, bytes: ByteArray): JSONObject
    }

    fun upload(api: BlobApi, cipher: PayloadCipher, size: Long, hash: String, slice: (Int) -> ByteArray): String {
        val count = chunkCount(size)
        val opened = api.open(hash, size, CHUNK, count)
        if (opened.optBoolean("complete") && opened.optString("contentHash") == hash) return opened.getString("blobId")
        val blobId = opened.getString("blobId")
        val received = opened.optJSONArray("received") ?: JSONArray()
        val have = (0 until received.length()).map { received.getInt(it) }.toSet()
        for (index in missing(have, count)) {
            val sealed = cipher.encrypt(slice(index), aad("chunk", "$hash:$index"))
            val digest = sha256(sealed)
            val reply = api.putChunk(blobId, index, digest, sealed)
            if (reply.optInt("status") == 413 || reply.optString("error") == "quota") throw Quota()
            require(reply.optString("sha256", digest) == digest) { "Chunk hash mismatch" }
        }
        return blobId
    }

    private fun jsonString(value: String): String = JSONObject().put("v", value).getString("v").let { "\"${escape(value)}\"" }

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

    private fun keyBytes(cipher: PayloadCipher): ByteArray {
        val field = PayloadCipher::class.java.getDeclaredField("key")
        field.isAccessible = true
        val key = field.get(cipher) as javax.crypto.SecretKey
        return key.encoded ?: error("Sync key is not exportable")
    }
}
