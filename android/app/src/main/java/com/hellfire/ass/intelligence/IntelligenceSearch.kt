package com.hellfire.ass.intelligence

import com.hellfire.ass.data.SearchText
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.library.LibraryTimestampSearch
import com.hellfire.ass.library.TimestampHit
import com.hellfire.ass.memory.VectorMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

data class SearchHit(
    val itemId: String,
    val title: String,
    val snippet: String,
    val contentKind: String,
    val matchSource: String,
    val relevance: Double,
    val startMs: Long?,
    val at: Long?,
    val thumbnailPath: String?,
    val open: String,
)

class IntelligenceSearch(private val app: HellFireApp) {
    suspend fun searchWithin(itemId: String, query: String): List<TimestampHit> =
        LibraryTimestampSearch(app.database.library()).searchWithin(itemId, query)

    suspend fun search(constraints: SearchConstraints, limit: Int = 40): List<SearchHit> = withContext(Dispatchers.IO) {
        val dao = app.database.library()
        val queryText = constraints.text
        val fts = SearchText.query(queryText)
        val raw = mutableListOf<Triple<String, String, Double>>()
        val snippets = mutableMapOf<String, String>()
        if (fts != null) {
            runCatching { dao.search(fts, limit) }.getOrDefault(emptyList()).forEach { hit ->
                val key = "${hit.itemId}:-1"
                raw += Triple(key, "FTS", 1.0)
                snippets.putIfAbsent(key, hit.text.take(180))
            }
            runCatching { dao.timestampSearch(fts, null, limit) }.getOrDefault(emptyList()).forEach { hit ->
                val key = "${hit.mediaId}:${hit.startMs}"
                raw += Triple(key, "TIMESTAMP", 1.0)
                snippets.putIfAbsent(key, hit.text.take(180))
            }
        }
        val categories = dao.categories().first()
        val required = mutableListOf<Set<String>>()
        constraints.categories.filter { it != "adult" }.forEach { word ->
            val roots = categories.filter { it.name.contains(word, ignoreCase = true) }.map { it.id }
            val ids = dao.itemsInCategories(com.hellfire.ass.library.LibraryOrganize.subtree(categories, roots).toList(), 100_000).toSet() +
                dao.itemsTagged(word.lowercase(), 100_000)
            required += ids
            ids.forEach { raw += Triple("$it:-1", "CATEGORY", 1.0) }
        }
        if (queryText.isBlank()) {
            dao.inDateRange(
                constraints.kinds.ifEmpty { com.hellfire.ass.library.ContentKind.entries.toSet() }.map { it.name },
                constraints.fromMs ?: Long.MIN_VALUE, constraints.toMs ?: Long.MAX_VALUE, 100_000,
            ).forEach { item -> raw += Triple("${item.id}:-1", "DATE", 1.0) }
        }
        semantic(queryText, raw)
        val textMatches = if (queryText.isBlank()) null else raw.filter { it.second in setOf("FTS", "TIMESTAMP", "SEMANTIC") }.map { it.first.substringBeforeLast(':') }.toSet()
        val ranked = HybridRanker.rank(raw).filter { hit -> required.all { hit.itemId in it } && (textMatches == null || hit.itemId in textMatches) }
        val items = dao.items(ranked.map { it.itemId }.distinct()).associateBy { it.id }
        ranked.mapNotNull { hit ->
            val item = items[hit.itemId]
            val inbox = item == null && snippets[hit.key] != null && hit.startMs == null
            if (item == null && !inbox) return@mapNotNull null
            val at = item?.recordedAt ?: item?.modifiedAt ?: item?.importedAt
            if (constraints.fromMs != null && (at == null || at < constraints.fromMs)) return@mapNotNull null
            if (constraints.toMs != null && (at == null || at >= constraints.toMs)) return@mapNotNull null
            if (constraints.kinds.isNotEmpty() && item?.contentKind !in constraints.kinds.map { it.name }) return@mapNotNull null
            if (constraints.adultOnly && item != null) {
                val links = dao.itemCategories(item.id).first()
                val adult = links.any { link ->
                    link.linkState == "ASSIGNED" && link.category.id in com.hellfire.ass.library.LibraryOrganize.subtree(categories, categories.filter { it.name in setOf("Adult", "Adult content") }.map { it.id })
                }
                if (!adult) return@mapNotNull null
            }
            val open = when {
                inbox -> "inbox"
                hit.startMs != null && item?.contentKind in setOf("VIDEO", "AUDIO") -> "player"
                else -> "detail"
            }
            SearchHit(hit.itemId, item?.name ?: "Saved item", snippets[hit.key].orEmpty(), item?.contentKind ?: "INBOX",
                hit.sources.joinToString("+"), hit.score, hit.startMs, at, hit.startMs?.let { start -> dao.segmentsNow(item!!.id).firstOrNull { it.startMs == start }?.thumbnailPath }, open)
        }.take(limit)
    }

    private suspend fun semantic(query: String, raw: MutableList<Triple<String, String, Double>>) {
        if (query.isBlank() || !app.semantic.configured) return
        val model = app.semantic.embeddingModel() ?: return
        val vector = app.semantic.embedText(query) ?: return
        var after = ""
        var afterEmbedding = ""
        var scanned = 0
        while (scanned < 1500) {
            val page = app.database.library().embeddings(model, after, 200, afterEmbedding)
            if (page.isEmpty()) break
            page.forEach { row ->
                val stored = runCatching { VectorMath.decode(row.vector) }.getOrNull() ?: return@forEach
                if (stored.size != vector.size) return@forEach
                val score = VectorMath.dot(vector, stored).toDouble()
                if (score >= 0.35) raw += Triple("${row.mediaId}:${row.startMs ?: -1}", "SEMANTIC", score.coerceIn(0.0, 1.0))
            }
            after = page.last().mediaId
            afterEmbedding = page.last().id
            scanned += page.size
            if (page.size < 200) break
        }
    }
}
