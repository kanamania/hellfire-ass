package com.hellfire.ass.ai

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.hellfire.ass.Diagnostics

class HttpRequest(val url: String, val headers: Map<String, String>, val body: String? = null) {
    override fun toString() = "HttpRequest([REDACTED])"
}
class HttpResponse(val status: Int, val body: String) {
    override fun toString() = "HttpResponse(status=$status, body=[REDACTED])"
}
fun interface AiTransport { suspend fun exchange(request: HttpRequest): HttpResponse }

class HttpsAiTransport : AiTransport {
    override suspend fun exchange(request: HttpRequest): HttpResponse = suspendCancellableCoroutine { continuation ->
        val connection = URL(request.url).openConnection() as HttpsURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15_000
        connection.readTimeout = 90_000
        connection.requestMethod = if (request.body == null) "GET" else "POST"
        request.headers.forEach(connection::setRequestProperty)
        continuation.invokeOnCancellation { connection.disconnect() }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (continuation.isActive) {
                    request.body?.let { body ->
                        connection.doOutput = true
                        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    }
                    val status = connection.responseCode
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    val body = stream?.use {
                        val bytes = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = it.read(buffer)
                            if (count < 0) break
                            if (bytes.size() + count > 2_000_000) throw AiException(AiError.INVALID_RESPONSE)
                            bytes.write(buffer, 0, count)
                        }
                        bytes.toString("UTF-8")
                    }.orEmpty()
                    continuation.resume(HttpResponse(status, body))
                }
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(e)
            } finally { connection.disconnect() }
        }
    }
}

abstract class CloudProvider(
    final override val id: ProviderId,
    private val baseUrl: String,
    private val transport: AiTransport,
) : AiProvider {
    protected open fun headers(key: String) = mapOf("Authorization" to "Bearer $key", "Content-Type" to "application/json")
    protected open fun quotaError(error: JSONObject?): Boolean = false
    protected suspend fun call(key: String, path: String, body: JSONObject? = null): JSONObject {
        if (key.isBlank()) throw AiException(AiError.MISSING_CREDENTIALS)
        if (key.any { it.isWhitespace() || it.isISOControl() }) throw AiException(AiError.UNAUTHORIZED)
        val response = try { transport.exchange(HttpRequest(baseUrl + path, headers(key), body?.toString())) }
        catch (e: CancellationException) { throw e }
        catch (e: AiException) { throw e }
        catch (_: IOException) { throw AiException(AiError.NETWORK) }
        catch (caught: Exception) { Diagnostics.warn(caught); throw AiException(AiError.INVALID_RESPONSE) }
        val json = runCatching { JSONObject(response.body) }.getOrNull()
        if (response.status !in 200..299) {
            val error = json?.optJSONObject("error")
            val code = error?.optString("code").orEmpty() + " " + error?.optString("type").orEmpty()
            throw AiException(when {
                response.status in setOf(401, 403) -> AiError.UNAUTHORIZED
                response.status == 402 || code.contains("quota", true) || code.contains("credit", true) || code.contains("balance", true) || quotaError(error) -> AiError.QUOTA
                response.status == 429 -> AiError.RATE_LIMIT
                response.status == 404 -> AiError.MODEL
                response.status in setOf(408, 504) -> AiError.NETWORK
                response.status >= 500 -> AiError.OUTAGE
                response.status in setOf(400, 422) -> AiError.INVALID_REQUEST
                else -> AiError.INVALID_RESPONSE
            })
        }
        return json ?: throw AiException(AiError.INVALID_RESPONSE)
    }
    override fun capabilities(model: String): Set<Capability> = if (textModel(model)) setOf(Capability.TEXT) else emptySet()
    protected open fun textModel(model: String) = model.isNotBlank()
    override suspend fun discover(apiKey: String): List<ModelInfo> {
        val models = mutableListOf<ModelInfo>()
        var path = "/models"
        val cursors = mutableSetOf<String>()
        do {
            val response = call(apiKey, path)
            val data = response.getJSONArray("data")
            for (i in 0 until data.length()) {
                val model = data.getJSONObject(i).getString("id")
                if (textModel(model)) models += ModelInfo(model, capabilities(model))
            }
            if (id != ProviderId.CLAUDE || !response.optBoolean("has_more")) break
            val cursor = response.optString("last_id")
            if (cursor.isBlank() || !cursors.add(cursor) || cursors.size > 100) throw AiException(AiError.INVALID_RESPONSE)
            path = "/models?after_id=" + URLEncoder.encode(cursor, "UTF-8")
        } while (true)
        return models.distinctBy { it.id }
    }
    final override suspend fun generate(apiKey: String, request: GenerationRequest): Generation {
        if (!capabilities(request.model).containsAll(request.required)) throw AiException(AiError.CAPABILITY)
        if (request.text.isBlank() || request.maxOutputTokens !in 1..4096) throw AiException(AiError.INVALID_REQUEST)
        return try { generateText(apiKey, request) }
        catch (e: CancellationException) { throw e }
        catch (e: AiException) { throw e }
        catch (caught: Exception) { Diagnostics.warn(caught); throw AiException(AiError.INVALID_RESPONSE) }
    }
    protected abstract suspend fun generateText(key: String, request: GenerationRequest): Generation
    protected fun usage(json: JSONObject?, input: String, output: String): TokenUsage? {
        if (json == null || !json.has(input) || !json.has(output)) return null
        val a = json.optLong(input, -1); val b = json.optLong(output, -1)
        return if (a in 0..1_000_000_000 && b in 0..1_000_000_000) TokenUsage(a, b) else null
    }
    protected fun nonempty(text: String): String = text.takeIf { it.isNotBlank() } ?: throw AiException(AiError.INVALID_RESPONSE)
}

abstract class ResponsesProvider(id: ProviderId, baseUrl: String, transport: AiTransport) : CloudProvider(id, baseUrl, transport) {
    override suspend fun generateText(key: String, request: GenerationRequest): Generation {
        val response = call(key, "/responses", JSONObject().put("model", request.model).put("input", request.text)
            .put("max_output_tokens", request.maxOutputTokens).put("stream", false).put("store", false))
        // "incomplete" because of the output limit still carries usable text; any other incomplete reason does not.
        val truncated = response.optString("status") == "incomplete" &&
            response.optJSONObject("incomplete_details")?.optString("reason") == "max_output_tokens"
        if (response.optString("status") != "completed" && !truncated) throw AiException(AiError.INVALID_RESPONSE)
        val text = buildString {
            val output = response.getJSONArray("output")
            for (i in 0 until output.length()) {
                val item = output.getJSONObject(i)
                if (item.optString("type") == "message") {
                    val content = item.getJSONArray("content")
                    for (j in 0 until content.length()) {
                        val part = content.getJSONObject(j)
                        if (part.optString("type") == "output_text") append(part.getString("text"))
                        else throw AiException(AiError.CAPABILITY)
                    }
                } else if (item.optString("type") != "reasoning") throw AiException(AiError.CAPABILITY)
            }
        }
        return Generation(nonempty(text), response.optString("model", request.model), usage(response.optJSONObject("usage"), "input_tokens", "output_tokens"), truncated)
    }
}
class OpenAiProvider(transport: AiTransport) : ResponsesProvider(ProviderId.OPENAI, "https://api.openai.com/v1", transport) {
    override fun textModel(model: String) = (model.startsWith("gpt-") || model.startsWith("chatgpt-") || Regex("o[0-9].*").matches(model)) &&
        listOf("audio", "realtime", "transcribe", "tts", "image").none { model.contains(it) }
}
class GrokProvider(transport: AiTransport) : ResponsesProvider(ProviderId.GROK, "https://api.x.ai/v1", transport) {
    override fun textModel(model: String) = model.startsWith("grok-") && listOf("image", "video", "voice").none { model.contains(it) }
}
class ClaudeProvider(transport: AiTransport) : CloudProvider(ProviderId.CLAUDE, "https://api.anthropic.com/v1", transport) {
    override fun headers(key: String) = super.headers(key) + ("anthropic-version" to "2026-10-21")
    override fun quotaError(error: JSONObject?): Boolean = Regex(
        "(?i)(credit balance|insufficient credits|spend(?:ing)? (?:limit|cap)|monthly spend)"
    ).containsMatchIn(error?.optString("message").orEmpty())
    override fun textModel(model: String) = model.startsWith("claude-")
    override suspend fun generateText(key: String, request: GenerationRequest): Generation {
        val response = call(key, "/messages", JSONObject().put("model", request.model).put("max_tokens", request.maxOutputTokens)
            .put("stream", false).put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", request.text))))
        val stop = response.optString("stop_reason")
        if (stop == "refusal") throw AiException(AiError.REFUSED)
        if (stop != "end_turn" && stop != "max_tokens") throw AiException(AiError.INVALID_RESPONSE)
        val content = response.getJSONArray("content")
        val text = buildString {
            for (i in 0 until content.length()) {
                val part = content.getJSONObject(i)
                when (part.optString("type")) {
                    "text" -> append(part.getString("text"))
                    // Current models return thinking blocks by default; they are not part of the answer.
                    "thinking", "redacted_thinking" -> {}
                    else -> throw AiException(AiError.CAPABILITY)
                }
            }
        }
        return Generation(nonempty(text), response.optString("model", request.model), usage(response.optJSONObject("usage"), "input_tokens", "output_tokens"),
            truncated = stop == "max_tokens")
    }
}
class DeepSeekProvider(transport: AiTransport) : CloudProvider(ProviderId.DEEPSEEK, "https://api.deepseek.com", transport) {
    override fun textModel(model: String) = model.startsWith("deepseek-")
    override suspend fun generateText(key: String, request: GenerationRequest): Generation {
        val response = call(key, "/chat/completions", JSONObject().put("model", request.model).put("max_tokens", request.maxOutputTokens)
            .put("stream", false).put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", request.text))))
        val choice = response.getJSONArray("choices").getJSONObject(0)
        val finish = choice.optString("finish_reason")
        if (finish != "stop" && finish != "length") throw AiException(AiError.INVALID_RESPONSE)
        val message = choice.getJSONObject("message")
        if (message.has("tool_calls") && !message.isNull("tool_calls")) throw AiException(AiError.CAPABILITY)
        return Generation(nonempty(message.getString("content")), response.optString("model", request.model),
            usage(response.optJSONObject("usage"), "prompt_tokens", "completion_tokens"), truncated = finish == "length")
    }
}
