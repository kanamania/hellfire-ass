package com.hellfire.ass.ui.player

import androidx.compose.ui.res.stringResource
import com.hellfire.ass.R

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.session.MediaController
import com.hellfire.ass.library.*
import com.hellfire.ass.player.*
import com.hellfire.ass.ui.components.Note
import com.hellfire.ass.ui.theme.Hf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


@Composable
internal fun VolumeBrightness(activity: Activity?) {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    val max = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()) }
    var brightness by remember { mutableFloatStateOf(0f) }
    var volumeNote by remember { mutableStateOf<String?>(null) }
    Text(stringResource(R.string.ui_volume), style = Hf.type.caption, color = Hf.colors.muted)
    Slider(volume, {
        volume = it
        val set = runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, (it * max).toInt().coerceIn(0, max), 0) }
        if (set.isFailure) volumeNote = context.getString(R.string.ui_volume_control_was_refused_by_this)
    }, valueRange = 0f..1f)
    volumeNote?.let { Note(it) }
    Text(stringResource(R.string.ui_brightness), style = Hf.type.caption, color = Hf.colors.muted)
    Slider(brightness, {
        brightness = it
        val window = activity?.window ?: return@Slider
        val params = window.attributes
        params.screenBrightness = if (it <= 0.02f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else it
        window.attributes = params
    }, valueRange = 0f..1f)
}

@Composable
internal fun TrackRow(controller: MediaController?, tracks: Tracks, scope: kotlinx.coroutines.CoroutineScope, app: com.hellfire.ass.HellFireApp, itemId: String) {
    val player = controller
    val audio = tracks.groups.withIndex().filter { it.value.type == C.TRACK_TYPE_AUDIO }
    val text = tracks.groups.withIndex().filter { it.value.type == C.TRACK_TYPE_TEXT }
    if (player == null || (audio.isEmpty() && text.isEmpty())) {
        Note(stringResource(R.string.ui_no_extra_audio_or_subtitle_tracks))
        return
    }
    @Composable
    fun choices(groups: List<IndexedValue<Tracks.Group>>) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            for ((groupIndex, group) in groups) {
                for (track in 0 until group.length) {
                    if (!group.isTrackSupported(track)) continue
                    val format = group.getTrackFormat(track)
                    val label = format.label ?: format.language ?: "${track + 1}"
                    TextButton({
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track))
                            .setTrackTypeDisabled(group.type, false)
                            .build()
                        val key = PlaybackLogic.trackKey(group.type, groupIndex, track)
                        if (group.type == C.TRACK_TYPE_AUDIO) PlaybackFlags.audioTrack = key else PlaybackFlags.subtitleTrack = key
                        scope.launch {
                            persistChoice(app, itemId) {
                                if (group.type == C.TRACK_TYPE_AUDIO) it.copy(audioTrack = key) else it.copy(subtitleTrack = key)
                            }
                        }
                    }) { Text(if (group.isTrackSelected(track)) "·$label" else label) }
                }
            }
        }
    }
    if (audio.isNotEmpty()) {
        Text(stringResource(R.string.ui_audio_track), style = Hf.type.caption, color = Hf.colors.muted)
        choices(audio)
    }
    if (text.isNotEmpty()) {
        Text("Subtitles", style = Hf.type.caption, color = Hf.colors.muted)
        choices(text)
        TextButton({
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            PlaybackFlags.subtitleTrack = "off"
            scope.launch { persistChoice(app, itemId) { it.copy(subtitleTrack = "off") } }
        }) { Text(stringResource(R.string.ui_subtitles_off)) }
    }
}

@Composable
internal fun BookmarkThumb(app: com.hellfire.ass.HellFireApp, relative: String?) {
    val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, relative) {
        value = withContext(Dispatchers.IO) {
            FrameScrubber.thumbFile(app, relative)?.let { file ->
                android.graphics.BitmapFactory.decodeFile(file.absolutePath, android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap()
            }
        }
    }
    image?.let { androidx.compose.foundation.Image(it, null, Modifier.height(40.dp)) }
}

@Composable
internal fun Timeline(drawn: List<TimelineMarker>, total: Int, ab: AbRange?, position: Long, duration: Long, onSeek: (Long) -> Unit) {
    Canvas(Modifier.fillMaxWidth().height(36.dp).pointerInput(duration) {
        detectTapGestures { offset -> if (duration > 0) onSeek((offset.x / size.width * duration).toLong()) }
    }) {
        val width = size.width
        val length = duration.coerceAtLeast(1).toFloat()
        drawRect(Color(0x22000000))
        ab?.let {
            val left = it.startMs / length * width
            val right = it.endMs / length * width
            drawRect(Color(0x553498DB), topLeft = androidx.compose.ui.geometry.Offset(left, 0f), size = androidx.compose.ui.geometry.Size((right - left).coerceAtLeast(2f), size.height))
        }
        drawn.forEach { marker ->
            val x = marker.startMs / length * width
            val color = when (marker.kind) {
                MarkerKind.BOOKMARK -> Color(0xFFE67E22)
                MarkerKind.AI, MarkerKind.SCENE -> Color(0xFF8E44AD)
                MarkerKind.CHAPTER -> Color(0xFF27AE60)
            }
            drawLine(color, androidx.compose.ui.geometry.Offset(x, 4f), androidx.compose.ui.geometry.Offset(x, size.height - 4f), 3f)
        }
        val head = position / length * width
        drawLine(Color.White, androidx.compose.ui.geometry.Offset(head, 0f), androidx.compose.ui.geometry.Offset(head, size.height), 2f)
    }
    if (total == 0) Note(stringResource(R.string.ui_no_bookmarks_or_chapters_yet))
}

@Composable
internal fun Waveform(peaks: FloatArray, position: Long, duration: Long, onSeek: (Long) -> Unit) {
    Canvas(Modifier.fillMaxWidth().height(64.dp).pointerInput(duration) {
        detectTapGestures { offset -> if (duration > 0) onSeek((offset.x / size.width * duration).toLong()) }
    }) {
        val step = size.width / peaks.size.coerceAtLeast(1)
        peaks.forEachIndexed { index, peak ->
            val height = (peak.coerceIn(0f, 1f) * size.height).coerceAtLeast(2f)
            val x = index * step
            drawLine(Color(0xFF5D6D7E), androidx.compose.ui.geometry.Offset(x, (size.height - height) / 2), androidx.compose.ui.geometry.Offset(x, (size.height + height) / 2), step.coerceAtLeast(1f))
        }
        if (duration > 0) {
            val x = position / duration.toFloat() * size.width
            drawLine(Color(0xFFE74C3C), androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), 2f)
        }
    }
}

internal fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("transcript", text))
}

internal fun restoreBrightness(activity: Activity?) {
    val window = activity?.window ?: return
    val params = window.attributes
    params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    window.attributes = params
}

internal fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
