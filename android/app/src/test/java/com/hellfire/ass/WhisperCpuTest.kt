package com.hellfire.ass

import com.hellfire.ass.media.WhisperNative
import org.junit.Assert.assertEquals
import org.junit.Test

class WhisperCpuTest {
    @Test fun snapdragonEliteFeaturesAreAccepted() {
        val cpuinfo = "processor\t: 0\nFeatures\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm asimddp i8mm bf16\n"
        assertEquals(emptyList<String>(), WhisperNative.missingCpuFeatures(cpuinfo))
    }

    @Test fun olderCpuReportsMissingFeatures() {
        val cpuinfo = "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n"
        assertEquals(listOf("asimddp", "asimdhp", "i8mm"), WhisperNative.missingCpuFeatures(cpuinfo))
    }

    @Test fun unreadableCpuinfoIsTreatedAsUnsupported() {
        assertEquals(WhisperNative.requiredCpuFeatures, WhisperNative.missingCpuFeatures(""))
    }
}
