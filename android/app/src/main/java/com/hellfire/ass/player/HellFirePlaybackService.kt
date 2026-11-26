package com.hellfire.ass.player

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.library.PlaybackState
import com.hellfire.ass.library.PlayHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** The only ExoPlayer. Full player, mini player and picture-in-picture attach to this session. */
@androidx.annotation.OptIn(UnstableApi::class)
class HellFirePlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var abMessage: PlayerMessage? = null
    private var sleepJob: Job? = null
    private var historyId: String? = null
    private var historyItem: String? = null
    private var appliedItem: String? = null
    /** Last known item and position, kept so the item being left is saved before the player switches. */
    private var lastId: String? = null
    private var lastPositionMs = 0L

    private val app get() = application as HellFireApp

    override fun onCreate() {
        super.onCreate()
        val dataSource = DefaultDataSource.Factory(this, VaultDataSource.Factory(app))
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        exo.addListener(listener)
        player = exo
        session = MediaSession.Builder(this, exo).setCallback(access).build().also { addSession(it) }
        scope.launch { PlaybackFlags.ab.collect { scheduleAb(it) } }
        scope.launch { PlaybackFlags.sleepUntil.collect { scheduleSleep(it) } }
        scope.launch { PlaybackFlags.sleepAtEnd.collect { exo.pauseAtEndOfMediaItems = it } }
        scope.launch { PlaybackFlags.skipMs.collect { exo.setSeekBackIncrementMs(it); exo.setSeekForwardIncrementMs(it) } }
        scope.launch {
            PlaybackLogic.tickPositions(PlaybackLogic.TICK_MS, { exo.isPlaying }, { exo.currentPosition }) { position ->
                PlaybackFlags.positionMs.value = position
                PlaybackFlags.durationMs.value = exo.duration.coerceAtLeast(0)
                lastId = exo.currentMediaItem?.mediaId
                lastPositionMs = position
            }
        }
        scope.launch {
            while (isActive) {
                delay(5_000)
                if (exo.isPlaying) persist(false)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val exo = player
        if (exo == null || !exo.playWhenReady || exo.mediaItemCount == 0) pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        // Writes run on the application scope so they finish after this service's scope is cancelled.
        persist(false)
        endHistory(player?.currentPosition ?: 0)
        scope.cancel()
        session?.release()
        player?.release()
        session = null
        player = null
        PlaybackFlags.videoAspect.value = null
        super.onDestroy()
    }

    private val access = object : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val allowed = SessionAccess.allowed(
                packageName, controller.packageName, controller.isTrusted, session.isMediaNotificationController(controller),
                session.isAutomotiveController(controller) || session.isAutoCompanionController(controller),
            )
            return if (allowed) super.onConnect(session, controller) else MediaSession.ConnectionResult.reject()
        }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            PlaybackFlags.playing.value = isPlaying
            val exo = player ?: return
            if (isPlaying) startHistory(exo) else endHistory(exo.currentPosition)
            publish(exo)
            if (!isPlaying) persist(exo.playbackState == Player.STATE_ENDED)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val exo = player ?: return
            publish(exo)
            if (playbackState == Player.STATE_READY) applyTracks(exo)
            if (playbackState == Player.STATE_ENDED) persist(true)
        }

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            val exo = player ?: return
            val newId = mediaItem?.mediaId
            val leaving = PlaybackLogic.outgoing(lastId, lastPositionMs, newId, reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
            if (leaving != null) {
                endHistory(leaving.positionMs)
                // A new playlist was opened through PlaybackHub.open, which already loaded the new item's choices.
                val ownChoices = reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
                save(leaving.itemId, leaving.positionMs, leaving.completed, ownChoices)
                if (ownChoices && newId != null) restore(newId)
            }
            lastId = newId
            lastPositionMs = exo.currentPosition.coerceAtLeast(0)
            appliedItem = null
            PlaybackFlags.error.value = null
            publish(exo)
            if (exo.isPlaying) startHistory(exo)
        }

        override fun onPlayerError(error: PlaybackException) {
            PlaybackFlags.error.value = PlaybackLogic.playbackError(error.errorCodeName)?.let { com.hellfire.ass.ui.UiLanguage.context(this@HellFirePlaybackService).getString(it) }
                ?: "Playback failed (${error.errorCodeName}). The original was not modified."
            PlaybackFlags.playing.value = false
            persist(false)
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            // Called before onMediaItemTransition: remember where the old item stopped.
            oldPosition.mediaItem?.mediaId?.let { lastId = it; lastPositionMs = oldPosition.positionMs }
            if (newPosition.mediaItem?.mediaId == oldPosition.mediaItem?.mediaId) lastPositionMs = newPosition.positionMs
            player?.let { publish(it); scheduleAb(PlaybackFlags.ab.value) }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            PlaybackFlags.videoAspect.value = if (videoSize.width > 0 && videoSize.height > 0)
                videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height else null
        }
    }

    private fun publish(exo: ExoPlayer) {
        PlaybackFlags.itemId.value = exo.currentMediaItem?.mediaId
        PlaybackFlags.title.value = exo.mediaMetadata.title?.toString().orEmpty()
        PlaybackFlags.positionMs.value = exo.currentPosition.coerceAtLeast(0)
        PlaybackFlags.durationMs.value = exo.duration.coerceAtLeast(0)
        PlaybackFlags.speed.value = exo.playbackParameters.speed
        PlaybackFlags.playing.value = exo.isPlaying
    }

    private fun scheduleAb(range: AbRange?) {
        val exo = player ?: return
        abMessage?.cancel()
        abMessage = null
        if (range == null) return
        PlaybackLogic.abSeek(exo.currentPosition, range)?.let { exo.seekTo(it) }
        abMessage = abLoopMessage(exo, range.endMs) {
            val current = PlaybackFlags.ab.value ?: return@abLoopMessage
            exo.seekTo(current.startMs)
            scheduleAb(current)
        }
    }

    private fun scheduleSleep(until: Long?) {
        sleepJob?.cancel()
        if (until == null) return
        sleepJob = scope.launch {
            val wait = until - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            player?.pause()
            PlaybackFlags.sleepUntil.value = null
        }
    }

    private fun applyTracks(exo: ExoPlayer) {
        val id = exo.currentMediaItem?.mediaId ?: return
        if (appliedItem == id) return
        appliedItem = id
        val audio = PlaybackFlags.pendingAudio
        val text = PlaybackFlags.pendingSubtitle
        if (audio == null && text == null) return
        val builder = exo.trackSelectionParameters.buildUpon()
        if (text == "off") builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).clearOverridesOfType(C.TRACK_TYPE_TEXT)
        listOf(C.TRACK_TYPE_AUDIO to audio, C.TRACK_TYPE_TEXT to text).forEach { (type, key) ->
            val parsed = key?.let { PlaybackLogic.parseTrackKey(it) } ?: return@forEach
            if (parsed.first != type) return@forEach
            val group = exo.currentTracks.groups.getOrNull(parsed.second) ?: return@forEach
            if (group.type != type || parsed.third !in 0 until group.length) return@forEach
            builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, parsed.third))
            builder.setTrackTypeDisabled(type, false)
        }
        exo.trackSelectionParameters = builder.build()
    }

    /** Loads the next queue item's own loop, tracks and resume position after an automatic or user skip. */
    private fun restore(itemId: String) {
        PlaybackFlags.ab.value = null
        PlaybackFlags.pendingAudio = null
        PlaybackFlags.pendingSubtitle = null
        PlaybackFlags.audioTrack = null
        PlaybackFlags.subtitleTrack = null
        scope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { app.database.playback().playback(itemId) }.getOrNull() }
            val exo = player ?: return@launch
            if (exo.currentMediaItem?.mediaId != itemId || saved == null) return@launch
            PlaybackFlags.pendingAudio = saved.audioTrack
            PlaybackFlags.pendingSubtitle = saved.subtitleTrack
            PlaybackFlags.audioTrack = saved.audioTrack
            PlaybackFlags.subtitleTrack = saved.subtitleTrack
            PlaybackFlags.ab.value = PlaybackLogic.savedLoop(saved.loopStartMs, saved.loopEndMs)
            val resume = PlaybackLogic.resumeMs(saved.positionMs, saved.completed, null, exo.duration.takeIf { it > 0 })
            if (resume > 0 && exo.currentPosition < 1_000) exo.seekTo(resume)
            appliedItem = null
            if (exo.playbackState == Player.STATE_READY) applyTracks(exo)
        }
    }

    private fun startHistory(exo: ExoPlayer) {
        val id = exo.currentMediaItem?.mediaId ?: return
        if (historyItem == id && historyId != null) return
        endHistory(exo.currentPosition)
        historyItem = id
        historyId = UUID.randomUUID().toString()
        val entry = historyId ?: return
        val from = exo.currentPosition.coerceAtLeast(0)
        val app = app
        app.captureScope.launch {
            runCatching { app.database.playback().insertHistory(PlayHistory(entry, id, System.currentTimeMillis(), fromMs = from)) }
        }
    }

    private fun endHistory(toMs: Long) {
        val id = historyId ?: return
        historyId = null
        historyItem = null
        val app = app
        app.captureScope.launch {
            runCatching { app.database.playback().endHistory(id, System.currentTimeMillis(), toMs.coerceAtLeast(0)) }
        }
    }

    private fun persist(completed: Boolean) {
        val exo = player ?: return
        val id = exo.currentMediaItem?.mediaId ?: return
        save(id, exo.currentPosition.coerceAtLeast(0), completed, true)
    }

    /** [withChoices] also stores speed, loop and tracks; otherwise only position and completion change. */
    private fun save(id: String, positionMs: Long, completed: Boolean, withChoices: Boolean) {
        val now = System.currentTimeMillis()
        val speed = player?.playbackParameters?.speed ?: PlaybackFlags.speed.value
        val range = PlaybackFlags.ab.value
        val audio = PlaybackFlags.audioTrack
        val subtitle = PlaybackFlags.subtitleTrack
        val app = app
        app.captureScope.launch {
            runCatching {
                val db = app.database.playback()
                val base = db.playback(id) ?: PlaybackState(id, lastPlayedAt = now)
                db.savePlayback(
                    if (withChoices) base.copy(positionMs = positionMs, speed = speed, audioTrack = audio, subtitleTrack = subtitle,
                        loopStartMs = range?.startMs, loopEndMs = range?.endMs, completed = completed, lastPlayedAt = now)
                    else base.copy(positionMs = positionMs, completed = completed, lastPlayedAt = now),
                )
            }
        }
    }

    companion object {
        /** The A–B message must run on the application thread: ExoPlayer rejects seekTo from its playback thread. */
        fun abLoopMessage(exo: ExoPlayer, endMs: Long, onReach: () -> Unit): PlayerMessage =
            exo.createMessage { _, _ -> onReach() }
                .setLooper(exo.applicationLooper)
                .setPosition(endMs)
                .setDeleteAfterDelivery(true)
                .send()
    }
}
