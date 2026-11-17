package com.hellfire.ass.ui.news

import com.hellfire.ass.news.NewsBriefing
import com.hellfire.ass.news.NewsProgress
import com.hellfire.ass.news.ScoreWeights
import com.hellfire.ass.news.StorySummary
import com.hellfire.ass.ui.components.Tone
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Pure helpers behind the news screens (no Android or Compose runtime), unit-tested. */
object NewsUi {
    /** A story counts as read after it has been on screen this long. */
    const val READ_AFTER_MS = 3_000L

    val lengths = listOf("SHORT" to "Short", "STANDARD" to "Standard", "DETAILED" to "Detailed")

    /** Explicit interest topics offered on the Interests page: key → label. */
    val topics = listOf(
        "topic:tanzania" to "Tanzania", "topic:east_africa" to "East Africa", "topic:global" to "Global affairs",
        "topic:technology" to "Technology", "topic:ai" to "Artificial intelligence", "topic:smartphones" to "Smartphones",
        "topic:hardware" to "Computer hardware", "topic:software" to "Software development", "topic:business" to "Business",
        "topic:economy" to "Economics", "topic:science" to "Science", "topic:sports" to "Sports", "topic:entertainment" to "Entertainment",
    )

    val allSlots = listOf("MORNING", "EVENING", "CUSTOM", "ON_DEMAND", "BREAKING")

    val categories = listOf("TANZANIA", "EAST_AFRICA", "INTERNATIONAL", "TECHNOLOGY", "SOCIAL", "CUSTOM")

    /** Reader page to open: the saved position clamped to the stories that exist. */
    fun startPage(saved: NewsProgress?, count: Int): Int = if (count <= 0) 0 else (saved?.position ?: 0).coerceIn(0, count - 1)

    fun levelLabel(level: String): String = when (level) {
        "CRITICAL" -> "Critical"; "HIGH" -> "High"; "MEDIUM" -> "Medium"; else -> "Low"
    }

    fun levelTone(level: String): Tone = when (level) {
        "CRITICAL" -> Tone.Danger; "HIGH" -> Tone.Warning; "MEDIUM" -> Tone.Info; else -> Tone.Neutral
    }

    /** Owner-facing text for a StorySummary label; null hides it. */
    fun labelText(label: String): String? = when (label) {
        "DEVELOPING" -> "Developing"
        "CONFLICTING" -> "Conflicting reports"
        "SINGLE_SOURCE" -> "Single source"
        "UNVERIFIED_SOCIAL" -> "Unverified social posts"
        "OPINION" -> "Opinion"
        "ANALYSIS" -> "Analysis"
        "SATIRE" -> "Satire"
        "ADVERTISEMENT" -> "Ad"
        "UPDATE" -> "Update"
        "CORRECTION" -> "Correction"
        "RESOLVED" -> "Resolved"
        "TRANSLATED" -> "Translated by AI"
        else -> null
    }

    fun labelTone(label: String): Tone = when (label) {
        "CONFLICTING", "UNVERIFIED_SOCIAL", "CORRECTION", "SINGLE_SOURCE" -> Tone.Warning
        "OPINION", "ANALYSIS", "SATIRE", "ADVERTISEMENT" -> Tone.Info
        "RESOLVED" -> Tone.Success
        "DEVELOPING", "UPDATE" -> Tone.Primary
        else -> Tone.Neutral
    }

    /** Stable label order: warnings first, then content type, then status. */
    fun labels(labels: Set<String>): List<String> = listOf("CORRECTION", "CONFLICTING", "UNVERIFIED_SOCIAL", "SINGLE_SOURCE", "OPINION", "ANALYSIS",
        "SATIRE", "ADVERTISEMENT", "DEVELOPING", "UPDATE", "RESOLVED", "TRANSLATED").filter { it in labels }

    fun contentTypeLabel(type: String): String = when (type) {
        "OPINION" -> "Opinion"; "ANALYSIS" -> "Analysis"; "SATIRE" -> "Satire"; "ADVERTISEMENT" -> "Ad"
        "SOCIAL_POST" -> "Social post"; "OFFICIAL_STATEMENT" -> "Official statement"; else -> "News"
    }

    fun classificationLabel(c: String): String = when (c) {
        "NEW" -> "First report"; "MAJOR" -> "Major update"; "MINOR" -> "Minor update"; "CORRECTION" -> "Correction"; "RESOLVED" -> "Resolved"; else -> c
    }

    fun sourceStateLabel(state: String): String = when (state) {
        "ACTIVE" -> "Active"; "MUTED" -> "Muted"; "NEEDS_FEED" -> "Needs feed"; "NEEDS_KEY" -> "Needs X key"
        "NEEDS_LICENSE" -> "Needs licence"; "REMOVED" -> "Removed"; else -> state
    }

    fun sourceStateTone(state: String): Tone = when (state) {
        "ACTIVE" -> Tone.Success; "MUTED", "REMOVED" -> Tone.Neutral; else -> Tone.Warning
    }

    fun categoryLabel(category: String): String = when (category) {
        "TANZANIA" -> "Tanzania"; "EAST_AFRICA" -> "East Africa"; "INTERNATIONAL" -> "International"
        "TECHNOLOGY" -> "Technology"; "SOCIAL" -> "Social (X)"; "CUSTOM" -> "Custom"; else -> category
    }

    /** "ev topics" key → readable label, e.g. east_africa → East Africa. */
    fun topicLabel(key: String): String = topics.firstOrNull { it.first == "topic:$key" }?.second
        ?: key.replace('_', ' ').replaceFirstChar { it.uppercase() }

    /** Only links a browser or publisher app can open; never javascript:, intent: or file: URLs. */
    fun openable(url: String): Boolean = runCatching {
        val u = URI(url.trim())
        u.scheme?.lowercase() in setOf("https", "http") && !u.host.isNullOrBlank()
    }.getOrDefault(false)

    /** Text for Android's share sheet: headline, quick summary, links to the original reporting. No files. */
    fun shareText(summary: StorySummary, fallbackHeadline: String = ""): String = buildString {
        appendLine(summary.headline.ifBlank { fallbackHeadline })
        summary.quick.filter { it.isNotBlank() }.forEach { appendLine("• $it") }
        val links = summary.sources.filter { openable(it.url) }.distinctBy { it.url }.take(5)
        if (links.isNotEmpty()) {
            appendLine()
            appendLine("Sources:")
            links.forEach { appendLine("${it.publisher}: ${it.url}") }
        }
        appendLine()
        append("via HellFire Ass")
    }

    /** First http(s) link in shared text, for "Check against news". */
    fun firstUrl(text: String): String? = Regex("https?://[^\\s<>\"]+").find(text)?.value?.trimEnd('.', ',', ')', ';')?.takeIf(::openable)

    /** micro-USD → "$0.0123". */
    fun money(microUsd: Long): String = "$" + String.format(java.util.Locale.ROOT, "%.4f", microUsd / 1_000_000.0)

    /** Weights as whole percentages of their sum, in the order impact, relevance, recency, corroboration, reliability, novelty. */
    fun percentages(w: ScoreWeights): List<Int> {
        val values = listOf(w.impact, w.relevance, w.recency, w.corroboration, w.reliability, w.novelty).map { it.coerceAtLeast(0.0) }
        val sum = values.sum()
        return if (sum <= 0) values.map { 0 } else values.map { Math.round(it / sum * 100).toInt() }
    }

    val weightNames = listOf("Impact", "Relevance to you", "Recency", "Corroboration", "Source reliability", "Novelty")

    fun validTime(value: String): Boolean = Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(value.trim()) &&
        runCatching { LocalTime.parse(value.trim()) }.isSuccess

    fun validZone(value: String): Boolean = value.isNotBlank() && runCatching { ZoneId.of(value.trim()) }.isSuccess

    /** Archive date chips → [from, to) in [zone]. */
    fun range(chip: String, now: Long, zone: ZoneId): Pair<Long?, Long?> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun start(day: LocalDate) = day.atStartOfDay(zone).toInstant().toEpochMilli()
        return when (chip) {
            "Today" -> start(today) to null
            "This week" -> start(today.minusDays(6)) to null
            "30 days" -> start(today.minusDays(29)) to null
            else -> null to null
        }
    }

    /** Morning list holds MORNING plus custom/on-demand briefings made before noon; Evening the rest. */
    fun isMorning(b: NewsBriefing, zone: ZoneId): Boolean = when (b.slot) {
        "MORNING" -> true
        "EVENING" -> false
        else -> Instant.ofEpochMilli(b.generatedAt).atZone(zone).hour < 12
    }

    /** Number of items in a briefing manifest without a JSON parser (counts "eventId" keys). */
    fun itemCount(itemsJson: String): Int = Regex("\"eventId\"\\s*:").findAll(itemsJson).count()

    fun stamp(millis: Long, zone: ZoneId): String = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm").format(Instant.ofEpochMilli(millis).atZone(zone))

    /** Summary request came back in another language: translation needs AI summaries. */
    fun translationNote(requested: String, got: String): String? =
        if (requested == got) null else "Translation needs AI summaries turned on in News settings; showing the original language."

    fun nextSpeed(current: Float, steps: List<Float>): Float = steps.firstOrNull { it > current + 0.01f } ?: steps.first()
}
