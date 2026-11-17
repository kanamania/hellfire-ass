package com.hellfire.ass.player

/**
 * Who may connect to the exported playback session. Titles of private recordings are visible to a
 * connected controller, so only this app, its media notification, Android Auto and controllers Android
 * marks as trusted (System UI, lock screen, Bluetooth) are accepted.
 */
object SessionAccess {
    fun allowed(ownPackage: String, controllerPackage: String, trusted: Boolean, notification: Boolean, automotive: Boolean): Boolean =
        controllerPackage == ownPackage || notification || trusted || automotive
}
