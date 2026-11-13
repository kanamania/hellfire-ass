package com.hellfire.ass.ai

import android.content.Context
import android.util.AtomicFile
import com.hellfire.ass.security.PayloadCipher
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode

// Prices are micro-USD per million tokens, entered from the owner's API price sheet.
data class ModelPrice(val input: Long, val output: Long) {
    init { require(input in 0..1_000_000_000_000 && output in 0..1_000_000_000_000) }
    fun estimate(inputTokens: Long, outputTokens: Long): Long = BigDecimal.valueOf(inputTokens)
        .multiply(BigDecimal.valueOf(input)).add(BigDecimal.valueOf(outputTokens).multiply(BigDecimal.valueOf(output)))
        .divide(BigDecimal.valueOf(1_000_000), 0, RoundingMode.CEILING).longValueExact()
}
data class ProviderConfig(
    val enabled: Boolean = false, val apiKey: String = "", val defaultModel: String = "",
    val models: List<ModelInfo> = emptyList(), val prices: Map<String, ModelPrice> = emptyMap(),
    val status: String = "Not tested",
) {
    override fun toString() = "ProviderConfig(enabled=$enabled, apiKey=[REDACTED])"
}
data class UsageTotal(val requests: Long = 0, val input: Long = 0, val output: Long = 0, val charged: Long = 0)
data class AiAudit(
    val requestId: String, val at: Long, val task: AiTask, val provider: ProviderId,
    val model: String, val result: String, val input: Long? = null, val output: Long? = null,
    val estimatedMicroUsd: Long = 0,
)
data class AiState(
    val cloudEnabled: Boolean = false, val monthlyCap: Long = 0,
    val providers: Map<ProviderId, ProviderConfig> = emptyMap(),
    val routes: Map<AiTask, List<ModelRef>> = emptyMap(),
    val usage: Map<String, UsageTotal> = emptyMap(), val audit: List<AiAudit> = emptyList(),
) {
    fun config(id: ProviderId) = providers[id] ?: ProviderConfig()
    fun route(task: AiTask) = routes[task] ?: listOf(OfflineProvider.ref)
    override fun toString() = "AiState(cloudEnabled=$cloudEnabled, providers=[REDACTED])"
}
interface AiStore {
    fun read(): AiState
    fun update(transform: (AiState) -> AiState): AiState
}

class EncryptedAiStore(private val file: AtomicFile, private val cipher: () -> PayloadCipher) : AiStore {
    constructor(context: Context, cipher: () -> PayloadCipher) : this(
        AtomicFile(File(context.noBackupFilesDir, "ai-settings.enc")), cipher)

    @Synchronized override fun read(): AiState = try {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) AiState()
        else AiStateCodec.decode(cipher().decrypt(file.readFully()).toString(Charsets.UTF_8))
    } catch (_: Exception) { throw AiException(AiError.STORAGE) }

    @Synchronized override fun update(transform: (AiState) -> AiState): AiState {
        val next = transform(read())
        val bytes = try { cipher().encrypt(AiStateCodec.encode(next).toByteArray(Charsets.UTF_8)) }
            catch (_: Exception) { throw AiException(AiError.STORAGE) }
        val stream = try { file.startWrite() } catch (_: Exception) { throw AiException(AiError.STORAGE) }
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (_: Exception) { file.failWrite(stream); throw AiException(AiError.STORAGE) }
        return next
    }
}

internal object AiStateCodec {
    fun encode(state: AiState): String = JSONObject().apply {
        put("version", 1); put("cloud", state.cloudEnabled); put("cap", state.monthlyCap)
        put("providers", JSONObject().apply {
            state.providers.forEach { (id, config) -> put(id.name, JSONObject().apply {
                put("enabled", config.enabled); put("key", config.apiKey); put("model", config.defaultModel); put("status", config.status)
                put("models", JSONArray(config.models.map { it.id }))
                put("prices", JSONObject().apply { config.prices.forEach { (model, price) ->
                    put(model, JSONObject().put("input", price.input).put("output", price.output))
                } })
            }) }
        })
        put("routes", JSONObject().apply { state.routes.forEach { (task, refs) -> put(task.name, JSONArray().apply {
            refs.forEach { put(JSONObject().put("provider", it.provider.name).put("model", it.model)) }
        }) } })
        put("usage", JSONObject().apply { state.usage.forEach { (key, total) -> put(key, JSONObject()
            .put("requests", total.requests).put("input", total.input).put("output", total.output).put("charged", total.charged)) } })
        put("audit", JSONArray().apply { state.audit.forEach { a -> put(JSONObject()
            .put("id", a.requestId).put("at", a.at).put("task", a.task.name).put("provider", a.provider.name)
            .put("model", a.model).put("result", a.result).put("input", a.input).put("output", a.output).put("cost", a.estimatedMicroUsd)) } })
    }.toString()

    fun decode(text: String): AiState {
        val json = JSONObject(text)
        require(json.getInt("version") == 1)
        val providers = json.getJSONObject("providers")
        val routes = json.getJSONObject("routes")
        val usage = json.getJSONObject("usage")
        val audit = json.getJSONArray("audit")
        return AiState(json.getBoolean("cloud"), json.getLong("cap").also { require(it >= 0) },
            providers.keys().asSequence().associate { key ->
                val c = providers.getJSONObject(key); val models = c.getJSONArray("models"); val prices = c.getJSONObject("prices")
                ProviderId.valueOf(key) to ProviderConfig(c.getBoolean("enabled"), c.getString("key"), c.getString("model"),
                    (0 until models.length()).map { ModelInfo(models.getString(it)) },
                    prices.keys().asSequence().associate { model -> val p = prices.getJSONObject(model)
                        model to ModelPrice(p.getLong("input"), p.getLong("output")) }, c.getString("status"))
            },
            routes.keys().asSequence().associate { key -> val refs = routes.getJSONArray(key)
                AiTask.valueOf(key) to (0 until refs.length()).map { i -> val r = refs.getJSONObject(i)
                    ModelRef(ProviderId.valueOf(r.getString("provider")), r.getString("model")) }
            },
            usage.keys().asSequence().associate { key -> val u = usage.getJSONObject(key)
                key to UsageTotal(u.getLong("requests"), u.getLong("input"), u.getLong("output"), u.getLong("charged"))
            },
            (0 until audit.length()).map { i -> val a = audit.getJSONObject(i)
                AiAudit(a.getString("id"), a.getLong("at"), AiTask.valueOf(a.getString("task")), ProviderId.valueOf(a.getString("provider")),
                    a.getString("model"), a.getString("result"), if (a.isNull("input")) null else a.getLong("input"),
                    if (a.isNull("output")) null else a.getLong("output"), a.getLong("cost"))
            })
    }
}
