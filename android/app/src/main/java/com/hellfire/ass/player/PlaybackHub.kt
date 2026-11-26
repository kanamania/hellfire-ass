package com.hellfire.ass.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.library.Availability
import com.hellfire.ass.library.ContentKind
import com.hellfire.ass.library.StorageMode
import com.hellfire.ass.media.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One MediaController for the service-owned player. UI presentations share it. */
object PlaybackHub {
    private var future: ListenableFuture<MediaController>? = null

    fun connect(context: Context): ListenableFuture<MediaController> {
        future?.let { if (!it.isCancelled) return it }
        val app = context.applicationContext
        val token = SessionToken(app, ComponentName(app, HellFirePlaybackService::class.java))
        return MediaController.Builder(app, token).buildAsync().also { future = it }
    }

    suspend fun controller(context: Context): MediaController = suspendCancellableCoroutine { cont ->
        val pending = connect(context)
        pending.addListener({
            runCatching { cont.resume(pending.get()) }.onFailure {
                future = null
                cont.resumeWithException(it)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun toggle(context: Context) {
        val pending = connect(context)
        pending.addListener({
            val controller = runCatching { pending.get() }.getOrNull() ?: return@addListener
            if (controller.isPlaying) controller.pause() else controller.play()
        }, ContextCompat.getMainExecutor(context))
    }

    suspend fun open(app: HellFireApp, controller: MediaController, itemId: String, requestedStartMs: Long?) {
        val item = app.database.library().item(itemId)
        if (item == null) {
            PlaybackFlags.error.value = com.hellfire.ass.ui.UiLanguage.context(app).getString(com.hellfire.ass.R.string.ui_original_is_not_available)
            return
        }
        val playable = item.contentKind == ContentKind.AUDIO.name || item.contentKind == ContentKind.VIDEO.name ||
            item.mime.startsWith("audio/") || item.mime.startsWith("video/")
        if (!playable) {
            PlaybackFlags.error.value = com.hellfire.ass.ui.UiLanguage.context(app).getString(com.hellfire.ass.R.string.ui_this_item_is_not_audio_or_video)
            return
        }
        if (item.availability == Availability.MISSING.name || item.availability == Availability.PERMISSION_REVOKED.name) {
            PlaybackFlags.error.value = "Original is not available (${item.availability.replace('_', ' ').lowercase()})."
            return
        }
        if (item.storage == StorageMode.EXTERNAL.name && item.externalUri.isNullOrBlank()) {
            PlaybackFlags.error.value = com.hellfire.ass.ui.UiLanguage.context(app).getString(com.hellfire.ass.R.string.ui_this_external_item_has_no_content_uri)
            return
        }
        val saved = app.database.playback().playback(itemId)
        val start = PlaybackLogic.resumeMs(saved?.positionMs ?: 0, saved?.completed == true, requestedStartMs, item.durationMs)
        val queue = PlayerStore.queue(app, item)
        val index = queue.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
        val media = queue.map { exoItem(app, it) }
        PlaybackFlags.pendingAudio = saved?.audioTrack
        PlaybackFlags.pendingSubtitle = saved?.subtitleTrack
        PlaybackFlags.audioTrack = saved?.audioTrack
        PlaybackFlags.subtitleTrack = saved?.subtitleTrack
        saved?.speed?.takeIf { it > 0f }?.let { PlaybackFlags.speed.value = it }
        val loop = saved?.loopStartMs?.let { startMs -> saved.loopEndMs?.let { endMs -> if (endMs > startMs) AbRange(startMs, endMs) else null } }
        withContext(Dispatchers.Main) {
            PlaybackFlags.error.value = null
            PlaybackFlags.video.value = item.contentKind == ContentKind.VIDEO.name || item.mime.startsWith("video/")
            PlaybackFlags.ab.value = loop
            if (!PlaybackLogic.shouldRestart(controller.currentMediaItem?.mediaId, itemId, requestedStartMs) &&
                controller.playbackState != Player.STATE_IDLE && controller.playbackState != Player.STATE_ENDED) {
                PlaybackFlags.present(PresentationEvent.OPEN)
                return@withContext
            }
            controller.setMediaItems(media, index, start)
            saved?.speed?.takeIf { it > 0f }?.let { controller.setPlaybackSpeed(it) }
            controller.prepare()
            controller.play()
            PlaybackFlags.present(PresentationEvent.OPEN)
        }
    }

    fun close(controller: MediaController) {
        controller.pause()
        controller.clearMediaItems()
        PlaybackFlags.ab.value = null
        PlaybackFlags.sleepUntil.value = null
        PlaybackFlags.sleepAtEnd.value = false
        PlaybackFlags.video.value = false
        PlaybackFlags.playing.value = false
        PlaybackFlags.itemId.value = null
        PlaybackFlags.present(PresentationEvent.CLOSE)
    }

    private suspend fun exoItem(app: HellFireApp, item: MediaItem): androidx.media3.common.MediaItem = withContext(Dispatchers.IO) {
        val builder = androidx.media3.common.MediaItem.Builder()
            .setMediaId(item.id)
            .setUri(playbackUri(item))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(item.name).setArtist("HellFire Ass").build())
        if (item.storage == StorageMode.MANAGED.name && app.media.file(item.id).isFile) {
            val pcm = runCatching { app.media.reader(item).use { it.pcm } }.getOrDefault(false)
            if (pcm) builder.setMimeType(androidx.media3.common.MimeTypes.AUDIO_WAV)
            else if (item.mime.isNotBlank()) builder.setMimeType(item.mime)
        } else if (item.mime.isNotBlank()) builder.setMimeType(item.mime)
        builder.build()
    }

    fun playbackUri(item: MediaItem): Uri = if (item.storage == StorageMode.EXTERNAL.name) Uri.parse(item.externalUri)
        else VaultDataSource.uri(item.id)
}
