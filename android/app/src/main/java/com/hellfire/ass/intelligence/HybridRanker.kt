package com.hellfire.ass.intelligence

data class RankHit(val key: String, val itemId: String, val startMs: Long?, val score: Double, val sources: List<String>)

object HybridRanker {
    private val weight = mapOf("FTS" to 1.0, "TIMESTAMP" to 1.2, "SEMANTIC" to 0.8, "CATEGORY" to 0.6, "TAG" to 0.5, "DATE" to 0.15)

    fun rank(hits: List<Triple<String, String, Double>>): List<RankHit> {
        val grouped = linkedMapOf<String, MutableList<Pair<String, Double>>>()
        hits.forEach { (key, source, raw) -> grouped.getOrPut(key) { mutableListOf() } += source to raw.coerceIn(0.0, 1.0) }
        return grouped.map { (key, parts) ->
            val score = parts.sumOf { (source, raw) -> (weight[source] ?: 0.4) * raw }
            val itemId = key.substringBefore(':')
            val start = key.substringAfter(':').toLongOrNull()?.takeIf { it >= 0 }
            RankHit(key, itemId, start, score, parts.map { it.first }.distinct())
        }.sortedWith(compareByDescending<RankHit> { it.score }.thenBy { it.key })
    }
}
