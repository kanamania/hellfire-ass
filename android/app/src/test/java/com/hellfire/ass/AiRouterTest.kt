package com.hellfire.ass

import com.hellfire.ass.ai.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AiRouterTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-13T10:00:00Z"), ZoneOffset.UTC)
    private val first = ModelRef(ProviderId.CLAUDE, "claude-fixture")
    private val second = ModelRef(ProviderId.GROK, "grok-fixture")
    private val calls = mutableListOf<ProviderId>()
    private val price = ModelPrice(1_000_000, 2_000_000)

    private fun state(route: List<ModelRef> = listOf(first, second, OfflineProvider.ref)) = AiState(
        cloudEnabled = true, monthlyCap = 1_000_000,
        providers = listOf(first, second).associate { it.provider to ProviderConfig(true, "synthetic-key", it.model, prices = mapOf(it.model to price)) },
        routes = AiTask.entries.associateWith { route },
    )
    private class MemoryStore(var state: AiState) : AiStore {
        @Synchronized override fun read() = state
        @Synchronized override fun update(transform: (AiState) -> AiState): AiState { state = transform(state); return state }
    }
    private fun provider(id: ProviderId, action: suspend (GenerationRequest) -> Generation = { Generation("Synthetic suggestion", it.model, TokenUsage(10, 4)) }) = object : AiProvider {
        override val id = id
        override suspend fun discover(apiKey: String) = listOf(ModelInfo(if (id == first.provider) first.model else second.model))
        override fun capabilities(model: String) = setOf(Capability.TEXT)
        override suspend fun generate(apiKey: String, request: GenerationRequest): Generation { calls += id; return action(request) }
    }
    private fun router(store: AiStore, a: AiProvider = provider(first.provider), b: AiProvider = provider(second.provider)) =
        AiRouter(store, listOf(a, b, OfflineProvider()), clock)

    @Test fun `unconfigured app stays offline with original local response`() = runBlocking {
        val result = router(MemoryStore(AiState())).generate(AiTask.CHAT, "habari")
        assertEquals(ProviderId.LOCAL, result.provider); assertTrue(result.generation.text.contains("Habari")); assertTrue(calls.isEmpty())
    }
    @Test fun `preferred model is used and reported token usage settles reservation`() = runBlocking {
        val store = MemoryStore(state())
        val result = router(store).generate(AiTask.CHAT, "Hello")
        assertEquals(first.provider, result.provider); assertEquals(first.model, result.generation.model)
        assertEquals(listOf(first.provider), calls)
        val usage = store.read().usage.values.single()
        assertEquals(1L, usage.requests); assertEquals(10L, usage.input); assertEquals(4L, usage.output)
        assertEquals(18L, usage.charged); assertEquals(18L, result.estimatedMicroUsd)
    }
    @Test fun `fallbacks use configured order once after safe inference failure`() = runBlocking {
        val store = MemoryStore(state(listOf(first, first, second, OfflineProvider.ref)))
        val result = router(store, provider(first.provider) { throw AiException(AiError.RATE_LIMIT) }).generate(AiTask.CHAT, "Hello")
        assertEquals(listOf(first.provider, second.provider), calls)
        assertEquals(second.provider, result.provider); assertEquals(AiError.RATE_LIMIT, result.attempts.single().error)
        assertTrue(store.read().usage.values.sumOf { it.charged } > 18L)
    }
    @Test fun `unauthorized quota network outage and capability errors all fall back`() = runBlocking {
        listOf(AiError.UNAUTHORIZED, AiError.QUOTA, AiError.NETWORK, AiError.OUTAGE, AiError.CAPABILITY).forEach { error ->
            calls.clear()
            val result = router(MemoryStore(state()), provider(first.provider) { throw AiException(error) }).generate(AiTask.CHAT, "Hello")
            assertEquals(second.provider, result.provider); assertEquals(error, result.attempts.single().error)
            assertEquals(listOf(first.provider, second.provider), calls)
        }
    }
    @Test fun `cloud disable missing credentials and disabled providers use local fallback`() = runBlocking {
        val configurations = listOf(state().copy(cloudEnabled = false), state().copy(providers = emptyMap()),
            state().copy(providers = state().providers.mapValues { it.value.copy(apiKey = "") }))
        configurations.forEach { config ->
            val result = router(MemoryStore(config)).generate(AiTask.CHAT, "time")
            assertEquals(ProviderId.LOCAL, result.provider)
        }
        assertTrue(calls.isEmpty())
    }
    @Test fun `sensitive input categories override cloud routes`() = runBlocking {
        val ai = router(MemoryStore(state()))
        InputKind.entries.filter { it != InputKind.USER_TEXT }.forEach { kind ->
            assertEquals(ProviderId.LOCAL, ai.generate(AiTask.CHAT, "hello", first, kind).provider)
        }
        assertTrue(calls.isEmpty())
    }
    @Test fun `cloud redaction uses existing security policy plus emails and long numbers`() = runBlocking {
        var sent = ""
        val ai = router(MemoryStore(state()), provider(first.provider) { sent = it.text; Generation("Safe", it.model) })
        ai.generate(AiTask.CHAT, "Email synthetic@example.invalid phone +255 712 345 678")
        assertFalse(sent.contains("synthetic@")); assertFalse(sent.contains("712"))
        ai.generate(AiTask.CHAT, "Your OTP is 923751")
        assertEquals("[SECURITY CONTENT REDACTED]", sent)
    }
    @Test fun `cap and missing price block network and allow configured offline fallback`() = runBlocking {
        listOf(state().copy(monthlyCap = 1), state().copy(monthlyCap = 0), state().copy(providers = state().providers.mapValues { it.value.copy(prices = emptyMap()) })).forEach { config ->
            val result = router(MemoryStore(config)).generate(AiTask.CHAT, "hello")
            assertEquals(ProviderId.LOCAL, result.provider)
            assertTrue(result.attempts.all { it.error == AiError.BUDGET })
        }
        assertTrue(calls.isEmpty())
    }
    @Test fun `manual model switch overrides preferred and keeps ordered saved fallbacks`() = runBlocking {
        val result = router(MemoryStore(state())).generate(AiTask.CHAT, "Hello", second)
        assertEquals(second.provider, result.provider); assertEquals(listOf(second.provider), calls)
    }
    @Test fun `task routes are independent and local does not pretend to perform complex tasks`() = runBlocking {
        val store = MemoryStore(state().copy(routes = mapOf(AiTask.SUMMARY to listOf(second))))
        assertEquals(second.provider, router(store).generate(AiTask.SUMMARY, "Synthetic text").provider)
        assertEquals(ProviderId.LOCAL, router(store).generate(AiTask.CHAT, "hello").provider)
        expect(AiError.CAPABILITY) { router(MemoryStore(AiState())).generate(AiTask.SUMMARY, "Synthetic text") }
    }
    @Test fun `unsupported features fail before cloud call and never silently downgrade`() = runBlocking {
        expect(AiError.CAPABILITY) { router(MemoryStore(state())).generate(AiTask.CHAT, "Hello", required = setOf(Capability.TOOLS)) }
        assertTrue(calls.isEmpty())
    }
    @Test fun `no implicit fallback is appended when owner configured none`() = runBlocking {
        expect(AiError.NETWORK) { router(MemoryStore(state(listOf(first))), provider(first.provider) { throw AiException(AiError.NETWORK) })
            .generate(AiTask.CHAT, "Hello") }
        assertEquals(listOf(first.provider), calls)
    }
    @Test fun `cancellation stops routing and preserves uncertain reservation`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val store = MemoryStore(state())
        val ai = router(store, provider(first.provider) { started.complete(Unit); awaitCancellation() })
        val job = launch { ai.generate(AiTask.CHAT, "Hello") }
        started.await(); job.cancelAndJoin()
        assertEquals(listOf(first.provider), calls)
        assertTrue(store.read().usage.values.single().charged > 0)
        assertEquals("CANCELLED", store.read().audit.last().result)
    }
    @Test fun `missing usage retains reservation and metadata excludes prompts answers and keys`() = runBlocking {
        val store = MemoryStore(state())
        router(store, provider(first.provider) { Generation("private-answer", it.model) }).generate(AiTask.CHAT, "private-prompt")
        assertTrue(store.read().usage.values.single().charged > 0)
        val metadata = store.read().audit.toString()
        assertFalse(metadata.contains("private-prompt")); assertFalse(metadata.contains("private-answer")); assertFalse(metadata.contains("synthetic-key"))
    }
    @Test fun `spending survives a new router and blocks subsequent requests`() = runBlocking {
        val store = MemoryStore(state(listOf(first, OfflineProvider.ref)).copy(monthlyCap = 4_613))  // exactly one reservation: (5 + 512) input + 2 x 2,048 output micro-USD
        router(store, provider(first.provider) { throw AiException(AiError.NETWORK) }).generate(AiTask.CHAT, "Hello")
        assertEquals(ProviderId.LOCAL, router(store).generate(AiTask.CHAT, "Hello").provider)
        assertEquals(1, calls.size)
    }
    @Test fun `local chat survives unavailable encrypted settings`() = runBlocking {
        val broken = object : AiStore {
            override fun read(): AiState = throw AiException(AiError.STORAGE)
            override fun update(transform: (AiState) -> AiState): AiState = throw AiException(AiError.STORAGE)
        }
        val result = router(broken).generate(AiTask.CHAT, "hello")
        assertEquals(ProviderId.LOCAL, result.provider); assertTrue(result.generation.text.contains("Hello")); assertTrue(calls.isEmpty())
    }
    @Test fun `connection discovery persists models but not a claim of inference success`() = runBlocking {
        val store = MemoryStore(state())
        router(store).testConnection(first.provider)
        assertEquals(first.model, store.read().config(first.provider).models.single().id)
        assertTrue(store.read().config(first.provider).status.contains("inference not tested"))
        assertTrue(calls.isEmpty())
    }
    @Test fun `month follows owner timezone and integer costs round upward`() {
        val ai = AiRouter(MemoryStore(AiState()), emptyList(), Clock.fixed(Instant.parse("2026-11-30T22:00:00Z"), ZoneOffset.UTC))
        assertEquals("2026-12", ai.month())
        assertEquals(1L, ModelPrice(1, 1).estimate(1, 1))
        assertEquals(3_000_000L, ModelPrice(1_000_000, 2_000_000).estimate(1_000_000, 1_000_000))
    }
    private suspend fun expect(error: AiError, block: suspend () -> Unit) {
        try { block(); fail("Expected $error") } catch (e: AiException) { assertEquals(error, e.error) }
    }
}
