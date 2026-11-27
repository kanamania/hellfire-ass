package com.hellfire.ass.ai

import com.hellfire.ass.events.NotificationNormalizer
import com.hellfire.ass.events.NotificationSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

object CloudPrivacy {
    fun redact(text: String): String {
        val normalized = NotificationNormalizer.normalize(NotificationSnapshot(
            "assistant", "user", 0, 0, fields = mapOf("text" to listOf(text))), "hellfire")!!
        var safe = normalized.snapshot.fields.getValue("text").single()
        safe = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").replace(safe, "[EMAIL REDACTED]")
        safe = Regex("(?<!\\w)\\+?\\d[\\d ()-]{7,}\\d(?!\\w)").replace(safe, "[NUMBER REDACTED]")
        return safe
    }
}
data class RouteAttempt(val ref: ModelRef, val error: AiError)
data class RoutedGeneration(
    val requestId: String, val provider: ProviderId, val generation: Generation,
    val attempts: List<RouteAttempt>, val estimatedMicroUsd: Long = 0,
)

class AiRouter(
    val store: AiStore, providers: List<AiProvider>, private val clock: Clock = Clock.systemUTC(),
    /** Called with each finished attempt's metadata (never prompt or answer text), e.g. to keep the usage log. */
    private val onRecord: (AiAudit) -> Unit = {},
) {
    private val providers = providers.associateBy { it.id }
    private val inference = Mutex()
    fun month(): String = YearMonth.now(clock.withZone(ZoneId.of("Africa/Dar_es_Salaam"))).toString()

    suspend fun testConnection(id: ProviderId): List<ModelInfo> {
        val state = store.read()
        val config = state.config(id)
        if (id != ProviderId.LOCAL && !state.cloudEnabled) throw AiException(AiError.PRIVACY)
        if (id != ProviderId.LOCAL && !config.enabled) throw AiException(AiError.DISABLED)
        try {
            val models = providers.getValue(id).discover(config.apiKey)
            if (models.isEmpty()) throw AiException(AiError.MODEL)
            store.update { current ->
                // Do not mark replacement credentials as verified by an older in-flight test.
                if (current.config(id).apiKey != config.apiKey) current else current.copy(providers = current.providers +
                    (id to current.config(id).copy(models = models, status = "Model discovery passed; inference not tested")))
            }
            return models
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val error = (e as? AiException)?.error ?: AiError.INVALID_RESPONSE
            store.update { current -> if (current.config(id).apiKey != config.apiKey) current else current.copy(
                providers = current.providers + (id to current.config(id).copy(status = error.message))) }
            throw AiException(error)
        }
    }

    suspend fun generate(
        task: AiTask, text: String, manual: ModelRef? = null, kind: InputKind = InputKind.USER_TEXT,
        required: Set<Capability> = setOf(Capability.TEXT),
    ): RoutedGeneration = inference.withLock {
        if (text.isBlank() || text.toByteArray(Charsets.UTF_8).size > 32_000) throw AiException(AiError.INVALID_REQUEST)
        val initial = try { store.read() } catch (e: AiException) {
            if (task != AiTask.CHAT) throw e
            val generated = providers.getValue(ProviderId.LOCAL).generate("", GenerationRequest(OfflineProvider.MODEL, text, required = required))
            return@withLock RoutedGeneration(UUID.randomUUID().toString(), ProviderId.LOCAL, generated,
                listOf(RouteAttempt(OfflineProvider.ref, AiError.STORAGE)))
        }
        // A manual choice becomes preferred; the saved fallback list remains visible and ordered.
        val route = if (kind != InputKind.USER_TEXT) listOf(OfflineProvider.ref)
            else if (manual != null) listOf(manual) + initial.route(task).drop(1) else initial.route(task)
        val failures = mutableListOf<RouteAttempt>()
        val requestId = UUID.randomUUID().toString()
        for (ref in route.distinct()) {
            currentCoroutineContext().ensureActive()
            val state = store.read()
            val config = state.config(ref.provider)
            val provider = providers[ref.provider]
            val local = ref.provider == ProviderId.LOCAL
            val preflight = when {
                provider == null -> AiError.MODEL
                !local && !state.cloudEnabled -> AiError.PRIVACY
                !local && !config.enabled -> AiError.DISABLED
                !local && config.apiKey.isBlank() -> AiError.MISSING_CREDENTIALS
                local && task != AiTask.CHAT && (provider as? OfflineProvider)?.generalPurpose != true -> AiError.CAPABILITY
                !provider.capabilities(ref.model).containsAll(required) -> AiError.CAPABILITY
                else -> null
            }
            if (preflight != null) {
                failures += RouteAttempt(ref, preflight)
                record(AiAudit(requestId, clock.millis(), task, ref.provider, ref.model, preflight.name))
                continue
            }
            val safe = if (local) text else CloudPrivacy.redact(text)
            val offlineHelp = local && (provider as? OfflineProvider)?.generalPurpose != true
            val prompt = if (offlineHelp) safe else when (task) {
                AiTask.CHAT -> safe
                AiTask.REASONING -> "Reason carefully about this user-provided question:\n$safe"
                AiTask.DRAFT -> "Draft a reply for review. Do not send anything:\n$safe"
                AiTask.EXTRACTION -> "Suggest tasks from this user-provided text for review. Do not create records:\n$safe"
                AiTask.SUMMARY -> "Summarize this user-provided text:\n$safe"
            }
            val request = GenerationRequest(ref.model, prompt, required = required)
            val period = month()
            val key = "$period/${ref.provider.name}/${ref.model}"
            var reservation = 0L
            var price: ModelPrice? = null
            try {
                if (!local) {
                    store.update { latest ->
                        if (!latest.cloudEnabled) throw AiException(AiError.PRIVACY)
                        if (!latest.config(ref.provider).enabled) throw AiException(AiError.DISABLED)
                        price = latest.config(ref.provider).prices[ref.model] ?: throw AiException(AiError.BUDGET)
                        // UTF-8 bytes plus framing allowance deliberately overestimate ordinary text input tokens.
                        reservation = price!!.estimate(prompt.toByteArray(Charsets.UTF_8).size.toLong() + 512, request.maxOutputTokens.toLong())
                        val spent = latest.usage.filterKeys { it.startsWith("$period/") }.values.sumOf { it.charged }
                        if (latest.monthlyCap <= 0 || reservation > latest.monthlyCap - spent) throw AiException(AiError.BUDGET)
                        val total = latest.usage[key] ?: UsageTotal()
                        latest.copy(usage = latest.usage + (key to total.copy(requests = total.requests + 1, charged = total.charged + reservation)),
                            audit = (latest.audit + AiAudit(requestId, clock.millis(), task, ref.provider, ref.model, "STARTED", estimatedMicroUsd = reservation)).takeLast(100))
                    }
                }
                val generated = provider!!.generate(config.apiKey, request)
                val cost = if (local) 0 else generated.usage?.let { price!!.estimate(it.input, it.output) } ?: reservation
                if (!local) store.update { latest ->
                    val total = latest.usage.getValue(key)
                    latest.copy(usage = latest.usage + (key to total.copy(
                        input = total.input + (generated.usage?.input ?: 0), output = total.output + (generated.usage?.output ?: 0),
                        charged = total.charged - reservation + cost)))
                }
                record(AiAudit(requestId, clock.millis(), task, ref.provider, ref.model, "SUCCESS", generated.usage?.input, generated.usage?.output, cost))
                return@withLock RoutedGeneration(requestId, ref.provider, generated, failures, cost)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { runCatching { record(AiAudit(requestId, clock.millis(), task, ref.provider, ref.model, AiError.CANCELLED.name, estimatedMicroUsd = reservation)) } }
                throw e
            } catch (e: Exception) {
                val error = (e as? AiException)?.error ?: AiError.INVALID_RESPONSE
                if (error == AiError.STORAGE) throw AiException(error)
                // Keep reservations on errors: delivery/billing may already have happened. No same-provider retries.
                record(AiAudit(requestId, clock.millis(), task, ref.provider, ref.model, error.name, estimatedMicroUsd = reservation))
                failures += RouteAttempt(ref, error)
            }
        }
        throw AiException(failures.lastOrNull()?.error ?: AiError.MODEL)
    }

    private fun record(audit: AiAudit) {
        runCatching { onRecord(audit) }
        store.update { state -> state.copy(audit = (state.audit + audit).takeLast(100),
            providers = if (audit.provider == ProviderId.LOCAL) state.providers else state.providers +
                (audit.provider to state.config(audit.provider).copy(status = if (audit.result == "SUCCESS") "Last inference passed" else
                    AiError.entries.firstOrNull { it.name == audit.result }?.message ?: audit.result))) }
    }
}
