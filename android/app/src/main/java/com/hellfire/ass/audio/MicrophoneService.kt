package com.hellfire.ass.audio

import android.annotation.SuppressLint
import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.os.Build
import android.os.IBinder
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.MainActivity
import com.hellfire.ass.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import com.hellfire.ass.Diagnostics
import com.hellfire.ass.media.AacEncoderStream
import com.hellfire.ass.media.ChunkedMedia
import com.hellfire.ass.media.LearnedVad
import com.hellfire.ass.media.MediaWork
import com.hellfire.ass.media.RecordingPolicy
import com.hellfire.ass.media.SpeechActivity
import com.hellfire.ass.media.VadModels
import com.hellfire.ass.widgets.Widgets

object MicrophoneStatus {
    val state = MutableStateFlow("Off")
    val active = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
}

class MicrophoneService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var capturing = false
    private var job: Job? = null
    @Volatile private var queueAfter = false
    private fun refreshWidget() { (application as HellFireApp).captureScope.launch { Widgets.refreshRecord(application) } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            capturing = false
            if (job == null) stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || job?.isActive == true) return START_NOT_STICKY
        val blocked = startBlock(this, intent)
        if (blocked != null) {
            MicrophoneStatus.state.value = blocked
            stopSelf()
            return START_NOT_STICKY
        }
        val monitoring = intent.getBooleanExtra("monitoring", false)
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Microphone recording", NotificationManager.IMPORTANCE_LOW))
            if (manager.getNotificationChannel(CHANNEL).importance == NotificationManager.IMPORTANCE_NONE) {
                MicrophoneStatus.state.value = "Recording channel blocked. Enable it in notification settings."
                stopSelf()
                return START_NOT_STICKY
            }
            val stop = PendingIntent.getService(this, 1,
                Intent(this, MicrophoneService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
            val open = PendingIntent.getActivity(this, 2,
                Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_hellfire).setContentTitle("HellFire Ass · microphone recording")
                .setContentText(if (monitoring) "Owner-started conversation monitoring · compressed · 30 minute limit" else "User-started test · stops after 60 seconds")
                .setContentIntent(open).setOngoing(true)
                .addAction(Notification.Action.Builder(null, "Stop recording", stop).build()).build()
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(41, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(41, notification)
            }
        } catch (caught: Exception) { Diagnostics.warn(caught);
            MicrophoneStatus.state.value = "Recording unavailable: Android denied the foreground microphone service."
            stopSelf()
            return START_NOT_STICKY
        }
        capturing = true
        queueAfter = intent.getBooleanExtra("queue", false)
        if (monitoring) getSharedPreferences("widgets", 0).edit().putLong("record_started_at", System.currentTimeMillis()).apply()
        refreshWidget()
        MicrophoneStatus.busy.value = true
        MicrophoneStatus.state.value = "Starting microphone…"
        job = scope.launch { recordSample(monitoring) }
        return START_NOT_STICKY
    }

    // Start is permission-checked; revocation during capture is handled as a visible error.
    @SuppressLint("MissingPermission")
    private suspend fun recordSample(monitoring: Boolean) {
        var recorder: AudioRecord? = null
        var file: File? = null
        var written = 0
        val startedAt = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        var learnedVad: LearnedVad? = null
        val maximum = if (monitoring) SAMPLE_RATE * 2 * 30 * 60 else MAX_BYTES
        try {
            val bufferSize = maxOf(4096, AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))
            recorder = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(bufferSize).build()
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
            val app = application as HellFireApp
            if (monitoring && VadModels.file(app).isFile) {
                learnedVad = runCatching { LearnedVad.open(VadModels.file(app)) }
                    .onFailure(Diagnostics::warn).getOrNull()
            }
            val activity = if (learnedVad == null) SpeechActivity() else SpeechActivity(frameProbability = { frame ->
                runCatching { learnedVad?.probability(frame) }.getOrElse {
                    Diagnostics.warn(it)
                    runCatching { learnedVad?.close() }; learnedVad = null
                    MicrophoneStatus.state.value = "Monitoring active · encrypted · adaptive VAD fallback · 30 minute limit"
                    null
                }
            })
            val cipher = app.cipher
            file = if (monitoring) app.media.file(id) else File(recordingDirectory(app), "$id.pcm.enc")
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            MicrophoneStatus.active.value = true
            MicrophoneStatus.state.value = if (monitoring) "Monitoring active · visibly recording · encrypted · 30 minute limit" else "Recording · encrypted on device · 60 second limit"
            if (monitoring) MicrophoneStatus.state.value += if (learnedVad != null) " · learned VAD" else " · adaptive energy VAD"
            val audio = recorder
            var nextNoticeCheck = 0L
            val input = object : InputStream() {
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (!capturing || written >= maximum) return -1
                    if (android.os.SystemClock.elapsedRealtime() >= nextNoticeCheck) {
                        val notices = getSystemService(NotificationManager::class.java)
                        if (!notices.areNotificationsEnabled() || notices.getNotificationChannel(CHANNEL).importance == NotificationManager.IMPORTANCE_NONE)
                            throw IOException("Recording notice was disabled")
                        nextNoticeCheck = android.os.SystemClock.elapsedRealtime() + 1000
                    }
                    if (audio.activeRecordingConfiguration?.isClientSilenced == true) {
                        throw IOException("Android silenced the microphone")
                    }
                    val count = audio.read(buffer, offset, minOf(length, 2048, maximum - written), AudioRecord.READ_BLOCKING)
                    if (count <= 0) throw IOException("Microphone interrupted")
                    if (monitoring) activity.accept(buffer, offset, count)
                    written += count
                    return count
                }
            }
            if (monitoring) {
                // Compressed AAC-LC (ADTS, 32 kbit/s) instead of 256 kbit/s PCM; VAD still sees the raw PCM above.
                val saved = AacEncoderStream(input).use { encoded ->
                    ChunkedMedia.write(encoded, file, id, false, cipher, retainInterrupted = true)
                }
                if (saved.size > 0) {
                    val item = app.media.registerRecording(id, startedAt, saved.interrupted, activity.finish(), written.toLong() * 1000 / (SAMPLE_RATE * 2))
                    if (queueAfter) {
                        // Started from the Record widget: queue local transcription (charging and battery rules still apply).
                        runCatching { MediaWork.queue(app, item.id) }
                        getSharedPreferences("widgets", 0).edit().putLong("record_saved_at", System.currentTimeMillis()).apply()
                    }
                }
                if (saved.interrupted) { MicrophoneStatus.state.value = "Monitoring interrupted · authenticated original retained in Media"; return }
            } else file.outputStream().use { output -> cipher.encryptStream(input, output) }
            MicrophoneStatus.state.value = if (written > 0) "Saved encrypted ${if (monitoring) "conversation in Media" else "sample"} · ${written / (SAMPLE_RATE * 2)} seconds"
                else "Stopped before audio was captured"
            if (written == 0) file.delete()
        } catch (caught: Exception) { Diagnostics.warn(caught);
            // A failed GCM stream is not a playable sample; never report it as saved.
            if (!monitoring) file?.delete()
            MicrophoneStatus.state.value = "Error: microphone interrupted, silenced, permission denied, or storage unavailable. Start again from the app."
        } finally {
            runCatching { learnedVad?.close() }
            capturing = false
            try { recorder?.stop() } catch (caught: Exception) { Diagnostics.warn(caught); }
            recorder?.release()
            MicrophoneStatus.active.value = false
            MicrophoneStatus.busy.value = false
            getSharedPreferences("widgets", 0).edit().putLong("record_started_at", 0).apply()
            refreshWidget()
            withContext(Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        capturing = false
        if (job == null) scope.cancel() else job?.invokeOnCompletion { scope.cancel() }
        super.onDestroy()
    }

    companion object {
        /** Check current grants/consent before creating Android's foreground-service start obligation. */
        fun requestStart(context: android.content.Context, intent: Intent): Boolean {
            require(intent.component?.className == MicrophoneService::class.java.name && intent.action == ACTION_START)
            startBlock(context, intent)?.let { MicrophoneStatus.state.value = it; return false }
            return try { context.startForegroundService(intent) != null }
            catch (error: Exception) {
                Diagnostics.warn(error)
                MicrophoneStatus.state.value = "Android blocked recording. Keep the app visible and check permissions."
                false
            }
        }

        private fun startBlock(context: android.content.Context, intent: Intent): String? {
            val notices = context.getSystemService(NotificationManager::class.java)
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED || !notices.areNotificationsEnabled())
                return "Permission missing: allow microphone and recording notifications first."
            if (notices.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE)
                return "Recording channel blocked. Enable it in notification settings."
            if (intent.getBooleanExtra("monitoring", false)) {
                val allowed = runCatching {
                    val app = (context as? HellFireApp) ?: context.applicationContext as HellFireApp
                    val settings = app.secrets.read("recording-exclusions")
                    intent.getBooleanExtra("notice", false) && RecordingPolicy.allowed(
                        intent.getStringExtra("participants").orEmpty(), intent.getStringExtra("place").orEmpty(),
                        settings?.optString("people").orEmpty(), settings?.optString("places").orEmpty())
                }.getOrDefault(false)
                if (!allowed) return "Monitoring blocked: consent notice or session exclusions need review."
            }
            return null
        }

        const val ACTION_START = "com.hellfire.ass.START_RECORDING"
        const val ACTION_STOP = "com.hellfire.ass.STOP_RECORDING"
        const val CHANNEL = "microphone"
        const val SAMPLE_RATE = 16000
        const val MAX_BYTES = SAMPLE_RATE * 2 * 60
        fun recordingDirectory(app: android.content.Context): File = File(app.filesDir, "recordings").apply { mkdirs() }
    }
}
