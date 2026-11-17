package com.hellfire.ass.player

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.library.StorageMode
import com.hellfire.ass.media.EncryptedMediaSource
import com.hellfire.ass.media.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

sealed class WaveformResult {
    data class Ready(val peaks: FloatArray) : WaveformResult()
    data class Unavailable(val reason: String) : WaveformResult()
}

object WaveformLoader {
    private const val REASON = "Waveform not available for this file. Playback can still continue."

    suspend fun load(app: HellFireApp, item: MediaItem): WaveformResult = withContext(Dispatchers.IO) {
        val cache = cacheFile(app, item)
        readCache(cache)?.let { return@withContext WaveformResult.Ready(it) }
        val peaks = runCatching { compute(app, item) }.getOrNull() ?: return@withContext WaveformResult.Unavailable(REASON)
        writeCache(cache, peaks)
        WaveformResult.Ready(peaks)
    }

    private fun compute(app: HellFireApp, item: MediaItem): FloatArray? {
        if (item.storage == StorageMode.MANAGED.name) {
            val reader = app.media.reader(item)
            if (reader.pcm) {
                val acc = WaveformPeaks.accumulator(reader.size)
                val buffer = ByteArray(64 * 1024)
                var position = 0L
                try {
                    while (position < reader.size) {
                        val count = reader.readAt(position, buffer, 0, buffer.size)
                        if (count <= 0) break
                        acc.feedPcm16le(buffer, 0, count)
                        position += count
                    }
                } finally { reader.close() }
                return acc.finish()
            }
            val source = EncryptedMediaSource(reader)
            try {
                return decode(extractor(source), item.durationMs)
            } finally {
                runCatching { source.close() }
            }
        }
        val uri = item.externalUri ?: return null
        val extractor = MediaExtractor()
        extractor.setDataSource(app, Uri.parse(uri), null)
        return decode(extractor, item.durationMs)
    }

    private fun extractor(source: EncryptedMediaSource): MediaExtractor = MediaExtractor().also { it.setDataSource(source) }

    private fun decode(extractor: MediaExtractor, durationMs: Long?): FloatArray? {
        try {
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val decoder = try { MediaCodec.createDecoderByType(mime) } catch (_: Exception) { return null }
            decoder.configure(format, null, null, 0)
            decoder.start()
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1)
            val durationUs = durationMs?.times(1000) ?: format.getLongOrNull(MediaFormat.KEY_DURATION)
            val totalSamples = if (durationUs != null && durationUs > 0) sampleRate * durationUs / 1_000_000 else sampleRate * 60L
            val acc = WaveformPeaks.accumulator(totalSamples.coerceAtLeast(1) * 2)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputSamples = 0L
            val limit = totalSamples.coerceAtLeast(sampleRate.toLong())
            while (outputSamples < limit) {
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(5_000)
                    if (inIndex >= 0) {
                        val input = decoder.getInputBuffer(inIndex) ?: break
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(info, 5_000)
                if (outIndex >= 0) {
                    val output = decoder.getOutputBuffer(outIndex)
                    if (output != null && info.size > 0) {
                        val bytes = ByteArray(info.size)
                        output.position(info.offset)
                        output.get(bytes, 0, info.size)
                        acc.feedPcm16le(bytes)
                        outputSamples += info.size / 2
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) break
            }
            decoder.stop()
            decoder.release()
            return acc.finish()
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun MediaFormat.getLongOrNull(key: String) = if (containsKey(key)) getLong(key) else null

    private fun cacheFile(app: HellFireApp, item: MediaItem): File {
        val dir = File(app.noBackupFilesDir, "waveforms").apply { mkdirs() }
        return File(dir, "${item.id}-${item.contentHash ?: "none"}.peaks")
    }

    private fun readCache(file: File): FloatArray? {
        if (!file.isFile || file.length() < 8) return null
        return runCatching {
            val bytes = file.readBytes()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val count = buffer.int
            if (count <= 0 || count > 4_000 || bytes.size < 4 + count * 4) return null
            FloatArray(count) { buffer.float }
        }.getOrNull()
    }

    private fun writeCache(file: File, peaks: FloatArray) {
        runCatching {
            val buffer = ByteBuffer.allocate(4 + peaks.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(peaks.size)
            peaks.forEach { buffer.putFloat(it) }
            file.writeBytes(buffer.array())
        }
    }
}
