package com.hellfire.ass.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.io.IOException
import java.io.InputStream

/** ADTS framing makes each AAC frame self-describing, so audio streams into encrypted chunks without a muxer or plaintext file. */
object Adts {
    private val rates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
    fun header(payload: Int, sampleRate: Int, channels: Int): ByteArray {
        val index = rates.indexOf(sampleRate).also { require(it >= 0) { "Unsupported AAC sample rate" } }
        require(channels in 1..7)
        val length = payload + 7
        require(payload > 0 && length < 8192)
        val profile = 1 // AAC-LC (audio object type 2) minus one
        return byteArrayOf(0xFF.toByte(), 0xF1.toByte(), ((profile shl 6) or (index shl 2) or (channels shr 2)).toByte(),
            (((channels and 3) shl 6) or (length shr 11)).toByte(), ((length shr 3) and 0xFF).toByte(),
            (((length and 7) shl 5) or 0x1F).toByte(), 0xFC.toByte())
    }
}

/**
 * Encodes 16-bit mono PCM from [pcm] into AAC-LC ADTS frames with the platform encoder.
 * A read failure from [pcm] still flushes already-captured audio, then is rethrown so callers keep the interrupted prefix.
 */
class AacEncoderStream(private val pcm: InputStream, private val sampleRate: Int = 16000, bitRate: Int = 32000) : InputStream() {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
        configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
        }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        start()
    }
    private val info = MediaCodec.BufferInfo()
    private val scratch = ByteArray(4096)
    private var frame = ByteArray(0)
    private var position = 0
    private var samples = 0L
    private var inputDone = false
    private var outputDone = false
    private var failure: IOException? = null

    override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255 }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (position >= frame.size) {
            if (outputDone) { failure?.let { failure = null; throw it }; return -1 }
            pump()
        }
        val count = minOf(length, frame.size - position)
        System.arraycopy(frame, position, buffer, offset, count)
        position += count
        return count
    }

    private fun pump() {
        if (!inputDone) {
            val slot = codec.dequeueInputBuffer(10_000)
            if (slot >= 0) {
                val input = codec.getInputBuffer(slot)!!.apply { clear() }
                val count = try { pcm.read(scratch, 0, minOf(input.remaining(), scratch.size) and 1.inv()) }
                    catch (e: IOException) { failure = e; -1 }
                val timeUs = samples * 1_000_000L / sampleRate
                if (count < 0) { codec.queueInputBuffer(slot, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                else { input.put(scratch, 0, count); codec.queueInputBuffer(slot, 0, count, timeUs, 0); samples += count / 2 }
            }
        }
        val slot = codec.dequeueOutputBuffer(info, 10_000)
        if (slot >= 0) {
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                val output = codec.getOutputBuffer(slot)!!
                output.position(info.offset); output.limit(info.offset + info.size)
                frame = Adts.header(info.size, sampleRate, 1).copyOf(7 + info.size).also { output.get(it, 7, info.size) }
                position = 0
            }
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            codec.releaseOutputBuffer(slot, false)
        }
    }

    override fun close() { runCatching { codec.stop() }; codec.release() }
}
