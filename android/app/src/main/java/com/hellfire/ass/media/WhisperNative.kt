package com.hellfire.ass.media

object WhisperNative {
    init { System.loadLibrary("hellfire_whisper") }
    external fun load(path: String): Long
    external fun transcribe(context: Long, samples: FloatArray, language: String): ByteArray
    external fun abort(context: Long)
    external fun reset(context: Long)
    external fun release(context: Long)
}
