package com.hellfire.ass.news

/*
 * CONTRACT between the engine (pure Kotlin, no Android APIs, JVM-testable) and the runtime/UI.
 * The engine branch implements every TODO() below and may add private helpers or new files in this package.
 * Do not rename, remove or change the parameters of anything declared here; adding optional parameters
 * with defaults and new members is fine. All engine functions are deterministic for the same inputs.
 */

/** Everything the engine needs about one article. [social]: an X post. */
data class ArticleInput(
    val id: String,
    val sourceId: String,
    val sourceName: String,
    val title: String,
    val text: String,
    val url: String,
    val publishedAt: Long,
    val sourceReliability: Int = 60,
    val sourceAgency: String? = null,
    val sourceOfficial: Boolean = false,
    val social: Boolean = false,
    /** Language hint from the feed ("en"/"sw"); detection decides when null. */
    val language: String? = null,
)

/**
 * A structured claim. [key] is language-neutral (e.g. "deaths", "injured", "rate", "price:fuel", "decision:approve",
 * "status:resolved"); [value] is normalized ("12", "6.5%", "approved"). [speculative]: hedged wording (may, expected,
 * reportedly, alleged, huenda, inadaiwa, inatarajiwa). [correction]: the sentence corrects earlier reporting.
 */
data class Fact(
    val key: String,
    val value: String,
    val kind: String, // FIGURE, DECISION, STATUS, CLAIM
    val speculative: Boolean,
    val correction: Boolean,
    val sentence: String,
    val articleIds: Set<String> = emptySet(),
)

/** Language-neutral features of one article. [concepts]: weighted concept keys (English lemmas; Kiswahili mapped through the lexicon). */
data class ArticleFeatures(
    val language: String,
    val concepts: Map<String, Double>,
    val entities: Set<String>,
    val places: Set<String>,
    val eventType: String,
    val topics: Set<String>,
    val facts: List<Fact>,
    val contentType: String,
    val agency: String?,
    val fingerprint: Long,
    val urgent: Boolean,
    val publicSafety: Boolean,
) {
    fun toJson(): String = TODO()
    companion object { fun fromJson(json: String): ArticleFeatures = TODO() }
}

/** Aggregated features of an event used for clustering. */
data class EventCentroid(
    val eventId: String,
    val concepts: Map<String, Double>,
    val entities: Map<String, Int>,
    val places: Map<String, Int>,
    val eventTypes: Map<String, Int>,
    val firstAt: Long,
    val lastAt: Long,
    val articleIds: Set<String>,
    val fingerprints: List<Long>,
    val vector: FloatArray? = null,
) {
    fun toJson(): String = TODO()
    companion object { fun fromJson(eventId: String, json: String): EventCentroid = TODO() }
}

/** Owner corrections the clusterer must honor. [mustNotLink]: (articleId, eventId) pairs. [mustLink]: articleId → eventId. */
data class ClusterConstraints(
    val mustNotLink: Set<Pair<String, String>> = emptySet(),
    val mustLink: Map<String, String> = emptyMap(),
    /** Event pairs the owner said are different events (never merge their centroids). */
    val distinctEvents: Set<Pair<String, String>> = emptySet(),
)

/** [eventId] null means "start a new event". [reasons] explain the decision for the health/debug view. */
data class ClusterDecision(val eventId: String?, val score: Double, val reasons: List<String>)

/** Facts of an event at one point in time with their independent support. [groups]: fact key|value → independence group keys. */
data class EventSnapshot(
    val facts: List<Fact>,
    val groups: Map<String, Set<String>>,
    val independentSources: Int,
    /** CONFIRMED, SINGLE_SOURCE, CONFLICTING, UNVERIFIED_SOCIAL. */
    val verification: String,
    /** Human-readable conflicts, e.g. "BBC reports 12 deaths; Daily News reports 9". */
    val conflicts: List<String>,
    val resolved: Boolean,
) {
    fun toJson(): String = TODO()
    companion object { fun fromJson(json: String): EventSnapshot = TODO() }
}

/** Result of comparing the current snapshot with the last delivered/seen one. [classification]: MAJOR, MINOR, CORRECTION, RESOLVED, NONE. */
data class Delta(
    val classification: String,
    val newFacts: List<Fact>,
    val changedFacts: List<Pair<Fact, Fact>>,
    val previously: String,
    val whatsNew: String,
    val whyItMatters: String,
)

/** Default weights from the spec; the owner may change them. Weights are normalized by their sum. */
data class ScoreWeights(
    val impact: Double = 30.0,
    val relevance: Double = 20.0,
    val recency: Double = 15.0,
    val corroboration: Double = 15.0,
    val reliability: Double = 10.0,
    val novelty: Double = 10.0,
)

/** One preference the scorer sees (from news_preference). */
data class Interest(val key: String, val kind: String, val weight: Double)

/** [factors]: name → 0..100 sub-score. [reasons]: the main reasons, most important first. */
data class NewsScore(val total: Int, val level: String, val factors: Map<String, Int>, val reasons: List<String>, val protectedPublic: Boolean) {
    fun toJson(): String = TODO()
    companion object { fun fromJson(json: String): NewsScore = TODO() }
}

/** What the scorer needs about one event. [latestClassification]: NEW for a first report, else the latest version's class. */
data class ScoreInput(
    val eventType: String,
    val topics: Set<String>,
    val places: Set<String>,
    val entities: Set<String>,
    val snapshot: EventSnapshot,
    val sourceReliabilities: List<Int>,
    val contentTypes: List<String>,
    val lastArticleAt: Long,
    val urgent: Boolean,
    val publicSafety: Boolean,
    val latestClassification: String,
    /** Owner feedback nudges: +1 per MORE_IMPORTANT/MISSED, -1 per LESS_IMPORTANT on this or similar events. */
    val feedbackBias: Int = 0,
)

/** One event offered to the briefing builder. */
data class BriefCandidate(
    val eventId: String,
    val version: Int,
    val score: Int,
    val level: String,
    val protectedPublic: Boolean,
    val topics: Set<String>,
    val firstSeenAt: Long,
    val updatedAt: Long,
    /** Classification of each version after 1, by version number. */
    val versionClasses: Map<Int, String>,
    /** Owner interest match 0..1 (for diversity: low-match stories keep some slots). */
    val interestMatch: Double,
)

/** [length]: SHORT, STANDARD, DETAILED. [maxStories] caps new stories; "What Changed" has its own cap. [minScore]: below this a story is left out unless protected. */
data class BriefConfig(val maxStories: Int = 12, val maxUpdates: Int = 6, val minScore: Int = 35, val length: String = "STANDARD", val explorationShare: Double = 0.2)

/** [section]: TOP (critical/high), STORY, CHANGED. [classification] is set for CHANGED items. */
data class BriefItem(val eventId: String, val version: Int, val section: String, val classification: String? = null)

/** One line with the sources that support it. */
data class SourcedLine(val text: String, val sources: List<String>)

/** A source reference shown under a story. */
data class SourceRef(val articleId: String, val publisher: String, val title: String, val url: String, val publishedAt: Long, val contentType: String, val agency: String?)

/**
 * Structured story summary. Sections the evidence cannot support are empty (never invented).
 * [labels]: DEVELOPING, CONFLICTING, SINGLE_SOURCE, UNVERIFIED_SOCIAL, OPINION, ANALYSIS, SATIRE, ADVERTISEMENT, UPDATE, CORRECTION, RESOLVED, TRANSLATED.
 * [generatedBy]: "EXTRACTIVE" or "AI:<provider>/<model>".
 */
data class StorySummary(
    val headline: String,
    val quick: List<String>,
    val details: List<SourcedLine>,
    val whyItMatters: String,
    val localImpact: String,
    val background: String,
    val whatsNext: List<String>,
    val conflicts: List<String>,
    val labels: Set<String>,
    val sources: List<SourceRef>,
    val language: String,
    val generatedBy: String,
) {
    fun toJson(): String = TODO()
    /** Plain text for text-to-speech and sharing (no links). */
    fun spoken(): String = TODO()
    companion object { fun fromJson(json: String): StorySummary = TODO() }
}

object NewsText {
    /** Lowercase host without www/m/amp, no fragment, no tracking parameters (utm_*, fbclid, gclid, ref, ...), no trailing slash, /amp removed. */
    fun canonicalUrl(url: String): String = TODO()
    /** "sw" or "en" (mixed text picks the majority). */
    fun detectLanguage(text: String): String = TODO()
    fun features(input: ArticleInput): ArticleFeatures = TODO()
    /** 64-bit simhash of normalized word shingles. */
    fun simhash(text: String): Long = TODO()
    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
    /** Neutral, informative headline: drops "BREAKING:", "WATCH:", emoji, clickbait punctuation, shouting caps. */
    fun neutralHeadline(title: String): String = TODO()
    /** NEWS, OPINION, ANALYSIS, SATIRE, ADVERTISEMENT, SOCIAL_POST, OFFICIAL_STATEMENT from title/text/url cues. */
    fun contentType(input: ArticleInput): String = TODO()
}

object NewsClusterer {
    /** Similarity 0..1 of an article to an event centroid. [vector] is the article embedding when available. */
    fun similarity(features: ArticleFeatures, publishedAt: Long, vector: FloatArray?, centroid: EventCentroid): Double = TODO()
    /** Best event for the article among [candidates] or null for a new event. Honors [constraints]. */
    fun assign(articleId: String, features: ArticleFeatures, publishedAt: Long, vector: FloatArray?, candidates: List<EventCentroid>,
        constraints: ClusterConstraints = ClusterConstraints()): ClusterDecision = TODO()
    /** Centroid after adding one article. */
    fun add(centroid: EventCentroid?, eventId: String, articleId: String, features: ArticleFeatures, publishedAt: Long, vector: FloatArray?): EventCentroid = TODO()
    /** Deterministic event id for the first article of a new event. */
    fun newEventId(firstArticleId: String): String = "ev-" + firstArticleId.take(24)
    /** Canonical identity string: type|place|yyyy-MM-dd (EAT)|top concepts. */
    fun signature(centroid: EventCentroid): String = TODO()
}

object NewsIndependence {
    /** Independence group per article: copies of the same wire report or the same publisher share a group. */
    fun groups(articles: List<Pair<ArticleInput, ArticleFeatures>>): Map<String, String> = TODO()
}

object NewsFacts {
    /** Snapshot of an event from all its articles (facts merged, support counted by independence group). */
    fun snapshot(articles: List<Pair<ArticleInput, ArticleFeatures>>): EventSnapshot = TODO()
    /**
     * Compares [current] with [previous] (null = first report → classification "NEW" is not returned here; callers treat
     * null as new). Restated facts → NONE. Speculative or unverified social claims are never MAJOR. Corrections → CORRECTION.
     */
    fun delta(previous: EventSnapshot?, current: EventSnapshot, eventType: String): Delta = TODO()
}

object NewsScoring {
    fun score(input: ScoreInput, interests: List<Interest>, now: Long, weights: ScoreWeights = ScoreWeights()): NewsScore = TODO()
    /** CRITICAL ≥ 80, HIGH ≥ 60, MEDIUM ≥ 40, else LOW. */
    fun level(total: Int): String = when { total >= 80 -> "CRITICAL"; total >= 60 -> "HIGH"; total >= 40 -> "MEDIUM"; else -> "LOW" }
    /** 0..1 match of an event's topics/places/entities with EXPLICIT and INFERRED interests (HIDDEN subtracts). */
    fun interestMatch(topics: Set<String>, places: Set<String>, entities: Set<String>, interests: List<Interest>): Double = TODO()
}

object NewsBriefings {
    /**
     * Picks one briefing's items. [states]: eventId → owner state. Rules: an event appears at most once; an event already
     * delivered (deliveredVersion ≥ 1) returns only in CHANGED, and only if a version above deliveredVersion is MAJOR,
     * CORRECTION or RESOLVED; MINOR-only changes never resurface; hidden events are left out unless protectedPublic and
     * CRITICAL; [window] limits new stories to events first seen or updated inside it; low-interest public stories keep
     * [BriefConfig.explorationShare] of the slots.
     */
    fun build(candidates: List<BriefCandidate>, states: Map<String, NewsEventState>, window: LongRange, config: BriefConfig): List<BriefItem> = TODO()
}

object NewsSummaries {
    /** Grounded summary built only from the articles' own sentences, with attribution. Never invents. */
    fun extractive(headline: String, articles: List<Pair<ArticleInput, ArticleFeatures>>, snapshot: EventSnapshot,
        versions: List<NewsEventVersion>, length: String, language: String): StorySummary = TODO()
    /** Prompt for the cloud/local model: numbered excerpts, strict JSON output contract, grounding rules. */
    fun prompt(headline: String, articles: List<Pair<ArticleInput, ArticleFeatures>>, snapshot: EventSnapshot, length: String, language: String): String = TODO()
    /**
     * Parses and validates model output against [articles]: every source index must exist, every number and quotation
     * must appear in the excerpts, unsupported sections must be empty. Throws IllegalArgumentException on violation.
     */
    fun parseModel(output: String, articles: List<Pair<ArticleInput, ArticleFeatures>>, snapshot: EventSnapshot, language: String, generatedBy: String): StorySummary = TODO()
}
