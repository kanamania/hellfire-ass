package com.hellfire.ass

import android.app.Application
import com.hellfire.ass.news.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)

class NewsEngineTest {
    private val now = 1_763_400_000_000L

    private fun article(id: String, source: String, name: String, title: String, text: String, at: Long = now, agency: String? = null, social: Boolean = false) =
        ArticleInput(id, source, name, title, text, "https://$source.example/$id", at, sourceAgency = agency, social = social)

    private fun cluster(articles: List<ArticleInput>): Map<String, String> {
        var centroids = emptyList<EventCentroid>()
        val assigned = linkedMapOf<String, String>()
        for (item in articles) {
            val features = NewsText.features(item)
            val decision = NewsClusterer.assign(item.id, features, item.publishedAt, null, centroids)
            val eventId = decision.eventId ?: NewsClusterer.newEventId(item.id)
            assigned[item.id] = eventId
            val existing = centroids.find { it.eventId == eventId }
            val updated = NewsClusterer.add(existing, eventId, item.id, features, item.publishedAt, null)
            centroids = centroids.filter { it.eventId != eventId } + updated
        }
        return assigned
    }

    @Test fun `five publishers about one flood become one event`() {
        val text = "At least 12 people died after flooding in Dar es Salaam. Rescue teams are searching Jangwani."
        val titles = listOf(
            "bbc" to "BBC", "citizen" to "The Citizen", "guardian" to "The Guardian", "aljazeera" to "Al Jazeera", "dw" to "DW",
        )
        val assigned = cluster(titles.mapIndexed { i, (id, name) -> article("f$i", id, name, "Floods kill 12 in Dar es Salaam", text) })
        assertEquals(setOf(assigned.values.first()), assigned.values.toSet())
    }

    @Test fun `english and kiswahili coverage of the same flood cluster`() {
        val en = article("en", "bbc", "BBC", "Floods kill 12 in Dar es Salaam", "At least 12 people died after flooding in Dar es Salaam. Rescue teams are searching Jangwani.")
        val sw = article("sw", "mwananchi", "Mwananchi", "Mafuriko yaua watu 12 Dar es Salaam", "Watu 12 wamefariki kutokana na mafuriko jijini Dar es Salaam. Uokoaji unaendelea Jangwani.")
        val assigned = cluster(listOf(en, sw))
        assertEquals(assigned["en"], assigned["sw"])
    }

    @Test fun `two events about the same person stay separate`() {
        val budget = article("b", "citizen", "The Citizen", "President Samia approved the national budget in Dodoma",
            "President Samia approved the national budget in Dodoma. Parliament passed the spending plan.")
        val hospital = article("h", "daily", "Daily News", "President Samia opened a new hospital in Mwanza",
            "President Samia opened a new hospital in Mwanza. The health facility will serve patients in the region.")
        val assigned = cluster(listOf(budget, hospital))
        assertNotEquals(assigned["b"], assigned["h"])
    }

    @Test fun `republished wire copy is not an independent confirmation`() {
        val text = "Reuters reported that floods killed 12 people in Dar es Salaam."
        val articles = listOf(
            article("r1", "citizen", "The Citizen", "Floods kill 12 in Dar es Salaam", text, agency = "REUTERS"),
            article("r2", "guardian", "The Guardian", "Dar es Salaam floods kill 12", text, agency = "REUTERS"),
            article("r3", "habari", "HabariLeo", "Mafuriko Dar es Salaam", text, agency = "REUTERS"),
        )
        val pairs = articles.map { it to NewsText.features(it) }
        val groups = NewsIndependence.groups(pairs)
        assertEquals(1, groups.values.toSet().size)
        assertEquals(1, NewsFacts.snapshot(pairs).independentSources)
    }

    @Test fun `contradictory death tolls are labelled conflicting`() {
        val a = article("a", "bbc", "BBC", "Floods kill 12 in Dar es Salaam", "BBC reports 12 people died after flooding in Dar es Salaam.")
        val b = article("b", "daily", "Daily News", "Floods kill 9 in Dar es Salaam", "Daily News reports 9 people died after flooding in Dar es Salaam.")
        val snapshot = NewsFacts.snapshot(listOf(a, b).map { it to NewsText.features(it) })
        assertEquals("CONFLICTING", snapshot.verification)
        assertTrue(snapshot.conflicts.joinToString(" "), snapshot.conflicts.any { it.contains("12") && it.contains("9") })
    }

    @Test fun `restated facts are not a material update and a correction is labelled`() {
        val first = article("a", "bbc", "BBC", "Floods kill 9 in Dar es Salaam", "9 people died after flooding in Dar es Salaam.")
        val repeat = article("b", "citizen", "The Citizen", "Dar floods: 9 dead", "9 people died after flooding in Dar es Salaam.")
        val correction = article("c", "bbc", "BBC", "Correction: floods killed 12, not 9", "Correction: 12 people died, not 9, after flooding in Dar es Salaam.")
        val base = NewsFacts.snapshot(listOf(first).map { it to NewsText.features(it) })
        val restated = NewsFacts.snapshot(listOf(first, repeat).map { it to NewsText.features(it) })
        val fixed = NewsFacts.snapshot(listOf(first, correction).map { it to NewsText.features(it) })
        assertEquals("NONE", NewsFacts.delta(base, restated, "DISASTER").classification)
        val delta = NewsFacts.delta(base, fixed, "DISASTER")
        assertEquals("CORRECTION", delta.classification)
        assertTrue(delta.whatsNew.contains("12"))
        assertFalse(delta.whatsNew.contains("Jangwani"))
    }

    @Test fun `a confirmed new decision is a major update and ten restatements do not reappear`() {
        val known = article("a", "citizen", "The Citizen", "Budget debate opens in Dodoma", "Parliament opened the budget debate in Dodoma.")
        val approved = article("b", "daily", "Daily News", "Parliament approved the budget", "Parliament approved the national budget in Dodoma.")
        val before = NewsFacts.snapshot(listOf(known).map { it to NewsText.features(it) })
        val after = NewsFacts.snapshot(listOf(known, approved).map { it to NewsText.features(it) })
        val delta = NewsFacts.delta(before, after, "LEGISLATION")
        assertEquals("MAJOR", delta.classification)
        assertTrue(delta.whatsNew.contains("approved"))
        val state = mapOf("ev" to NewsEventState("ev", deliveredVersion = 1, updatedAt = now))
        val restated = (1..10).map { n ->
            BriefCandidate("ev", 1, 80, "HIGH", false, setOf("economy"), now, now, emptyMap(), 0.9)
        }
        assertTrue(NewsBriefings.build(restated.take(1), state, now - 86_400_000..now, BriefConfig()).none { it.section == "CHANGED" })
    }

    @Test fun `delivered morning story stays out of the evening unless a material version exists`() {
        val window = now - 3_600_000..now
        val state = mapOf("flood" to NewsEventState("flood", deliveredVersion = 1, updatedAt = now - 10_000))
        val same = BriefCandidate("flood", 1, 70, "HIGH", true, setOf("tanzania"), now - 20_000, now - 10_000, mapOf(2 to "MINOR"), 0.1)
        assertTrue(NewsBriefings.build(listOf(same), state, window, BriefConfig()).isEmpty())
        val updated = same.copy(version = 2, versionClasses = mapOf(2 to "MAJOR"))
        val items = NewsBriefings.build(listOf(updated), state, window, BriefConfig())
        assertEquals(listOf("CHANGED"), items.map { it.section })
        assertEquals("MAJOR", items.single().classification)
    }

    @Test fun `public safety outranks a personalized technology story`() {
        val safety = ScoreInput("DISASTER", setOf("tanzania"), setOf("dar es salaam"), emptySet(),
            EventSnapshot(emptyList(), emptyMap(), 2, "CONFIRMED", emptyList(), false), listOf(80), listOf("NEWS"), now, true, true, "NEW")
        val tech = ScoreInput("PRODUCT_LAUNCH", setOf("technology", "smartphones"), emptySet(), setOf("samsung"),
            EventSnapshot(emptyList(), emptyMap(), 1, "SINGLE_SOURCE", emptyList(), false), listOf(65), listOf("NEWS"), now, false, false, "NEW")
        val interests = listOf(Interest("topic:smartphones", "EXPLICIT", 2.0), Interest("topic:technology", "EXPLICIT", 2.0))
        val public = NewsScoring.score(safety, interests, now)
        val personal = NewsScoring.score(tech, interests, now)
        assertTrue("public ${public.total} personal ${personal.total}", public.total > personal.total)
        assertTrue(public.protectedPublic)
    }

    @Test fun `extractive summary does not invent numbers and the parser rejects them`() {
        val item = article("a", "bbc", "BBC", "Floods kill 12 in Dar es Salaam", "At least 12 people died after flooding in Dar es Salaam.")
        val features = NewsText.features(item)
        val snapshot = NewsFacts.snapshot(listOf(item to features))
        val summary = NewsSummaries.extractive(item.title, listOf(item to features), snapshot, emptyList(), "STANDARD", "en")
        val spoken = summary.spoken()
        assertFalse(spoken.contains("99"))
        assertTrue(summary.quick.all { !it.contains("99") })
        val bad = """{"headline":"Floods","quick":["99 people died"],"details":[],"whyItMatters":"","localImpact":"","background":"","whatsNext":[]}"""
        assertThrows(IllegalArgumentException::class.java) {
            NewsSummaries.parseModel(bad, listOf(item to features), snapshot, "en", "AI:test/test")
        }
    }

    @Test fun `feature json round trips`() {
        val item = article("a", "bbc", "BBC", "Floods kill 12 in Dar es Salaam", "At least 12 people died after flooding in Dar es Salaam.")
        val features = NewsText.features(item)
        val restored = ArticleFeatures.fromJson(features.toJson())
        assertEquals(features.eventType, restored.eventType)
        assertEquals(features.places, restored.places)
        assertEquals(features.fingerprint, restored.fingerprint)
    }
}
