package com.hellfire.ass.assistant

import com.hellfire.ass.HellFireApp
import com.hellfire.ass.events.NotificationNormalizer
import com.hellfire.ass.events.NotificationSnapshot
import org.json.JSONArray
import org.json.JSONObject

/** Optional encrypted text history. Restored history never contains executable proposals or approval tokens. */
object AskHistory {
    const val KEY = "ask-history"
    fun enabled(app: HellFireApp) = app.getSharedPreferences("ui", 0).getBoolean(KEY, false)
    fun read(app: HellFireApp): List<Pair<String, String>> = if (!enabled(app)) emptyList() else
        app.secrets.read(KEY)?.optJSONArray("items")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).let { it.getString("q") to it.getString("a") } } }.orEmpty()
    fun append(app: HellFireApp, question: String, answer: String) {
        if (!enabled(app)) return
        fun safe(text: String): String? {
            val value = NotificationNormalizer.normalize(NotificationSnapshot("history", "owner.history", 0, 0, fields = mapOf("text" to listOf(text.take(12000)))), "hellfire")!!
            return if (value.securitySensitive) null else value.snapshot.fields["text"]?.joinToString("\n")
        }
        val q = safe(question) ?: return
        val a = safe(answer) ?: return
        val rows = (read(app) + (q to a)).takeLast(40)
        app.secrets.save(KEY, JSONObject().put("items", JSONArray(rows.map { JSONObject().put("q", it.first).put("a", it.second) })))
    }
}
