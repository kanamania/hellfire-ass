package com.hellfire.ass.notifications

import com.hellfire.ass.events.NotificationSnapshot

/**
 * Media players, downloads, navigation and timers re-post the same notification many times a minute. Each re-post would
 * become a new encrypted event. Keep the first post of such a notification, then at most one per [windowMillis], and
 * always its removal (which carries its final text). Ordinary notifications are never coalesced.
 */
class RepostCoalescer(private val windowMillis: Long = 10 * 60_000L, private val maxKeys: Int = 500) {
    private val stored = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > maxKeys
    }
    private val busyCategories = setOf("progress", "transport", "navigation", "service", "stopwatch", "status", "sys")

    @Synchronized fun keep(snapshot: NotificationSnapshot, now: Long): Boolean {
        val key = snapshot.packageName + "|" + snapshot.key
        if (snapshot.kind == "REMOVED") { stored.remove(key); return true }
        val category = snapshot.fields["category"]?.firstOrNull()
        if (!snapshot.ongoing && category !in busyCategories) return true
        val last = stored[key]
        if (last != null && now - last < windowMillis) return false
        stored[key] = now
        return true
    }
}
