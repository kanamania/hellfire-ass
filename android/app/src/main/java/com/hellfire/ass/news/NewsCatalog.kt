package com.hellfire.ass.news

/** Built-in sources. Feed URLs are the publishers' documented feeds; SITE entries are resolved by autodiscovery on first run. */
object NewsCatalog {
    const val X_NOTE = "Needs an X API bearer token with recent-search access (a paid X API tier). Add it in News settings."
    const val SHARE_ID = "owner-share"
    private fun licenseNote(name: String) = "$name content needs a licensed API or contract; HellFire never scrapes it. " +
        "Alternatives: its verified X account through the X API, and $name attribution detected in other outlets' reports."

    private data class Entry(val id: String, val name: String, val kind: String, val url: String, val category: String, val language: String,
        val reliability: Int, val official: Boolean = false, val agency: String? = null, val note: String = "")

    private val entries = listOf(
        Entry("bbc-world", "BBC News World", "RSS", "https://feeds.bbci.co.uk/news/world/rss.xml", "INTERNATIONAL", "en", 85),
        Entry("bbc-africa", "BBC News Africa", "RSS", "https://feeds.bbci.co.uk/news/world/africa/rss.xml", "INTERNATIONAL", "en", 85),
        Entry("bbc-swahili", "BBC Swahili", "RSS", "https://feeds.bbci.co.uk/swahili/rss.xml", "EAST_AFRICA", "sw", 85),
        Entry("aljazeera", "Al Jazeera", "RSS", "https://www.aljazeera.com/xml/rss/all.xml", "INTERNATIONAL", "en", 75),
        Entry("dw", "DW News", "RSS", "https://rss.dw.com/rdf/rss-en-all", "INTERNATIONAL", "en", 85),
        Entry("the-verge", "The Verge", "RSS", "https://www.theverge.com/rss/index.xml", "TECHNOLOGY", "en", 65),
        Entry("ars-technica", "Ars Technica", "RSS", "https://feeds.arstechnica.com/arstechnica/index", "TECHNOLOGY", "en", 65),
        Entry("techcrunch", "TechCrunch", "RSS", "https://techcrunch.com/feed/", "TECHNOLOGY", "en", 65),
        Entry("android-authority", "Android Authority", "RSS", "https://www.androidauthority.com/feed/", "TECHNOLOGY", "en", 65),
        Entry("9to5google", "9to5Google", "RSS", "https://9to5google.com/feed/", "TECHNOLOGY", "en", 65),
        Entry("gsmarena", "GSMArena", "RSS", "https://www.gsmarena.com/rss-news-reviews.php3", "TECHNOLOGY", "en", 65),
        Entry("toms-hardware", "Tom's Hardware", "RSS", "https://www.tomshardware.com/feeds/all", "TECHNOLOGY", "en", 65),
        Entry("the-citizen", "The Citizen", "SITE", "https://www.thecitizen.co.tz", "TANZANIA", "en", 75),
        Entry("mwananchi", "Mwananchi", "SITE", "https://www.mwananchi.co.tz", "TANZANIA", "sw", 75),
        Entry("daily-news-tz", "Daily News", "SITE", "https://dailynews.co.tz", "TANZANIA", "en", 75),
        Entry("habari-leo", "HabariLeo", "SITE", "https://habarileo.co.tz", "TANZANIA", "sw", 75),
        Entry("guardian-tz", "The Guardian (Tanzania)", "SITE", "https://www.ippmedia.com", "TANZANIA", "en", 75),
        Entry("nipashe", "Nipashe", "SITE", "https://www.ippmedia.com", "TANZANIA", "sw", 75,
            note = "Published on ippmedia.com with The Guardian; a shared feed may carry both titles. Duplicate links are stored once."),
        Entry("tbc", "TBC", "SITE", "https://www.tbc.go.tz", "TANZANIA", "mixed", 80, official = true, note = "Public broadcaster."),
        Entry("itv-tz", "ITV / Radio One", "SITE", "https://www.itv.co.tz", "TANZANIA", "mixed", 75),
        Entry("the-eastafrican", "The EastAfrican", "SITE", "https://www.theeastafrican.co.ke", "EAST_AFRICA", "en", 75),
        Entry("reuters", "Reuters", "LICENSED", "https://www.reuters.com", "INTERNATIONAL", "en", 85, agency = "REUTERS", note = licenseNote("Reuters")),
        Entry("ap", "Associated Press", "LICENSED", "https://apnews.com", "INTERNATIONAL", "en", 85, agency = "AP", note = licenseNote("AP")),
        Entry("afp", "AFP", "LICENSED", "https://www.afp.com", "INTERNATIONAL", "en", 85, agency = "AFP", note = licenseNote("AFP")),
        Entry("x-reuters", "Reuters on X", "X_ACCOUNT", "Reuters", "SOCIAL", "en", 70, official = true, agency = "REUTERS"),
        Entry("x-ap", "AP on X", "X_ACCOUNT", "AP", "SOCIAL", "en", 70, official = true, agency = "AP"),
        Entry("x-afp", "AFP on X", "X_ACCOUNT", "AFP", "SOCIAL", "en", 70, official = true, agency = "AFP"),
        Entry("x-bbcbreaking", "BBC Breaking on X", "X_ACCOUNT", "BBCBreaking", "SOCIAL", "en", 70, official = true),
        Entry("x-ajenglish", "Al Jazeera English on X", "X_ACCOUNT", "AJEnglish", "SOCIAL", "en", 70, official = true),
    )

    val ids: List<String> get() = entries.map { it.id }

    fun defaults(now: Long, hasXToken: Boolean): List<NewsSource> = entries.map { e ->
        val state = when {
            e.kind == "LICENSED" -> "NEEDS_LICENSE"
            e.kind.startsWith("X_") && !hasXToken -> "NEEDS_KEY"
            else -> "ACTIVE"
        }
        val note = if (e.kind.startsWith("X_") && e.note.isEmpty()) X_NOTE else e.note
        NewsSource(e.id, e.name, e.kind, e.url, e.category, e.language, e.reliability, state = state, official = e.official, agency = e.agency,
            note = note, builtIn = true, createdAt = now, updatedAt = now)
    }

    /** Hidden source row for items the owner shares or drops in; never fetched. */
    fun share(now: Long) = NewsSource(SHARE_ID, "Shared by you", "SHARE", "", "CUSTOM", "mixed", 50, state = "REMOVED",
        note = "Items you shared or dropped in. Never fetched.", builtIn = true, createdAt = now, updatedAt = now)
}
