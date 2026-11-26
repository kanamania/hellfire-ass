package com.hellfire.ass

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.hellfire.ass.assistant.AssistantCommands
import com.hellfire.ass.assistant.OwnerCommand
import com.hellfire.ass.data.EventDatabase
import com.hellfire.ass.security.PayloadCipher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OwnerCommandSafetyTest {
    private lateinit var db: EventDatabase
    private lateinit var app: HellFireApp

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, EventDatabase::class.java).build()
        app = HellFireApp()
        ReflectionHelpers.callInstanceMethod<Unit>(app, "attach", ClassParameter.from(Context::class.java, context))
        ReflectionHelpers.setField(app, "database\$delegate", lazyOf(db))
        ReflectionHelpers.setField(app, "cipher\$delegate", lazyOf(PayloadCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())))
    }
    @After fun close() = db.close()

    @Test fun `confirming the same proposal twice applies it once`() = runBlocking {
        val now = System.currentTimeMillis()
        val cash = OwnerCommand.Cash("log cash maji 1500", listOf("maji" to 1500L), now)
        AssistantCommands.execute(app, cash)
        assertEquals("Already applied.", AssistantCommands.execute(app, cash))
        assertEquals(1, db.finances().allCandidates().size)
        // Asking again is a new proposal and applies.
        AssistantCommands.execute(app, OwnerCommand.Cash("log cash maji 1500", listOf("maji" to 1500L), now + 1))
        assertEquals(2, db.finances().allCandidates().size)
    }
    @Test fun `a reminder confirmed after its time is refused and can be retried`() = runBlocking {
        val late = OwnerCommand.Remind("remind me to pay rent today 06:00", "pay rent", System.currentTimeMillis() - 3_600_000)
        assertTrue(runCatching { AssistantCommands.execute(app, late) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { AssistantCommands.execute(app, late) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(db.tasks().all().isEmpty())
    }
}
