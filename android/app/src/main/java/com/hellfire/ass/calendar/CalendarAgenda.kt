package com.hellfire.ass.calendar

import java.time.*
import com.hellfire.ass.tasks.TaskTime

// Provider rows are transient. Only normalized, redacted source snapshots enter encrypted storage.
data class DeviceCalendar(val id: Long, val label: String)
data class CalendarEvent(val id: Long, val calendarId: Long, val title: String, val start: Long, val end: Long,
    val allDay: Boolean = false, val busy: Boolean = true, val sourceId: String? = null)
data class CalendarAgenda(val status: String, val events: List<CalendarEvent> = emptyList(), val truncated: Boolean = false) {
    val statusLabel: String get() = when (status) {
        "AVAILABLE" -> "available on this phone"
        "READ_PERMISSION_MISSING" -> "read permission missing"
        "NOT_CHECKED" -> "not checked"
        else -> "provider or encrypted storage unavailable"
    }
    fun text(includeSources: Boolean = false): String {
        val conflicts = CalendarAgendaLogic.conflicts(events)
        return "Calendar · $statusLabel" + (if (truncated) " · showing first 200 occurrences; more may exist" else "") +
            (if (status == "AVAILABLE" && events.isEmpty()) "\nNo appointments in this window." else "") +
            events.joinToString("") { "\n${CalendarAgendaLogic.whenText(it)} · ${it.title}" +
                (if (it in conflicts) " · OVERLAP" else "") + (if (includeSources) it.sourceId?.let { id -> " [$id]" }.orEmpty() else "") }
    }
}
object CalendarAgendaLogic {
    val question = Regex("(?i)\\b(calendar|appointments?|meetings?|schedule|kalenda|miadi|ratiba)\\b")
    /** Half-open ranges: touching appointments do not overlap. Free/all-day entries are not timed conflicts. */
    fun conflicts(events: List<CalendarEvent>): Set<CalendarEvent> = events.filter { it.busy && !it.allDay }.let { timed ->
        timed.filter { a -> timed.any { b -> a !== b && a.start < b.end && b.start < a.end } }.toSet()
    }
    fun whenText(e: CalendarEvent): String = if (e.allDay) "${Instant.ofEpochMilli(e.start).atZone(ZoneOffset.UTC).toLocalDate()} · all day"
        else "${TaskTime.display(e.start)} – ${Instant.ofEpochMilli(e.end).atZone(TaskTime.zone).toLocalTime()} EAT"
    fun inWindow(e: CalendarEvent, start: Long, end: Long): Boolean {
        if (!e.allDay) return e.start < end && e.end > start
        val first = Instant.ofEpochMilli(start).atZone(TaskTime.zone).toLocalDate()
        val last = Instant.ofEpochMilli(end - 1).atZone(TaskTime.zone).toLocalDate().plusDays(1)
        return Instant.ofEpochMilli(e.start).atZone(ZoneOffset.UTC).toLocalDate() < last &&
            Instant.ofEpochMilli(e.end).atZone(ZoneOffset.UTC).toLocalDate() > first
    }
}
