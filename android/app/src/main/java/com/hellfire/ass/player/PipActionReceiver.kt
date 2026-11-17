package com.hellfire.ass.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PipActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION) PlaybackHub.toggle(context)
    }

    companion object {
        const val ACTION = "com.hellfire.ass.player.PIP_TOGGLE"
    }
}
