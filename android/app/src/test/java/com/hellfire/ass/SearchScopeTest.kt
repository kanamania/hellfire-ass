package com.hellfire.ass

import android.app.Application
import androidx.room.Room
import com.hellfire.ass.assistant.SearchScope
import com.hellfire.ass.contacts.PersonContact
import com.hellfire.ass.data.EventDatabase
import com.hellfire.ass.data.SourceEvent
import com.hellfire.ass.insights.PersonAlias
import com.hellfire.ass.tasks.TaskTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SearchScopeTest {
    private val now = Instant.parse("2026-11-26T07:00:00Z")

    @Test fun `month names become the most recent such month`() {
        val sept = SearchScope.month("what did Asha and I agree about the invoice in September?", now)!!
        assertEquals("2026-09", sept.label)
        assertEquals("2026-09-01 00:00", TaskTime.display(sept.start))
        assertEquals("2026-10-01 00:00", TaskTime.display(sept.end))
        assertEquals("2025-12", SearchScope.month("tulikubaliana nini desemba", now)!!.label)
        assertEquals("2024-03", SearchScope.month("invoice march 2024", now)!!.label)
        assertNull(SearchScope.month("may I see the invoice", now))
        assertEquals("2026-05", SearchScope.month("what happened in may", now)!!.label)
    }
    @Test fun `a person's name expands to their saved numbers and addresses`() {
        val aliases = listOf(PersonAlias("tel:712345678", "Asha Mwita", 1, "person:asha"), PersonAlias("mail:asha@example.com", "Asha M", 1, "person:asha"),
            PersonAlias("tel:754000111", "Juma", 1, "person:juma"))
        val contacts = listOf(PersonContact("person:asha", null, "Asha Mwita", 1))
        val asha = SearchScope.person(listOf("agree", "asha", "invoice"), aliases, contacts)!!
        assertEquals("asha", asha.word)
        for (term in listOf("255712345678", "0712345678", "712345678", "\"asha example com\"")) assertTrue(term, asha.clause.contains(term))
        assertFalse(asha.clause.contains("754000111"))
        assertNull(SearchScope.person(listOf("invoice"), aliases, contacts))
    }
    @Test fun `person clause and month range find her September messages only`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), EventDatabase::class.java).build()
        try {
            fun event(id: String, at: String) = SourceEvent(id, externalId = id, packageName = "fixture", occurredAt = Instant.parse(at).toEpochMilli(),
                receivedAt = Instant.parse(at).toEpochMilli(), kind = "INCOMING", payloadHash = id, encryptedPayload = byteArrayOf(1),
                contentAvailable = true, contentRedacted = false, securitySensitive = false)
            db.events().insertCaptured(event("sept", "2026-09-10T08:00:00Z"), "+255712345678\nTumekubaliana invoice ya 400,000 ilipwe Ijumaa")
            db.events().insertCaptured(event("oct", "2026-10-10T08:00:00Z"), "255712345678@c.us\nInvoice imefika")
            db.events().insertCaptured(event("other", "2026-09-11T08:00:00Z"), "+255754000111\nInvoice ya Juma")
            val clause = SearchScope.person(listOf("asha"), listOf(PersonAlias("tel:712345678", "Asha", 1)), emptyList())!!.clause
            val sept = SearchScope.month("in september", now)!!
            assertEquals(listOf("sept"), db.events().searchBetween(clause, sept.start, sept.end, 100).map { it.eventId })
            assertEquals(setOf("sept", "oct"), db.events().search(clause, 100).map { it.eventId }.toSet())
        } finally { db.close() }
    }
}
