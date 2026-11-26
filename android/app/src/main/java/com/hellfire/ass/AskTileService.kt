package com.hellfire.ass

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.service.quicksettings.TileService

/** Quick Settings tile: one tap opens Ask with the microphone, from any screen. */
class AskTileService : TileService() {
    @SuppressLint("StartActivityAndCollapseDeprecated") // The Intent form is the only one before Android 14.
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).setData(Uri.parse("hellfire://ask?voice=1"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 90, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(intent)
        }
    }
}
