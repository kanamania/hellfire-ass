package com.hellfire.ass.library

import org.json.JSONObject
import java.util.UUID

object LibraryOrganize {
    /** The one adult root: seeded private and LOCAL_ONLY; classifier labels are filed under it. */
    const val ADULT_ROOT = "Adult content"
    const val USER_DESCRIPTION = "user_description"
    val DEFAULTS = listOf(
        "Personal photos", "Family and social events", "Travel", "Nature", "Animals", "Food", "Vehicles",
        "Work", "Technology", "Documents", "Screenshots", "Artwork", "Memes", "Entertainment", "Adult content", "Other",
    )

    suspend fun seedDefaults(dao: LibraryDao, now: Long) {
        DEFAULTS.forEach { name ->
            if (name == ADULT_ROOT) adultRoot(dao, now)
            else if (dao.findCategory(null, name) == null) {
                dao.insertCategory(MediaCategory(UUID.randomUUID().toString(), null, name, Origin.USER.name, false, CategoryPolicy.DEFAULT.name, null, now, now))
            }
        }
    }

    /**
     * The canonical adult root, created private/LOCAL_ONLY when absent. A root named "Adult" made by older analysis builds is
     * merged into it (links and children move, USER links win) and its children become private.
     */
    suspend fun adultRoot(dao: LibraryDao, now: Long): MediaCategory {
        val root = dao.findCategory(null, ADULT_ROOT) ?: MediaCategory(UUID.randomUUID().toString(), null, ADULT_ROOT, Origin.USER.name, true,
            CategoryPolicy.LOCAL_ONLY.name, null, now, now).also { dao.insertCategory(it) }
        if (!root.isPrivate || root.policy != CategoryPolicy.LOCAL_ONLY.name)
            dao.updateCategory(root.copy(isPrivate = true, policy = CategoryPolicy.LOCAL_ONLY.name, updatedAt = now))
        dao.findCategory(null, "Adult")?.takeIf { it.id != root.id && it.origin == Origin.AI.name }?.let { legacy -> dao.mergeCategory(legacy.id, root.id, now) }
        val all = dao.categoriesNow()
        val descendants = subtree(all, listOf(root.id))
        all.filter { it.id in descendants && (!it.isPrivate || it.policy != CategoryPolicy.LOCAL_ONLY.name) }
            .forEach { dao.updateCategory(it.copy(isPrivate = true, policy = CategoryPolicy.LOCAL_ONLY.name, updatedAt = now)) }
        return dao.category(root.id) ?: root
    }

    /** Ids of [rootId] and every live descendant. */
    fun subtree(all: List<MediaCategory>, rootIds: Collection<String>): Set<String> {
        val out = rootIds.toMutableSet()
        var grew = true
        while (grew) {
            val next = all.filter { it.parentId in out && it.id !in out }.map { it.id }
            grew = next.isNotEmpty()
            out += next
        }
        return out
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

    fun descriptionId(itemId: String) = "$itemId:$USER_DESCRIPTION"

    /**
     * The user's description lives in its own analysis row (fixed id per item, analyzer [USER_DESCRIPTION]): new analysis runs
     * never hide it, raw analysis rows and their search text stay untouched, and rebuildSearch re-indexes it from its text.
     */
    suspend fun saveDescription(dao: LibraryDao, itemId: String, text: String, now: Long) {
        dao.saveAnalysis(MediaAnalysis(descriptionId(itemId), itemId, USER_DESCRIPTION, "user", "0", 0, "COMPLETED", "{}",
            descriptionJson(text), text, null, now, now))
    }

    /** The current user description, including one saved by older builds as an edit on another analysis row. */
    suspend fun description(dao: LibraryDao, itemId: String): String? =
        dao.latestAnalysis(itemId, USER_DESCRIPTION)?.let { editedDescription(it.editedJson) ?: it.text }
            ?: editedDescription(dao.latestEdited(itemId)?.editedJson)

    /** Move [itemId] one place up, renumbering the whole collection so positions stay 0..n-1. */
    suspend fun moveUp(dao: LibraryDao, collectionId: String, itemId: String): Boolean {
        val ids = dao.membersNow(collectionId).map { it.id }.toMutableList()
        val index = ids.indexOf(itemId)
        if (index <= 0) return false
        ids[index] = ids[index - 1]; ids[index - 1] = itemId
        dao.reorder(collectionId, ids)
        return true
    }

    /** Keep a picture out of the normal library list (tag [HIDDEN_TAG]); removing the tag shows it again. Nothing is deleted. */
    suspend fun hide(dao: LibraryDao, itemId: String, now: Long) { dao.setTag(ItemTag(itemId, HIDDEN_TAG, Origin.USER.name, LinkState.ASSIGNED.name, now)) }

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
