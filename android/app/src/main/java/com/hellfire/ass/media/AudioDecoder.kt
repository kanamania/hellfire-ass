package com.hellfire.ass.media

import android.media.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.ByteOrder

object AudioDecoder {
    data class Chunk(val samples: FloatArray, val end: Boolean)
    suspend fun chunk(source: EncryptedMediaSource, startMs: Long, maxSeconds: Int = 120): Chunk {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(source)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: error("NO_ACCESSIBLE_AUDIO_TRACK")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).apply { configure(format, null, null, 0); start() }
            val output = FloatArray(16000 * maxSeconds)
            var count = 0
            var inputEnded = false
            var ended = false
            val info = MediaCodec.BufferInfo()
            var nextSampleUs = startMs * 1000.0
            while (!ended && count < output.size) {
                currentCoroutineContext().ensureActive()
                if (!inputEnded) {
                    val slot = codec.dequeueInputBuffer(10000)
                    if (slot >= 0) {
                        val input = codec.getInputBuffer(slot)!!
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) { codec.queueInputBuffer(slot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true }
                        else { codec.queueInputBuffer(slot, 0, size, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                val slot = codec.dequeueOutputBuffer(info, 10000)
                if (slot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val changed = codec.outputFormat
                    rate = changed.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = changed.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    encoding = if (changed.containsKey(MediaFormat.KEY_PCM_ENCODING)) changed.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    require(channels in 1..8 && rate in 8000..192000 && encoding in setOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT))
                } else if (slot >= 0) {
                    val buffer = codec.getOutputBuffer(slot)!!.order(ByteOrder.LITTLE_ENDIAN)
                    buffer.position(info.offset); buffer.limit(info.offset + info.size)
                    var frame = 0
                    val width = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                    while (buffer.remaining() >= channels * width && count < output.size) {
                        var mono = 0f
                        repeat(channels) { mono += if (width == 4) buffer.float else buffer.short.toFloat() / 32768 }
                        mono /= channels
                        val at = info.presentationTimeUs + frame * 1_000_000.0 / rate
                        frame++
                        while (nextSampleUs <= at && count < output.size) {
                            if (at >= startMs * 1000) output[count++] = mono.coerceIn(-1f, 1f)
                            nextSampleUs += 1_000_000.0 / 16000
                        }
                    }
                    ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(slot, false)
                }
            }
            return Chunk(output.copyOf(count), ended)
        } finally { runCatching { codec?.stop() }; codec?.release(); extractor.release(); source.close() }
    }
}
