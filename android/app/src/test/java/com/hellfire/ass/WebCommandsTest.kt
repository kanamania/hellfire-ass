package com.hellfire.ass

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.hellfire.ass.assistant.AssistantCommands
import com.hellfire.ass.assistant.OwnerCommand
import com.hellfire.ass.data.EventDatabase
import com.hellfire.ass.security.PayloadCipher
import com.hellfire.ass.sync.WebCommands
import com.hellfire.ass.sync.WebSync
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
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
class WebCommandsTest {
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

    @Test fun `the phone applies valid web commands and reports stale ones`() = runBlocking {
        AssistantCommands.execute(app, OwnerCommand.Remind("remind me to pay rent tomorrow", "pay rent", System.currentTimeMillis() + 86_400_000))
        AssistantCommands.execute(app, OwnerCommand.Cash("log cash maji 1500", listOf("maji" to 1500L), System.currentTimeMillis()))
        val task = db.tasks().all().single()
        val candidate = db.finances().allCandidates().single()
        val posted = mutableListOf<JSONObject>()
        val server = WebSync.Call { method, path, body ->
            when {
                method == "GET" && path.startsWith("/api/sync/commands") -> JSONObject().put("commands", JSONArray(listOf(
                    JSONObject().put("seq", 1).put("kind", "task.done").put("target", task.id),
                    JSONObject().put("seq", 2).put("kind", "candidate.dismiss").put("target", candidate.id),
                    JSONObject().put("seq", 3).put("kind", "task.snooze").put("target", task.id))))
                path == "/api/sync/commands/results" -> { posted += body!!; JSONObject().put("updated", 3) }
                else -> error("unexpected $path")
            }
        }
        assertEquals(2, WebCommands.sync(app, server))
        assertEquals("DONE", db.tasks().task(task.id)!!.state)
        assertEquals("REJECTED", db.finances().findCandidate(candidate.id)!!.state)
        val results = posted.single().getJSONArray("results")
        assertEquals(listOf("APPLIED", "APPLIED", "STALE"), (0 until results.length()).map { results.getJSONObject(it).getString("status") })
        assertEquals("Task is done on the phone", results.getJSONObject(2).getString("result"))
        // A server without commands is skipped.
        assertEquals(0, WebCommands.sync(app) { _, _, _ -> throw WebSync.Rejected(404) })
    }
}
