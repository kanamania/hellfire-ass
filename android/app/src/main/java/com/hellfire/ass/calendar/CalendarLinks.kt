package com.hellfire.ass.calendar

import android.content.Intent
import android.net.Uri

/** The exported alias accepts only read-only provenance links, never commands, shared content or approvals. */
object CalendarLinks {
    fun external(intent: Intent?): Uri? {
        val uri = intent?.data ?: return null
        if (intent.component?.className != "com.hellfire.ass.CalendarEntry" || intent.action != Intent.ACTION_VIEW ||
            uri.scheme != "hellfire" || uri.host !in setOf("task", "source") || uri.pathSegments.size != 1 ||
            !uri.pathSegments.single().matches(Regex("[a-f0-9-]{32,64}"))) return null
        return Uri.Builder().scheme("hellfire").authority(uri.host).appendPath(uri.pathSegments.single()).build()
    }
}
