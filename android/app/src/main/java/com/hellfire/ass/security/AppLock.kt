package com.hellfire.ass.security

import android.content.Context

object AppLock {
    private const val PREFS = "app-lock"
    fun enabled(context: Context): Boolean = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)
    } catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); true }

    fun setEnabled(context: Context, enabled: Boolean) {
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).commit()) {
            "Could not save app lock preference"
        }
    }
}

/** Authentication is deliberately not saved across activity recreation or backgrounding. */
class AppLockSession {
    var authenticated = false
        private set
    fun authenticate() { authenticated = true }
    fun lock() { authenticated = false }
    fun requiresUnlock(enabled: Boolean) = enabled && !authenticated
}
