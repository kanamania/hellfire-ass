package com.hellfire.ass.news

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.hellfire.ass.HellFireApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/** Sentence splitting and duration estimates for spoken news (pure Kotlin). */
object NewsSpeech {
    /** Rough on-device TTS pace at rate 1.0, used only to map positions to an audio offset. */
    const val CHARS_PER_SECOND = 14.0
    const val SENTENCE_PAUSE_MS = 300L
    /** Long sentences are cut so each utterance stays well under the engine's input limit. */
    const val MAX_SENTENCE = 400

    private val abbreviations = setOf("mr.", "mrs.", "ms.", "dr.", "prof.", "st.", "no.", "vs.", "etc.", "jr.", "sr.", "gen.", "gov.", "sen.",
        "rep.", "inc.", "ltd.", "co.", "corp.", "jan.", "feb.", "mar.", "apr.", "jun.", "jul.", "aug.", "sep.", "sept.", "oct.", "nov.", "dec.", "bi.", "mh.")
    private val initials = Regex("^\\(?([A-Za-z]\\.)+$")

    fun sentences(text: String): List<String> {
        val out = mutableListOf<String>()
        text.split(Regex("\\n+")).map { it.trim() }.filter { it.isNotEmpty() }.forEach { para ->
            var start = 0
            for (i in para.indices) {
                val c = para[i]
                val end = when {
                    c in ".!?…" -> i
                    c in "\"”’')" && i > 0 && para[i - 1] in ".!?…" -> i
                    else -> continue
                }
                val next = end + 1
                if (next < para.length && !para[next].isWhitespace()) continue
                val candidate = para.substring(start, next).trim()
                val lastWord = candidate.substringAfterLast(' ').lowercase()
                if (next < para.length && c == '.' && (lastWord in abbreviations || initials.matches(lastWord))) continue
                if (candidate.isNotEmpty()) out += candidate
                start = next
            }
            para.substring(start).trim().takeIf { it.isNotEmpty() }?.let { out += it }
        }
        return out.flatMap(::chunk)
    }

    private fun chunk(sentence: String): List<String> {
        if (sentence.length <= MAX_SENTENCE) return listOf(sentence)
        val parts = mutableListOf<String>()
        var rest = sentence
        while (rest.length > MAX_SENTENCE) {
            val window = rest.substring(0, MAX_SENTENCE)
            // Prefer a clause break (keeping its comma), then a word break, else a hard cut.
            val at = listOf(window.lastIndexOf(", "), window.lastIndexOf("; "), window.lastIndexOf(' ')).firstOrNull { it > MAX_SENTENCE / 2 }
                ?: (MAX_SENTENCE - 1)
            parts += rest.substring(0, at + 1).trim()
            rest = rest.substring(at + 1).trim()
        }
        if (rest.isNotEmpty()) parts += rest
        return parts
    }

    fun estimateMs(sentence: String, rate: Float): Long =
        (sentence.length / (CHARS_PER_SECOND * rate.coerceIn(0.25f, 4f)) * 1000).toLong() + SENTENCE_PAUSE_MS
}

/** One story to read aloud: [text] is StorySummary.spoken() (or a "What changed" update text). */
data class SpokenStory(val id: String, val title: String, val text: String) {
    val sentences: List<String> = NewsSpeech.sentences(text).ifEmpty { listOf(title) }
}

/**
 * Pure playback queue: which story and which sentence are being read. Pause/resume restart the current sentence;
 * next/previous jump to a story's first sentence. Positions map to an estimated audio offset for syncing.
 */
class NewsAudioQueue(val stories: List<SpokenStory>, start: Int = 0) {
    enum class Step { SENTENCE, STORY, END }

    var index = start.coerceIn(0, (stories.size - 1).coerceAtLeast(0)); private set
    var sentence = 0; private set
    var finished = stories.isEmpty(); private set

    val size get() = stories.size
    val current: SpokenStory? get() = stories.getOrNull(index)
    fun currentSentence(): String? = if (finished) null else current?.sentences?.getOrNull(sentence)

    fun jumpTo(story: Int) { if (stories.isEmpty()) return; index = story.coerceIn(0, stories.lastIndex); sentence = 0; finished = false }

    /** Next story; false (and finished) at the end of the queue. */
    fun next(): Boolean {
        if (index >= stories.lastIndex) { finished = true; return false }
        jumpTo(index + 1); return true
    }

    /** Previous story; the first story restarts. */
    fun previous() = jumpTo(index - 1)

    /** After a sentence was spoken. [continuous]: continue into the next story instead of stopping at the story's end. */
    fun advance(continuous: Boolean): Step {
        val story = current ?: run { finished = true; return Step.END }
        if (sentence < story.sentences.lastIndex) { sentence++; return Step.SENTENCE }
        if (!continuous || !next()) { finished = true; return Step.END }
        return Step.STORY
    }

    /** Estimated audio offset from the start of the queue to the current sentence. */
    fun audioMs(rate: Float): Long {
        var ms = 0L
        for (i in 0 until index) ms += stories[i].sentences.sumOf { NewsSpeech.estimateMs(it, rate) }
        current?.sentences?.take(sentence)?.forEach { ms += NewsSpeech.estimateMs(it, rate) }
        return ms
    }

    /** Moves to the sentence playing at estimated offset [ms]; past the end lands on the last sentence. */
    fun seekMs(ms: Long, rate: Float) {
        if (stories.isEmpty()) return
        var left = ms.coerceAtLeast(0)
        stories.forEachIndexed { i, story ->
            story.sentences.forEachIndexed { s, text ->
                val d = NewsSpeech.estimateMs(text, rate)
                if (left < d) { index = i; sentence = s; finished = false; return }
                left -= d
            }
        }
        index = stories.lastIndex; sentence = stories.last().sentences.lastIndex; finished = false
    }

    companion object {
        /**
         * Where to resume a briefing saved at reader [position] with [audioMs]: the audio offset wins when it falls inside
         * the saved story, otherwise the saved story's first sentence.
         */
        fun resume(stories: List<SpokenStory>, position: Int, audioMs: Long, rate: Float): NewsAudioQueue {
            val queue = NewsAudioQueue(stories, position)
            if (audioMs > 0) {
                val probe = NewsAudioQueue(stories).apply { seekMs(audioMs, rate) }
                if (probe.index == queue.index) queue.sentence = probe.sentence
            }
            return queue
        }
    }
}

data class NewsAudioState(
    val active: Boolean = false,
    val playing: Boolean = false,
    /** Null for queues that are not a briefing (e.g. "Listen to updates only"). */
    val briefingId: String? = null,
    val index: Int = 0,
    val count: Int = 0,
    val title: String = "",
    val rate: Float = 1f,
    val continuous: Boolean = true,
    val note: String? = null,
)

/**
 * Spoken news through the platform on-device TextToSpeech engine. One queue at a time; a foreground media service
 * keeps it alive in the background. Started only by an explicit Play from the owner.
 */
object NewsAudio {
    private val _state = MutableStateFlow(NewsAudioState())
    val state: StateFlow<NewsAudioState> = _state
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var app: Context? = null
    private var tts: TextToSpeech? = null
    private var ready = false
    private var queue: NewsAudioQueue? = null
    private var language = "en"
    private var generation = 0
    private var focus: AudioFocusRequest? = null
    private var startWhenReady = false

    val speedSteps = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

    /** Current estimated offset in the queue, for saving the reader position. */
    fun audioMs(): Long = queue?.takeIf { _state.value.active }?.audioMs(_state.value.rate) ?: 0

    fun play(context: Context, briefingId: String?, stories: List<SpokenStory>, start: Int = 0, startMs: Long = 0,
        rate: Float = 1f, language: String = "en", continuous: Boolean = true) {
        main.post {
            stopSpeaking()
            if (stories.isEmpty()) { _state.value = NewsAudioState(note = "Nothing to read aloud."); return@post }
            app = context.applicationContext
            this.language = language
            queue = NewsAudioQueue.resume(stories, start, startMs, rate)
            val q = queue!!
            _state.value = NewsAudioState(true, true, briefingId, q.index, q.size, q.current?.title.orEmpty(), rate.coerceIn(0.75f, 2f), continuous)
            val background = runCatching {
                context.applicationContext.startForegroundService(Intent(context.applicationContext, NewsAudioService::class.java).setAction(NewsAudioService.ACTION_SHOW))
            }.onFailure { com.hellfire.ass.Diagnostics.warn(it) }.isSuccess
            if (!background) note("Background listening is unavailable right now; keep HellFire open while listening.")
            val engine = tts
            if (engine == null) {
                startWhenReady = true
                tts = TextToSpeech(context.applicationContext) { status -> main.post { onInit(status) } }
            } else if (ready) { applyLanguage(); speakCurrent() } else startWhenReady = true
        }
    }

    fun pause() = main.post {
        if (!_state.value.active || !_state.value.playing) return@post
        stopSpeaking(); abandonFocus()
        _state.value = _state.value.copy(playing = false)
        savePosition()
    }

    /** Restarts the current sentence of the current story. */
    fun resume() = main.post {
        if (!_state.value.active || _state.value.playing) return@post
        _state.value = _state.value.copy(playing = true)
        if (ready) speakCurrent() else startWhenReady = true
    }

    fun next() = main.post { val q = queue ?: return@post; if (q.next()) moved() else finish() }
    fun previous() = main.post { val q = queue ?: return@post; q.previous(); moved() }

    /** Reader page changed while synced: read that story from its start. */
    fun jumpTo(index: Int) = main.post {
        val q = queue ?: return@post
        if (!_state.value.active || q.index == index) return@post
        q.jumpTo(index); moved()
    }

    fun setRate(rate: Float) = main.post {
        _state.value = _state.value.copy(rate = rate.coerceIn(0.75f, 2f))
        tts?.setSpeechRate(_state.value.rate)
    }

    fun stop() = main.post { finish() }

    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            tts?.shutdown(); tts = null; ready = false
            _state.value = NewsAudioState(note = "No text-to-speech engine is available. Install or enable a voice in Android settings.")
            return
        }
        ready = true
        tts?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { main.post { spoken(utteranceId) } }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { main.post { failed(utteranceId) } }
            override fun onError(utteranceId: String?, errorCode: Int) { main.post { failed(utteranceId) } }
        })
        if (startWhenReady && _state.value.active) { startWhenReady = false; applyLanguage(); if (_state.value.playing) speakCurrent() }
    }

    private fun applyLanguage() {
        val engine = tts ?: return
        if (language == "sw") {
            val result = engine.setLanguage(Locale.forLanguageTag("sw-TZ")).takeIf { it >= TextToSpeech.LANG_AVAILABLE }
                ?: engine.setLanguage(Locale.forLanguageTag("sw"))
            if (result < TextToSpeech.LANG_AVAILABLE) {
                engine.setLanguage(Locale.forLanguageTag("en-GB"))
                note("No Kiswahili voice is installed on this phone; reading with the English voice. Add a Kiswahili voice in Android text-to-speech settings.")
            }
        } else if (engine.setLanguage(Locale.forLanguageTag("en-GB")) < TextToSpeech.LANG_AVAILABLE) engine.setLanguage(Locale.ENGLISH)
    }

    private fun speakCurrent() {
        val engine = tts ?: return
        val q = queue ?: return
        val text = q.currentSentence() ?: return finish()
        if (!requestFocus()) { _state.value = _state.value.copy(playing = false); note("Another app is using audio; press Play again."); return }
        engine.setSpeechRate(_state.value.rate)
        generation++
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "news-$generation")
    }

    private fun spoken(utteranceId: String?) {
        if (utteranceId != "news-$generation" || !_state.value.playing) return
        val q = queue ?: return
        when (q.advance(_state.value.continuous)) {
            NewsAudioQueue.Step.SENTENCE -> speakCurrent()
            NewsAudioQueue.Step.STORY -> { publishIndex(); savePosition(); speakCurrent() }
            NewsAudioQueue.Step.END -> finish()
        }
    }

    private fun failed(utteranceId: String?) {
        if (utteranceId != "news-$generation") return
        note("The voice engine could not read this sentence; skipped.")
        spoken(utteranceId)
    }

    private fun moved() {
        publishIndex(); savePosition()
        if (_state.value.playing) { stopSpeaking(); if (ready) speakCurrent() }
    }

    private fun publishIndex() {
        val q = queue ?: return
        _state.value = _state.value.copy(index = q.index, title = q.current?.title.orEmpty())
    }

    private fun finish() {
        savePosition()
        stopSpeaking(); abandonFocus()
        queue = null
        _state.value = NewsAudioState(rate = _state.value.rate, note = _state.value.note)
    }

    private fun stopSpeaking() { generation++; runCatching { tts?.stop() } }

    private fun note(text: String) { _state.value = _state.value.copy(note = text) }

    private fun savePosition() {
        val s = _state.value
        val id = s.briefingId ?: return
        val q = queue ?: return
        val context = app as? HellFireApp ?: return
        val index = q.index; val ms = q.audioMs(s.rate)
        scope.launch { runCatching { context.news.savePosition(id, index, ms) }.onFailure { com.hellfire.ass.Diagnostics.warn(it) } }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
    }

    private fun requestFocus(): Boolean {
        val manager = app?.getSystemService(AudioManager::class.java) ?: return true
        val request = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener(focusListener, main).build().also { focus = it }
        return manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        val request = focus ?: return
        app?.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request)
    }
}
