package com.hellfire.ass.news

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.Base64
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal object NewsFactExtractor {
    private val speculative = Regex("\\b(may|might|could|expected to|reportedly|alleged|allegedly|possible|unconfirmed|huenda|inadaiwa|inatarajiwa|labda)\\b", RegexOption.IGNORE_CASE)
    private val correction = Regex("\\b(correction|corrects|corrected|rather than|instead of|not \\d+|was wrong|siyo|sio|imerekebishwa|rekebisho)\\b", RegexOption.IGNORE_CASE)
    private val death = Regex("(?i)(?:(\\d{1,7})\\s+(?:people |persons )?(?:died|dead|killed|deaths|fatalities)|(?:watu|vifo)\\s+(\\d{1,7})|(?:wamefariki|wamekufa|wameuawa|wamepoteza maisha)\\s+watu\\s+(\\d{1,7})|(\\d{1,7})\\s+wame(?:fariki|kufa|uawa))")
    private val injured = Regex("(?i)(?:(\\d{1,7})\\s+(?:people |persons )?(?:injured|wounded|hurt)|(?:waliojeruhiwa|wamejeruhiwa)\\s+(\\d{1,7})|(\\d{1,7})\\s+wamejeruhiwa)")
    private val percent = Regex("(\\d{1,3}(?:\\.\\d+)?)\\s*%")
    private val approve = Regex("(?i)\\b(approved|approval|passed|ratified|endorsed|ameidhinisha|imeidhinishwa|wameidhinisha|imepitishwa|wamepitisha)\\b")
    private val reject = Regex("(?i)\\b(rejected|voted down|amekataa|imekataliwa|wamekataa)\\b")
    private val resolved = Regex("(?i)\\b(has ended|have ended|called off|lifted|resolved|rescued all|imeisha|imekomeshwa|imesitishwa|wameokoka wote)\\b")

    fun extract(title: String, text: String, articleId: String): List<Fact> {
        val body = if (text.isBlank()) title else "$title. $text"
        val sentences = NewsTextCore.sentences(body).ifEmpty { listOf(body) }
        val out = ArrayList<Fact>()
        for (sentence in sentences) {
            val spec = speculative.containsMatchIn(sentence)
            val corr = correction.containsMatchIn(sentence)
            val low = NewsLexicon.lower(sentence)
            fun add(key: String, value: String, kind: String) {
                if (value.isBlank()) return
                out += Fact(key, value, kind, spec, corr, sentence.trim(), setOf(articleId))
            }
            death.find(sentence)?.let { m -> add("deaths", (m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: ""), "FIGURE") }
            injured.find(sentence)?.let { m -> add("injured", (m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: ""), "FIGURE") }
            if (Regex("(?i)fuel|petrol|diesel|bei ya mafuta").containsMatchIn(sentence)) {
                percent.find(sentence)?.let { add("price:fuel", it.groupValues[1] + "%", "FIGURE") }
                    ?: Regex("(?i)(?:tsh|tzs)\\s*([\\d,]+)").find(sentence)?.let { add("price:fuel", it.groupValues[1].replace(",", ""), "FIGURE") }
            }
            if (approve.containsMatchIn(sentence)) add("decision:approve", "approved", "DECISION")
            if (reject.containsMatchIn(sentence)) add("decision:reject", "rejected", "DECISION")
            if (resolved.containsMatchIn(sentence)) add("status", "resolved", "STATUS")
            if (Regex("(?i)\\b(ongoing|continues|still searching|bado inaendelea|inaendelea)\\b").containsMatchIn(low)) add("status", "ongoing", "STATUS")
        }
        return out.distinctBy { "${it.key}|${it.value}|${it.speculative}|${it.correction}" }
    }
}

internal object NewsJson {
    fun features(value: ArticleFeatures): String = JSONObject()
        .put("language", value.language)
        .put("concepts", jsonMap(value.concepts))
        .put("entities", JSONArray(value.entities.toList()))
        .put("places", JSONArray(value.places.toList()))
        .put("eventType", value.eventType)
        .put("topics", JSONArray(value.topics.toList()))
        .put("facts", JSONArray(value.facts.map(::fact)))
        .put("contentType", value.contentType)
        .put("agency", value.agency ?: JSONObject.NULL)
        .put("fingerprint", value.fingerprint)
        .put("urgent", value.urgent)
        .put("publicSafety", value.publicSafety)
        .toString()

    fun features(json: String): ArticleFeatures {
        val o = JSONObject(json)
        return ArticleFeatures(
            o.getString("language"), doubles(o.getJSONObject("concepts")), strings(o.getJSONArray("entities")),
            strings(o.getJSONArray("places")), o.getString("eventType"), strings(o.getJSONArray("topics")),
            facts(o.getJSONArray("facts")), o.getString("contentType"), o.optString("agency").ifBlank { null },
            o.getLong("fingerprint"), o.optBoolean("urgent"), o.optBoolean("publicSafety"),
        )
    }

    fun centroid(value: EventCentroid): String = JSONObject()
        .put("concepts", jsonMap(value.concepts))
        .put("entities", JSONObject().apply { value.entities.forEach { (k, v) -> put(k, v) } })
        .put("places", JSONObject().apply { value.places.forEach { (k, v) -> put(k, v) } })
        .put("eventTypes", JSONObject().apply { value.eventTypes.forEach { (k, v) -> put(k, v) } })
        .put("firstAt", value.firstAt).put("lastAt", value.lastAt)
        .put("articleIds", JSONArray(value.articleIds.toList()))
        .put("fingerprints", JSONArray(value.fingerprints.map { it.toString() }))
        .put("vector", value.vector?.let { JSONArray(it.toList()) } ?: JSONObject.NULL)
        .toString()

    fun centroid(eventId: String, json: String): EventCentroid {
        val o = JSONObject(json)
        return EventCentroid(eventId, doubles(o.getJSONObject("concepts")), ints(o.getJSONObject("entities")), ints(o.getJSONObject("places")),
            ints(o.getJSONObject("eventTypes")), o.getLong("firstAt"), o.getLong("lastAt"), strings(o.getJSONArray("articleIds")),
            (0 until o.getJSONArray("fingerprints").length()).map { o.getJSONArray("fingerprints").getString(it).toLong() },
            o.optJSONArray("vector")?.let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } })
    }

    fun snapshot(value: EventSnapshot): String = JSONObject()
        .put("facts", JSONArray(value.facts.map(::fact)))
        .put("groups", JSONObject().apply { value.groups.forEach { (k, v) -> put(k, JSONArray(v.toList())) } })
        .put("independentSources", value.independentSources)
        .put("verification", value.verification)
        .put("conflicts", JSONArray(value.conflicts))
        .put("resolved", value.resolved)
        .toString()

    fun snapshot(json: String): EventSnapshot {
        val o = JSONObject(json)
        val groups = o.getJSONObject("groups")
        return EventSnapshot(facts(o.getJSONArray("facts")), groups.keys().asSequence().associateWith { strings(groups.getJSONArray(it)) },
            o.getInt("independentSources"), o.getString("verification"), strings(o.getJSONArray("conflicts")).toList(), o.optBoolean("resolved"))
    }

    fun score(value: NewsScore): String = JSONObject()
        .put("total", value.total).put("level", value.level)
        .put("factors", JSONObject().apply { value.factors.forEach { (k, v) -> put(k, v) } })
        .put("reasons", JSONArray(value.reasons))
        .put("protectedPublic", value.protectedPublic)
        .toString()

    fun score(json: String): NewsScore {
        val o = JSONObject(json)
        val factors = o.getJSONObject("factors")
        return NewsScore(o.getInt("total"), o.getString("level"), factors.keys().asSequence().associateWith { factors.getInt(it) },
            strings(o.getJSONArray("reasons")).toList(), o.optBoolean("protectedPublic"))
    }

    fun summary(value: StorySummary): String = JSONObject()
        .put("headline", value.headline)
        .put("quick", JSONArray(value.quick))
        .put("details", JSONArray(value.details.map { JSONObject().put("text", it.text).put("sources", JSONArray(it.sources)) }))
        .put("whyItMatters", value.whyItMatters).put("localImpact", value.localImpact).put("background", value.background)
        .put("whatsNext", JSONArray(value.whatsNext)).put("conflicts", JSONArray(value.conflicts))
        .put("labels", JSONArray(value.labels.toList()))
        .put("sources", JSONArray(value.sources.map {
            JSONObject().put("articleId", it.articleId).put("publisher", it.publisher).put("title", it.title).put("url", it.url)
                .put("publishedAt", it.publishedAt).put("contentType", it.contentType).put("agency", it.agency ?: JSONObject.NULL)
        }))
        .put("language", value.language).put("generatedBy", value.generatedBy)
        .toString()

    fun summary(json: String): StorySummary {
        val o = JSONObject(json)
        val details = o.getJSONArray("details")
        val sources = o.getJSONArray("sources")
        return StorySummary(
            o.getString("headline"), strings(o.getJSONArray("quick")).toList(),
            (0 until details.length()).map { i -> val d = details.getJSONObject(i); SourcedLine(d.getString("text"), strings(d.getJSONArray("sources")).toList()) },
            o.optString("whyItMatters"), o.optString("localImpact"), o.optString("background"),
            strings(o.getJSONArray("whatsNext")).toList(), strings(o.getJSONArray("conflicts")).toList(),
            strings(o.getJSONArray("labels")),
            (0 until sources.length()).map { i ->
                val s = sources.getJSONObject(i)
                SourceRef(s.getString("articleId"), s.getString("publisher"), s.getString("title"), s.getString("url"), s.getLong("publishedAt"), s.getString("contentType"), s.optString("agency").ifBlank { null })
            },
            o.getString("language"), o.getString("generatedBy"),
        )
    }

    private fun fact(f: Fact) = JSONObject().put("key", f.key).put("value", f.value).put("kind", f.kind)
        .put("speculative", f.speculative).put("correction", f.correction).put("sentence", f.sentence).put("articleIds", JSONArray(f.articleIds.toList()))

    private fun facts(array: JSONArray) = (0 until array.length()).map { i ->
        val o = array.getJSONObject(i)
        Fact(o.getString("key"), o.getString("value"), o.getString("kind"), o.optBoolean("speculative"), o.optBoolean("correction"), o.optString("sentence"), strings(o.optJSONArray("articleIds") ?: JSONArray()))
    }

    private fun jsonMap(map: Map<String, Double>) = JSONObject().apply { map.forEach { (k, v) -> put(k, v) } }
    private fun doubles(o: JSONObject) = o.keys().asSequence().associateWith { o.getDouble(it) }
    private fun ints(o: JSONObject) = o.keys().asSequence().associateWith { o.getInt(it) }
    private fun strings(array: JSONArray) = (0 until array.length()).map { array.getString(it) }.toSet()
}

internal object NewsClusterLogic {
    private val zone = ZoneId.of("Africa/Dar_es_Salaam")
    const val THRESHOLD = 0.42

    fun similarity(features: ArticleFeatures, publishedAt: Long, vector: FloatArray?, centroid: EventCentroid): Double {
        if (centroid.fingerprints.any { NewsText.hamming(it, features.fingerprint) <= 3 }) return 0.96
        val concept = cosine(features.concepts, centroid.concepts)
        val entity = entityScore(features.entities, centroid.entities)
        val place = overlap(features.places, centroid.places.keys)
        val type = if (features.eventType == dominant(centroid.eventTypes)) 1.0 else 0.0
        val hours = kotlin.math.abs(publishedAt - centroid.lastAt) / 3_600_000.0
        val time = when { hours <= 36 -> 1.0; hours <= 96 -> 0.55; else -> 0.15 }
        var score = 0.38 * concept + 0.12 * entity + 0.22 * place + 0.18 * type + 0.10 * time
        if (type == 0.0) score *= 0.40
        if (place == 0.0 && concept < 0.22) score *= 0.45
        val famousOnly = features.entities.isNotEmpty() && features.entities.all { it in NewsLexicon.famousEntities } && concept < 0.2 && place == 0.0
        if (famousOnly) score = score.coerceAtMost(0.28)
        if (vector != null && centroid.vector != null && vector.size == centroid.vector.size) {
            score = 0.75 * score + 0.25 * ((cosineArray(vector, centroid.vector) + 1) / 2)
        }
        return score.coerceIn(0.0, 1.0)
    }

    fun assign(articleId: String, features: ArticleFeatures, publishedAt: Long, vector: FloatArray?, candidates: List<EventCentroid>, constraints: ClusterConstraints): ClusterDecision {
        constraints.mustLink[articleId]?.let { return ClusterDecision(it, 1.0, listOf("owner said this is the same event")) }
        var best: EventCentroid? = null
        var bestScore = 0.0
        for (candidate in candidates) {
            if (articleId to candidate.eventId in constraints.mustNotLink) continue
            val score = similarity(features, publishedAt, vector, candidate)
            if (score > bestScore) { bestScore = score; best = candidate }
        }
        val chosen = best?.takeIf { bestScore >= THRESHOLD }
        val reasons = if (chosen == null) listOf("no event is close enough (${"%.2f".format(bestScore)})")
        else listOf("matched ${chosen.eventId} at ${"%.2f".format(bestScore)}")
        return ClusterDecision(chosen?.eventId, bestScore, reasons)
    }

    fun add(centroid: EventCentroid?, eventId: String, articleId: String, features: ArticleFeatures, publishedAt: Long, vector: FloatArray?): EventCentroid {
        val base = centroid ?: return EventCentroid(eventId, features.concepts, features.entities.associateWith { 1 }, features.places.associateWith { 1 },
            mapOf(features.eventType to 1), publishedAt, publishedAt, setOf(articleId), listOf(features.fingerprint), vector)
        val concepts = HashMap(base.concepts)
        features.concepts.forEach { (k, v) -> concepts[k] = ((concepts[k] ?: 0.0) * 0.85 + v).let { (it * 1000).roundToInt() / 1000.0 } }
        val entities = HashMap(base.entities)
        features.entities.forEach { entities[it] = (entities[it] ?: 0) + 1 }
        val places = HashMap(base.places)
        features.places.forEach { places[it] = (places[it] ?: 0) + 1 }
        val types = HashMap(base.eventTypes)
        types[features.eventType] = (types[features.eventType] ?: 0) + 1
        val mixed = if (vector == null || base.vector == null || vector.size != base.vector.size) base.vector ?: vector
        else FloatArray(vector.size) { ((base.vector[it] + vector[it]) / 2f) }
        return base.copy(concepts = concepts, entities = entities, places = places, eventTypes = types,
            firstAt = minOf(base.firstAt, publishedAt), lastAt = maxOf(base.lastAt, publishedAt),
            articleIds = base.articleIds + articleId, fingerprints = (base.fingerprints + features.fingerprint).takeLast(24), vector = mixed)
    }

    fun signature(centroid: EventCentroid): String {
        val day = Instant.ofEpochMilli(centroid.firstAt).atZone(zone).toLocalDate()
        val place = centroid.places.maxByOrNull { it.value }?.key ?: "-"
        val type = dominant(centroid.eventTypes)
        val concepts = centroid.concepts.filterKeys { !it.startsWith("~") }.entries.sortedByDescending { it.value }.take(3).joinToString(",") { it.key }
        return "$type|$place|$day|$concepts"
    }

    private fun dominant(types: Map<String, Int>) = types.maxByOrNull { it.value }?.key ?: "OTHER"
    private fun overlap(left: Set<String>, right: Set<String>): Double {
        if (left.isEmpty() || right.isEmpty()) return 0.0
        return left.intersect(right).size.toDouble() / left.union(right).size
    }
    private fun entityScore(left: Set<String>, counts: Map<String, Int>): Double {
        if (left.isEmpty() || counts.isEmpty()) return 0.0
        val shared = left.filter { it in counts }
        if (shared.isEmpty()) return 0.0
        val weight = shared.sumOf { NewsLexicon.famousEntities[it] ?: 1.0 }
        return (weight / left.size).coerceIn(0.0, 1.0)
    }
    private fun cosine(a: Map<String, Double>, b: Map<String, Double>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (key in a.keys + b.keys) {
            val x = a[key] ?: 0.0; val y = b[key] ?: 0.0
            dot += x * y; na += x * x; nb += y * y
        }
        return if (na == 0.0 || nb == 0.0) 0.0 else dot / sqrt(na * nb)
    }
    private fun cosineArray(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0.0 || nb == 0.0) 0.0 else dot / sqrt(na * nb)
    }
}

internal object NewsFactLogic {
    private val material = setOf("FIGURE", "DECISION", "STATUS")

    fun groups(articles: List<Pair<ArticleInput, ArticleFeatures>>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val prints = ArrayList<Pair<Long, String>>()
        for ((input, features) in articles) {
            val agency = features.agency?.takeIf { it.isNotBlank() } ?: input.sourceAgency?.takeIf { it.isNotBlank() }
            val copy = prints.firstOrNull { NewsText.hamming(it.first, features.fingerprint) <= 3 }?.second
            val group = when {
                agency != null -> "agency:$agency"
                copy != null -> copy
                else -> "src:${input.sourceId}"
            }
            out[input.id] = group
            prints += features.fingerprint to group
        }
        return out
    }

    fun snapshot(articles: List<Pair<ArticleInput, ArticleFeatures>>): EventSnapshot {
        val groups = groups(articles)
        val merged = LinkedHashMap<String, Fact>()
        val support = LinkedHashMap<String, MutableSet<String>>()
        for ((input, features) in articles) {
            val group = groups[input.id] ?: input.id
            for (fact in features.facts) {
                val id = "${fact.key}|${fact.value}"
                val prior = merged[id]
                merged[id] = fact.copy(articleIds = (prior?.articleIds ?: emptySet()) + input.id, speculative = fact.speculative && (prior?.speculative != false), correction = fact.correction || prior?.correction == true)
                support.getOrPut(id) { linkedSetOf() }.add(group)
            }
        }
        val byKey = merged.values.groupBy { it.key }
        val conflicts = ArrayList<String>()
        for ((key, facts) in byKey) {
            val confirmed = facts.filter { !it.speculative }.distinctBy { it.value }
            if (confirmed.size < 2) continue
            conflicts += confirmed.joinToString("; ") { fact ->
                val names = articles.filter { it.first.id in fact.articleIds }.map { it.first.sourceName }.distinct().ifEmpty { listOf("A report") }
                "${names.joinToString(" and ")} reports ${fact.value} ${key.substringAfter(':')}"
            }
        }
        val independent = groups.values.toSet().size
        val socialOnly = articles.isNotEmpty() && articles.all { it.first.social && !it.first.sourceOfficial }
        val agreed = merged.values.any { !it.speculative && (support["${it.key}|${it.value}"]?.size ?: 0) >= 2 }
        val verification = when {
            conflicts.isNotEmpty() -> "CONFLICTING"
            socialOnly -> "UNVERIFIED_SOCIAL"
            agreed || independent >= 2 -> "CONFIRMED"
            else -> "SINGLE_SOURCE"
        }
        val resolved = merged.values.any { it.key == "status" && it.value == "resolved" && !it.speculative }
        return EventSnapshot(merged.values.toList(), support.mapValues { it.value.toSet() }, independent, verification, conflicts, resolved)
    }

    fun delta(previous: EventSnapshot?, current: EventSnapshot, eventType: String): Delta {
        if (previous == null) {
            val confirmed = current.facts.filter { !it.speculative && it.kind in material }
            return if (confirmed.isEmpty()) Delta("NONE", emptyList(), emptyList(), "", "", "")
            else pack("MAJOR", confirmed, emptyList(), "", current, eventType)
        }
        val prevConfirmed = previous.facts.filter { !it.speculative }
        val prevByKey = prevConfirmed.groupBy { it.key }
        val seen = prevConfirmed.map { it.key to it.value }.toSet()
        val fresh = ArrayList<Fact>()
        val changed = ArrayList<Pair<Fact, Fact>>()
        var correction = false
        for (fact in current.facts) {
            if (fact.speculative) continue
            if (fact.key to fact.value in seen) continue
            val old = prevByKey[fact.key].orEmpty().firstOrNull { it.value != fact.value }
            if (old != null) {
                changed += old to fact
                if (fact.correction || old.correction) correction = true
            } else fresh += fact
            if (fact.correction) correction = true
        }
        val resolvedNow = current.resolved && !previous.resolved
        val classification = when {
            correction && changed.isNotEmpty() -> "CORRECTION"
            resolvedNow && fresh.none { it.kind in material } && changed.isEmpty() -> "RESOLVED"
            fresh.any { it.kind in material } || changed.isNotEmpty() -> "MAJOR"
            fresh.isNotEmpty() -> "MINOR"
            resolvedNow -> "RESOLVED"
            else -> "NONE"
        }
        return pack(classification, fresh, changed, previousLine(prevConfirmed), current, eventType)
    }

    private fun pack(classification: String, fresh: List<Fact>, changed: List<Pair<Fact, Fact>>, previously: String, current: EventSnapshot, eventType: String): Delta {
        if (classification == "NONE") return Delta("NONE", emptyList(), emptyList(), previously, "", "")
        val whatsNew = buildString {
            changed.forEach { (old, now) -> append(if (classification == "CORRECTION") "Corrected: " else "Updated: "); append("${old.key} was ${old.value}, now ${now.value}. ") }
            fresh.filter { it.kind in material }.forEach { append("${it.key.replace(':', ' ')} ${it.value}. ") }
            if (classification == "RESOLVED") append("The event has reached a conclusion. ")
        }.trim()
        val why = when (classification) {
            "CORRECTION" -> "A previously reported figure or claim has been corrected."
            "RESOLVED" -> "The situation has reached a meaningful conclusion."
            "MAJOR" -> if (eventType == "DISASTER" || eventType == "PUBLIC_HEALTH") "The new information changes the scale or the official response." else "A confirmed decision, figure, or outcome has changed."
            else -> "Additional detail is available, without a material change."
        }
        return Delta(classification, fresh, changed, previously, whatsNew, why)
    }

    private fun previousLine(facts: List<Fact>): String = facts.filter { it.kind in material }.take(4).joinToString("; ") { "${it.key} ${it.value}" }
}

internal object NewsScoreLogic {
    fun interestMatch(topics: Set<String>, places: Set<String>, entities: Set<String>, interests: List<Interest>): Double {
        if (interests.isEmpty()) return 0.0
        var score = 0.0
        for (interest in interests) {
            val key = interest.key.removePrefix("topic:").removePrefix("place:").removePrefix("term:")
            val hit = key in topics || key in places || key in entities || entities.any { key in it } || topics.any { key in it }
            if (!hit) continue
            val w = interest.weight.coerceIn(-2.0, 2.0)
            score += if (interest.kind == "HIDDEN") -kotlin.math.abs(w) else if (interest.kind == "EXPLICIT") w else w * 0.5
        }
        return (score / 2.0).coerceIn(0.0, 1.0)
    }

    fun score(input: ScoreInput, interests: List<Interest>, now: Long, weights: ScoreWeights): NewsScore {
        val match = interestMatch(input.topics, input.places, input.entities, interests)
        val protectedPublic = input.publicSafety || input.eventType in setOf("DISASTER", "PUBLIC_HEALTH", "WEATHER_ALERT", "SECURITY_INCIDENT", "ECONOMY_RATES")
        val impact = when {
            input.publicSafety || input.eventType == "DISASTER" -> 95
            input.eventType == "PUBLIC_HEALTH" || input.eventType == "SECURITY_INCIDENT" -> 88
            input.eventType == "ECONOMY_RATES" || input.eventType == "POLICY_DECISION" || input.eventType == "LEGISLATION" -> 72
            input.contentTypes.any { it == "ADVERTISEMENT" || it == "SATIRE" } -> 8
            input.contentTypes.all { it == "OPINION" } -> 25
            else -> 48
        }
        val hours = ((now - input.lastArticleAt).coerceAtLeast(0)) / 3_600_000.0
        var recency = when { hours <= 3 -> 95; hours <= 12 -> 80; hours <= 24 -> 65; hours <= 48 -> 45; else -> 25 }
        if (input.urgent) recency = (recency + 10).coerceAtMost(100)
        val corroboration = when {
            input.snapshot.verification == "CONFLICTING" -> 55
            input.snapshot.verification == "UNVERIFIED_SOCIAL" -> 15
            input.snapshot.independentSources >= 3 -> 90
            input.snapshot.independentSources == 2 -> 70
            else -> 32
        }
        val reliabilities = input.sourceReliabilities.ifEmpty { listOf(40) }
        var reliability = reliabilities.average().roundToInt().coerceIn(0, 100)
        if (input.contentTypes.any { it == "SOCIAL_POST" }) reliability = (reliability * 0.6).roundToInt()
        val novelty = when (input.latestClassification) {
            "MAJOR", "CORRECTION" -> 90
            "NEW" -> 75
            "RESOLVED" -> 70
            "MINOR" -> 40
            else -> 12
        } + input.feedbackBias.coerceIn(-2, 2) * 5
        val factors = mapOf(
            "impact" to impact,
            "relevance" to (match * 100).roundToInt(),
            "recency" to recency,
            "corroboration" to corroboration,
            "reliability" to reliability,
            "novelty" to novelty.coerceIn(0, 100),
        )
        val weight = mapOf("impact" to weights.impact, "relevance" to weights.relevance, "recency" to weights.recency,
            "corroboration" to weights.corroboration, "reliability" to weights.reliability, "novelty" to weights.novelty)
        val sum = weight.values.sum().takeIf { it > 0 } ?: 1.0
        val total = factors.entries.sumOf { (name, value) -> value * (weight[name] ?: 0.0) / sum }.roundToInt().coerceIn(0, 100)
        val reasons = factors.entries.sortedByDescending { it.value * (weight[it.key] ?: 0.0) }.take(3).map { (name, value) ->
            when (name) {
                "impact" -> if (protectedPublic) "Public-interest impact ($value)" else "Impact ($value)"
                "relevance" -> if (match < 0.15) "Outside your usual topics, kept for public importance" else "Matches your interests ($value)"
                "corroboration" -> "${input.snapshot.independentSources} independent source group(s), not article count"
                else -> "${name.replaceFirstChar { it.uppercase() }} ($value)"
            }
        }
        return NewsScore(total, NewsScoring.level(total), factors, reasons, protectedPublic)
    }
}

internal object NewsBriefLogic {
    private val resurface = setOf("MAJOR", "CORRECTION", "RESOLVED")

    fun build(candidates: List<BriefCandidate>, states: Map<String, NewsEventState>, window: LongRange, config: BriefConfig): List<BriefItem> {
        val visible = candidates.filter { candidate ->
            val hidden = states[candidate.eventId]?.hidden == true
            !hidden || (candidate.protectedPublic && candidate.level == "CRITICAL")
        }
        val changed = visible.mapNotNull { candidate ->
            val delivered = states[candidate.eventId]?.deliveredVersion ?: 0
            if (delivered < 1) return@mapNotNull null
            val material = candidate.versionClasses.filter { (version, cls) -> version > delivered && cls in resurface }
            if (material.isEmpty()) return@mapNotNull null
            val version = material.keys.max()
            BriefItem(candidate.eventId, version, "CHANGED", material[version])
        }.sortedByDescending { item -> visible.first { it.eventId == item.eventId }.score }.take(config.maxUpdates)
        val changedIds = changed.map { it.eventId }.toSet()
        val fresh = visible.filter { candidate ->
            val delivered = states[candidate.eventId]?.deliveredVersion ?: 0
            candidate.eventId !in changedIds && delivered < 1 &&
                (candidate.firstSeenAt in window || candidate.updatedAt in window) &&
                (candidate.score >= config.minScore || candidate.protectedPublic)
        }.sortedByDescending { it.score }
        val explore = (config.maxStories * config.explorationShare).toInt().let { if (config.explorationShare > 0 && config.maxStories > 0) it.coerceAtLeast(1) else 0 }
        val low = fresh.filter { it.interestMatch < 0.35 }
        val picked = (low.take(explore) + fresh.filter { it.interestMatch >= 0.35 } + low.drop(explore)).distinctBy { it.eventId }.take(config.maxStories)
        val stories = picked.map { candidate ->
            val section = if (candidate.level == "CRITICAL" || candidate.level == "HIGH") "TOP" else "STORY"
            BriefItem(candidate.eventId, candidate.version, section)
        }
        return (stories + changed).distinctBy { it.eventId }
    }
}

internal object NewsSummaryLogic {
    fun extractive(headline: String, articles: List<Pair<ArticleInput, ArticleFeatures>>, snapshot: EventSnapshot, versions: List<NewsEventVersion>, length: String, language: String): StorySummary {
        val ranked = articles.flatMap { (input, _) -> NewsTextCore.sentences(input.text.ifBlank { input.title }).map { it.trim() to input } }
            .filter { it.first.length in 20..400 }.distinctBy { it.first }
        val cap = when (length) { "SHORT" -> 2; "DETAILED" -> 4; else -> 3 }
        val quick = ranked.take(cap).map { it.first }
        val detailCap = when (length) { "SHORT" -> 2; "DETAILED" -> 8; else -> 4 }
        val details = ranked.take(detailCap).map { (sentence, input) -> SourcedLine(sentence, listOf(input.sourceName)) }
        val why = ranked.firstOrNull { Regex("(?i)because|so that|means|kwa sababu|maana yake").containsMatchIn(it.first) }?.first.orEmpty()
        val local = ranked.firstOrNull { (sentence, _) ->
            val places = NewsTextCore.scan(sentence).places
            places.any { NewsLexicon.places[it]?.region == "tanzania" || it == "tanzania" }
        }?.first.orEmpty()
        val next = ranked.filter { speculative(it.first) }.map { it.first }.take(2)
        val labels = linkedSetOf<String>()
        when (snapshot.verification) {
            "CONFLICTING" -> labels += "CONFLICTING"
            "SINGLE_SOURCE" -> labels += "SINGLE_SOURCE"
            "UNVERIFIED_SOCIAL" -> labels += "UNVERIFIED_SOCIAL"
        }
        if (!snapshot.resolved && articles.isNotEmpty()) labels += "DEVELOPING"
        if (snapshot.resolved) labels += "RESOLVED"
        articles.map { it.second.contentType }.forEach { type ->
            if (type in setOf("OPINION", "ANALYSIS", "SATIRE", "ADVERTISEMENT")) labels += type
        }
        versions.lastOrNull()?.classification?.let { cls ->
            if (cls == "CORRECTION") labels += "CORRECTION"
            if (cls == "MAJOR" || cls == "MINOR") labels += "UPDATE"
            if (cls == "RESOLVED") labels += "RESOLVED"
        }
        val preferred = articles.filter { it.second.language == language }.ifEmpty { articles }
        if (preferred !== articles && language != articles.first().second.language) labels += "TRANSLATED"
        return StorySummary(
            NewsText.neutralHeadline(headline.ifBlank { articles.firstOrNull()?.first?.title.orEmpty() }),
            quick, details, why, local, "", next, snapshot.conflicts, labels,
            articles.map { (input, features) -> SourceRef(input.id, input.sourceName, input.title, input.url, input.publishedAt, features.contentType, features.agency) },
            preferred.firstOrNull()?.second?.language ?: language, "EXTRACTIVE",
        )
    }

    fun prompt(headline: String, articles: List<Pair<ArticleInput, ArticleFeatures>>, snapshot: EventSnapshot, length: String, language: String): String = buildString {
        appendLine("Write a grounded news summary as JSON only. Do not invent facts, numbers, or quotations. Empty string or empty array when the excerpts do not support a section.")
        appendLine("language=$language length=$length")
        appendLine("headline_hint=${JSONObject.quote(headline)}")
        appendLine("verification=${snapshot.verification}")
        snapshot.conflicts.forEach { appendLine("conflict=${JSONObject.quote(it)}") }
        articles.forEachIndexed { index, (input, _) ->
            appendLine("[$index] ${input.sourceName} ${input.publishedAt}")
            appendLine(input.title)
            appendLine(input.text.take(1200))
        }
        append("""{"headline":"","quick":[],"details":[{"text":"","sources":[0]}],"whyItMatters":"","localImpact":"","background":"","whatsNext":[]}""")
    }

    fun parseModel(output: String, articles: List<Pair<ArticleInput, ArticleFeatures>>, snapshot: EventSnapshot, language: String, generatedBy: String): StorySummary {
        val cleaned = output.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val json = JSONObject(cleaned)
        val corpus = articles.joinToString("\n") { it.first.title + "\n" + it.first.text }
        fun grounded(text: String) {
            Regex("\\d+(?:[.,]\\d+)?").findAll(text).forEach { match ->
                val raw = match.value
                val plain = raw.replace(",", "")
                if (!corpus.contains(raw) && !corpus.contains(plain)) throw IllegalArgumentException("number $raw is not in the excerpts")
            }
            Regex("[\"“]([^\"”]{2,})[\"”]").findAll(text).forEach { match ->
                if (!corpus.contains(match.groupValues[1])) throw IllegalArgumentException("quotation is not in the excerpts")
            }
        }
        val headline = json.optString("headline")
        grounded(headline)
        val quick = json.optJSONArray("quick")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        quick.forEach(::grounded)
        val details = json.optJSONArray("details")?.let { a ->
            (0 until a.length()).map { i ->
                val row = a.getJSONObject(i)
                val text = row.getString("text")
                grounded(text)
                val sources = row.optJSONArray("sources")?.let { s -> (0 until s.length()).map { s.getInt(it) } }.orEmpty()
                if (sources.any { it !in articles.indices }) throw IllegalArgumentException("source index out of range")
                SourcedLine(text, sources.map { articles[it].first.sourceName })
            }
        }.orEmpty()
        listOf("whyItMatters", "localImpact", "background").forEach { grounded(json.optString(it)) }
        val next = json.optJSONArray("whatsNext")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        next.forEach(::grounded)
        return StorySummary(headline, quick, details, json.optString("whyItMatters"), json.optString("localImpact"), json.optString("background"),
            next, snapshot.conflicts, setOf("DEVELOPING"), articles.map { (input, features) ->
                SourceRef(input.id, input.sourceName, input.title, input.url, input.publishedAt, features.contentType, features.agency)
            }, language, generatedBy)
    }

    fun spoken(summary: StorySummary): String = buildString {
        append(summary.headline)
        summary.quick.forEach { append(". ").append(it) }
        if (summary.whyItMatters.isNotBlank()) append(". ").append(summary.whyItMatters)
        if (summary.localImpact.isNotBlank()) append(". ").append(summary.localImpact)
        summary.whatsNext.forEach { append(". Expected, not confirmed: ").append(it) }
    }.trim().trimStart('.')

    private fun speculative(text: String) = Regex("(?i)\\b(may|might|expected|inatarajiwa|huenda)\\b").containsMatchIn(text)
}
