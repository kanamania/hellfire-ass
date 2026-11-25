package com.hellfire.ass.calendar

import androidx.room.withTransaction
import com.hellfire.ass.communications.ActionAudit
import com.hellfire.ass.data.EventDatabase
import com.hellfire.ass.data.EventRepository
import com.hellfire.ass.events.NotificationNormalizer
import com.hellfire.ass.events.NotificationSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import java.util.UUID

class CalendarRepository(private val db: EventDatabase, private val events: EventRepository,
    private val provider: CalendarProvider, private val packageName: String, private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = Mutex()
    private val dao get() = db.calendar()
    suspend fun recover() = db.withTransaction { dao.revokeApprovals(); dao.interrupt() }
    suspend fun calendars(): List<DeviceCalendar> = withContext(Dispatchers.IO) { provider.calendars() }
    suspend fun agenda(start: Long, end: Long): CalendarAgenda = withContext(Dispatchers.IO) {
        require(start >= 0 && end > start && end - start <= 32 * 86_400_000L)
        try {
            val agenda = provider.instances(start, end)
            agenda.copy(events = agenda.events.map { event ->
                val raw = NotificationSnapshot("${event.calendarId}:${event.id}:${event.start}", "android.calendar", event.start, clock(),
                    source = "device_calendar", kind = "CALENDAR_INSTANCE", fields = mapOf("title" to listOf(event.title),
                        "text" to listOf(CalendarAgendaLogic.whenText(event))),
                    provenance = mapOf("calendar_id" to event.calendarId.toString(), "event_id" to event.id.toString(), "end" to event.end.toString(), "all_day" to event.allDay.toString(), "busy" to event.busy.toString()))
                val source = requireNotNull(events.store(raw, packageName))
                val safe = requireNotNull(NotificationNormalizer.normalize(raw, packageName))
                event.copy(title = safe.snapshot.fields["title"]!!.single(), sourceId = source.id)
            })
        } catch (e: CancellationException) { throw e }
        catch (_: SecurityException) { CalendarAgenda("READ_PERMISSION_MISSING") }
        catch (_: Exception) { CalendarAgenda("PROVIDER_OR_STORAGE_UNAVAILABLE") }
    }
    suspend fun prepare(taskId: String, operation: String, calendarId: Long, title: String, start: Long, end: Long,
        timezone: String): CalendarChange = withContext(Dispatchers.IO) { lock.withLock {
        require(provider.canRead() && provider.canWrite()) { "Read/write calendar permissions required" }
        require(operation in setOf("CREATE", "UPDATE", "CANCEL"))
        require(title.isNotBlank() && title.length <= 200 && start > 0 && end > start && end - start <= 31 * 86_400_000L) { "Check title and event times" }
        ZoneId.of(timezone)
        require(dao.unresolved(taskId) == null) { "Previous result is unconfirmed. Check calendar result first." }
        val task = requireNotNull(db.tasks().task(taskId))
        require(task.state == "ACTIVE" || operation == "CANCEL") { "Confirm the task before adding or editing its calendar event" }
        val calendar = requireNotNull(provider.calendars().find { it.id == calendarId }) { "Choose a writable calendar" }
        val link = dao.link(taskId)
        val id = UUID.randomUUID().toString()
        val previous = if (operation == "CREATE") {
            require(link == null || link.state in setOf("CANCELED", "RESTORED")) { "Task already has a calendar event" }; null
        } else {
            require(link?.state == "LINKED" && link.calendarId == calendarId) { "No verified event link on this phone" }
            requireNotNull(provider.event(link.eventId)).also {
                require(it.ownerPackage == packageName && it.marker == link.marker && !it.recurring && !it.hasAttendees && !it.allDay) { "Event is no longer a HellFire one-off event; inspect it in your calendar app." }
            }
        }
        val change = CalendarChange(id, taskId, task.sourceId, task.updatedAt, operation, calendarId, calendar.label,
            previous?.id, previous?.marker ?: "hellfire://task/${task.id}?calendar_change=$id", previous?.fingerprint,
            if (operation == "CANCEL") previous!!.title else title.trim(),
            if (operation == "CANCEL") previous!!.start else start, if (operation == "CANCEL") previous!!.end else end,
            if (operation == "CANCEL") previous!!.timezone else timezone, clock())
        dao.insert(change)
        change
    } }
    /** Called only by the owner's confirmation control; immutable proposal, single-use nonce, five-minute expiry. */
    suspend fun approve(id: String): String = db.withTransaction {
        val token = UUID.randomUUID().toString()
        require(dao.approve(id, token, clock(), clock() - 300_000) == 1) { "Proposal expired or already approved; review again" }
        audit(id, "OWNER_APPROVAL", "APPROVED")
        token
    }
    suspend fun applyApproved(id: String, token: String): String = withContext(Dispatchers.IO) { lock.withLock {
        val change = requireNotNull(dao.change(id))
        db.withTransaction {
            require(dao.unresolved(change.taskId) == null) { "An earlier result needs checking" }
            require(dao.claim(id, token, clock() - 300_000) == 1) { "Fresh owner approval required" }
            audit(id, "CALENDAR_${change.operation}", "APPLYING")
        }
        var providerStarted = false
        try {
            require(provider.canRead() && provider.canWrite()) { "Calendar permission was revoked" }
            val task = requireNotNull(db.tasks().task(change.taskId))
            require(task.updatedAt == change.taskVersion && task.sourceId == change.sourceId && (task.state == "ACTIVE" || change.operation == "CANCEL")) { "Task changed; review a new proposal" }
            require(provider.calendars().any { it.id == change.calendarId && it.label == change.calendarLabel }) { "Calendar destination changed; review again" }
            val currentLink = dao.link(change.taskId)
            if (change.operation == "CREATE") require(currentLink == null || currentLink.state in setOf("CANCELED", "RESTORED")) { "Another approved proposal already created this event" }
            else require(currentLink?.state == "LINKED" && currentLink.eventId == change.eventId && currentLink.marker == change.marker) { "Calendar link changed; review again" }
            if (change.eventId != null) require(provider.event(change.eventId)?.fingerprint == change.expectedFingerprint) { "Calendar event changed; review again" }
            providerStarted = true
            val eventId = provider.apply(change)
            val result = requireNotNull(provider.event(eventId)) { "Provider result could not be read" }
            require(matches(change, result)) { "Provider result is unconfirmed" }
            complete(change, result)
            "APPLIED"
        } catch (e: CancellationException) {
            withContext(NonCancellable) { unknown(change) }; throw e
        } catch (_: Exception) {
            if (providerStarted) {
                unknown(change)
                "UNKNOWN · check calendar result before trying again"
            } else {
                db.withTransaction { dao.finish(change.id, "NOT_APPLIED", null, "REVIEW_REQUIRED"); audit(change.id, "CALENDAR_${change.operation}", "NOT_APPLIED") }
                "NOT_APPLIED · permissions, task or calendar changed; review a new proposal"
            }
        }
    } }
    /** Read-only recovery. Never repeats a provider write, even if a process died between insert and local commit. */
    suspend fun reconcile(taskId: String): String = withContext(Dispatchers.IO) { lock.withLock {
        val change = dao.unresolved(taskId) ?: return@withLock "No unconfirmed calendar action."
        val found = if (change.eventId != null) listOfNotNull(provider.event(change.eventId)) else provider.find(change.marker)
        if (found.size == 1 && matches(change, found.single())) {
            complete(change, found.single()); "Calendar result verified."
        } else if ((found.isEmpty() && change.operation == "CREATE") || (found.size == 1 && found.single().fingerprint == change.expectedFingerprint)) {
            // A completed query proves no matching insertion remains; a new request still needs owner approval.
            db.withTransaction { dao.finish(change.id, "NOT_APPLIED", null, null); audit(change.id, "CALENDAR_CHECK", "NOT_APPLIED") }
            "Change was not applied. Review a new proposal if needed."
        } else "Result remains unconfirmed. Inspect the event in your calendar; no write was retried."
    } }
    private fun matches(c: CalendarChange, e: ProviderEvent): Boolean = e.calendarId == c.calendarId && e.marker == c.marker &&
        e.ownerPackage == packageName && !e.recurring && !e.hasAttendees && !e.allDay &&
        if (c.operation == "CANCEL") e.status == android.provider.CalendarContract.Events.STATUS_CANCELED
        else e.title == c.title && e.start == c.start && e.end == c.end && e.timezone == c.timezone && e.status != android.provider.CalendarContract.Events.STATUS_CANCELED
    private suspend fun complete(c: CalendarChange, e: ProviderEvent) = db.withTransaction {
        dao.link(CalendarLink(c.taskId, e.calendarId, e.id, e.marker, e.fingerprint, if (c.operation == "CANCEL") "CANCELED" else "LINKED", clock()))
        dao.finish(c.id, "APPLIED", e.id, null); audit(c.id, "CALENDAR_${c.operation}", "APPLIED")
    }
    private suspend fun unknown(c: CalendarChange) = db.withTransaction {
        dao.finish(c.id, "UNKNOWN", null, "CHECK_PROVIDER_BEFORE_RETRY"); audit(c.id, "CALENDAR_${c.operation}", "UNKNOWN")
    }
    private suspend fun audit(id: String, action: String, state: String) {
        db.communications().audit(ActionAudit(UUID.randomUUID().toString(), id, action, state, clock()))
    }
}
