package com.hellfire.ass.library

import com.hellfire.ass.media.MediaItem
import java.io.InputStream
import java.security.MessageDigest

/** What a scan can see. Partial access must not treat unseen files as deleted. */
enum class ScanVisibility { FULL, PARTIAL, DENIED }

enum class IndexChange { UNCHANGED, CHANGED, INSERT, MOVED, MISSING, REVOKED }

data class ScannedMedia(
    val uri: String,
    val stableKey: String,
    val name: String,
    val mime: String,
    val size: Long,
    val modifiedAt: Long,
    val generation: Long,
    val contentHash: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
)

data class IndexedMedia(
    val id: String,
    val uri: String,
    val stableKey: String?,
    val modifiedAt: Long?,
    val generation: Long?,
    val contentHash: String?,
    val size: Long,
    val availability: String,
    val storage: String,
)

data class IndexDecision(val change: IndexChange, val id: String?, val scanned: ScannedMedia?)

data class HashMove(val keepId: String, val dropId: String, val uri: String)

data class SimilarPair(val leftId: String, val rightId: String, val distance: Int)

object LibraryDiscovery {
    const val SIMILAR_MAX_DISTANCE = 10

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun diff(scanned: List<ScannedMedia>, indexed: List<IndexedMedia>, visibility: ScanVisibility): List<IndexDecision> {
        val seen = scanned.distinctBy { it.stableKey.ifBlank { it.uri } }
        val byKey = indexed.filter { !it.stableKey.isNullOrBlank() }.associateBy { it.stableKey!! }
        val byUri = indexed.associateBy { it.uri }
        val matched = mutableSetOf<String>()
        val pending = mutableListOf<ScannedMedia>()
        val out = mutableListOf<IndexDecision>()
        for (scan in seen) {
            val hit = byKey[scan.stableKey] ?: byUri[scan.uri]
            if (hit == null) {
                pending += scan
                continue
            }
            if (!matched.add(hit.id)) continue
            if (hit.storage == StorageMode.MANAGED.name) {
                out += IndexDecision(IndexChange.UNCHANGED, hit.id, scan)
                continue
            }
            val same = hit.modifiedAt == scan.modifiedAt && hit.generation == scan.generation && hit.size == scan.size &&
                hit.uri == scan.uri && hit.stableKey == scan.stableKey && hit.availability == Availability.AVAILABLE.name
            out += IndexDecision(if (same) IndexChange.UNCHANGED else IndexChange.CHANGED, hit.id, scan)
        }
        val unmatched = indexed.filter { it.id !in matched && it.storage != StorageMode.MANAGED.name }
        if (visibility == ScanVisibility.DENIED) {
            unmatched.forEach { out += IndexDecision(IndexChange.REVOKED, it.id, null) }
            return out
        }
        if (visibility == ScanVisibility.PARTIAL) {
            pending.forEach { out += IndexDecision(IndexChange.INSERT, null, it) }
            return out
        }
        val missing = unmatched.toMutableList()
        val inserts = mutableListOf<ScannedMedia>()
        for (scan in pending) {
            val hash = scan.contentHash
            val moved = if (hash.isNullOrBlank()) null else missing.firstOrNull { it.contentHash == hash }
            if (moved == null) inserts += scan
            else {
                missing.remove(moved)
                out += IndexDecision(IndexChange.MOVED, moved.id, scan)
            }
        }
        inserts.forEach { out += IndexDecision(IndexChange.INSERT, null, it) }
        missing.forEach { out += IndexDecision(IndexChange.MISSING, it.id, null) }
        return out
    }

    /** A missing external row and a newer available row with the same hash are the same file at a new URI. */
    fun hashMoves(items: List<MediaItem>): List<HashMove> {
        val external = items.filter { it.storage == StorageMode.EXTERNAL.name && !it.contentHash.isNullOrBlank() && !it.externalUri.isNullOrBlank() }
        return external.groupBy { it.contentHash }.values.flatMap { group ->
            val missing = group.filter { it.availability == Availability.MISSING.name }.minByOrNull { it.importedAt } ?: return@flatMap emptyList()
            group.filter { it.availability == Availability.AVAILABLE.name && it.id != missing.id }.map { drop ->
                HashMove(missing.id, drop.id, drop.externalUri!!)
            }
        }
    }

    fun dHash(argb: IntArray, width: Int, height: Int): Long {
        require(width > 0 && height > 0 && argb.size == width * height)
        val small = if (width == 9 && height == 8) argb else resample(argb, width, height, 9, 8)
        var bits = 0L
        var bit = 0
        for (y in 0 until 8) {
            val row = y * 9
            for (x in 0 until 8) {
                if (gray(small[row + x]) > gray(small[row + x + 1])) bits = bits or (1L shl bit)
                bit++
            }
        }
        return bits
    }

    fun hamming(left: Long, right: Long): Int = (left xor right).countOneBits()

    fun similar(hashes: List<Pair<String, Long>>, threshold: Int = SIMILAR_MAX_DISTANCE, limit: Int = 100): List<SimilarPair> {
        val out = mutableListOf<SimilarPair>()
        for (i in hashes.indices) {
            for (j in i + 1 until hashes.size) {
                val distance = hamming(hashes[i].second, hashes[j].second)
                if (distance <= threshold) out += SimilarPair(hashes[i].first, hashes[j].first, distance)
                if (out.size >= limit) return out.sortedBy { it.distance }
            }
        }
        return out.sortedBy { it.distance }
    }

    private fun resample(argb: IntArray, width: Int, height: Int, targetWidth: Int, targetHeight: Int): IntArray {
        val out = IntArray(targetWidth * targetHeight)
        for (y in 0 until targetHeight) {
            val sourceY = y * height / targetHeight
            for (x in 0 until targetWidth) {
                val sourceX = x * width / targetWidth
                out[y * targetWidth + x] = argb[sourceY * width + sourceX]
            }
        }
        return out
    }

    private fun gray(pixel: Int): Int {
        val red = (pixel shr 16) and 0xff
        val green = (pixel shr 8) and 0xff
        val blue = pixel and 0xff
        return (red * 30 + green * 59 + blue * 11) / 100
    }
}
