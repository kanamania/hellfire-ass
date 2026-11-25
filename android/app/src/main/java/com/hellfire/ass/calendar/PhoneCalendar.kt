package com.hellfire.ass.calendar

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.provider.CalendarContract.*
import androidx.core.content.ContextCompat
import com.hellfire.ass.events.NotificationNormalizer
import org.json.JSONArray

/** Injectable boundary; production always uses the permitted local Android provider. */
interface CalendarProvider {
    fun canRead(): Boolean
    fun canWrite(): Boolean
    fun calendars(): List<DeviceCalendar>
    fun instances(start: Long, end: Long): CalendarAgenda
    fun event(id: Long): ProviderEvent?
    fun find(marker: String): List<ProviderEvent>
    fun apply(change: CalendarChange): Long
}

data class ProviderEvent(val id: Long, val calendarId: Long, val title: String, val start: Long, val end: Long,
    val timezone: String, val description: String, val marker: String, val ownerPackage: String,
    val status: Int, val recurring: Boolean, val allDay: Boolean, val hasAttendees: Boolean) {
    val fingerprint: String get() = NotificationNormalizer.sha256(JSONArray(listOf(id, calendarId, title, start, end, timezone,
        description, marker, ownerPackage, status, recurring, allDay, hasAttendees)).toString())
}

class PhoneCalendar(private val context: Context) : CalendarProvider {
    private val resolver get() = context.contentResolver
    override fun canRead() = granted(Manifest.permission.READ_CALENDAR)
    override fun canWrite() = granted(Manifest.permission.WRITE_CALENDAR)
    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    override fun calendars(): List<DeviceCalendar> {
        check(canRead()) { "Read calendar permission required" }
        return resolver.query(Calendars.CONTENT_URI, arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME),
            "${Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${Calendars.VISIBLE} = ?", arrayOf(Calendars.CAL_ACCESS_CONTRIBUTOR.toString(), "1"), null)?.use { rows ->
            buildList { while (rows.moveToNext()) add(DeviceCalendar(rows.getLong(0), "${rows.getString(1).orEmpty()} · ${rows.getString(2).orEmpty()}")) }
        } ?: error("Calendar provider unavailable")
    }
    override fun instances(start: Long, end: Long): CalendarAgenda {
        if (!canRead()) return CalendarAgenda("READ_PERMISSION_MISSING")
        // Padding includes UTC-midnight all-day occurrences on either side of local midnight.
        val uri = Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, start - 86_400_000); ContentUris.appendId(it, end + 86_400_000) }.build()
        val projection = arrayOf(Instances.EVENT_ID, Instances.CALENDAR_ID, Instances.TITLE, Instances.BEGIN, Instances.END, Instances.ALL_DAY, Instances.AVAILABILITY)
        val selection = "${Instances.VISIBLE} = ? AND (${Instances.STATUS} IS NULL OR ${Instances.STATUS} != ?) AND (${Instances.SELF_ATTENDEE_STATUS} IS NULL OR ${Instances.SELF_ATTENDEE_STATUS} != ?)"
        return resolver.query(uri, projection, selection, arrayOf("1", Events.STATUS_CANCELED.toString(), Attendees.ATTENDEE_STATUS_DECLINED.toString()), "${Instances.BEGIN} ASC")?.use { rows ->
            val events = mutableListOf<CalendarEvent>()
            var more = false
            while (rows.moveToNext()) {
                val event = CalendarEvent(rows.getLong(0), rows.getLong(1), rows.getString(2).orEmpty().ifBlank { "Untitled event" },
                    rows.getLong(3), rows.getLong(4), rows.getInt(5) != 0, rows.getInt(6) != Events.AVAILABILITY_FREE)
                if (event.end <= event.start || !CalendarAgendaLogic.inWindow(event, start, end)) continue
                if (events.size == 200) { more = true; break }
                events += event
            }
            CalendarAgenda("AVAILABLE", events.distinctBy { it.id to it.start }, more)
        } ?: CalendarAgenda("PROVIDER_UNAVAILABLE")
    }
    private val projection = arrayOf(Events._ID, Events.CALENDAR_ID, Events.TITLE, Events.DTSTART, Events.DTEND, Events.EVENT_TIMEZONE,
        Events.DESCRIPTION, Events.CUSTOM_APP_URI, Events.CUSTOM_APP_PACKAGE, Events.STATUS, Events.RRULE, Events.RDATE, Events.ORIGINAL_ID, Events.ALL_DAY, Events.HAS_ATTENDEE_DATA)
    private fun query(selection: String, args: Array<String>): List<ProviderEvent> = resolver.query(Events.CONTENT_URI, projection, selection, args, null)?.use { rows ->
        buildList { while (rows.moveToNext()) add(ProviderEvent(rows.getLong(0), rows.getLong(1), rows.getString(2).orEmpty(), rows.getLong(3), rows.getLong(4),
            rows.getString(5).orEmpty(), rows.getString(6).orEmpty(), rows.getString(7).orEmpty(), rows.getString(8).orEmpty(), rows.getInt(9),
            !rows.getString(10).isNullOrEmpty() || !rows.getString(11).isNullOrEmpty() || !rows.isNull(12), rows.getInt(13) != 0, rows.getInt(14) != 0)) }
    } ?: error("Calendar provider unavailable")
    override fun event(id: Long): ProviderEvent? { check(canRead()); return query("${Events._ID} = ? AND ${Events.DELETED} = ?", arrayOf(id.toString(), "0")).singleOrNull() }
    override fun find(marker: String): List<ProviderEvent> { check(canRead()); return query("${Events.CUSTOM_APP_URI} = ? AND ${Events.CUSTOM_APP_PACKAGE} = ? AND ${Events.DELETED} = ?", arrayOf(marker, context.packageName, "0")) }
    override fun apply(change: CalendarChange): Long {
        check(canRead() && canWrite()) { "Read/write calendar permissions required" }
        require(calendars().any { it.id == change.calendarId }) { "Selected calendar is no longer writable" }
        val values = ContentValues().apply {
            put(Events.TITLE, change.title); put(Events.DTSTART, change.start); put(Events.DTEND, change.end)
            put(Events.EVENT_TIMEZONE, change.timezone)
        }
        if (change.operation == "CREATE") {
            require(find(change.marker).isEmpty()) { "Event already exists; check its result" }
            values.put(Events.CALENDAR_ID, change.calendarId)
            values.put(Events.DESCRIPTION, "HellFire Ass source: hellfire://source/${change.sourceId}\nTask: hellfire://task/${change.taskId}")
            values.put(Events.CUSTOM_APP_PACKAGE, context.packageName); values.put(Events.CUSTOM_APP_URI, change.marker)
            values.put(Events.STATUS, Events.STATUS_CONFIRMED)
            return ContentUris.parseId(requireNotNull(resolver.insert(Events.CONTENT_URI, values)) { "Provider refused event" })
        }
        val old = requireNotNull(event(requireNotNull(change.eventId))) { "Event was removed" }
        require(old.fingerprint == change.expectedFingerprint && old.ownerPackage == context.packageName && old.marker == change.marker &&
            !old.recurring && !old.allDay && !old.hasAttendees) { "Event changed outside HellFire; review it again" }
        if (change.operation == "CANCEL") { values.clear(); values.put(Events.STATUS, Events.STATUS_CANCELED) }
        else require(change.operation == "UPDATE")
        // Compare writable fields again inside the provider update to avoid overwriting a concurrent edit.
        val selection = "${Events.CALENDAR_ID}=? AND ${Events.TITLE}=? AND ${Events.DTSTART}=? AND ${Events.DTEND}=? AND ${Events.EVENT_TIMEZONE}=? AND ${Events.DESCRIPTION}=? AND ${Events.CUSTOM_APP_URI}=? AND ${Events.CUSTOM_APP_PACKAGE}=? AND ${Events.STATUS}=? AND ${Events.DELETED}=0 AND ${Events.ALL_DAY}=0 AND ${Events.HAS_ATTENDEE_DATA}=0 AND ${Events.RRULE} IS NULL AND ${Events.RDATE} IS NULL AND ${Events.ORIGINAL_ID} IS NULL"
        val args = arrayOf(old.calendarId.toString(), old.title, old.start.toString(), old.end.toString(), old.timezone, old.description, old.marker, old.ownerPackage, old.status.toString())
        require(resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, old.id), values, selection, args) == 1) { "Provider refused or event changed; check calendar" }
        return old.id
    }
}
