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
        } else {
            if (wasLocked) {
                wasLocked = false
                setContentView(onCreateContentView())
            }
            // Invoking the assistant is a request to talk: listen straight away when an on-device recognizer is allowed.
            if (canListen()) scope.launch { delay(250); if (input != null && pending == null) listen() }
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

    private var input: EditText? = null
    private var screenView: Button? = null
    private var screen = ""

    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)
        if (!ScreenText.enabled(sessionContext) || AppLock.enabled(sessionContext)) return
        screen = ScreenText.from(state.assistStructure)
        screenView?.let { button -> button.visibility = if (screen.isEmpty()) View.GONE else View.VISIBLE
            button.text = "Save what's on screen to HellFire (${screen.length} characters)" }
    }
    private var answerView: TextView? = null
    private var confirmButton: Button? = null
    /** The current turn started by voice: answer aloud and listen for a spoken yes or no. */
    private var voiceTurn = false

    private val app get() = sessionContext.applicationContext as HellFireApp

    private fun ask(text: String, spoken: Boolean) {
        queryJob?.cancel()
        voiceTurn = spoken
        pending = null
        confirmButton?.visibility = View.GONE
        queryJob = scope.launch {
            answerView?.text = "Checking local evidence…"
            try {
                val result = app.assistant.answer(text)
                lastAnswer = result.text
                answerView?.text = result.text + if (result.sources.isNotEmpty()) "\n${result.sources.size} saved source(s) · open HellFire Ass to view them" else ""
                pending = result.command
                if (pending != null) confirmButton?.visibility = View.VISIBLE
                if (spoken) say(SpokenAnswer.of(result.text) + if (pending != null) ". Say yes to confirm, or no to cancel." else "")
            } catch (e: CancellationException) { throw e }
            catch (caught: Exception) { Diagnostics.warn(caught); answerView?.text = "Local evidence unavailable. Open HellFire Ass to check storage, dates and source access." }
        }
    }

    private fun confirm() {
        val command = pending ?: return
        pending = null
        confirmButton?.visibility = View.GONE
        queryJob = scope.launch {
            lastAnswer = try {
                withContext(Dispatchers.IO) { AssistantCommands.execute(app, command) }
            } catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { e.message ?: "Command could not be applied." }
            catch (caught: Exception) { Diagnostics.warn(caught); "Command could not be fully applied. Open HellFire Ass to check; nothing was sent." }
            answerView?.text = lastAnswer
            if (voiceTurn) say(SpokenAnswer.of(lastAnswer))
        }
    }

    private fun cancelPending() {
        pending = null
        confirmButton?.visibility = View.GONE
        lastAnswer = "Cancelled. Nothing was changed."
        answerView?.text = lastAnswer
        if (voiceTurn) say(lastAnswer)
    }

    /** Speaks [text]; after a proposal, listens again for the spoken yes or no. */
    private fun say(text: String) {
        if (!speechReady) { voiceStatus?.text = "Offline voice unavailable; read the answer on screen."; return }
        speech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, if (pending != null) "hellfire-confirm" else "hellfire-answer")
    }

    private fun canListen(): Boolean = sessionContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
        Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(sessionContext)

    private fun listen() {
        if (sessionContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            voiceStatus?.text = "Allow microphone access in HellFire Ass first."; return
        }
        if (!canListen()) { voiceStatus?.text = "An on-device recognizer is unavailable. Type your question instead."; return }
        try {
            speech?.stop()
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(sessionContext,
                ComponentName(sessionContext, HellFireRecognitionService::class.java)).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { voiceStatus?.text = if (pending != null) "Listening · say yes or no" else "Listening offline…" }
                    override fun onBeginningOfSpeech() { }
                    override fun onRmsChanged(rmsdB: Float) { }
                    override fun onBufferReceived(buffer: ByteArray?) { }
                    override fun onEndOfSpeech() { voiceStatus?.text = "Processing offline…" }
                    override fun onError(error: Int) { voiceStatus?.text = "Offline speech unavailable or interrupted (Android error $error). Tap Speak or type instead." }
                    override fun onResults(results: Bundle?) {
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                        voiceStatus?.text = "Heard on device."
                        when (if (pending != null) SpokenAnswer.confirmation(text) else null) {
                            true -> confirm()
                            false -> cancelPending()
                            null -> { input?.setText(text); ask(text, spoken = true) }
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
        val field = EditText(sessionContext).apply {
            hint = "Ask or command: tasks tomorrow, remind me to…, log cash…"
            contentDescription = "Question for HellFire"
            setSingleLine(true)
            setTextColor(Color.rgb(35, 30, 27))
            setHintTextColor(Color.DKGRAY)
        }
        input = field
        layout.addView(field)
        answerView = label(lastAnswer)
        // Only text the owner chose to share: nothing is saved until this tap, and it goes through the usual share intake.
        screenView = Button(sessionContext).apply {
            visibility = if (screen.isEmpty()) View.GONE else View.VISIBLE
            text = "Save what's on screen to HellFire (${screen.length} characters)"
            setOnClickListener {
                if (screen.isEmpty()) return@setOnClickListener
                sessionContext.startActivity(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, screen)
                    .putExtra("hellfire-share-id", java.util.UUID.randomUUID().toString()).setClass(sessionContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                screen = ""
                hide()
            }
        }
        layout.addView(screenView)
        confirmButton = Button(sessionContext).apply {
            text = "Confirm proposed action"; visibility = View.GONE
            setOnClickListener { confirm() }
        }
        layout.addView(confirmButton)
        layout.addView(Button(sessionContext).apply {
            this.text = "Ask HellFire"
            setOnClickListener { ask(field.text.toString(), spoken = false) }
        })
        voiceStatus = label("Offline voice output is optional; text input works without microphone access.")
        speech = TextToSpeech(sessionContext) { status ->
            val engine = speech
            // Most answers are English; use the UI language's offline voice, else an offline English one.
            val voices = engine?.voices.orEmpty().filter { !it.isNetworkConnectionRequired }
            val voice = voices.firstOrNull { it.locale.language == UiLanguage.locale.language } ?: voices.firstOrNull { it.locale.language == "en" }
            speechReady = status == TextToSpeech.SUCCESS && voice != null
            if (speechReady) {
                engine?.voice = voice
                engine?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { }
                    @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { }
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == "hellfire-confirm") scope.launch { if (pending != null) listen() }
                    }
                })
            }
            voiceStatus?.text = if (speechReady) "Offline voice ready" else "Offline voice unavailable. Install an offline system voice; text remains available."
        }
        layout.addView(Button(sessionContext).apply {
            text = "Speak a question offline"
            setOnClickListener { listen() }
        })
        layout.addView(Button(sessionContext).apply {
            text = "Read answer aloud"
            setOnClickListener {
                if (speechReady) speech?.speak(SpokenAnswer.of(lastAnswer), TextToSpeech.QUEUE_FLUSH, null, "hellfire-answer")
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

    override fun onHide() { screen = ""; queryJob?.cancel(); recognizer?.cancel(); speech?.stop(); super.onHide() }
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
