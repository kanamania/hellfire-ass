package com.hellfire.ass.ai

import com.hellfire.ass.assistant.LocalAssistant

// These are capabilities exposed by the adapter, not every feature advertised by a vendor.
enum class Capability { TEXT, STREAMING, TOOLS, IMAGES, STRUCTURED_OUTPUT }
enum class ProviderId(val label: String) {
    LOCAL("On-device offline test"), OPENAI("OpenAI"), CLAUDE("Anthropic Claude"), GROK("xAI Grok"), DEEPSEEK("DeepSeek")
}
enum class AiTask(val label: String) {
    CHAT("General chat"), REASONING("Complex reasoning"), DRAFT("Reply drafting"),
    EXTRACTION("Task extraction"), SUMMARY("Summarization")
}
enum class InputKind { USER_TEXT, NOTIFICATION, SMS, FINANCE, RECORDING, MEDIA }
data class ModelInfo(val id: String, val capabilities: Set<Capability> = setOf(Capability.TEXT))
data class ModelRef(val provider: ProviderId, val model: String)
data class TokenUsage(val input: Long, val output: Long)
class GenerationRequest(
    val model: String, val text: String, val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT,
    val required: Set<Capability> = setOf(Capability.TEXT),
) {
    override fun toString() = "GenerationRequest(model=$model, text=[REDACTED])"
    companion object { const val DEFAULT_MAX_OUTPUT = 2048 }
}
/** [truncated]: the provider stopped at the output limit; the text is kept and shown as cut off. */
data class Generation(val text: String, val model: String, val usage: TokenUsage? = null, val truncated: Boolean = false)

enum class AiError(val message: String) {
    DISABLED("Provider disabled. Enable it in AI settings."),
    MISSING_CREDENTIALS("API key missing. Add it in AI settings."),
    UNAUTHORIZED("Authorization rejected or expired. Check your API key and account permissions."),
    RATE_LIMIT("Provider rate limit reached. Wait before trying again."),
    QUOTA("Provider balance or quota exhausted. Check API billing."),
    NETWORK("Network unavailable or request timed out."),
    OUTAGE("Provider temporarily unavailable."),
    CAPABILITY("This adapter or model does not support the requested capability."),
    MODEL("Model unavailable. Test the connection and select an available model."),
    INVALID_REQUEST("Provider rejected this request. Check model compatibility."),
    INVALID_RESPONSE("Provider returned an incomplete or unsupported response."),
    PRIVACY("Cloud use is disabled or this input category must stay on-device."),
    BUDGET("Monthly estimated spending cap reached, or pricing/cap is not configured."),
    STORAGE("Encrypted AI settings are unavailable. Cloud access is blocked."),
    CANCELLED("Request cancelled. The provider may still bill work already started."),
    REFUSED("The provider declined this request.")
}
class AiException(val error: AiError) : Exception(error.message)

interface AiProvider {
    val id: ProviderId
    suspend fun discover(apiKey: String): List<ModelInfo>
    fun capabilities(model: String): Set<Capability>
    // Coroutine cancellation must stop waiting/transport; it must never start a fallback.
    suspend fun generate(apiKey: String, request: GenerationRequest): Generation
}

class OfflineProvider(private val assistant: LocalAssistant = LocalAssistant()) : AiProvider {
    override val id = ProviderId.LOCAL
    override suspend fun discover(apiKey: String) = listOf(ModelInfo(MODEL))
    override fun capabilities(model: String) = if (model == MODEL) setOf(Capability.TEXT) else emptySet()
    override suspend fun generate(apiKey: String, request: GenerationRequest): Generation {
        if (request.model != MODEL) throw AiException(AiError.MODEL)
        if (!capabilities(request.model).containsAll(request.required)) throw AiException(AiError.CAPABILITY)
        return Generation(assistant.answer(request.text), MODEL)
    }
    companion object {
        const val MODEL = "offline-test-v1"
        val ref = ModelRef(ProviderId.LOCAL, MODEL)
    }
}
