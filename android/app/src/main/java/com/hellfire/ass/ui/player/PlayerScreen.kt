package com.hellfire.ass.ui.player

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.ui.PlayerView
import com.hellfire.ass.library.*
import com.hellfire.ass.media.MediaItem
import com.hellfire.ass.player.*
import com.hellfire.ass.ui.LocalEnv
import com.hellfire.ass.ui.LocalNav
import com.hellfire.ass.ui.components.HfTextField
import com.hellfire.ass.ui.components.Note
import com.hellfire.ass.ui.components.SecondaryButton
import com.hellfire.ass.ui.components.SectionCard
import com.hellfire.ass.ui.theme.Hf
import com.hellfire.ass.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(itemId: String, startMs: Long?) {
    val env = LocalEnv.current
    val nav = LocalNav.current
    val app = env.app
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()
    val item by remember(itemId) { app.database.library().observeItem(itemId) }.collectAsStateWithLifecycle(null)
    val bookmarks by remember(itemId) { app.database.playback().bookmarks(itemId, true) }.collectAsStateWithLifecycle(emptyList())
    val segments by remember(itemId) { app.database.library().segments(itemId) }.collectAsStateWithLifecycle(emptyList())
    val transcript by remember(itemId) { app.database.media().segments(itemId) }.collectAsStateWithLifecycle(emptyList())
    val categories by remember { app.database.library().categories() }.collectAsStateWithLifecycle(emptyList())
    val history by remember { app.database.playback().history(30) }.collectAsStateWithLifecycle(emptyList())
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var connectError by remember { mutableStateOf<String?>(null) }
    var edits by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var started by remember(itemId, startMs) { mutableStateOf(false) }
    val playbackError by PlaybackFlags.error.collectAsState()
    val position by PlaybackFlags.positionMs.collectAsState()
    val duration by PlaybackFlags.durationMs.collectAsState()
    val playing by PlaybackFlags.playing.collectAsState()
    val speed by PlaybackFlags.speed.collectAsState()
    val video by PlaybackFlags.video.collectAsState()
    var locked by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    var scrub by remember { mutableStateOf<Long?>(null) }
    var thumb by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var waveform by remember { mutableStateOf<WaveformResult?>(null) }
    var kinds by remember { mutableStateOf(setOf(MarkerKind.BOOKMARK, MarkerKind.AI, MarkerKind.CHAPTER, MarkerKind.SCENE)) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<TimestampHit>>(emptyList()) }
    var searchNote by remember { mutableStateOf<String?>(null) }
    var pointA by remember { mutableStateOf<Long?>(null) }
    var editor by remember { mutableStateOf<MediaBookmark?>(null) }
    var correct by remember { mutableStateOf<com.hellfire.ass.media.TranscriptSegment?>(null) }
    var correctText by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var pipNote by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val skip = remember { context.getSharedPreferences("player", 0).getLong("skip_ms", 10_000L) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val media = item
        if (media != null && granted.values.any { it }) scope.launch { open(app, controller, media, startMs) { started = true } }
        else connectError = "Storage permission was not granted. The external file was not opened."
    }

    LaunchedEffect(Unit) { PlaybackFlags.skipMs.value = skip }
    LaunchedEffect(itemId) {
        controller = runCatching { PlaybackHub.controller(context) }.onFailure { connectError = "Playback session could not start." }.getOrNull()
        edits = PlayerStore.transcriptEdits(app, itemId)
    }
    LaunchedEffect(item?.id, controller, started) {
        val media = item ?: return@LaunchedEffect
        val player = controller ?: return@LaunchedEffect
        if (started) return@LaunchedEffect
        if (media.storage == StorageMode.EXTERNAL.name && !context.hasMediaRead()) {
            permission.launch(mediaReadPermissions())
            return@LaunchedEffect
        }
        open(app, player, media, startMs) { started = true }
    }
    LaunchedEffect(item?.id) {
        val media = item ?: return@LaunchedEffect
        if (media.contentKind == ContentKind.AUDIO.name || media.mime.startsWith("audio/")) waveform = WaveformLoader.load(app, media)
    }
    DisposableEffect(itemId) {
        PlaybackFlags.present(PresentationEvent.OPEN)
        onDispose {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            restoreBrightness(activity)
            if (PlaybackFlags.presentation.value == Presentation.FULL && PlaybackFlags.itemId.value != null) PlaybackFlags.present(PresentationEvent.MINIMIZE)
        }
    }
    LaunchedEffect(fullscreen) {
        val window = activity?.window ?: return@LaunchedEffect
        val controllerInsets = WindowCompat.getInsetsController(window, window.decorView)
        if (fullscreen) {
            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controllerInsets.hide(WindowInsetsCompat.Type.systemBars())
            controllerInsets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controllerInsets.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    val lines = PlayerStore.lines(transcript, edits)
    val shownLines = PlaybackLogic.filterTranscript(lines, query)
    val active = PlaybackLogic.segmentIndexAt(lines, scrub ?: position)
    LaunchedEffect(active) { if (active >= 0) listState.animateScrollToItem(active.coerceAtMost(shownLines.lastIndex.coerceAtLeast(0))) }
    val markers = PlaybackLogic.timeline(bookmarks, segments, kinds)
    val drawn = PlaybackLogic.drawMarkers(markers)
    val ab by PlaybackFlags.ab.collectAsState()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ if (fullscreen) fullscreen = false else nav.pop() }) { Text(tr("Back")) }
                Text(item?.name ?: tr("Player"), Modifier.weight(1f), style = Hf.type.cardTitle, color = Hf.colors.onSurface, maxLines = 1)
                TextButton({ locked = !locked }) { Text(if (locked) tr("Unlock") else tr("Lock screen")) }
            }
            if (item == null && connectError == null) Note(tr("Original is not available."))
            connectError?.let { Note(tr(it)) }
            playbackError?.let { Note(tr(it)) }
            pipNote?.let { Note(tr(it)) }
            if (!env.permissions.notificationPosting) Note(tr("Notifications are off, so lock-screen controls may be hidden by Android."))
            Note(tr(OverlayEvaluation.SUMMARY))
            if (video) {
                AndroidView(
                    factory = { ctx -> PlayerView(ctx).apply { useController = false; setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING) } },
                    update = { it.player = controller },
                    modifier = Modifier.fillMaxWidth().height(if (fullscreen) 280.dp else 200.dp).background(Color.Black),
                )
            }
            waveform?.let { result ->
                when (result) {
                    is WaveformResult.Unavailable -> Note(tr(result.reason))
                    is WaveformResult.Ready -> Waveform(result.peaks, position, duration) { controller?.seekTo(it) }
                }
            }
            val span = duration.coerceAtLeast(1).toFloat()
            val shown = (scrub ?: position).coerceIn(0, duration.coerceAtLeast(0)).toFloat().coerceIn(0f, span)
            Slider(shown, {
                scrub = it.toLong()
                val media = item
                if (media != null) scope.launch { thumb = FrameScrubber.frame(app, media, it.toLong()) }
            }, Modifier.fillMaxWidth(), valueRange = 0f..span, onValueChangeFinished = {
                scrub?.let { controller?.seekTo(it) }
                scrub = null
            })
            thumb?.let { androidx.compose.foundation.Image(it.asImageBitmap(), null, Modifier.height(48.dp)) }
            if (scrub != null && thumb == null && video) Note(tr("Thumbnail unavailable"))
            Text("${PlaybackLogic.formatTimestamp(scrub ?: position)} / ${PlaybackLogic.formatTimestamp(duration)}", style = Hf.type.caption, color = Hf.colors.muted)
            Timeline(drawn, markers.size, ab, position, duration) { controller?.seekTo(it) }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SecondaryButton(if (playing) tr("Pause") else tr("Play"), { controller?.let { if (it.isPlaying) it.pause() else it.play() } })
                SecondaryButton(tr("Skip back"), { controller?.seekTo(PlaybackLogic.skip(position, -PlaybackFlags.skipMs.value, duration)) })
                SecondaryButton(tr("Skip forward"), { controller?.seekTo(PlaybackLogic.skip(position, PlaybackFlags.skipMs.value, duration)) })
                SecondaryButton(tr("Previous"), { if (controller?.hasPreviousMediaItem() == true) controller?.seekToPreviousMediaItem() })
                SecondaryButton(tr("Next"), { if (controller?.hasNextMediaItem() == true) controller?.seekToNextMediaItem() })
                SecondaryButton(if (fullscreen) tr("Exit full screen") else tr("Full screen"), { fullscreen = !fullscreen })
                SecondaryButton(tr("Picture-in-picture"), {
                    val host = activity
                    if (host == null || !host.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                        pipNote = "Picture-in-picture is not available on this device."
                    } else {
                        val entered = host.enterPictureInPictureMode(PipControls.params(host, false, playing))
                        if (entered) PlaybackFlags.present(PresentationEvent.ENTER_PIP) else {
                            PlaybackFlags.present(PresentationEvent.PIP_REFUSED)
                            pipNote = "Picture-in-picture was refused by this device. Playback continues in the app."
                        }
                    }
                })
            }
            val skipNow by PlaybackFlags.skipMs.collectAsState()
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (choice in PlaybackLogic.SKIP_CHOICES_MS) {
                    TextButton({
                        PlaybackFlags.skipMs.value = choice
                        context.getSharedPreferences("player", 0).edit().putLong("skip_ms", choice).apply()
                    }) { Text(if (skipNow == choice) "·${choice / 1000}s" else "${choice / 1000}s") }
                }
            }
            Text(tr("Speed"), style = Hf.type.caption, color = Hf.colors.muted)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                for (value in PlaybackLogic.SPEEDS) {
                    TextButton({
                        controller?.setPlaybackSpeed(value)
                        PlaybackFlags.speed.value = value
                        scope.launch { persistChoice(app, itemId) { it.copy(speed = value) } }
                    }) { Text(if (speed == value) "·${value}x" else "${value}x") }
                }
            }
            VolumeBrightness(activity)
            TrackRow(controller, scope, app, itemId)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SecondaryButton(tr("Repeat"), {
                    val next = when (controller?.repeatMode) {
                        Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_OFF
                        else -> Player.REPEAT_MODE_ONE
                    }
                    controller?.repeatMode = next
                })
                SecondaryButton(tr("Shuffle"), { controller?.shuffleModeEnabled = controller?.shuffleModeEnabled != true })
                SecondaryButton(if (item?.favorite == true) tr("Unfavorite") else tr("Favorite"), {
                    scope.launch {
                        val next = item?.favorite != true
                        app.database.library().setFavorite(itemId, next)
                        app.database.sync().markChanged("media_item", itemId, false, System.currentTimeMillis())
                    }
                })
            }
            Text(tr("Sleep timer"), style = Hf.type.caption, color = Hf.colors.muted)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton({ PlaybackFlags.sleepUntil.value = null; PlaybackFlags.sleepAtEnd.value = false }) { Text(tr("Off")) }
                for (minutes in PlaybackLogic.SLEEP_CHOICES_MIN) {
                    TextButton({
                        PlaybackFlags.sleepAtEnd.value = false
                        PlaybackFlags.sleepUntil.value = PlaybackLogic.sleepDeadline(System.currentTimeMillis(), minutes)
                    }) { Text("${minutes}m") }
                }
                TextButton({ PlaybackFlags.sleepUntil.value = null; PlaybackFlags.sleepAtEnd.value = true }) { Text(tr("End of item")) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SecondaryButton(tr("Set A"), { pointA = position })
                SecondaryButton(tr("Set B"), {
                    val start = pointA
                    if (start != null && position > start) PlaybackFlags.ab.value = AbRange(start, position)
                })
                SecondaryButton(tr("Clear loop"), { PlaybackFlags.ab.value = null })
                SecondaryButton(tr("Save range"), {
                    val range = PlaybackFlags.ab.value ?: return@SecondaryButton
                    scope.launch {
                        val now = System.currentTimeMillis()
                        PlayerStore.saveBookmark(app, MediaBookmark(UUID.randomUUID().toString(), itemId, range.startMs, range.endMs, title = "Loop", origin = Origin.USER.name, createdAt = now, updatedAt = now))
                    }
                })
            }
            ab?.let { Note("A–B ${PlaybackLogic.formatTimestamp(it.startMs)}–${PlaybackLogic.formatTimestamp(it.endMs)}") }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SecondaryButton(tr("Previous chapter"), {
                    PlaybackLogic.neighbor(markers.filter { it.kind == MarkerKind.CHAPTER || it.kind == MarkerKind.SCENE }, position, false)?.let { controller?.seekTo(it.startMs) }
                })
                SecondaryButton(tr("Next chapter"), {
                    PlaybackLogic.neighbor(markers.filter { it.kind == MarkerKind.CHAPTER || it.kind == MarkerKind.SCENE }, position, true)?.let { controller?.seekTo(it.startMs) }
                })
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                for (kind in MarkerKind.entries) {
                    TextButton({ kinds = if (kind in kinds) kinds - kind else kinds + kind }) { Text(if (kind in kinds) "·${kind.name}" else kind.name) }
                }
            }
            if (markers.size > drawn.size) Note("Showing ${drawn.size} of ${markers.size} markers.")
            HfTextField(query, { query = it }, tr("Search in this item"))
            SecondaryButton(tr("Indexed search"), {
                scope.launch {
                    hits = LibrarySearchWithin(app.database).searchWithin(itemId, query)
                    searchNote = if (hits.isEmpty()) "No indexed match. Analysis was not started." else null
                }
            })
            searchNote?.let { Note(tr(it)) }
            for (hit in hits) {
                Text("${PlaybackLogic.formatTimestamp(hit.startMs)} ${hit.text}", Modifier.clickable { controller?.seekTo(hit.startMs) }, color = Hf.colors.onSurface)
            }
            SecondaryButton(tr("Bookmark"), {
                title = ""
                note = ""
                editor = MediaBookmark(UUID.randomUUID().toString(), itemId, scrub ?: position, null, createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
            })
            LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(bookmarks.filter { !it.hidden }, key = { it.id }) { bookmark ->
                    Column {
                        Text("${PlaybackLogic.formatTimestamp(bookmark.startMs)} ${bookmark.title.ifBlank { tr("Bookmark") }} · ${bookmark.origin}", Modifier.clickable { controller?.seekTo(bookmark.startMs) }, color = Hf.colors.onSurface)
                        Row {
                            TextButton({ controller?.seekTo(bookmark.startMs) }) { Text(tr("Seek")) }
                            TextButton({ editor = bookmark; title = bookmark.title; note = bookmark.note }) { Text(tr("Edit")) }
                            TextButton({ scope.launch { PlayerStore.setHidden(app, bookmark.id, true, System.currentTimeMillis()) } }) { Text(tr("Hide")) }
                            TextButton({ scope.launch { PlayerStore.deleteBookmark(app, bookmark.id, System.currentTimeMillis()) } }) { Text(tr("Delete")) }
                            if (bookmark.origin == Origin.AI.name) TextButton({ scope.launch { PlayerStore.convertToManual(app, bookmark.id, System.currentTimeMillis()) } }) { Text(tr("Convert to manual")) }
                        }
                    }
                }
                items(segments, key = { "seg-${it.id}" }) { segment ->
                    if (bookmarks.any { it.segmentId == segment.id }) return@items
                    Text("${segment.kind} ${PlaybackLogic.formatTimestamp(segment.startMs)} ${segment.title.ifBlank { segment.description }}", Modifier.clickable { controller?.seekTo(segment.startMs) }, color = Hf.colors.onSurface)
                    Row {
                        TextButton({
                            scope.launch {
                                val now = System.currentTimeMillis()
                                PlayerStore.saveBookmark(app, MediaBookmark(UUID.randomUUID().toString(), itemId, segment.startMs, segment.endMs, segment.title.ifBlank { segment.description }, origin = Origin.USER.name, segmentId = segment.id, createdAt = now, updatedAt = now))
                            }
                        }) { Text(tr("Convert to manual")) }
                        TextButton({ scope.launch { PlayerStore.dismissSegment(app, itemId, segment, System.currentTimeMillis()) } }) { Text(tr("Remove")) }
                    }
                }
                item { Text(tr("Transcript"), style = Hf.type.cardTitle, color = Hf.colors.onSurface) }
                items(shownLines, key = { it.id }) { line ->
                    val on = line.id == lines.getOrNull(active)?.id
                    Column(Modifier.background(if (on) Hf.colors.primaryContainer else Color.Transparent).clickable { controller?.seekTo(line.startMs) }.padding(4.dp)) {
                        Text("${line.speaker} ${PlaybackLogic.formatTimestamp(line.startMs)}", style = Hf.type.caption, color = Hf.colors.muted)
                        Text(line.text, color = Hf.colors.onSurface)
                        Row {
                            TextButton({ copy(context, line.text) }) { Text(tr("Copy")) }
                            TextButton({
                                correct = transcript.firstOrNull { it.id == line.id }
                                correctText = line.text
                            }) { Text(tr("Correct")) }
                            TextButton({
                                scope.launch {
                                    val now = System.currentTimeMillis()
                                    PlayerStore.saveBookmark(app, MediaBookmark(UUID.randomUUID().toString(), itemId, line.startMs, line.endMs, line.text.take(80), origin = Origin.USER.name, createdAt = now, updatedAt = now))
                                }
                            }) { Text(tr("Bookmark")) }
                        }
                    }
                }
                item {
                    SectionCard(tr("Playback history")) {
                        for (entry in history.filter { it.mediaId == itemId }.take(8)) {
                            Text("${PlaybackLogic.formatTimestamp(entry.fromMs)} → ${entry.toMs?.let(PlaybackLogic::formatTimestamp) ?: "…"}", color = Hf.colors.onSurface)
                        }
                        if (history.none { it.mediaId == itemId }) Note(tr("No playback history yet."))
                    }
                }
            }
        }
        if (locked) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable { }, contentAlignment = Alignment.Center) {
            SecondaryButton(tr("Unlock"), { locked = false })
        }
    }
    editor?.let { draft ->
        AlertDialog(
            onDismissRequest = { editor = null },
            title = { Text(tr("Bookmark")) },
            text = {
                Column {
                    HfTextField(title, { title = it }, tr("Title"))
                    HfTextField(note, { note = it }, tr("Notes"))
                    for (category in categories.take(12)) {
                        Text(category.name, Modifier.clickable { editor = draft.copy(categoryId = category.id) }, color = if (draft.categoryId == category.id) Hf.colors.primary else Hf.colors.onSurface)
                    }
                }
            },
            confirmButton = {
                TextButton({
                    scope.launch {
                        val now = System.currentTimeMillis()
                        val frame = item?.let { FrameScrubber.frame(app, it, draft.startMs) }
                        val path = frame?.let { FrameScrubber.writeThumb(app, it, draft.id) }
                        PlayerStore.saveBookmark(app, draft.copy(title = title, note = note, thumbnailPath = path ?: draft.thumbnailPath, updatedAt = now, origin = Origin.USER.name))
                        editor = null
                    }
                }) { Text(tr("Save bookmark")) }
            },
            dismissButton = { TextButton({ editor = null }) { Text(tr("Close")) } },
        )
    }
    correct?.let { segment ->
        AlertDialog(
            onDismissRequest = { correct = null },
            title = { Text(tr("Correct")) },
            text = { HfTextField(correctText, { correctText = it }, tr("Transcript"), singleLine = false) },
            confirmButton = {
                TextButton({
                    scope.launch {
                        PlayerStore.saveTranscriptEdit(app, itemId, segment, correctText, System.currentTimeMillis())
                        edits = PlayerStore.transcriptEdits(app, itemId)
                        correct = null
                    }
                }) { Text(tr("Save")) }
            },
            dismissButton = { TextButton({ correct = null }) { Text(tr("Close")) } },
        )
    }
}

private suspend fun open(app: com.hellfire.ass.HellFireApp, controller: MediaController?, item: MediaItem, startMs: Long?, started: () -> Unit) {
    val player = controller ?: return
    PlaybackHub.open(app, player, item.id, startMs)
    started()
}

private suspend fun persistChoice(app: com.hellfire.ass.HellFireApp, itemId: String, edit: (com.hellfire.ass.library.PlaybackState) -> com.hellfire.ass.library.PlaybackState) {
    withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val existing = app.database.playback().playback(itemId) ?: com.hellfire.ass.library.PlaybackState(itemId, lastPlayedAt = now)
        app.database.playback().savePlayback(edit(existing.copy(lastPlayedAt = now)))
    }
}

@Composable
private fun VolumeBrightness(activity: Activity?) {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    val max = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()) }
    var brightness by remember { mutableFloatStateOf(0f) }
    var volumeNote by remember { mutableStateOf<String?>(null) }
    Text(tr("Volume"), style = Hf.type.caption, color = Hf.colors.muted)
    Slider(volume, {
        volume = it
        val set = runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, (it * max).toInt().coerceIn(0, max), 0) }
        if (set.isFailure) volumeNote = "Volume control was refused by this device."
    }, valueRange = 0f..1f)
    volumeNote?.let { Note(tr(it)) }
    Text(tr("Brightness"), style = Hf.type.caption, color = Hf.colors.muted)
    Slider(brightness, {
        brightness = it
        val window = activity?.window ?: return@Slider
        val params = window.attributes
        params.screenBrightness = if (it <= 0.02f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else it
        window.attributes = params
    }, valueRange = 0f..1f)
}

@Composable
private fun TrackRow(controller: MediaController?, scope: kotlinx.coroutines.CoroutineScope, app: com.hellfire.ass.HellFireApp, itemId: String) {
    val tracks = controller?.currentTracks
    if (tracks == null || tracks.groups.isEmpty()) {
        Note(tr("No extra audio or subtitle tracks reported."))
        return
    }
    Text(tr("Audio track"), style = Hf.type.caption, color = Hf.colors.muted)
    for ((groupIndex, group) in tracks.groups.withIndex()) {
        if (group.type != C.TRACK_TYPE_AUDIO && group.type != C.TRACK_TYPE_TEXT) continue
        for (track in 0 until group.length) {
            if (!group.isTrackSupported(track)) continue
            val format = group.getTrackFormat(track)
            val label = format.label ?: format.language ?: "${group.type}:${track}"
            TextButton({
                controller.trackSelectionParameters = controller.trackSelectionParameters.buildUpon()
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
            }) { Text(label) }
        }
    }
    TextButton({
        controller.trackSelectionParameters = controller.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        PlaybackFlags.subtitleTrack = "off"
        scope.launch { persistChoice(app, itemId) { it.copy(subtitleTrack = "off") } }
    }) { Text(tr("Subtitles off")) }
}

@Composable
private fun Timeline(drawn: List<TimelineMarker>, total: Int, ab: AbRange?, position: Long, duration: Long, onSeek: (Long) -> Unit) {
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
    if (total == 0) Note(tr("No bookmarks or chapters yet."))
}

@Composable
private fun Waveform(peaks: FloatArray, position: Long, duration: Long, onSeek: (Long) -> Unit) {
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

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("transcript", text))
}

private fun restoreBrightness(activity: Activity?) {
    val window = activity?.window ?: return
    val params = window.attributes
    params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    window.attributes = params
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private fun Context.hasMediaRead(): Boolean = if (Build.VERSION.SDK_INT >= 33) {
    checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
} else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

private fun mediaReadPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 33) {
    arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO)
} else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
