package com.hellfire.ass.library

import org.json.JSONObject
import java.util.UUID

object LibraryOrganize {
    val DEFAULTS = listOf(
        "Personal photos", "Family and social events", "Travel", "Nature", "Animals", "Food", "Vehicles",
        "Work", "Technology", "Documents", "Screenshots", "Artwork", "Memes", "Entertainment", "Adult content", "Other",
    )

    suspend fun seedDefaults(dao: LibraryDao, now: Long) {
        DEFAULTS.forEach { name ->
            if (dao.findCategory(null, name) == null) {
                val private = name == "Adult content"
                dao.insertCategory(MediaCategory(UUID.randomUUID().toString(), null, name, Origin.USER.name, private,
                    if (private) CategoryPolicy.LOCAL_ONLY.name else CategoryPolicy.DEFAULT.name, null, now, now))
            }
        }
    }

    suspend fun createCategory(dao: LibraryDao, parentId: String?, name: String, isPrivate: Boolean, now: Long): MediaCategory? {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > 80) return null
        dao.findCategory(parentId, trimmed)?.let { return it }
        val row = MediaCategory(UUID.randomUUID().toString(), parentId, trimmed, Origin.USER.name, isPrivate, CategoryPolicy.DEFAULT.name, null, now, now)
        dao.insertCategory(row)
        return dao.findCategory(parentId, trimmed)
    }

    suspend fun rename(dao: LibraryDao, id: String, name: String, now: Long): Boolean {
        val category = dao.category(id) ?: return false
        if (category.mergedInto != null) return false
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        val clash = dao.findCategory(category.parentId, trimmed)
        if (clash != null && clash.id != id) return false
        dao.updateCategory(category.copy(name = trimmed, updatedAt = now))
        return true
    }

    suspend fun setPrivate(dao: LibraryDao, id: String, isPrivate: Boolean, now: Long) {
        val category = dao.category(id) ?: return
        dao.updateCategory(category.copy(isPrivate = isPrivate, updatedAt = now))
    }

    suspend fun merge(dao: LibraryDao, from: String, into: String, now: Long): Boolean {
        if (from == into) return false
        val source = dao.category(from) ?: return false
        val target = dao.category(into) ?: return false
        if (source.mergedInto != null || target.mergedInto != null) return false
        if (descendant(dao, into, from)) return false
        dao.mergeCategory(from, into, now)
        return true
    }

    suspend fun assign(dao: LibraryDao, itemId: String, categoryId: String, state: LinkState, now: Long) {
        dao.setCategory(ItemCategory(itemId, categoryId, Origin.USER.name, state.name, null, now))
    }

    /** AI suggestion only. Returns false when a user assignment or exclusion already occupies the link. */
    suspend fun suggest(dao: LibraryDao, itemId: String, categoryId: String, confidence: Double?, now: Long): Boolean =
        dao.suggestCategory(ItemCategory(itemId, categoryId, Origin.AI.name, LinkState.ASSIGNED.name, confidence, now)) != -1L

    suspend fun applySuggestions(dao: LibraryDao, itemId: String, categoryIds: List<String>, now: Long) {
        categoryIds.distinct().forEach { suggest(dao, itemId, it, null, now) }
    }

    fun normalizeTag(raw: String): String? {
        val tag = raw.trim().lowercase().replace(Regex("\\s+"), " ")
        if (tag.isEmpty() || tag.length > 40 || tag.any { it.isISOControl() }) return null
        return tag
    }

    suspend fun setTag(dao: LibraryDao, itemId: String, raw: String, state: LinkState, now: Long): String? {
        val tag = normalizeTag(raw) ?: return null
        dao.setTag(ItemTag(itemId, tag, Origin.USER.name, state.name, now))
        return tag
    }

    suspend fun suggestTag(dao: LibraryDao, itemId: String, raw: String, now: Long): Boolean {
        val tag = normalizeTag(raw) ?: return false
        return dao.suggestTag(ItemTag(itemId, tag, Origin.AI.name, LinkState.ASSIGNED.name, now)) != -1L
    }

    suspend fun createCollection(dao: LibraryDao, name: String, kind: CollectionKind, now: Long): MediaCollection? {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > 80) return null
        val row = MediaCollection(UUID.randomUUID().toString(), trimmed, kind.name, "", null, now, now)
        dao.insertCollection(row)
        return row
    }

    fun editedDescription(json: String?): String? =
        json?.let { runCatching { JSONObject(it).optString("description").ifBlank { null } }.getOrNull() }

    fun descriptionJson(text: String): String = JSONObject().put("description", text).toString()

    suspend fun saveDescription(dao: LibraryDao, itemId: String, text: String, now: Long) {
        val edited = descriptionJson(text)
        val existing = dao.latestAny(itemId)
        if (existing == null) {
            val id = UUID.randomUUID().toString()
            dao.saveAnalysis(MediaAnalysis(id, itemId, "user_description", "user", "0", 0, "COMPLETED", "{}", edited, text, null, now, now))
            return
        }
        dao.editAnalysis(existing.id, edited, now)
        dao.deleteSearch(existing.id)
        if (text.isNotBlank()) dao.insertSearch(LibrarySearch(existing.id, itemId, "ANALYSIS", text))
    }

    private suspend fun descendant(dao: LibraryDao, id: String, ancestor: String): Boolean {
        var current = dao.category(id)?.parentId
        var guard = 0
        while (current != null && guard++ < 32) {
            if (current == ancestor) return true
            current = dao.category(current)?.parentId
        }
        return false
    }
}
