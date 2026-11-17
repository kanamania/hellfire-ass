package com.hellfire.ass.player

import kotlinx.coroutines.flow.MutableStateFlow

/** In-process controls for the single player owned by [HellFirePlaybackService]. Not a second player. */
object PlaybackFlags {
    val ab = MutableStateFlow<AbRange?>(null)
    val sleepUntil = MutableStateFlow<Long?>(null)
    val sleepAtEnd = MutableStateFlow(false)
    val skipMs = MutableStateFlow(10_000L)
    val error = MutableStateFlow<String?>(null)
    val presentation = MutableStateFlow(Presentation.CLOSED)
    val video = MutableStateFlow(false)
    val playing = MutableStateFlow(false)
    val positionMs = MutableStateFlow(0L)
    val durationMs = MutableStateFlow(0L)
    val speed = MutableStateFlow(1f)
    val itemId = MutableStateFlow<String?>(null)
    val title = MutableStateFlow("")
    var pendingAudio: String? = null
    var pendingSubtitle: String? = null
    var audioTrack: String? = null
    var subtitleTrack: String? = null

    fun present(event: PresentationEvent) {
        presentation.value = PlaybackLogic.transition(presentation.value, event, video.value)
    }
}
