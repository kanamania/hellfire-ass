package com.hellfire.ass.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import kotlin.math.roundToInt

object PipControls {
    /** Android accepts PiP aspect ratios between 1:2.39 and 2.39:1. */
    private const val MAX_RATIO = 2.39f

    fun params(context: Context, autoEnter: Boolean, playing: Boolean): PictureInPictureParams {
        val intent = PendingIntent.getBroadcast(
            context, 7,
            Intent(context, PipActionReceiver::class.java).setAction(PipActionReceiver.ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val label = if (playing) "Pause" else "Play"
        val icon = Icon.createWithResource(context, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        val builder = PictureInPictureParams.Builder()
            .setActions(listOf(RemoteAction(icon, label, label, intent)))
            .setAspectRatio(aspect(PlaybackFlags.videoAspect.value))
        if (Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(autoEnter)
        return builder.build()
    }

    /** The video's own shape, clamped to what Android allows; 16:9 until the size is known. */
    fun aspect(ratio: Float?): Rational {
        if (ratio == null || !ratio.isFinite() || ratio <= 0f) return Rational(16, 9)
        val clamped = ratio.coerceIn(1f / MAX_RATIO, MAX_RATIO)
        return Rational((clamped * 1000).roundToInt(), 1000)
    }
}
