package com.hellfire.ass.assistant

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Build
import android.os.Bundle
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.service.voice.VoiceInteractionSession
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.*
import com.hellfire.ass.MainActivity
import java.util.Locale

class HellFireSession(private val sessionContext: Context) : VoiceInteractionSession(sessionContext) {
    private val assistant = LocalAssistant()
    private var recognizer: SpeechRecognizer? = null
    private var speech: TextToSpeech? = null
    private var speechReady = false
    private var lastAnswer = assistant.answer("hello")
    private var voiceStatus: TextView? = null

    override fun onCreateContentView(): View {
        val padding = (20 * sessionContext.resources.displayMetrics.density).toInt()
        val layout = LinearLayout(sessionContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.rgb(250, 247, 244))
        }
        fun label(text: String) = TextView(sessionContext).apply {
            this.text = text
            setTextColor(Color.rgb(35, 30, 27))
            textSize = 18f
            setPadding(0, 8, 0, 8)
            layout.addView(this)
        }
        label("HellFire Ass · offline assistant test")
        val input = EditText(sessionContext).apply {
            hint = "Ask HellFire: hello, habari, time"
            contentDescription = "Question for HellFire"
            setSingleLine(true)
            setTextColor(Color.rgb(35, 30, 27))
            setHintTextColor(Color.DKGRAY)
        }
        layout.addView(input)
        val answer = label(lastAnswer)
        layout.addView(Button(sessionContext).apply {
            text = "Ask HellFire"
            setOnClickListener { lastAnswer = assistant.answer(input.text.toString()); answer.text = lastAnswer }
        })
        voiceStatus = label("Offline voice output is optional; text input works without microphone access.")
        speech = TextToSpeech(sessionContext) { status ->
            val engine = speech
            val voice = engine?.voices?.firstOrNull { !it.isNetworkConnectionRequired && it.locale.language == Locale.ENGLISH.language }
            speechReady = status == TextToSpeech.SUCCESS && voice != null
            if (speechReady) engine?.voice = voice
            voiceStatus?.text = if (speechReady) "Offline voice ready" else "Offline voice unavailable. Install an offline system voice; text remains available."
        }
        layout.addView(Button(sessionContext).apply {
            text = "Speak a question offline"
            setOnClickListener {
                if (sessionContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    voiceStatus?.text = "Allow microphone access in HellFire Ass first."
                } else if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(sessionContext)) {
                    voiceStatus?.text = "An on-device recognizer is unavailable. Type your question instead."
                } else {
                    try {
                        recognizer?.destroy()
                        recognizer = SpeechRecognizer.createSpeechRecognizer(sessionContext,
                            ComponentName(sessionContext, HellFireRecognitionService::class.java)).apply {
                            setRecognitionListener(object : RecognitionListener {
                                override fun onReadyForSpeech(params: Bundle?) { voiceStatus?.text = "Listening offline…" }
                                override fun onBeginningOfSpeech() { }
                                override fun onRmsChanged(rmsdB: Float) { }
                                override fun onBufferReceived(buffer: ByteArray?) { }
                                override fun onEndOfSpeech() { voiceStatus?.text = "Processing offline…" }
                                override fun onError(error: Int) { voiceStatus?.text = "Offline speech unavailable or interrupted (Android error $error). Type your question instead." }
                                override fun onResults(results: Bundle?) {
                                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                                    if (text != null) {
                                        input.setText(text)
                                        lastAnswer = assistant.answer(text)
                                        answer.text = lastAnswer
                                        voiceStatus?.text = "Question recognized on device."
                                    }
                                }
                                override fun onPartialResults(partialResults: Bundle?) { }
                                override fun onEvent(eventType: Int, params: Bundle?) { }
                            })
                            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true))
                        }
                    } catch (_: Exception) { voiceStatus?.text = "Offline speech could not start. Type your question instead." }
                }
            }
        })
        layout.addView(Button(sessionContext).apply {
            text = "Read answer aloud"
            setOnClickListener {
                if (speechReady) speech?.speak(lastAnswer, TextToSpeech.QUEUE_FLUSH, null, "hellfire-answer")
                else voiceStatus?.text = "Offline voice unavailable; use the text response."
            }
        })
        layout.addView(Button(sessionContext).apply {
            text = "Open HellFire Ass"
            setOnClickListener {
                sessionContext.startActivity(Intent(sessionContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                hide()
            }
        })
        layout.addView(Button(sessionContext).apply { text = "Close"; setOnClickListener { hide() } })
        return ScrollView(sessionContext).apply { addView(layout) }
    }

    override fun onHide() { recognizer?.cancel(); speech?.stop(); super.onHide() }
    override fun onDestroy() {
        speechReady = false
        recognizer?.destroy()
        recognizer = null
        speech?.shutdown()
        speech = null
        super.onDestroy()
    }
}
