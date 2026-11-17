package com.hellfire.ass.performance

import android.content.Context

/**
 * Rebuildable analysis markers. They are not part of the encrypted database or backups.
 * Losing them only repeats a stage; it never skips a changed file.
 */
object ProcessingCheckpoint {
    private const val PREFS = "perf-checkpoints"

    fun videoDone(context: Context, mediaId: String, contentHash: String, version: Int): Boolean =
        context.getSharedPreferences(PREFS, 0).getBoolean(key(mediaId, contentHash, version), false)

    fun markVideo(context: Context, mediaId: String, contentHash: String, version: Int) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean(key(mediaId, contentHash, version), true).apply()
    }

    fun clear(context: Context, mediaId: String) {
        val prefs = context.getSharedPreferences(PREFS, 0)
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith("$mediaId:") }.forEach { edit.remove(it) }
        edit.apply()
    }

    private fun key(mediaId: String, contentHash: String, version: Int) = "$mediaId:$contentHash:$version"
}
