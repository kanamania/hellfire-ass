package com.hellfire.ass

import android.Manifest
import android.app.Application
import android.content.*
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.CalendarContract.*
import com.hellfire.ass.calendar.*
import com.hellfire.ass.tasks.TaskTime
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** Executes the production CalendarContract adapter against a synthetic local ContentProvider. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PhoneCalendarTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var provider: SyntheticCalendarProvider
    private lateinit var phone: PhoneCalendar
    private val start = TaskTime.parse("2026-11-25 00:00")
    @Before fun setup() {
        provider = SyntheticCalendarProvider().apply { onCreate() }
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider)
        phone = PhoneCalendar(app)
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        provider.db.execSQL("INSERT INTO calendars VALUES(7,'Synthetic calendar','local fixture',700,1)")
    }
    @After fun close() { provider.db.close() }
    private fun change(op: String = "CREATE", event: ProviderEvent? = null) = CalendarChange("request", "task", "source", 1, op, 7,
        "Synthetic calendar · local fixture", event?.id, "hellfire://calendar/request", event?.fingerprint, "Meeting fixture", start + 1000, start + 5000,
        "Africa/Dar_es_Salaam", start)

    @Test fun `calendar provenance alias accepts read only links and strips untrusted extras`() {
        val id = "a".repeat(64)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("hellfire://task/$id?approval=forged"))
            .setComponent(ComponentName(app.packageName, "com.hellfire.ass.CalendarEntry"))
        assertEquals("hellfire://task/$id", CalendarLinks.external(intent).toString())
        for (bad in listOf("hellfire://ask/$id", "hellfire://task/bad", "hellfire://source/$id/extra", "https://task/$id")) {
            assertNull(CalendarLinks.external(Intent(intent).setData(Uri.parse(bad))))
        }
        assertNull(CalendarLinks.external(Intent(intent).setAction(Intent.ACTION_SEND)))
        assertNull(CalendarLinks.external(Intent(intent).setComponent(ComponentName(app.packageName, "com.hellfire.ass.Launcher"))))
    }
    @Test fun `missing permissions perform no read and cannot write`() {
        shadowOf(app).denyPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        assertEquals("READ_PERMISSION_MISSING", phone.instances(start, start + 86_400_000).status)
        assertThrows(IllegalStateException::class.java) { phone.apply(change()) }
        assertEquals(0, provider.calls)
    }
    @Test fun `only visible writable calendars are offered and null provider is unavailable`() {
        provider.db.execSQL("INSERT INTO calendars VALUES(8,'Read only','fixture',200,1),(9,'Hidden','fixture',700,0)")
        assertEquals(listOf(7L), phone.calendars().map { it.id })
        provider.unavailable = true
        assertEquals("PROVIDER_UNAVAILABLE", phone.instances(start, start + 86_400_000).status)
        assertThrows(IllegalStateException::class.java) { phone.calendars() }
    }
    @Test fun `instances expand supplied recurring occurrences and exclude canceled declined hidden events`() {
        provider.instance(1, "first", start + 1000, start + 5000)
        provider.instance(1, "second occurrence", start + 10_000, start + 15_000)
        provider.instance(2, "canceled", start + 1000, start + 5000, status = Events.STATUS_CANCELED)
        provider.instance(3, "declined", start + 1000, start + 5000, attendee = Attendees.ATTENDEE_STATUS_DECLINED)
        provider.instance(4, "hidden", start + 1000, start + 5000, visible = 0)
        provider.instance(5, "free", start + 1000, start + 5000, availability = Events.AVAILABILITY_FREE)
        provider.instance(6, "unspecified status", start + 1000, start + 5000)
        provider.db.execSQL("UPDATE instances SET eventStatus=NULL WHERE event_id=6")
        val agenda = phone.instances(start, start + 86_400_000)
        assertEquals(setOf("first", "second occurrence", "free", "unspecified status"), agenda.events.map { it.title }.toSet())
        assertEquals(2, agenda.events.count { it.id == 1L })
        assertFalse(agenda.events.single { it.id == 5L }.busy)
        assertEquals(listOf("instances", "when", (start - 86_400_000).toString(), (start + 2 * 86_400_000).toString()), provider.instancePath)
    }
    @Test fun `bounded occurrence results disclose truncation`() {
        repeat(201) { provider.instance(it.toLong(), "fixture $it", start + it + 1, start + it + 1000) }
        val agenda = phone.instances(start, start + 86_400_000)
        assertEquals(200, agenda.events.size); assertTrue(agenda.truncated)
    }
    @Test fun `create edit and cancel call real provider URIs and preserve provenance`() {
        val c = change()
        val id = phone.apply(c)
        val created = phone.event(id)!!
        assertTrue(created.description.contains("hellfire://source/source"))
        assertEquals(app.packageName, created.ownerPackage)
        assertEquals(listOf(id), phone.find(c.marker).map { it.id })
        assertThrows(IllegalArgumentException::class.java) { phone.apply(c) }
        phone.apply(change("UPDATE", created).copy(title = "Approved edit"))
        assertEquals("Approved edit", phone.event(id)!!.title)
        phone.apply(change("CANCEL", phone.event(id)))
        assertEquals(Events.STATUS_CANCELED, phone.event(id)!!.status)
        assertEquals(3, provider.writes)
    }
    @Test fun `concurrent edits recurrence and attendee additions cannot be overwritten`() {
        val id = phone.apply(change())
        val before = phone.event(id)!!
        provider.db.execSQL("UPDATE events SET title='External edit' WHERE _id=?", arrayOf(id))
        assertThrows(IllegalArgumentException::class.java) { phone.apply(change("UPDATE", before)) }
        provider.db.execSQL("UPDATE events SET rrule='FREQ=DAILY' WHERE _id=?", arrayOf(id))
        assertThrows(IllegalArgumentException::class.java) { phone.apply(change("CANCEL", phone.event(id))) }
        provider.db.execSQL("UPDATE events SET rrule=NULL, hasAttendeeData=1 WHERE _id=?", arrayOf(id))
        assertThrows(IllegalArgumentException::class.java) { phone.apply(change("CANCEL", phone.event(id))) }
        assertEquals(1, provider.writes)
    }
    @Test fun `permission revocation during query throws rather than reporting empty calendar`() {
        provider.denied = true
        assertThrows(SecurityException::class.java) { phone.instances(start, start + 86_400_000) }
    }

    private class SyntheticCalendarProvider : ContentProvider() {
        lateinit var db: SQLiteDatabase
        var calls = 0
        var writes = 0
        var unavailable = false
        var denied = false
        var instancePath: List<String>? = null
        override fun onCreate(): Boolean {
            db = SQLiteDatabase.create(null)
            db.execSQL("CREATE TABLE calendars(_id INTEGER PRIMARY KEY, calendar_displayName TEXT, account_name TEXT, calendar_access_level INTEGER, visible INTEGER)")
            db.execSQL("CREATE TABLE events(_id INTEGER PRIMARY KEY AUTOINCREMENT, calendar_id INTEGER, title TEXT, dtstart INTEGER, dtend INTEGER, eventTimezone TEXT, description TEXT, customAppUri TEXT, customAppPackage TEXT, eventStatus INTEGER, rrule TEXT, rdate TEXT, original_id INTEGER, allDay INTEGER DEFAULT 0, hasAttendeeData INTEGER DEFAULT 0, deleted INTEGER DEFAULT 0)")
            db.execSQL("CREATE TABLE instances(event_id INTEGER, calendar_id INTEGER, title TEXT, begin INTEGER, end INTEGER, allDay INTEGER, availability INTEGER, visible INTEGER, eventStatus INTEGER, selfAttendeeStatus INTEGER)")
            return true
        }
        fun instance(id: Long, title: String, begin: Long, end: Long, status: Int = Events.STATUS_CONFIRMED, attendee: Int = Attendees.ATTENDEE_STATUS_ACCEPTED, visible: Int = 1, availability: Int = Events.AVAILABILITY_BUSY) {
            db.execSQL("INSERT INTO instances VALUES(?,7,?,?,?,0,?,?,?,?)", arrayOf<Any>(id, title, begin, end, availability, visible, status, attendee))
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
            calls++
            if (denied) throw SecurityException("Synthetic revoked access")
            if (unavailable) return null
            val table = uri.pathSegments.first()
            if (table == "instances") instancePath = uri.pathSegments
            return db.query(table, projection, selection, selectionArgs, null, null, sortOrder)
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            writes++
            return ContentUris.withAppendedId(uri, db.insertOrThrow(uri.pathSegments.first(), null, values))
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            val count = db.update(uri.pathSegments.first(), values, "_id=? AND ($selection)", arrayOf(uri.lastPathSegment!!) + selectionArgs.orEmpty())
            if (count > 0) writes++
            return count
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("No physical deletion expected")
        override fun getType(uri: Uri): String? = null
    }
}
