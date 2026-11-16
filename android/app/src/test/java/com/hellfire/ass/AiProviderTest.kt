package com.hellfire.ass

import android.app.Application
import com.hellfire.ass.ai.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AiProviderTest {
    private val key = "synthetic-credential"
    private val responses = """{"status":"completed","model":"resolved-model","output":[{"type":"reasoning"},{"type":"message","content":[{"type":"output_text","text":"Synthetic answer"}]}],"usage":{"input_tokens":12,"output_tokens":4}}"""
    private val claude = """{"model":"claude-fixture","stop_reason":"end_turn","content":[{"type":"text","text":"Habari"},{"type":"text","text":"!"}],"usage":{"input_tokens":5,"output_tokens":2}}"""
    private val deepseek = """{"model":"deepseek-fixture","choices":[{"finish_reason":"stop","message":{"content":"Synthetic answer","reasoning_content":"Private reasoning"}}],"usage":{"prompt_tokens":9,"completion_tokens":6}}"""

    @Test fun `OpenAI uses Responses with no stored conversation`() = runBlocking {
        var sent: HttpRequest? = null
        val result = OpenAiProvider { sent = it; HttpResponse(200, responses) }.generate(key, GenerationRequest("gpt-fixture", "Hello"))
        assertEquals("https://api.openai.com/v1/responses", sent!!.url)
        val body = JSONObject(sent!!.body!!)
        assertEquals("Hello", body.getString("input")); assertEquals(GenerationRequest.DEFAULT_MAX_OUTPUT, body.getInt("max_output_tokens"))
        assertFalse(body.getBoolean("store")); assertFalse(body.has("messages")); assertFalse(body.has("tools"))
        assertEquals("Bearer $key", sent!!.headers["Authorization"])
        assertEquals("Synthetic answer", result.text); assertEquals(TokenUsage(12, 4), result.usage)
    }
    @Test fun `Grok uses official xAI Responses endpoint`() = runBlocking {
        val result = GrokProvider { request ->
            assertEquals("https://api.x.ai/v1/responses", request.url)
            assertFalse(JSONObject(request.body!!).getBoolean("store"))
            HttpResponse(200, responses)
        }.generate(key, GenerationRequest("grok-fixture", "Hello"))
        assertEquals("resolved-model", result.model)
    }
    @Test fun `Claude uses versioned Messages and text blocks`() = runBlocking {
        val result = ClaudeProvider { request ->
            assertEquals("https://api.anthropic.com/v1/messages", request.url)
            assertEquals("2026-10-21", request.headers["anthropic-version"])
            assertEquals("Bearer $key", request.headers["Authorization"])
            val body = JSONObject(request.body!!)
            assertEquals(GenerationRequest.DEFAULT_MAX_OUTPUT, body.getInt("max_tokens")); assertFalse(body.has("max_output_tokens"))
            assertEquals("Hello", body.getJSONArray("messages").getJSONObject(0).getString("content"))
            HttpResponse(200, claude)
        }.generate(key, GenerationRequest("claude-fixture", "Hello"))
        assertEquals("Habari!", result.text); assertEquals(TokenUsage(5, 2), result.usage)
    }
    @Test fun `DeepSeek uses chat fields and excludes reasoning from answer`() = runBlocking {
        val result = DeepSeekProvider { request ->
            assertEquals("https://api.deepseek.com/chat/completions", request.url)
            assertTrue(JSONObject(request.body!!).has("messages"))
            assertEquals(GenerationRequest.DEFAULT_MAX_OUTPUT, JSONObject(request.body!!).getInt("max_tokens"))
            HttpResponse(200, deepseek)
        }.generate(key, GenerationRequest("deepseek-fixture", "Hello"))
        assertEquals("Synthetic answer", result.text); assertEquals(TokenUsage(9, 6), result.usage)
        assertFalse(result.text.contains("Private reasoning"))
    }
    @Test fun `all providers discover model IDs using authenticated official endpoints`() = runBlocking {
        listOf<Pair<(AiTransport) -> AiProvider, String>>(
            ::OpenAiProvider to "gpt-fixture", ::GrokProvider to "grok-fixture", ::ClaudeProvider to "claude-fixture", ::DeepSeekProvider to "deepseek-fixture"
        ).forEach { (factory, model) ->
            val provider = factory(AiTransport { request ->
                assertTrue(request.url.endsWith("/models")); assertNull(request.body)
                assertEquals("Bearer $key", request.headers["Authorization"])
                HttpResponse(200, """{"data":[{"id":"$model"}]}""")
            })
            val discovered = provider.discover(key).single()
            assertEquals(model, discovered.id)
            assertEquals(setOf(Capability.TEXT), discovered.capabilities)
        }
    }
    @Test fun `Claude discovery follows paginated model results`() = runBlocking {
        var calls = 0
        val models = ClaudeProvider { request ->
            calls++
            if (calls == 1) HttpResponse(200, """{"data":[{"id":"claude-first"}],"has_more":true,"last_id":"claude-first"}""")
            else { assertTrue(request.url.endsWith("?after_id=claude-first")); HttpResponse(200, """{"data":[{"id":"claude-second"}],"has_more":false}""") }
        }.discover(key)
        assertEquals(listOf("claude-first", "claude-second"), models.map { it.id })
    }
    @Test fun `unsupported capabilities never reach transport for any cloud adapter`() = runBlocking {
        val transport = AiTransport { error("Must not call network") }
        listOf(OpenAiProvider(transport) to "gpt-fixture", GrokProvider(transport) to "grok-fixture",
            ClaudeProvider(transport) to "claude-fixture", DeepSeekProvider(transport) to "deepseek-fixture").forEach { (provider, model) ->
            Capability.entries.filter { it != Capability.TEXT }.forEach { feature ->
                expect(AiError.CAPABILITY) { provider.generate(key, GenerationRequest(model, "Hello", required = setOf(feature))) }
            }
        }
    }
    @Test fun `HTTP errors have distinct sanitized classifications`() = runBlocking {
        listOf(401 to AiError.UNAUTHORIZED, 403 to AiError.UNAUTHORIZED, 402 to AiError.QUOTA,
            429 to AiError.RATE_LIMIT, 404 to AiError.MODEL, 503 to AiError.OUTAGE, 408 to AiError.NETWORK,
            400 to AiError.INVALID_REQUEST).forEach { (status, expected) ->
            expect(expected) { DeepSeekProvider { HttpResponse(status, """{"error":{"message":"$key secret prompt"}}""") }
                .generate(key, GenerationRequest("deepseek-fixture", "Hello")) }
        }
        expect(AiError.QUOTA) { OpenAiProvider { HttpResponse(429, """{"error":{"code":"insufficient_quota"}}""") }
            .generate(key, GenerationRequest("gpt-fixture", "Hello")) }
        listOf(400, 429).forEach { status ->
            expect(AiError.QUOTA) { ClaudeProvider { HttpResponse(status,
                """{"error":{"type":"invalid_request_error","message":"Organization spend limit reached."}}""") }
                .generate(key, GenerationRequest("claude-fixture", "Hello")) }
        }
        expect(AiError.NETWORK) { GrokProvider { throw IOException("secret network message") }
            .generate(key, GenerationRequest("grok-fixture", "Hello")) }
    }
    @Test fun `missing keys fail before transmission`() = runBlocking {
        expect(AiError.MISSING_CREDENTIALS) { ClaudeProvider { error("No network") }.generate("", GenerationRequest("claude-fixture", "Hello")) }
    }
    @Test fun `answers cut off at the output limit are kept and marked`() = runBlocking {
        val deep = DeepSeekProvider { HttpResponse(200, deepseek.replace("\"stop\"", "\"length\"")) }.generate(key, GenerationRequest("deepseek-fixture", "Hello"))
        assertEquals("Synthetic answer", deep.text); assertTrue(deep.truncated)
        val cut = ClaudeProvider { HttpResponse(200, claude.replace("end_turn", "max_tokens")) }.generate(key, GenerationRequest("claude-fixture", "Hello"))
        assertEquals("Habari!", cut.text); assertTrue(cut.truncated)
        val open = OpenAiProvider { HttpResponse(200, responses.replace("\"completed\"", "\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"}")) }
            .generate(key, GenerationRequest("gpt-fixture", "Hello"))
        assertTrue(open.truncated)
        assertFalse(ClaudeProvider { HttpResponse(200, claude) }.generate(key, GenerationRequest("claude-fixture", "Hello")).truncated)
    }
    @Test fun `Claude thinking blocks are skipped and refusals fail`() = runBlocking {
        val thinking = claude.replace("\"content\":[", "\"content\":[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"sig\"},")
        assertEquals("Habari!", ClaudeProvider { HttpResponse(200, thinking) }.generate(key, GenerationRequest("claude-fixture", "Hello")).text)
        expect(AiError.REFUSED) { ClaudeProvider { HttpResponse(200, claude.replace("end_turn", "refusal")) }.generate(key, GenerationRequest("claude-fixture", "Hello")) }
    }
    @Test fun `malformed truncated and tool responses fail closed`() = runBlocking {
        expect(AiError.INVALID_RESPONSE) { DeepSeekProvider { HttpResponse(200, deepseek.replace("\"stop\"", "\"content_filter\"")) }
            .generate(key, GenerationRequest("deepseek-fixture", "Hello")) }
        expect(AiError.INVALID_RESPONSE) { ClaudeProvider { HttpResponse(200, "not JSON") }.generate(key, GenerationRequest("claude-fixture", "Hello")) }
        expect(AiError.INVALID_RESPONSE) { OpenAiProvider { HttpResponse(200, responses.replace("completed", "incomplete")) }.generate(key, GenerationRequest("gpt-fixture", "Hello")) }
        expect(AiError.CAPABILITY) { ClaudeProvider { HttpResponse(200, claude.replace("\"type\":\"text\"", "\"type\":\"tool_use\"")) }
            .generate(key, GenerationRequest("claude-fixture", "Hello")) }
    }
    @Test fun `coroutine cancellation reaches suspended transport`() = runBlocking {
        val started = CompletableDeferred<Unit>(); var cancelled = false
        val provider = GrokProvider { started.complete(Unit); try { awaitCancellation() } finally { cancelled = true } }
        val job = launch { provider.generate(key, GenerationRequest("grok-fixture", "Hello")) }
        started.await(); job.cancelAndJoin(); assertTrue(cancelled)
    }
    @Test fun `debug strings contain neither keys nor prompts`() {
        val request = HttpRequest("https://example.invalid", mapOf("Authorization" to key), "secret-prompt")
        assertFalse(request.toString().contains(key)); assertFalse(request.toString().contains("secret-prompt"))
        assertFalse(ProviderConfig(apiKey = key).toString().contains(key))
        assertFalse(GenerationRequest("model", "secret-prompt").toString().contains("secret-prompt"))
    }
    private suspend fun expect(error: AiError, block: suspend () -> Unit) {
        try { block(); fail("Expected $error") } catch (e: AiException) { assertEquals(error, e.error); assertFalse(e.message!!.contains(key)) }
    }
}
