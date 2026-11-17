package com.hellfire.ass.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational

object PipControls {
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
            .setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(autoEnter)
        return builder.build()
    }
}
