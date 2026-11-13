package com.hellfire.ass.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.hellfire.ass.HellFireApp
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

suspend fun playSample(app: HellFireApp, file: File) {
    require(file.length() <= MicrophoneService.MAX_BYTES + 29L)
    val bytes = app.cipher.decrypt(file.readBytes())
    val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(MicrophoneService.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setBufferSizeInBytes(maxOf(4096, AudioTrack.getMinBufferSize(MicrophoneService.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)))
        .setTransferMode(AudioTrack.MODE_STREAM).build()
    try {
        track.play()
        var offset = 0
        while (offset < bytes.size) {
            currentCoroutineContext().ensureActive()
            val count = track.write(bytes, offset, minOf(4096, bytes.size - offset), AudioTrack.WRITE_BLOCKING)
            check(count > 0)
            offset += count
        }
        while (track.playbackHeadPosition < bytes.size / 2) {
            currentCoroutineContext().ensureActive()
            kotlinx.coroutines.delay(20)
        }
    } finally {
        track.stop()
        track.release()
        bytes.fill(0)
    }
}
