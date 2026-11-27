package com.hellfire.ass

import com.hellfire.ass.ai.*
import com.hellfire.ass.insights.CaptureGaps
import org.junit.Assert.*
import org.junit.Test

class CloudKeyGapTest {
    private fun audit(result: String, at: Long, provider: ProviderId = ProviderId.CLAUDE) = AiAudit("r$at", at, AiTask.values().first(), provider, "m", result)
    private fun state(vararg audits: AiAudit, enabled: Boolean = true, cloud: Boolean = true) =
        AiState(cloudEnabled = cloud, providers = mapOf(ProviderId.CLAUDE to ProviderConfig(enabled = enabled, apiKey = "k")), audit = audits.toList())

    @Test fun `an enabled provider whose latest call was refused raises a gap`() {
        assertEquals(listOf("Cloud AI"), CaptureGaps.cloudKeyGaps(state(audit("SUCCESS", 1), audit("UNAUTHORIZED", 2))).map { it.source })
        assertTrue(CaptureGaps.cloudKeyGaps(state(audit("UNAUTHORIZED", 1), audit("SUCCESS", 2))).isEmpty())
        assertTrue(CaptureGaps.cloudKeyGaps(state(audit("UNAUTHORIZED", 1), audit("STARTED", 2))).isNotEmpty())
        assertTrue(CaptureGaps.cloudKeyGaps(state(audit("UNAUTHORIZED", 1), enabled = false)).isEmpty())
        assertTrue(CaptureGaps.cloudKeyGaps(state(audit("UNAUTHORIZED", 1), cloud = false)).isEmpty())
        assertTrue(CaptureGaps.cloudKeyGaps(state(audit("RATE_LIMIT", 1))).isEmpty())
    }
}
