package com.hellfire.ass.processing

import com.hellfire.ass.data.EventDao
import com.hellfire.ass.security.PayloadCipher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

class EventProcessor(private val dao: EventDao, private val cipher: () -> PayloadCipher) {
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
                        else -> {
                            val fields = JSONObject(cipher().decrypt(event.encryptedPayload).toString(Charsets.UTF_8)).getJSONObject("fields")
                            val text = listOf("title", "text", "big_text", "text_lines", "sub_text", "messages")
                                .joinToString("\n") { fields.optJSONArray(it)?.join(" ").orEmpty() }
                            RuleClassifier.classify(text, fields.optJSONArray("category")?.optString(0), event.source)
                        }
                    }
                    dao.finish(event.id, "DONE", label, null, System.currentTimeMillis())
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    dao.finish(event.id, "FAILED", null, "PAYLOAD_OR_KEY_UNAVAILABLE", System.currentTimeMillis())
                }
            }
        }
    }
}
