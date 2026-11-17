package com.hellfire.ass.news

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import com.hellfire.ass.MainActivity
import com.hellfire.ass.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Keeps a spoken news briefing playing in the background with a visible media notification (Pause/Resume, Next, Stop). */
class NewsAudioService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> NewsAudio.pause()
            ACTION_RESUME -> NewsAudio.resume()
            ACTION_NEXT -> NewsAudio.next()
            ACTION_STOP -> { NewsAudio.stop(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        }
        try {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "News briefing playback", NotificationManager.IMPORTANCE_LOW))
            startForeground(NOTIFICATION_ID, notification(NewsAudio.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } catch (caught: Exception) {
            com.hellfire.ass.Diagnostics.warn(caught)
            stopSelf(); return START_NOT_STICKY
        }
        if (watcher == null) watcher = scope.launch {
            NewsAudio.state.collect { s ->
                if (!s.active) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                else runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(s)) }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun action(action: String, code: Int) =
        PendingIntent.getService(this, code, Intent(this, NewsAudioService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)

    private fun notification(s: NewsAudioState): Notification {
        val link = s.briefingId?.let { "hellfire://news/briefing/" + Uri.encode(it) } ?: "hellfire://news"
        val open = PendingIntent.getActivity(this, 60, Intent(this, MainActivity::class.java).setData(Uri.parse(link)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val toggle = if (s.playing) Notification.Action.Builder(null, "Pause", action(ACTION_PAUSE, 61)).build()
            else Notification.Action.Builder(null, "Resume", action(ACTION_RESUME, 62)).build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_hellfire)
            .setContentTitle("HellFire Ass news briefing")
            .setContentText(if (s.count > 0) "Story ${s.index + 1} of ${s.count} · ${s.title}" else s.title)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .setOngoing(s.playing)
            .setOnlyAlertOnce(true)
            .addAction(toggle)
            .addAction(Notification.Action.Builder(null, "Next", action(ACTION_NEXT, 63)).build())
            .addAction(Notification.Action.Builder(null, "Stop", action(ACTION_STOP, 64)).build())
            .setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    companion object {
        const val ACTION_SHOW = "com.hellfire.ass.news.AUDIO_SHOW"
        const val ACTION_PAUSE = "com.hellfire.ass.news.AUDIO_PAUSE"
        const val ACTION_RESUME = "com.hellfire.ass.news.AUDIO_RESUME"
        const val ACTION_NEXT = "com.hellfire.ass.news.AUDIO_NEXT"
        const val ACTION_STOP = "com.hellfire.ass.news.AUDIO_STOP"
        private const val CHANNEL = "news-audio"
        private const val NOTIFICATION_ID = 47
    }
}
