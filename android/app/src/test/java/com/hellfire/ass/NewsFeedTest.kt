package com.hellfire.ass

import android.app.Application
import com.hellfire.ass.news.NewsFeeds
import com.hellfire.ass.news.XPosts
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)

class NewsFeedTest {
    @Test fun `rss and atom excerpts parse without a network`() {
        val rss = """<?xml version="1.0"?><rss version="2.0"><channel><title>Daily</title><item><title>Floods</title><link>https://daily.example/a</link><pubDate>Tue, 17 Nov 2026 07:00:00 GMT</pubDate><description>12 people died</description></item></channel></rss>"""
        val parsed = NewsFeeds.parse(rss.toByteArray(), "https://daily.example/rss")
        assertEquals("Floods", parsed.items.single().title)
        assertEquals("https://daily.example/a", parsed.items.single().url)
        val atom = """<?xml version="1.0"?><feed xmlns="http://www.w3.org/2005/Atom"><title>Mwananchi</title><entry><title>Mafuriko</title><link href="https://mwananchi.example/b"/><updated>2026-11-17T07:00:00Z</updated><summary>Watu 12</summary></entry></feed>"""
        assertEquals("Mafuriko", NewsFeeds.parse(atom.toByteArray(), "https://mwananchi.example/feed").items.single().title)
    }

    @Test fun `one bad feed does not stop the parser from reading the next document`() {
        assertTrue(runCatching { NewsFeeds.parse("<html>nope</html>".toByteArray(), "https://example.com") }.isFailure)
        val ok = NewsFeeds.parse("""{"version":"https://jsonfeed.org/version/1.1","title":"Tech","items":[{"id":"1","url":"https://tech.example/1","title":"Chip","content_text":"A new chip"}]}""".toByteArray(), "https://tech.example/feed.json")
        assertEquals(1, ok.items.size)
    }

    @Test fun `x posts become links and do not treat virality as a fact`() {
        val json = """{"data":[{"id":"1","text":"Floods in Dar es Salaam","author_id":"9","created_at":"2026-11-17T07:00:00.000Z"}],"includes":{"users":[{"id":"9","username":"BBCBreaking","name":"BBC Breaking","verified":true}]}}"""
        val (items, newest) = XPosts.parse(json)
        assertEquals("1", newest)
        assertEquals("https://x.com/BBCBreaking/status/1", items.single().url)
    }
}
