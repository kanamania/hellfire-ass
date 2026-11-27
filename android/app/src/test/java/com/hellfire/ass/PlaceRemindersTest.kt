package com.hellfire.ass

import com.hellfire.ass.assistant.AssistantCommands
import com.hellfire.ass.assistant.OwnerCommand
import com.hellfire.ass.library.SavedLocation
import com.hellfire.ass.tasks.AssistantTask
import com.hellfire.ass.tasks.Geofences
import com.hellfire.ass.tasks.TaskConditions
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PlaceRemindersTest {
    private val now = Instant.parse("2026-11-13T07:00:00Z")
    private val kariakoo = SavedLocation("loc-k", null, null, "Kariakoo market", latitude = -6.8160, longitude = 39.2803, createdAt = 1, updatedAt = 1)
    private val office = SavedLocation("loc-o", null, null, "Office", createdAt = 1, updatedAt = 1) // no map position

    @Test fun `when I get to a place needs no time`() {
        val en = AssistantCommands.parse("remind me to buy milk when I get to Kariakoo", now) as OwnerCommand.Remind
        assertEquals("buy milk", en.title); assertEquals("ARRIVE:kariakoo", en.condition)
        assertEquals(now.toEpochMilli() + 30L * 86_400_000L, en.dueAt) // armed for 30 days
        val sw = AssistantCommands.parse("nikumbushe kununua maziwa nikifika Kariakoo", now) as OwnerCommand.Remind
        assertEquals("kununua maziwa", sw.title); assertEquals("ARRIVE:kariakoo", sw.condition)
        assertTrue(en.summary.contains("when you arrive at Kariakoo"))
        assertTrue(TaskConditions.arrival("ARRIVED:loc-k|Kariakoo market"))
    }
    @Test fun `places need a saved map position and become geofences while armed`() {
        assertEquals("loc-k", Geofences.place(listOf(office, kariakoo), "kariakoo")!!.id)
        assertNull(Geofences.place(listOf(office), "office")) // saved, but no coordinates
        fun task(id: String, condition: String?, due: Long) = AssistantTask(id, "src", id, due, party = "OWNER", evidence = "x", state = "ACTIVE",
            createdAt = 1, updatedAt = 1, condition = condition)
        val fences = Geofences.fences(listOf(task("a", "ARRIVE:loc-k|Kariakoo market", now.toEpochMilli() + 1000),
            task("b", "ARRIVE:loc-k|Kariakoo market", now.toEpochMilli() - 1000), task("c", null, now.toEpochMilli() + 1000)),
            listOf(kariakoo), now.toEpochMilli())
        assertEquals(listOf("a"), fences.map { it.taskId })
        assertEquals(-6.8160, fences.single().latitude, 1e-9)
    }
}
