package com.hellfire.ass.player

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
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
import java.util.UUID

/** The only ExoPlayer. Full player, mini player and picture-in-picture attach to this session. */
@androidx.annotation.OptIn(UnstableApi::class)
class HellFirePlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var abMessage: androidx.media3.exoplayer.PlayerMessage? = null
    private var sleepJob: Job? = null
    private var historyId: String? = null
    private var historyItem: String? = null
    private var appliedItem: String? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as HellFireApp
        val dataSource = DefaultDataSource.Factory(this, VaultDataSource.Factory(app))
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        exo.addListener(listener)
        player = exo
        session = MediaSession.Builder(this, exo).build().also { addSession(it) }
        scope.launch { PlaybackFlags.ab.collect { scheduleAb(it) } }
        scope.launch { PlaybackFlags.sleepUntil.collect { scheduleSleep(it) } }
        scope.launch { PlaybackFlags.sleepAtEnd.collect { exo.pauseAtEndOfMediaItems = it } }
        scope.launch { PlaybackFlags.skipMs.collect { exo.setSeekBackIncrementMs(it); exo.setSeekForwardIncrementMs(it) } }
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
        persist(false)
        endHistory(player?.currentPosition ?: 0)
        scope.cancel()
        session?.release()
        player?.release()
        session = null
        player = null
        super.onDestroy()
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
            endHistory(player?.currentPosition ?: 0)
            appliedItem = null
            PlaybackFlags.error.value = null
            player?.let { publish(it); if (it.isPlaying) startHistory(it) }
            persist(false)
        }

        override fun onPlayerError(error: PlaybackException) {
            PlaybackFlags.error.value = PlaybackLogic.playbackError(error.errorCodeName)
            PlaybackFlags.playing.value = false
            persist(false)
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            player?.let { publish(it); scheduleAb(PlaybackFlags.ab.value) }
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
        abMessage = exo.createMessage { _, _ ->
            val current = PlaybackFlags.ab.value ?: return@createMessage
            exo.seekTo(current.startMs)
            scheduleAb(current)
        }.setPosition(range.endMs).send()
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

    private fun startHistory(exo: ExoPlayer) {
        val id = exo.currentMediaItem?.mediaId ?: return
        if (historyItem == id && historyId != null) return
        endHistory(exo.currentPosition)
        historyItem = id
        historyId = UUID.randomUUID().toString()
        val entry = historyId ?: return
        val from = exo.currentPosition.coerceAtLeast(0)
        scope.launch(Dispatchers.IO) {
            runCatching { (application as HellFireApp).database.playback().insertHistory(PlayHistory(entry, id, System.currentTimeMillis(), fromMs = from)) }
        }
    }

    private fun endHistory(toMs: Long) {
        val id = historyId ?: return
        historyId = null
        historyItem = null
        scope.launch(Dispatchers.IO) {
            runCatching { (application as HellFireApp).database.playback().endHistory(id, System.currentTimeMillis(), toMs.coerceAtLeast(0)) }
        }
    }

    private fun persist(completed: Boolean) {
        val exo = player ?: return
        val id = exo.currentMediaItem?.mediaId ?: return
        val now = System.currentTimeMillis()
        val position = exo.currentPosition.coerceAtLeast(0)
        val speed = exo.playbackParameters.speed
        val range = PlaybackFlags.ab.value
        scope.launch(Dispatchers.IO) {
            runCatching {
                val db = (application as HellFireApp).database.playback()
                val existing = db.playback(id)
                db.savePlayback((existing ?: PlaybackState(id, lastPlayedAt = now)).copy(
                    positionMs = position, speed = speed, audioTrack = PlaybackFlags.audioTrack, subtitleTrack = PlaybackFlags.subtitleTrack,
                    loopStartMs = range?.startMs, loopEndMs = range?.endMs, completed = completed, lastPlayedAt = now,
                ))
            }
        }
    }
}
