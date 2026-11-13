package com.hellfire.ass.assistant

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/** Delegates only to an installed on-device recognizer; never starts a model download. */
class HellFireRecognitionService : RecognitionService() {
    private var recognizer: SpeechRecognizer? = null

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            listener.error(SpeechRecognizer.ERROR_CLIENT)
            return
        }
        try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { listener.readyForSpeech(params) }
                    override fun onBeginningOfSpeech() { listener.beginningOfSpeech() }
                    override fun onRmsChanged(rmsdB: Float) { listener.rmsChanged(rmsdB) }
                    override fun onBufferReceived(buffer: ByteArray?) { if (buffer != null) listener.bufferReceived(buffer) }
                    override fun onEndOfSpeech() { listener.endOfSpeech() }
                    override fun onError(error: Int) { listener.error(error) }
                    override fun onResults(results: Bundle?) { if (results != null) listener.results(results) else listener.error(SpeechRecognizer.ERROR_NO_MATCH) }
                    override fun onPartialResults(partialResults: Bundle?) { if (partialResults != null) listener.partialResults(partialResults) }
                    override fun onEvent(eventType: Int, params: Bundle?) { }
                })
                startListening(recognizerIntent)
            }
        } catch (_: Exception) { listener.error(SpeechRecognizer.ERROR_CLIENT) }
    }
    override fun onStopListening(listener: Callback) { recognizer?.stopListening() }
    override fun onCancel(listener: Callback) { recognizer?.cancel(); recognizer?.destroy(); recognizer = null }
    override fun onDestroy() { recognizer?.destroy(); super.onDestroy() }
}
