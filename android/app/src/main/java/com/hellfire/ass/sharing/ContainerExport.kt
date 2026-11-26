package com.hellfire.ass.sharing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import com.hellfire.ass.BuildConfig
import com.hellfire.ass.Diagnostics

data class ContainerResult(val ok: Boolean, val message: String, val findings: List<MarkerHit>)

@androidx.annotation.OptIn(UnstableApi::class)
object ContainerExport {
    suspend fun export(
        context: Context,
        input: File,
        output: File,
        audioOnly: Boolean,
        startMs: Long?,
        endMs: Long?,
        muteRangesMs: List<Pair<Long, Long>>,
        covers: List<RedactRect>,
    ): ContainerResult {
        check(input.absolutePath != output.absolutePath)
        output.parentFile?.mkdirs()
        output.delete()
        val coverNote = if (covers.isNotEmpty()) "Video redaction is a static cover for the whole clip. Blur of the underlying frames and temporal tracking are not implemented." else ""
        var error = transcode(context, input, output, audioOnly, startMs, endMs, muteRangesMs, covers, forceAudio = true)
        if (error != null && muteRangesMs.isEmpty()) {
            output.delete()
            error = transcode(context, input, output, audioOnly, startMs, endMs, emptyList(), covers, forceAudio = false)
        }
        if (error != null || !output.isFile || output.length() == 0L) {
            output.delete()
            return ContainerResult(false, error ?: "Transcode produced no file. Nothing was shared.", emptyList())
        }
        val scan = Mp4Privacy.stripFile(output)
        val findings = scan.identifying.map { MarkerHit("MP4", it, "still present after rewrite") } +
            scan.creationTimes.filter { it.second != 0L }.map { MarkerHit("MP4", it.first, "creation_time ${it.second}") }
        if (!Mp4Privacy.clean(scan)) {
            output.delete()
            return ContainerResult(false, "Container metadata could not be verified as removed. Nothing was shared.", findings)
        }
        val playback = playbackFindings(output)
        if (playback.isNotEmpty()) {
            output.delete()
            return ContainerResult(false, "The copy still has identifying tags or does not open. Nothing was shared.", findings + playback)
        }
        val message = listOf(
            "Container metadata was removed from this copy. Audible or visible content can still identify people, places and devices.",
            coverNote,
        ).filter { it.isNotBlank() }.joinToString(" ")
        return ContainerResult(true, message, findings)
    }

    private suspend fun transcode(
        context: Context,
        input: File,
        output: File,
        audioOnly: Boolean,
        startMs: Long?,
        endMs: Long?,
        muteRangesMs: List<Pair<Long, Long>>,
        covers: List<RedactRect>,
        forceAudio: Boolean,
    ): String? = suspendCancellableCoroutine { continuation ->
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            if (!continuation.isActive) return@post
            try {
                val item = androidx.media3.common.MediaItem.Builder().setUri(Uri.fromFile(input)).apply {
                    if (startMs != null && endMs != null && endMs > startMs) {
                        setClippingConfiguration(
                            androidx.media3.common.MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(startMs)
                                .setEndPositionMs(endMs)
                                .build(),
                        )
                    }
                }.build()
                val processors = if (forceAudio || muteRangesMs.isNotEmpty()) listOf(RangeMuteProcessor(muteRangesMs)) else emptyList()
                val effects = if (audioOnly) Effects(processors, emptyList()) else Effects(processors, listOf(coverEffect(covers)))
                val edited = EditedMediaItem.Builder(item).setRemoveVideo(audioOnly).setEffects(effects).build()
                val transformer = Transformer.Builder(context).setAudioMimeType(MimeTypes.AUDIO_AAC).apply {
                    if (BuildConfig.EMULATOR_BUILD) {
                        val decoder = androidx.media3.transformer.DefaultDecoderFactory.Builder(context)
                            .setMediaCodecSelector { mime, secure, tunneling ->
                                androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
                                    .filter { it.softwareOnly }
                            }.build()
                        setAssetLoaderFactory(androidx.media3.transformer.ExoPlayerAssetLoader.Factory(context, decoder, androidx.media3.common.util.Clock.DEFAULT))
                    }
                    if (!audioOnly) setVideoMimeType(MimeTypes.VIDEO_H264)
                    setUsePlatformDiagnostics(false)
                }.build()
                transformer.addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: androidx.media3.transformer.Composition, exportResult: ExportResult) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                    override fun onError(composition: androidx.media3.transformer.Composition, exportResult: ExportResult, exportException: ExportException) {
                        Diagnostics.warn(exportException)
                        output.delete()
                        if (continuation.isActive) continuation.resume("Transcode failed: ${exportException.errorCodeName}")
                    }
                })
                continuation.invokeOnCancellation { handler.post { try { transformer.cancel() } finally { output.delete() } } }
                transformer.start(edited, output.absolutePath)
            } catch (error: Exception) {
                output.delete()
                if (continuation.isActive) continuation.resume(error.message ?: "Transcode failed")
            }
        }
    }

    private fun coverEffect(covers: List<RedactRect>): OverlayEffect = OverlayEffect(listOf(object : BitmapOverlay() {
        private var bitmap: Bitmap? = null
        override fun configure(videoSize: androidx.media3.common.util.Size) {
            super.configure(videoSize)
            bitmap?.recycle()
            bitmap = coverBitmap(videoSize.width, videoSize.height, covers)
        }
        override fun getBitmap(presentationTimeUs: Long): Bitmap = checkNotNull(bitmap)
        override fun release() { super.release(); bitmap?.recycle(); bitmap = null }
    }))

    internal fun coverBitmap(width: Int, height: Int, covers: List<RedactRect>): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { color = Color.BLACK }
        covers.filter { it.valid() }.forEach { canvas.drawRect(it.left * width, it.top * height, it.right * width, it.bottom * height, paint) }
        return bitmap
    }

    private fun playbackFindings(file: File): List<MarkerHit> {
        val hits = mutableListOf<MarkerHit>()
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            if (extractor.trackCount < 1) hits += MarkerHit("CONTAINER", "tracks", "No playable tracks")
            for (index in 0 until extractor.trackCount) {
                extractor.selectTrack(index)
                val size = extractor.sampleSize
                require(size in 1..16L * 1024 * 1024) { "Unreadable or oversized sample" }
                val sample = ByteBuffer.allocate(size.toInt())
                if (extractor.readSampleData(sample, 0) <= 0) hits += MarkerHit("CONTAINER", "sample", "Track $index has no readable sample")
                extractor.unselectTrack(index)
            }
        } catch (error: Exception) {
            hits += MarkerHit("CONTAINER", "extractor", error.javaClass.simpleName)
        } finally {
            extractor.release()
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes") {
                val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST)
                if (frame == null) hits += MarkerHit("CONTAINER", "frame", "Video does not decode")
                frame?.recycle()
            }
            val keys = listOf(
                "location" to MediaMetadataRetriever.METADATA_KEY_LOCATION,
                "author" to MediaMetadataRetriever.METADATA_KEY_AUTHOR,
                "artist" to MediaMetadataRetriever.METADATA_KEY_ARTIST,
                "title" to MediaMetadataRetriever.METADATA_KEY_TITLE,
                "writer" to MediaMetadataRetriever.METADATA_KEY_WRITER,
                "composer" to MediaMetadataRetriever.METADATA_KEY_COMPOSER,
                "album" to MediaMetadataRetriever.METADATA_KEY_ALBUM,
                "genre" to MediaMetadataRetriever.METADATA_KEY_GENRE,
                "albumArtist" to MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST,
            )
            keys.forEach { (name, key) ->
                val value = retriever.extractMetadata(key)
                if (!value.isNullOrBlank()) hits += MarkerHit("RETRIEVER", name, "still present")
            }
            val date = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
            if (!neutralDate(date)) hits += MarkerHit("RETRIEVER", "date", "still present")
        } catch (error: Exception) {
            hits += MarkerHit("CONTAINER", "retriever", error.javaClass.simpleName)
        } finally {
            retriever.release()
        }
        return hits
    }

    private fun neutralDate(value: String?): Boolean {
        if (value.isNullOrBlank()) return true
        val digits = value.filter { it.isDigit() }
        return digits.isEmpty() || digits.all { it == '0' } || digits.startsWith("19040101")
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private class RangeMuteProcessor(private val rangesMs: List<Pair<Long, Long>>) : BaseAudioProcessor() {
    private var sampleIndex = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != android.media.AudioFormat.ENCODING_PCM_16BIT && encoding != android.media.AudioFormat.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val output = replaceOutputBuffer(inputBuffer.remaining())
        if (inputAudioFormat.encoding == android.media.AudioFormat.ENCODING_PCM_FLOAT) zeroFloat(inputBuffer, output)
        else sampleIndex = PcmMute.zero16(inputBuffer, output, inputAudioFormat.sampleRate, inputAudioFormat.channelCount, rangesMs, sampleIndex)
        output.flip()
    }

    private fun zeroFloat(input: ByteBuffer, output: ByteBuffer) {
        val channels = inputAudioFormat.channelCount
        val frame = channels * 4
        while (input.remaining() >= frame) {
            val timeMs = sampleIndex * 1000L / inputAudioFormat.sampleRate
            val mute = rangesMs.any { timeMs >= it.first && timeMs < it.second }
            if (mute) {
                input.position(input.position() + frame)
                repeat(frame) { output.put(0) }
            } else repeat(frame) { output.put(input.get()) }
            sampleIndex++
        }
    }

    override fun onFlush() {
        sampleIndex = 0
    }

    override fun onReset() {
        sampleIndex = 0
    }
}
