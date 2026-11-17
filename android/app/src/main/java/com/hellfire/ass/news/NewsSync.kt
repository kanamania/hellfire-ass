package com.hellfire.ass.news

import org.json.JSONObject

/**
 * Applies the owner's news choices made on the web copy (web/api `news_action`) to Room.
 * Last writer wins per field against [NewsEventState.fieldTimes] (falling back to [NewsEventState.updatedAt]);
 * the read version only grows; a briefing position is taken when it is newer than the phone's.
 * web/api/src/news.ts overlays pending actions with the same rules.
 */
object NewsSync {
    data class Action(val seq: Long, val at: Long, val target: String, val field: String, val value: Any?)

    val flags = setOf("bookmarked", "readLater", "hidden", "followed")

    private fun times(state: NewsEventState) = runCatching { JSONObject(state.fieldTimes) }.getOrElse { JSONObject() }

    fun parse(json: JSONObject) = Action(json.getLong("seq"), json.getLong("at"), json.getString("target"), json.getString("field"), json.opt("value"))

    /** Applies [actions] in sequence order; returns how many changed something. Actions for unknown events or briefings are dropped. */
    suspend fun apply(dao: NewsDao, actions: List<Action>): Int = actions.sortedBy { it.seq }.count { apply(dao, it) }

    private suspend fun apply(dao: NewsDao, action: Action): Boolean = when (action.field) {
        "position" -> {
            val value = action.value as? JSONObject
            val local = dao.progress(action.target)
            if (value == null || dao.briefing(action.target) == null || (local != null && action.at <= local.updatedAt)) false
            else {
                dao.saveProgress(NewsProgress(action.target, value.optInt("position").coerceAtLeast(0), value.optLong("audioMs").coerceAtLeast(0), action.at))
                true
            }
        }
        "seen" -> {
            val version = (action.value as? Number)?.toInt()
            if (version == null || dao.event(action.target) == null) false
            else {
                val local = dao.state(action.target) ?: NewsEventState(action.target, updatedAt = 0)
                if (version <= local.seenVersion && version <= local.deliveredVersion) false
                else {
                    val times = times(local)
                    times.put("seen", maxOf(times.optLong("seen"), action.at))
                    val read = version > local.seenVersion
                    dao.saveState(local.copy(
                        seenVersion = maxOf(local.seenVersion, version),
                        readAt = if (read) action.at else local.readAt,
                        // Read on the web means shown: briefings must not repeat it as new.
                        deliveredVersion = maxOf(local.deliveredVersion, version),
                        deliveredAt = local.deliveredAt ?: action.at,
                        fieldTimes = times.toString(),
                        updatedAt = maxOf(local.updatedAt, action.at),
                    ))
                    true
                }
            }
        }
        in flags -> {
            val value = action.value as? Boolean
            if (value == null || dao.event(action.target) == null) false
            else {
                val local = dao.state(action.target) ?: NewsEventState(action.target, updatedAt = 0)
                val times = times(local)
                if (action.at <= times.optLong(action.field, local.updatedAt)) false
                else {
                    times.put(action.field, action.at)
                    val changed = when (action.field) {
                        "bookmarked" -> local.copy(bookmarked = value)
                        "readLater" -> local.copy(readLater = value)
                        "hidden" -> local.copy(hidden = value)
                        else -> local.copy(followed = value)
                    }
                    dao.saveState(changed.copy(fieldTimes = times.toString(), updatedAt = maxOf(local.updatedAt, action.at)))
                    true
                }
            }
        }
        else -> false
    }
}
