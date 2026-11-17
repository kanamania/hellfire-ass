package com.hellfire.ass.processing

import com.hellfire.ass.data.EventDao
import com.hellfire.ass.security.PayloadCipher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

class EventProcessor(private val dao: EventDao,
    private val collectFinance: suspend (String, Long, String, String) -> Unit = { _, _, _, _ -> },
    private val collectTask: suspend (String, Long, String, Boolean) -> Unit = { _, _, _, _ -> },
    private val cipher: () -> PayloadCipher) {
    suspend fun drain() {
        while (true) {
            val batch = dao.pending()
            if (batch.isEmpty()) return
            for (event in batch) {
                currentCoroutineContext().ensureActive()
                val enabled = event.source != "android_notification" || dao.processEnabled(event.packageName) != false
                if (!enabled) {
                    dao.finish(event.id, "CAPTURE_ONLY", null, null, System.currentTimeMillis())
                    continue
                }
                try {
                    val label = when {
                        event.securitySensitive -> "SECURITY_REDACTED"
                        !event.contentAvailable -> "CONTENT_UNAVAILABLE"
                        // The removal is retained as evidence; its content was already processed when posted.
                        event.kind == "REMOVED" -> "REMOVAL_RECORD"
                        else -> {
                            val payload = JSONObject(cipher().decrypt(event.encryptedPayload).toString(Charsets.UTF_8))
                            val fields = payload.getJSONObject("fields")
                            val text = listOf("title", "text", "big_text", "text_lines", "sub_text", "messages")
                                .flatMap { name -> fields.optJSONArray(name)?.let { array ->
                                    (0 until array.length()).map { array.optString(it) }
                                }.orEmpty() }.joinToString("\n")
                            val label = RuleClassifier.classify(text, fields.optJSONArray("category")?.optString(0), event.source)
                            if (event.source !in setOf("owner_share", "owner_memory", "owner_skill_use") && label == "FINANCE_CANDIDATE") collectFinance(event.id, event.occurredAt,
                                fields.optJSONArray("sender_hint")?.join(" ").orEmpty() + " " + event.packageName, text.replace("\"", ""))
                            if (event.source !in setOf("owner_share", "owner_memory", "owner_skill_use") && label != "PROMOTION_CANDIDATE" && label != "SYSTEM" &&
                                payload.optJSONObject("provenance")?.optString("recorded_time_known") != "false" && event.source != "media_summary")
                                collectTask(event.id, event.occurredAt, text, event.kind == "OUTGOING")
                            label
                        }
                    }
                    dao.finish(event.id, "DONE", label, null, System.currentTimeMillis())
                } catch (e: CancellationException) { throw e }
                catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught);
                    dao.finish(event.id, "FAILED", null, "PAYLOAD_OR_KEY_UNAVAILABLE", System.currentTimeMillis())
                }
            }
        }
    }
}
