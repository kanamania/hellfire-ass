package com.hellfire.ass

import com.hellfire.ass.library.MediaBookmark
import com.hellfire.ass.library.MediaSegment
import com.hellfire.ass.library.Origin
import com.hellfire.ass.library.SegmentKind
import com.hellfire.ass.player.AbRange
import com.hellfire.ass.player.BoundedCache
import com.hellfire.ass.player.MarkerKind
import com.hellfire.ass.player.PlaybackLogic
import com.hellfire.ass.player.Presentation
import com.hellfire.ass.player.PresentationEvent
import com.hellfire.ass.player.RepeatMode
import com.hellfire.ass.player.TranscriptLine
import com.hellfire.ass.player.WaveformPeaks
import org.junit.Assert.*
import org.junit.Test

class PlayerLogicTest {
    @Test fun `resume uses the saved position unless the route or the end says otherwise`() {
        assertEquals(12_000L, PlaybackLogic.resumeMs(12_000, false, null, 60_000))
        assertEquals(4_000L, PlaybackLogic.resumeMs(12_000, false, 4_000, 60_000))
        assertEquals(0L, PlaybackLogic.resumeMs(12_000, true, null, 60_000))
        assertEquals(0L, PlaybackLogic.resumeMs(59_000, false, null, 60_000))
        assertEquals(0L, PlaybackLogic.resumeMs(0, false, null, null))
    }

    @Test fun `A-B loop seeks to A at B and does not copy a range`() {
        val range = AbRange(1_000, 4_000)
        assertNull(PlaybackLogic.abSeek(3_999, range))
        assertEquals(1_000L, PlaybackLogic.abSeek(4_000, range))
        assertNull(PlaybackLogic.abSeek(4_000, null))
        assertEquals(2_500L, PlaybackLogic.skip(2_000, 500, 9_000))
        assertEquals(0L, PlaybackLogic.skip(100, -500, 9_000))
        assertEquals(9_000L, PlaybackLogic.skip(8_000, 5_000, 9_000))
    }

    @Test fun `timestamp entry accepts clock text seconds and milliseconds`() {
        assertEquals(3_723_000L, PlaybackLogic.parseTimestamp("1:02:03"))
        assertEquals(83_000L, PlaybackLogic.parseTimestamp("1:23"))
        assertEquals(90_000L, PlaybackLogic.parseTimestamp("90"))
        assertEquals(1500L, PlaybackLogic.parseTimestamp("1500ms"))
        assertNull(PlaybackLogic.parseTimestamp("1:99"))
        assertNull(PlaybackLogic.parseTimestamp("nope"))
        assertEquals("1:02:03", PlaybackLogic.formatTimestamp(3_723_000))
    }

    @Test fun `transcript lookup highlights the segment under the playhead`() {
        val lines = listOf(
            TranscriptLine("a", 0, 1_000, "Ada", "hello"),
            TranscriptLine("b", 1_000, 2_500, "Bo", "budget"),
            TranscriptLine("c", 2_500, 4_000, "Ada", "done"),
        )
        assertEquals(1, PlaybackLogic.segmentIndexAt(lines, 1_000))
        assertEquals(1, PlaybackLogic.segmentIndexAt(lines, 2_499))
        assertEquals(-1, PlaybackLogic.segmentIndexAt(lines, 4_000))
        val edited = PlaybackLogic.applyEdits(lines, mapOf("b" to "the payment deadline"))
        assertEquals("the payment deadline", edited[1].text)
        assertEquals(listOf("b"), PlaybackLogic.filterTranscript(edited, "payment").map { it.id })
    }

    @Test fun `AI marker conversion and timeline filtering keep hidden scenes out`() {
        val ai = MediaBookmark("ai", "m", 5_000, title = "boat", origin = Origin.AI.name, segmentId = "s1", createdAt = 1, updatedAt = 1)
        val manual = PlaybackLogic.toManual(ai, 9)
        assertEquals(Origin.USER.name, manual.origin)
        assertFalse(manual.hidden)
        val hidden = MediaBookmark("h", "m", 8_000, origin = Origin.AI.name, hidden = true, segmentId = "s2", createdAt = 1, updatedAt = 1)
        val segments = listOf(
            MediaSegment("s1", "m", "an", SegmentKind.SCENE.name, 5_000, 6_000, title = "boat", createdAt = 1),
            MediaSegment("s2", "m", "an", SegmentKind.SCENE.name, 8_000, 9_000, title = "cut", createdAt = 1),
            MediaSegment("c1", "m", "an", SegmentKind.CHAPTER.name, 0, 10_000, title = "Intro", createdAt = 1),
        )
        val visible = PlaybackLogic.timeline(listOf(ai, hidden), segments, MarkerKind.entries.toSet())
        assertEquals(listOf("c1", "ai"), visible.map { it.id })
        assertEquals(MarkerKind.AI, visible.first { it.id == "ai" }.kind)
        val chapters = PlaybackLogic.timeline(emptyList(), segments, setOf(MarkerKind.CHAPTER))
        assertEquals(listOf("c1"), chapters.map { it.id })
        assertEquals("c1", PlaybackLogic.neighbor(chapters, 100, false)?.id)
    }

    @Test fun `waveform peaks come from streamed samples and stay bounded`() {
        val pcm = ByteArray(8)
        pcm[0] = 0; pcm[1] = 0x10
        pcm[2] = 0; pcm[3] = 0x20
        pcm[4] = 0; pcm[5] = 0x7f
        pcm[6] = 0; pcm[7] = 0
        val acc = WaveformPeaks.accumulator(pcm.size.toLong(), 2)
        acc.feedPcm16le(pcm, 0, 4)
        acc.feedPcm16le(pcm, 4, 4)
        val peaks = acc.finish()
        assertEquals(2, peaks.size)
        assertTrue(peaks[0] > 0f)
        assertTrue(peaks[1] > peaks[0])
        assertTrue(peaks.all { it in 0f..1f })
        val cache = BoundedCache<Int, String>(2)
        cache.put(1, "a"); cache.put(2, "b"); cache.put(3, "c")
        assertEquals(2, cache.size())
        assertFalse(cache.contains(1))
        assertEquals("c", cache.get(3))
    }

    @Test fun `mini player and picture-in-picture follow the presentation rules`() {
        assertEquals(Presentation.MINI, PlaybackLogic.transition(Presentation.FULL, PresentationEvent.MINIMIZE, true))
        assertEquals(Presentation.FULL, PlaybackLogic.transition(Presentation.MINI, PresentationEvent.MAXIMIZE, true))
        assertEquals(Presentation.PIP, PlaybackLogic.transition(Presentation.FULL, PresentationEvent.USER_LEAVE, true))
        assertEquals(Presentation.MINI, PlaybackLogic.transition(Presentation.MINI, PresentationEvent.USER_LEAVE, true))
        assertEquals(Presentation.FULL, PlaybackLogic.transition(Presentation.FULL, PresentationEvent.USER_LEAVE, false))
        assertEquals(Presentation.PIP, PlaybackLogic.transition(Presentation.FULL, PresentationEvent.ENTER_PIP, true))
        assertEquals(Presentation.FULL, PlaybackLogic.transition(Presentation.PIP, PresentationEvent.LEAVE_PIP, true))
        assertEquals(Presentation.CLOSED, PlaybackLogic.transition(Presentation.PIP, PresentationEvent.CLOSE, true))
        assertEquals(Presentation.FULL, PlaybackLogic.transition(Presentation.FULL, PresentationEvent.PIP_REFUSED, true))
        assertTrue(PlaybackLogic.shouldAutoEnterPip(true, true, Presentation.FULL))
        assertFalse(PlaybackLogic.shouldAutoEnterPip(true, false, Presentation.FULL))
        assertFalse(PlaybackLogic.shouldAutoEnterPip(false, true, Presentation.FULL))
        assertFalse(PlaybackLogic.shouldAutoEnterPip(true, true, Presentation.MINI))
        assertEquals(1, PlaybackLogic.nextIndex(0, 3, RepeatMode.OFF, false, emptyList()))
        assertNull(PlaybackLogic.nextIndex(2, 3, RepeatMode.OFF, false, emptyList()))
        assertEquals(0, PlaybackLogic.nextIndex(2, 3, RepeatMode.ALL, false, emptyList()))
        assertEquals(2, PlaybackLogic.nextIndex(2, 3, RepeatMode.ONE, false, emptyList()))
        assertEquals("1:0:2", PlaybackLogic.trackKey(1, 0, 2))
        assertEquals(Triple(1, 0, 2), PlaybackLogic.parseTrackKey("1:0:2"))
        assertNull(PlaybackLogic.parseTrackKey("off"))
        assertEquals("This file cannot be played on this device. The codec or container is not supported. The original was not modified.",
            PlaybackLogic.playbackError("ERROR_CODE_DECODER_INIT_FAILED"))
    }
}
