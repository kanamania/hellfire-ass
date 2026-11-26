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
import kotlinx.coroutines.*
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.Diagnostics
import com.hellfire.ass.security.AppLock
import com.hellfire.ass.ui.UiLanguage

class HellFireSession(private val sessionContext: Context) : VoiceInteractionSession(sessionContext) {
    private val assistant = LocalAssistant()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var queryJob: Job? = null
    private var recognizer: SpeechRecognizer? = null
    private var speech: TextToSpeech? = null
    private var speechReady = false
    private var lastAnswer = assistant.answer("hello")
    private var voiceStatus: TextView? = null
    private var pending: OwnerCommand? = null
    private var wasLocked = false

    override fun onCreate() {
        super.onCreate()
        window?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        if (AppLock.enabled(sessionContext)) {
            queryJob?.cancel()
            recognizer?.cancel()
            speech?.stop()
            pending = null
            lastAnswer = assistant.answer("hello")
            setContentView(lockedView())
            wasLocked = true
        } else if (wasLocked) {
            wasLocked = false
            setContentView(onCreateContentView())
        }
    }

    private fun lockedView(): View = Button(sessionContext).apply {
        text = "HellFire Ass is locked · open and unlock"
        setOnClickListener {
            sessionContext.startActivity(Intent(sessionContext, MainActivity::class.java)
                .setAction(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            hide()
        }
    }

    override fun onCreateContentView(): View {
        if (AppLock.enabled(sessionContext)) return lockedView()
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
        label("HellFire Ass · offline assistant")
        val input = EditText(sessionContext).apply {
            hint = "Ask or command: tasks tomorrow, remind me to…, log cash…"
            contentDescription = "Question for HellFire"
            setSingleLine(true)
            setTextColor(Color.rgb(35, 30, 27))
            setHintTextColor(Color.DKGRAY)
        }
        layout.addView(input)
        val answer = label(lastAnswer)
        val confirm = Button(sessionContext).apply { text = "Confirm proposed action"; visibility = View.GONE }
        fun ask(text: String) {
            queryJob?.cancel()
            pending = null
            confirm.visibility = View.GONE
            queryJob = scope.launch {
                answer.text = "Checking local evidence…"
                try {
                    val app = sessionContext.applicationContext as HellFireApp
                    val result = app.assistant.answer(text)
                    lastAnswer = result.text + if (result.sources.isNotEmpty()) "\nEvidence IDs: " + result.sources.take(10).joinToString() else ""
                    answer.text = lastAnswer
                    pending = result.command
                    if (pending != null) confirm.visibility = View.VISIBLE
                } catch (e: CancellationException) { throw e }
                catch (caught: Exception) { Diagnostics.warn(caught); answer.text = "Local evidence unavailable. Open HellFire Ass to check storage, dates and source access." }
            }
        }
        confirm.setOnClickListener {
            val command = pending ?: return@setOnClickListener
            pending = null
            confirm.visibility = View.GONE
            queryJob = scope.launch {
                lastAnswer = try {
                    withContext(Dispatchers.IO) { AssistantCommands.execute(sessionContext.applicationContext as HellFireApp, command) }
                } catch (e: CancellationException) { throw e }
                catch (e: IllegalArgumentException) { e.message ?: "Command could not be applied." }
                catch (caught: Exception) { Diagnostics.warn(caught); "Command could not be fully applied. Open HellFire Ass to check; nothing was sent." }
                answer.text = lastAnswer
            }
        }
        layout.addView(confirm)
        layout.addView(Button(sessionContext).apply {
            this.text = "Ask HellFire"
            setOnClickListener { ask(input.text.toString()) }
        })
        voiceStatus = label("Offline voice output is optional; text input works without microphone access.")
        speech = TextToSpeech(sessionContext) { status ->
            val engine = speech
            val voice = engine?.voices?.firstOrNull { !it.isNetworkConnectionRequired && it.locale.language == UiLanguage.locale.language }
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
                                        ask(text)
                                        voiceStatus?.text = "Question recognized on device."
                                    }
                                }
                                override fun onPartialResults(partialResults: Bundle?) { }
                                override fun onEvent(eventType: Int, params: Bundle?) { }
                            })
                            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, UiLanguage.locale.toLanguageTag())
                                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true))
                        }
                    } catch (caught: Exception) { Diagnostics.warn(caught); voiceStatus?.text = "Offline speech could not start. Type your question instead." }
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

    override fun onHide() { queryJob?.cancel(); recognizer?.cancel(); speech?.stop(); super.onHide() }
    override fun onDestroy() {
        scope.cancel()
        speechReady = false
        recognizer?.destroy()
        recognizer = null
        speech?.shutdown()
        speech = null
        super.onDestroy()
    }
}
