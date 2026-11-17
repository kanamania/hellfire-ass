package com.hellfire.ass.drawer

import com.hellfire.ass.library.ContentKind
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Modular action providers. Each declares kinds, context, permissions, external services,
 * offline use, confirmation and failure text. Dropped code is never an executable action.
 */
data class RecommendedAction(
    val type: String,
    val label: String,
    val kinds: Set<ContentKind>,
    val requiredContext: List<String>,
    val permissions: List<String>,
    val externalServices: List<String>,
    val offline: Boolean,
    val needsConfirmation: Boolean,
    val failure: String,
    val priority: Int,
    val payload: String = "{}",
    val available: Boolean = true,
    val unavailableReason: String? = null,
)

object ActionEngine {
    const val LIVE_FACTS = "Opening hours, weather and travel advisories need a live source and are not shown."

    fun recommend(content: Normalized, modelInstalled: Boolean): List<RecommendedAction> {
        val actions = providers.flatMap { it.actions(content, modelInstalled) }
        return rank(content, actions).filter { it.type != "execute" && it.type != "run" }
    }

    fun liveFactsNote(content: Normalized): String? = if (content.places.isNotEmpty() || content.kind == ContentKind.LOCATION) LIVE_FACTS else null

    fun rank(content: Normalized, actions: List<RecommendedAction>): List<RecommendedAction> {
        val boost = when {
            "error" in content.signals -> setOf("troubleshoot", "explain_error", "suggest_fix", "review_code", "explain_screenshot")
            "receipt" in content.signals -> setOf("extract_receipt", "extract_dates", "extract_entities")
            content.kind == ContentKind.LOCATION || "maps" in content.signals -> setOf("open_map", "directions", "navigate")
            else -> emptySet()
        }
        return actions.distinctBy { it.type }.sortedByDescending { if (it.type in boost) it.priority + 100 else it.priority }
    }

    fun mapUri(place: Place, query: String? = null): String? {
        val q = query?.let { URLEncoder.encode(it, "UTF-8") }
        if (place.latitude != null && place.longitude != null) {
            val base = "geo:${place.latitude},${place.longitude}"
            return if (q == null) base else "$base?q=$q"
        }
        if (place.label.isNotBlank()) return "geo:0,0?q=" + URLEncoder.encode(place.label, "UTF-8")
        return null
    }

    fun navigationUri(place: Place): String? {
        val target = if (place.latitude != null && place.longitude != null) "${place.latitude},${place.longitude}"
        else place.label.takeIf { it.isNotBlank() } ?: return null
        return "google.navigation:q=" + URLEncoder.encode(target, "UTF-8")
    }

    private val providers = listOf(LocationActions, PictureActions, VideoActions, AudioActions, TextActions, DocumentActions, CodeActions, UrlActions)
}

private fun action(
    type: String, label: String, kinds: Set<ContentKind>, context: List<String> = emptyList(),
    permissions: List<String> = emptyList(), services: List<String> = emptyList(), offline: Boolean = true,
    confirm: Boolean = false, failure: String, priority: Int, payload: JSONObject = JSONObject(),
    available: Boolean = true, reason: String? = null,
) = RecommendedAction(type, label, kinds, context, permissions, services, offline, confirm, failure, priority, payload.toString(), available, reason)

private fun placePayload(content: Normalized): JSONObject {
    val place = content.places.firstOrNull { it.latitude != null } ?: content.places.firstOrNull()
    return JSONObject().put("label", place?.label.orEmpty()).put("lat", place?.latitude).put("lon", place?.longitude)
        .put("uncertain", place?.uncertain == true).put("source", place?.source.orEmpty())
}

private interface ActionProvider {
    fun actions(content: Normalized, modelInstalled: Boolean): List<RecommendedAction>
}

private object LocationActions : ActionProvider {
    private val kinds = setOf(ContentKind.LOCATION)
    override fun actions(content: Normalized, modelInstalled: Boolean): List<RecommendedAction> {
        if (content.kind !in kinds && "maps" !in content.signals && "coordinates" !in content.signals && "address" !in content.signals) return emptyList()
        val place = content.places.firstOrNull { it.latitude != null } ?: content.places.firstOrNull()
        val missing = place == null
        fun loc(type: String, label: String, priority: Int, confirm: Boolean, services: List<String>, query: String? = null) = action(
            type, label, kinds, listOf("place"), emptyList(), services, offline = true, confirm = confirm,
            failure = "No map app handled this, or no place was detected.", priority = priority,
            payload = JSONObject(placePayload(content).toString()).put("query", query ?: JSONObject.NULL),
            available = !missing || content.entities.any { it.type == "address" },
            reason = if (missing) "No coordinates or address were detected." else null,
        )
        return listOf(
            loc("open_map", "Open map", 90, true, listOf("geo")),
            loc("directions", "Get directions", 85, true, listOf("geo")),
            loc("navigate", "Start navigation", 80, true, listOf("google.navigation")),
            loc("nearby_restaurants", "Find nearby restaurants", 70, true, listOf("geo"), "restaurants"),
            loc("nearby_hotels", "Find hotels", 68, true, listOf("geo"), "hotels"),
            loc("nearby_attractions", "Find attractions", 66, true, listOf("geo"), "attractions"),
            action("save_location", "Save location", kinds, listOf("place"), emptyList(), emptyList(), true, false, "Could not save the location.", 60, placePayload(content)),
            action("add_to_trip", "Add to trip", kinds, listOf("item"), emptyList(), emptyList(), true, false, "Could not add this to a trip collection.", 55, placePayload(content)),
            action("copy_address", "Copy address", kinds, listOf("place"), emptyList(), emptyList(), true, false, "Nothing to copy.", 50, placePayload(content), available = place != null),
            action("share_location", "Share location", kinds, listOf("place"), emptyList(), listOf("share"), true, true, "Share was not handed off.", 40, placePayload(content)),
        )
    }
}

private object PictureActions : ActionProvider {
    override fun actions(content: Normalized, modelInstalled: Boolean): List<RecommendedAction> {
        if (content.kind != ContentKind.IMAGE && "error" !in content.signals && "receipt" !in content.signals) return emptyList()
        val kinds = setOf(ContentKind.IMAGE)
        val model = action("describe", "Describe", kinds, listOf("item"), emptyList(), emptyList(), true, false,
            "Description needs an installed local model.", 40, available = modelInstalled, reason = if (modelInstalled) null else "Local model is not installed.")
        return listOf(
            action("extract_receipt", "Extract receipt", kinds, listOf("text"), emptyList(), emptyList(), true, false, "No receipt amounts were found in the saved text.", 30),
            action("explain_screenshot", "Explain screenshot", kinds, listOf("item"), emptyList(), emptyList(), true, false, "Explanation needs an installed local model or extracted text.", 35, available = modelInstalled || content.text.isNotBlank(), reason = if (modelInstalled || content.text.isNotBlank()) null else "Local model is not installed."),
            action("troubleshoot", "Troubleshoot", kinds, listOf("text"), emptyList(), emptyList(), true, false, "No error text was extracted. Dropped code is not executed.", 25),
            model,
            action("extract_text", "Extract text", kinds, listOf("item"), emptyList(), emptyList(), true, false, "On-device OCR did not return text. QR content was not decoded.", 30),
            action("translate", "Translate text", kinds, listOf("text"), emptyList(), emptyList(), true, false, "Translation needs an installed local model.", 20, available = modelInstalled && content.text.isNotBlank(), reason = if (modelInstalled) null else "Local model is not installed."),
            action("search_similar", "Search similar", kinds, listOf("item"), emptyList(), emptyList(), true, false, "Nothing similar is indexed yet.", 15),
            action("categorize", "Categorize", kinds, listOf("item"), emptyList(), emptyList(), true, false, "Category was not saved.", 10),
            action("share_sanitized", "Share sanitized copy", kinds, listOf("item"), emptyList(), listOf("share"), true, true, "Share review was not opened.", 5),
        ).filter { content.kind == ContentKind.IMAGE || it.type in setOf("troubleshoot", "explain_screenshot", "extract_receipt") }
    }
}

private object VideoActions : ActionProvider {
    override fun actions(content: Normalized, modelInstalled: Boolean): List<RecommendedAction> {
        if (content.kind != ContentKind.VIDEO) return emptyList()
        val kinds = setOf(ContentKind.VIDEO)
        return media("video", kinds, modelInstalled)
    }
}

private object AudioActions : ActionProvider {
    override fun actions(content: Normalized, modelInstalled: Boolean): List<RecommendedAction> {
        if (content.kind != ContentKind.AUDIO) return emptyList()
        return media("audio", setOf(ContentKind.AUDIO), modelInstalled) + action(
            "extract_actions", "Extract action items", setOf(ContentKind.AUDIO), listOf("transcript"), emptyList(), emptyList(), true, false,
            "No transcript is available yet.", 20, available = modelInstalled, reason = if (modelInstalled) null else "Local model is not installed.",
        )
    }
}

private fun media(kind: String, kinds: Set<ContentKind>, modelInstalled: Boolean) = listOf(
    action("play", "Play", kinds, listOf("item"), emptyList(), emptyList(), true, false, "Player could not be opened.", 90),
    action("floating_player", "Open floating player", kinds, listOf("item"), emptyList(), emptyList(), true, false, "In-app player bar could not be opened. A system-wide overlay is not used.", 80),
    action("summarize", "Summarize", kinds, listOf("item"), emptyList(), emptyList(), true, false, "Summary needs an installed local model or a transcript.", 40, available = modelInstalled, reason = if (modelInstalled) null else "Local model is not installed."),
    action("transcribe", "Transcribe and index", kinds, listOf("item"), emptyList(), emptyList(), true, false, "Transcription job was not queued.", 70),
    action("search_within", "Search within", kinds, listOf("item"), emptyList(), emptyList(), true, false, "Nothing is indexed inside this item yet.", 30),
    action("bookmark", "Bookmark", kinds, listOf("item"), emptyList(), emptyList(), true, false, "Bookmark was not saved.", 25),
    action("share_sanitized", "Share sanitized copy", kinds, listOf("item"), emptyList(), listOf("share"), true, true, "Share review was not opened.", 10),
    action("translate", "Translate transcript", kinds, listOf("transcript"), emptyList(), emptyList(), true, false, "Translation needs an installed local model.", 15, available = modelInstalled, reason = if (modelInstalled) null else "Local model is not installed."),
)

private object TextActions : ActionProvider {
    override fun actions(content: Normalized, modelInstalled: Boolean): List<RecommendedAction> {
        if (content.kind != ContentKind.TEXT && content.kind != ContentKind.OTHER && content.text.isBlank()) return emptyList()
        if (content.kind != ContentKind.TEXT && content.kind != ContentKind.CODE && content.text.isBlank()) return emptyList()
        val kinds = setOf(ContentKind.TEXT, ContentKind.OTHER)
        val modelReason = if (modelInstalled) null else "Local model is not installed."
        return listOf(
            action("summarize", "Summarize", kinds, listOf("text"), emptyList(), emptyList(), true, false, "Summary needs an installed local model.", 40, available = modelInstalled, reason = modelReason),
            action("explain", "Explain", kinds, listOf("text"), emptyList(), emptyList(), true, false, "Explanation needs an installed local model.", 35, available = modelInstalled, reason = modelReason),
            action("translate", "Translate", kinds, listOf("text"), emptyList(), emptyList(), true, false, "Translation needs an installed local model.", 30, available = modelInstalled, reason = modelReason),
            action("rewrite", "Rewrite", kinds, listOf("text"), emptyList(), emptyList(), true, false, "Rewrite needs an installed local model. The original is not changed.", 20, available = modelInstalled, reason = modelReason),
            action("extract_entities", "Extract entities", kinds, listOf("text"), emptyList(), emptyList(), true, false, "No entities were detected.", 50),
            action("save_note", "Save as note", kinds, listOf("text"), emptyList(), emptyList(), true, false, "Note was not saved.", 45),
        )
    }
}

private object DocumentActions : ActionProvider {
    override fun actions(content: Normalized, modelInstalled: Boolean): List<RecommendedAction> {
        if (content.kind != ContentKind.DOCUMENT) return emptyList()
        val kinds = setOf(ContentKind.DOCUMENT)
        val modelReason = if (modelInstalled) null else "Local model is not installed."
        return listOf(
            action("summarize", "Summarize", kinds, listOf("text"), emptyList(), emptyList(), true, false, "Summary needs extracted text and an installed local model.", 40, available = modelInstalled && content.text.isNotBlank(), reason = modelReason),
            action("ask", "Ask about this document", kinds, listOf("text"), emptyList(), emptyList(), true, false, "A follow-up needs the saved document.", 35),
            action("extract_dates", "Extract dates", kinds, listOf("text"), emptyList(), emptyList(), true, false, "No dates were found in the extracted text. OCR is not installed.", 60),
            action("find_deadlines", "Find deadlines", kinds, listOf("text"), emptyList(), emptyList(), true, false, "No deadlines were found in the extracted text.", 55),
            action("share_sanitized", "Share sanitized copy", kinds, listOf("item"), emptyList(), listOf("share"), true, true, "Share review was not opened.", 10),
        )
    }
}

private object CodeActions : ActionProvider {
    override fun actions(content: Normalized, modelInstalled: Boolean): List<RecommendedAction> {
        if (content.kind != ContentKind.CODE && "code" !in content.signals && "error" !in content.signals && "json" !in content.signals) return emptyList()
        val kinds = setOf(ContentKind.CODE)
        val modelReason = if (modelInstalled) null else "Local model is not installed. The text was not executed."
        return listOf(
            action("explain_error", "Explain error", kinds, listOf("text"), emptyList(), emptyList(), true, false, "Explanation needs an installed local model. The text was not executed.", 40, available = modelInstalled, reason = modelReason),
            action("troubleshoot", "Troubleshoot", kinds, listOf("text"), emptyList(), emptyList(), true, false, "Troubleshooting needs an installed local model. The text was not executed.", 35, available = modelInstalled, reason = modelReason),
            action("review_code", "Review code", kinds, listOf("text"), emptyList(), emptyList(), true, false, "Review needs an installed local model. The text was not executed.", 30, available = modelInstalled, reason = modelReason),
            action("suggest_fix", "Suggest fix", kinds, listOf("text"), emptyList(), emptyList(), true, false, "A suggestion needs an installed local model. Nothing was applied or executed.", 25, available = modelInstalled, reason = modelReason),
            action("copy_snippet", "Copy snippet", kinds, listOf("text"), emptyList(), emptyList(), true, false, "Nothing to copy.", 20),
            action("save_note", "Save snippet", kinds, listOf("text"), emptyList(), emptyList(), true, false, "Snippet was not saved.", 15),
        )
    }
}

private object UrlActions : ActionProvider {
    override fun actions(content: Normalized, modelInstalled: Boolean): List<RecommendedAction> {
        val url = content.entities.firstOrNull { it.type == "url" }?.value
        if (content.kind != ContentKind.URL && url == null) return emptyList()
        val kinds = setOf(ContentKind.URL)
        val payload = JSONObject().put("url", url.orEmpty())
        return listOf(
            action("open_url", "Open link", kinds, listOf("url"), emptyList(), listOf("browser"), true, true, "Link was not opened.", 80, payload, available = url?.startsWith("http://") == true || url?.startsWith("https://") == true, reason = if (url == null) "No URL was detected." else null),
            action("bookmark_url", "Bookmark", kinds, listOf("url"), emptyList(), emptyList(), true, false, "Bookmark was not saved.", 50, payload),
            action("url_metadata", "Show link metadata", kinds, listOf("url"), emptyList(), emptyList(), true, false, "No metadata was available without fetching the page.", 40, payload),
            action("snapshot_url", "Save accessible snapshot", kinds, listOf("url"), listOf("INTERNET"), listOf("network"), false, true, "Snapshot was not fetched. Fetch runs only after confirmation and is stored separately.", 20, payload),
        )
    }
}
