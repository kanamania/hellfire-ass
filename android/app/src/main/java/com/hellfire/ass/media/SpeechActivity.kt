package com.hellfire.ass.media

/**
 * Experimental adaptive-energy speech intervals; this does not identify a speaker or prove participation.
 * The noise floor is the minimum 20 ms frame level over the last ~10 s, so steady background noise
 * (fans, traffic, hiss) is not reported as speech, while natural pauses keep the floor low during talk.
 * A span needs 3 consecutive speech frames (rejects clicks), ends after 2 s of quiet, and keeps a
 * 30 s pre-roll for transcription context; overlapping spans are merged.
 */
class SpeechActivity(private val sampleRate: Int = 16000, private val frameProbability: ((FloatArray) -> Float?)? = null) {
    init { require(sampleRate > 0 && (frameProbability == null || sampleRate == 16000)) }
    private val frameSize = if (frameProbability == null) sampleRate / 50 else 512
    private val learnedFrame = if (frameProbability == null) null else FloatArray(frameSize)
    private val blockFrames = 25
    private val maxBlocks = 20
    private val blockMinima = ArrayDeque<Double>()
    private var blockMin = Double.MAX_VALUE
    private var blockCount = 0
    private var frameSum = 0.0
    private var frameFill = 0
    private var frames = 0L
    private var pendingByte: Int? = null
    private var run = 0
    private var started: Long? = null
    private var lastSpeech = 0L
    private val spans = mutableListOf<Pair<Long, Long>>()

    fun accept(bytes: ByteArray, offset: Int, size: Int) {
        var i = offset
        val end = offset + size
        pendingByte?.let { low -> if (i < end) { sample((low or (bytes[i].toInt() shl 8)).toShort()); i++; pendingByte = null } }
        while (i + 1 < end) { sample(((bytes[i].toInt() and 255) or (bytes[i + 1].toInt() shl 8)).toShort()); i += 2 }
        if (i < end) pendingByte = bytes[i].toInt() and 255
    }

    private fun sample(value: Short) {
        val x = value / 32768.0
        learnedFrame?.set(frameFill, x.toFloat())
        frameSum += x * x
        if (++frameFill == frameSize) { frame(kotlin.math.sqrt(frameSum / frameSize)); frameSum = 0.0; frameFill = 0 }
    }

    private fun ms(frame: Long) = frame * frameSize * 1000 / sampleRate

    private fun frame(rms: Double) {
        blockMin = minOf(blockMin, rms)
        if (++blockCount == blockFrames) {
            blockMinima.addLast(blockMin); if (blockMinima.size > maxBlocks) blockMinima.removeFirst()
            blockMin = Double.MAX_VALUE; blockCount = 0
        }
        val floor = minOf(blockMinima.minOrNull() ?: Double.MAX_VALUE, blockMin).coerceAtLeast(0.001)
        val probability = learnedFrame?.let { frameProbability?.invoke(it) }?.takeIf { it.isFinite() && it in 0f..1f }
        val speech = probability?.let { it >= 0.5f } ?: (rms >= maxOf(MIN_LEVEL, floor * RATIO))
        val index = frames++
        if (speech) {
            run++
            lastSpeech = ms(index + 1)
            if (started == null && run >= ONSET_FRAMES) started = (ms(index + 1 - ONSET_FRAMES) - PRE_ROLL_MS).coerceAtLeast(0)
        } else {
            run = 0
            if (started != null && ms(index + 1) - lastSpeech >= HANGOVER_MS) close()
        }
    }

    private fun close() {
        val start = started ?: return
        started = null
        val previous = spans.lastOrNull()
        if (previous != null && start <= previous.second) spans[spans.lastIndex] = previous.first to lastSpeech
        else spans += start to lastSpeech
    }

    fun finish(): List<Pair<Long, Long>> { close(); return spans.toList() }

    private companion object {
        const val MIN_LEVEL = 0.01
        const val RATIO = 3.0
        const val ONSET_FRAMES = 3
        const val HANGOVER_MS = 2000L
        const val PRE_ROLL_MS = 30_000L
    }
}
