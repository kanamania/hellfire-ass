package com.hellfire.ass.intelligence

import com.hellfire.ass.data.SearchText
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.library.LibrarySearch
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
        LibrarySearch(app.database.library()).searchWithin(itemId, query)

    suspend fun search(constraints: SearchConstraints, limit: Int = 40): List<SearchHit> = withContext(Dispatchers.IO) {
        val dao = app.database.library()
        val queryText = listOf(constraints.text, constraints.categories.joinToString(" ")).filter { it.isNotBlank() }.joinToString(" ")
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
        constraints.categories.forEach { word ->
            categories.filter { it.name.contains(word, ignoreCase = true) }.forEach { category ->
                dao.inCategory(category.id, limit, 0).forEach { item ->
                    raw += Triple("${item.id}:-1", "CATEGORY", 1.0)
                    snippets.putIfAbsent("${item.id}:-1", category.name)
                }
            }
            dao.withTag(word.lowercase(), limit).forEach { item ->
                raw += Triple("${item.id}:-1", "TAG", 1.0)
            }
        }
        semantic(queryText, raw)
        val ranked = HybridRanker.rank(raw).take(limit * 2)
        val items = dao.items(ranked.map { it.itemId }.distinct()).associateBy { it.id }
        ranked.mapNotNull { hit ->
            val item = items[hit.itemId]
            val inbox = item == null && snippets[hit.key] != null && hit.startMs == null
            if (item == null && !inbox) return@mapNotNull null
            val at = item?.recordedAt ?: item?.importedAt
            if (constraints.fromMs != null && (at == null || at < constraints.fromMs)) return@mapNotNull null
            if (constraints.toMs != null && (at == null || at >= constraints.toMs)) return@mapNotNull null
            if (constraints.kinds.isNotEmpty() && item?.contentKind !in constraints.kinds.map { it.name }) return@mapNotNull null
            if (constraints.adultOnly && item != null) {
                val links = dao.itemCategories(item.id).first()
                val adult = links.any { link ->
                    link.category.name.equals("Adult", true) || categories.any { parent -> parent.id == link.category.parentId && parent.name == "Adult" }
                }
                if (!adult) return@mapNotNull null
            }
            val open = when {
                inbox -> "inbox"
                hit.startMs != null && item?.contentKind in setOf("VIDEO", "AUDIO") -> "player"
                else -> "detail"
            }
            SearchHit(hit.itemId, item?.name ?: "Saved item", snippets[hit.key].orEmpty(), item?.contentKind ?: "INBOX",
                hit.sources.joinToString("+"), hit.score, hit.startMs, at, null, open)
        }.take(limit)
    }

    private suspend fun semantic(query: String, raw: MutableList<Triple<String, String, Double>>) {
        if (query.isBlank() || !app.semantic.configured) return
        val model = app.semantic.embeddingModel() ?: return
        val vector = app.semantic.embedText(query) ?: return
        var after = ""
        var scanned = 0
        while (scanned < 1500) {
            val page = app.database.library().embeddings(model, after, 200)
            if (page.isEmpty()) break
            page.forEach { row ->
                val stored = runCatching { VectorMath.decode(row.vector) }.getOrNull() ?: return@forEach
                if (stored.size != vector.size) return@forEach
                val score = VectorMath.dot(vector, stored).toDouble()
                if (score >= 0.35) raw += Triple("${row.mediaId}:${row.startMs ?: -1}", "SEMANTIC", score.coerceIn(0.0, 1.0))
            }
            after = page.last().mediaId
            scanned += page.size
            if (page.size < 200) break
        }
    }
}
