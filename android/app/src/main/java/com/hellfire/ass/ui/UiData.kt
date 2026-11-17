package com.hellfire.ass.ui

import com.hellfire.ass.HellFireApp
import com.hellfire.ass.data.SourceEvent
import com.hellfire.ass.tasks.TaskTime
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Display formatting shared by the redesigned screens. All times are East Africa Time. */
object Fmt {
    const val MINUS = "−"
    fun amount(value: Long): String = String.format(Locale.ENGLISH, "%,d", kotlin.math.abs(value)).let { if (value < 0) MINUS + it else it }
    fun signed(value: Long): String = if (value > 0) "+" + amount(value) else amount(value)
    /** Expense shown as a negative figure, e.g. −12,000. */
    fun outflow(value: Long): String = if (value == 0L) "0" else MINUS + amount(kotlin.math.abs(value))
    fun compact(value: Long): String {
        val v = kotlin.math.abs(value)
        val text = when {
            v >= 1_000_000 -> String.format(Locale.ENGLISH, "%.2fM", v / 1_000_000.0).replace(".00M", "M")
            v >= 10_000 -> "${v / 1000}k"
            v >= 1000 -> String.format(Locale.ENGLISH, "%.1fk", v / 1000.0).replace(".0k", "k")
            else -> v.toString()
        }
        return if (value < 0) MINUS + text else text
    }
    fun zoned(millis: Long): ZonedDateTime = Instant.ofEpochMilli(millis).atZone(TaskTime.zone)
    fun today(): LocalDate = LocalDate.now(TaskTime.zone)
    fun startOf(day: LocalDate): Long = day.atStartOfDay(TaskTime.zone).toInstant().toEpochMilli()
    fun time(millis: Long): String = DateTimeFormatter.ofPattern("HH:mm", UiLanguage.locale).format(zoned(millis))
    fun day(millis: Long): String = DateTimeFormatter.ofPattern("d MMM", UiLanguage.locale).format(zoned(millis))
    fun dayTime(millis: Long): String = DateTimeFormatter.ofPattern("d MMM, HH:mm", UiLanguage.locale).format(zoned(millis))
    fun weekdayDayTime(millis: Long): String = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", UiLanguage.locale).format(zoned(millis))
    fun full(millis: Long): String = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", UiLanguage.locale).format(zoned(millis))
    /** "13:10" today, "Yesterday", otherwise "30 Oct". */
    fun relativeDay(millis: Long): String {
        val d = zoned(millis).toLocalDate(); val t = today()
        return when (d) { t -> time(millis); t.minusDays(1) -> "Yesterday"; else -> day(millis) }
    }
    /** Short age like "2h", "5m", "1d". */
    fun age(millis: Long, now: Long = System.currentTimeMillis()): String {
        val m = ((now - millis) / 60_000).coerceAtLeast(0)
        return when { m < 60 -> "${m}m"; m < 24 * 60 -> "${m / 60}h"; else -> "${m / (24 * 60)}d" }
    }
    fun kind(code: String): String = when (code) {
        "EXPENSE" -> "Expense"; "INCOME" -> "Income"; "OWN_TRANSFER" -> "Transfer"; "REFUND" -> "Refund"; "FEE" -> "Fee"
        "ADJUSTMENT" -> "Correction"; "BALANCE" -> "Balance"; else -> code.lowercase().replaceFirstChar { it.uppercase() }
    }
    fun channel(code: String): String = when (code) {
        "EMAIL_IMAP" -> "Email"; "WHATSAPP_BUSINESS" -> "WhatsApp"; "WHATSAPP_WEB" -> "WhatsApp"; else -> code
    }
}

/** Decrypts captured payloads for display. Call off the main thread. */
object EventText {
    fun fields(app: HellFireApp, event: SourceEvent): Map<String, List<String>> = runCatching {
        if (!event.contentAvailable || event.securitySensitive) return emptyMap()
        val fields = JSONObject(app.cipher.decrypt(event.encryptedPayload).toString(Charsets.UTF_8)).getJSONObject("fields")
        fields.keys().asSequence().associateWith { key -> fields.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty() }
    }.getOrDefault(emptyMap())
    fun body(fields: Map<String, List<String>>): String =
        listOf("big_text", "text", "text_lines", "messages").firstNotNullOfOrNull { k -> fields[k]?.filter(String::isNotBlank)?.joinToString("\n")?.takeIf(String::isNotBlank) }.orEmpty()
    /** Raw, read-only source text for evidence cards. */
    fun raw(app: HellFireApp, event: SourceEvent): String = when {
        event.securitySensitive -> "Authentication content was redacted when this was captured."
        !event.contentAvailable -> "Android did not share the content of this notification."
        else -> fields(app, event).let { f -> listOf("title", "text", "big_text", "text_lines", "messages", "sub_text")
            .flatMap { f[it].orEmpty() }.filter(String::isNotBlank).distinct().joinToString("\n") }
    }
    fun sourceLabel(event: SourceEvent): String = when (event.source) {
        "android_sms" -> "SMS"; "android_notification" -> "Notification"; "email_imap" -> "Email"
        "whatsapp_web", "whatsapp_business" -> "WhatsApp"; "owner_entry" -> "Your entry"; "owner_memory" -> "Your note"
        "call_recording_import" -> "Call recording"; "in_person_audio" -> "Conversation"; "media_import" -> "Media"
        "owner_skill_use" -> "Skill application"; "synthetic_notification" -> "Test event"; else -> event.source
    }
}
