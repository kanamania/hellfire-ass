package com.hellfire.ass

import android.app.Application
import com.hellfire.ass.ai.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Explicit opt-in only: these tests can incur API charges, and never consume personal app data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AiLiveProviderTest {
    @Test fun openai() = check("OPENAI", ::OpenAiProvider)
    @Test fun claude() = check("ANTHROPIC", ::ClaudeProvider)
    @Test fun grok() = check("XAI", ::GrokProvider)
    @Test fun deepseek() = check("DEEPSEEK", ::DeepSeekProvider)

    private fun check(prefix: String, factory: (AiTransport) -> AiProvider) = runBlocking {
        val key = System.getenv("${prefix}_API_KEY").orEmpty()
        val model = System.getenv("${prefix}_TEST_MODEL").orEmpty()
        assumeTrue("Live inference needs explicit opt-in, a key and a model.",
            System.getenv("HELLFIRE_RUN_LIVE_AI_TESTS") == "true" && key.isNotBlank() && model.isNotBlank())
        val provider = factory(HttpsAiTransport())
        assertTrue("Configured test model must be discoverable", provider.discover(key).any { it.id == model })
        val result = provider.generate(key, GenerationRequest(model, "Say hello briefly.", maxOutputTokens = 512))
        assertTrue("Provider must return text", result.text.isNotBlank())
    }
}
