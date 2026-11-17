package com.hellfire.ass.security

import com.hellfire.ass.media.MediaItem
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * METADATA_ONLY: the row has no original on this phone (for example a synced record whose bytes were never downloaded).
 * Its row is backed up and restored; there is no original to include, so it is not a blocked original.
 */
enum class BackupAction { INCLUDE, DEDUPE, OMITTED, BLOCKED, METADATA_ONLY }

data class PlannedItem(
    val id: String,
    val contentHash: String,
    val size: Long,
    val action: BackupAction,
    val reason: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("contentHash", contentHash)
        .put("size", size)
        .put("action", action.name)
        .put("reason", reason ?: JSONObject.NULL)

    companion object {
        fun fromJson(row: JSONObject) = PlannedItem(
            row.getString("id"),
            row.optString("contentHash"),
            row.getLong("size"),
            BackupAction.valueOf(row.getString("action")),
            if (row.isNull("reason")) null else row.optString("reason").ifBlank { null },
        )
    }
}

data class BackupPlan(val kind: String, val items: List<PlannedItem>) {
    val includedHashes: List<String> get() = items.filter { it.action == BackupAction.INCLUDE }.map { it.contentHash }.distinct()
    val blocked: List<PlannedItem> get() = items.filter { it.action == BackupAction.BLOCKED }
    val metadataOnly: List<PlannedItem> get() = items.filter { it.action == BackupAction.METADATA_ONLY }
    fun bytesIncluded(): Long = items.filter { it.action == BackupAction.INCLUDE }.distinctBy { it.contentHash }
        .fold(0L) { total, item -> Math.addExact(total, item.size) }
}

data class BackupOutcome(
    val id: String,
    val kind: String,
    val included: Int,
    val omitted: Int,
    val blocked: List<PlannedItem>,
    val verified: Boolean,
    /** Set by [PortableBackup.exportForUpload]: nothing is recorded as backed up until the caller confirms the upload. */
    val pending: PendingBackup? = null,
)

/** An archive written to local staging whose destination has not confirmed it yet. */
class PendingBackup internal constructor(
    internal val id: String,
    internal val plan: BackupPlan,
    internal val verified: Set<String>,
    internal val previous: Set<String>,
    internal val baseId: String?,
    internal val archiveHash: String,
)

/** Result of a restore. [missing] lists rows whose original was not in the archive (blocked or metadata-only). */
data class RestoreReport(
    val restoredOriginals: Int,
    val missing: List<String>,
    val kinds: Map<String, Int> = emptyMap(),
    val collections: List<Pair<String, String>> = emptyList(),
    val items: List<Pair<String, String>> = emptyList(),
)

class BackupBlocked(val blocked: List<PlannedItem>) : Exception(
    "Backup blocked: " + blocked.joinToString("; ") { "${it.id}: ${it.reason ?: "not included"}" }
)

/** Which managed originals go into this archive. Unchanged hashes are omitted, never silently dropped. */
object BackupPlanner {
    const val DEFAULT_KEEP = 7

    /** Staging holds an encrypted copy plus ZIP overhead. Plan against the bytes that can actually be staged. */
    fun budget(freeBytes: Long): Long {
        require(freeBytes >= 0)
        return freeBytes * 10 / 22
    }

    fun plan(items: List<MediaItem>, verifiedHashes: Set<String>, freeBytes: Long, incremental: Boolean,
        hasOriginal: (MediaItem) -> Boolean = { true }): BackupPlan {
        val planned = mutableListOf<PlannedItem>()
        val included = linkedSetOf<String>()
        var remaining = budget(freeBytes)
        for (item in items.sortedWith(compareByDescending<MediaItem> { it.size }.thenBy { it.id })) {
            if (item.storage != "MANAGED") continue
            require(item.state != "DELETING") { "Stop deletion before backup" }
            val hash = item.contentHash
            when {
                !hasOriginal(item) || (hash.isNullOrBlank() && item.size <= 0) ->
                    planned += PlannedItem(item.id, hash.orEmpty(), item.size, BackupAction.METADATA_ONLY, "no original stored on this phone")
                hash.isNullOrBlank() || !hash.matches(Regex("[0-9a-f]{64}")) || item.size <= 0 ->
                    planned += PlannedItem(item.id, hash.orEmpty(), item.size, BackupAction.BLOCKED, "missing content hash or empty original")
                incremental && hash in verifiedHashes ->
                    planned += PlannedItem(item.id, hash, item.size, BackupAction.OMITTED, "already verified in a retained backup")
                hash in included ->
                    planned += PlannedItem(item.id, hash, item.size, BackupAction.DEDUPE, "same content hash already included")
                item.size > remaining ->
                    planned += PlannedItem(item.id, hash, item.size, BackupAction.BLOCKED, "destination storage")
                else -> {
                    included += hash
                    remaining -= item.size
                    planned += PlannedItem(item.id, hash, item.size, BackupAction.INCLUDE)
                }
            }
        }
        return BackupPlan(if (incremental) "INCREMENTAL" else "FULL", planned)
    }

    /** Manifest ids to delete so only [keep] newest remain. 0 keeps all. */
    fun retain(manifests: List<Pair<String, Long>>, keep: Int, parents: Map<String, String?> = emptyMap()): List<String> {
        require(keep >= 0)
        if (keep == 0) return emptyList()
        val ordered = manifests.sortedByDescending { it.second }
        val retained = ordered.take(keep).map { it.first }.toMutableSet()
        for (id in retained.toList()) {
            var parent = parents[id]
            while (parent != null && retained.add(parent)) parent = parents[parent]
        }
        return ordered.drop(keep).map { it.first }.filterNot { it in retained }
    }

    /**
     * BACKED_UP only after this run re-read the bytes, or the item was omitted because a prior run already verified that hash.
     * A hash that failed verification is not marked.
     */
    fun backedUpIds(plan: BackupPlan, verifiedThisRun: Set<String>, previouslyVerified: Set<String>): Set<String> = plan.items.mapNotNull { item ->
        val ok = when (item.action) {
            BackupAction.INCLUDE, BackupAction.DEDUPE -> item.contentHash in verifiedThisRun
            BackupAction.OMITTED -> item.contentHash in previouslyVerified
            BackupAction.BLOCKED, BackupAction.METADATA_ONLY -> false
        }
        item.id.takeIf { ok }
    }.toSet()

    fun recoveryMetadata(): JSONObject = JSONObject()
        .put("scheme", "HFABACK2")
        .put("kdf", "PBKDF2WithHmacSHA256")
        .put("iterations", BackupCrypto.ITERATIONS)
        .put("saltBytes", 16)
        .put("keyStored", false)

    /** Allowlisted non-secret preferences. Passphrases, tokens and account secrets are never names here. */
    val settings = listOf(
        "ui" to setOf("language"),
        "media" to setOf("charging", "language"),
        "capture" to setOf("nightly_review", "gap_alerts", "brief_day", "overdue_minutes"),
        "model-catalog" to setOf("installed-LANGUAGE", "installed-SPEECH", "installed-EMBEDDING"),
    )
}

object BackupStream {
    const val BUFFER = 64 * 1024

    /** Copy [input] in 64 KiB chunks, hashing as it goes. Never buffers the whole stream. */
    fun copy(input: InputStream, output: OutputStream, maximum: Long, active: () -> Unit = {}): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER)
        var count = 0L
        try {
            while (true) {
                active()
                val n = input.read(buffer)
                if (n < 0) break
                require(n > 0)
                count += n
                require(count <= maximum) { "Backup entry exceeds its size" }
                digest.update(buffer, 0, n)
                output.write(buffer, 0, n)
            }
        } finally { buffer.fill(0) }
        return digest.digest().joinToString("") { "%02x".format(it) } to count
    }
}
