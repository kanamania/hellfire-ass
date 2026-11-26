package com.hellfire.ass.intelligence

import com.hellfire.ass.library.ItemCategory
import com.hellfire.ass.library.LibraryDao
import com.hellfire.ass.library.MediaCategory
import com.hellfire.ass.library.Origin
import java.util.UUID
import com.hellfire.ass.library.LibraryOrganize

data class CategorySuggestion(val parent: String?, val name: String, val confidence: Double)

object CategoryMap {
    private val ignored = setOf("person", "people", "face", "faces", "man", "woman", "boy", "girl", "human", "crowd")
    private val rules = listOf(
        setOf("dog", "cat", "animal", "bird", "pet") to ("Animals" to "Nature"),
        setOf("food", "meal", "dish", "cuisine") to ("Food" to "Personal"),
        setOf("car", "vehicle", "truck", "bus", "motorcycle") to ("Vehicles" to "Personal"),
        setOf("beach", "mountain", "landscape", "nature", "sky", "plant") to ("Nature" to "Travel"),
        setOf("screenshot", "font") to ("Screenshots" to "Documents"),
        setOf("document", "paper", "receipt", "text") to ("Documents" to null),
        setOf("building", "house", "city") to ("Travel" to null),
        setOf("computer", "phone", "electronics") to ("Technology" to "Work"),
    )

    fun from(labels: List<String>, ocr: String): List<CategorySuggestion> {
        val words = labels.map { it.lowercase() }.filter { it !in ignored }.toSet()
        val out = linkedMapOf<String, CategorySuggestion>()
        rules.forEach { (keys, target) ->
            if (words.any { label -> keys.any { key -> label.contains(key) } }) {
                out.putIfAbsent(target.first, CategorySuggestion(target.second, target.first, 0.6))
            }
        }
        if (ocr.length > 40 && (words.any { it.contains("text") || it.contains("screenshot") || it.contains("font") } || ocr.lines().size > 4)) {
            out.putIfAbsent("Screenshots", CategorySuggestion("Documents", "Screenshots", 0.55))
        }
        if (out.isEmpty() && words.isNotEmpty()) out["Other"] = CategorySuggestion(null, "Other", 0.3)
        return out.values.toList()
    }
}

object CategoryApply {
    suspend fun suggest(dao: LibraryDao, itemId: String, suggestions: List<CategorySuggestion>, now: Long) {
        suggestions.forEach { suggestion ->
            val parentId = suggestion.parent?.let { ensure(dao, null, it, now).id }
            val category = ensure(dao, parentId, suggestion.name, now)
            dao.suggestCategory(ItemCategory(itemId, category.id, Origin.AI.name, confidence = suggestion.confidence, updatedAt = now))
        }
    }

    private suspend fun ensure(dao: LibraryDao, parentId: String?, name: String, now: Long): MediaCategory {
        if (parentId == null && name in setOf("Adult", "Adult content")) return LibraryOrganize.adultRoot(dao, now)
        dao.findCategory(parentId, name)?.let { return it }
        val created = MediaCategory(UUID.randomUUID().toString(), parentId, name, Origin.AI.name, isPrivate = parentId?.let { dao.category(it)?.isPrivate } == true || name == "Adult", policy = if (parentId?.let { dao.category(it)?.isPrivate } == true) "LOCAL_ONLY" else "DEFAULT", createdAt = now, updatedAt = now)
        dao.insertCategory(created)
        return dao.findCategory(parentId, name) ?: created
    }
}
