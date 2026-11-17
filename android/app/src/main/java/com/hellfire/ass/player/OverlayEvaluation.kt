package com.hellfire.ass.player

/**
 * System-overlay player was evaluated and not implemented.
 * A TYPE_APPLICATION_OVERLAY window cannot host the single ExoPlayer surface while the activity
 * also shows it, without a second player or an OEM-fragile surface handoff. Media3 has no supported
 * overlay presentation that shares one player across apps. Picture-in-picture is the system floating
 * path and keeps this session. No SYSTEM_ALERT_WINDOW permission is requested.
 */
object OverlayEvaluation {
    const val IMPLEMENTED = false
    const val SUMMARY = "System overlay is not available. Picture-in-picture is the supported floating player."
}
