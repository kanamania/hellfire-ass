package com.hellfire.ass.news

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.math.ln

/** One word or number of a text. [low]: lowercase, possessive removed. [start]: first token of a sentence. */
internal class Tok(val raw: String, val low: String, val stem: String, val start: Boolean) {
    val isNumber get() = raw[0].isDigit()
    val capitalized get() = raw[0].isUpperCase()
}

/** Result of one pass over a text: lexicon concepts, places and entities with the token spans they cover. */
internal class Scan(val tokens: List<Tok>) {
    val concepts = ArrayList<Pair<Int, NewsLexicon.Lookup>>()
    val places = ArrayList<String>()
    val entities = ArrayList<String>()
    val covered = BooleanArray(tokens.size)
}

object NewsText {
    private val tokenRegex = Regex("\\d+(?:[.,]\\d+)*|[\\p{L}][\\p{L}\\p{M}'’]*|[%$€]")
    private val abbreviations = setOf("dr", "dkt", "dk", "mr", "mrs", "ms", "bw", "bi", "prof", "st", "no", "mhe", "inc", "ltd", "co", "jan",
        "feb", "aug", "sept", "oct", "nov", "dec", "gen", "col", "lt", "sgt", "rev", "u.s", "u.k", "vs", "e.g", "i.e", "approx", "est")

    internal fun tokenize(text: String): List<Tok> {
        val out = ArrayList<Tok>()
        var start = true
        var last = 0
        for (m in tokenRegex.findAll(text)) {
            val between = text.substring(last, m.range.first)
            if (out.isNotEmpty() && (between.contains('\n') || Regex("[.!?…]['\"”’)]*\\s*$").containsMatchIn(between) &&
                    out.last().low !in abbreviations)) start = true
            last = m.range.last + 1
            val raw = m.value.replace('’', '\'')
            var low = NewsLexicon.lower(raw)
            if (low.endsWith("'s") && low.length > 3) low = low.dropLast(2)
            out += Tok(raw, low, NewsLexicon.stem(low), start)
            start = false
        }
        return out
    }

    /** Sentences of [text]; abbreviations (Dr., Bw., U.S.) and decimals do not split. */
    internal fun sentences(text: String): List<String> {
        val pieces = text.split(Regex("(?<=[.!?…])['\"”’)]*\\s+(?=['\"“‘(]?[\\p{Lu}\\d])|\\n+"))
        val out = ArrayList<String>()
        var carry = ""
        for (piece in pieces) {
            val joined = (if (carry.isEmpty()) piece else "$carry $piece").trim()
            val lastWord = Regex("([\\p{L}.]+)\\.$").find(joined)?.groupValues?.get(1)?.let(NewsLexicon::lower)
            if (lastWord != null && (lastWord in abbreviations || lastWord.length == 1)) { carry = joined; continue }
            carry = ""
            if (joined.isNotBlank()) out += joined
        }
        if (carry.isNotBlank()) out += carry
        return out
    }

    internal fun scan(text: String): Scan = scan(tokenize(text))
    internal fun scan(tokens: List<Tok>): Scan {
        val s = Scan(tokens)
        val placeSpan = BooleanArray(tokens.size)
        var i = 0
        while (i < tokens.size) {
            var matched = 0
            for (len in minOf(NewsLexicon.maxPlaceLen, tokens.size - i) downTo 1) {
                val phrase = tokens.subList(i, i + len).joinToString(" ") { it.low }
                val place = NewsLexicon.placeAlias(phrase) ?: continue
                if (!tokens[i].capitalized && !tokens[i].raw.all { it.isUpperCase() || !it.isLetter() }) continue
                if (phrase in NewsLexicon.ambiguousPlaces && (tokens[i].start || tokens[i].raw.all { it.isUpperCase() })) continue
                s.places += place
                NewsLexicon.places[place]?.country?.let { s.places += it }
                matched = len; break
            }
            var entityLen = 0
            for (len in minOf(NewsLexicon.maxEntityLen, tokens.size - i) downTo 1) {
                val words = tokens.subList(i, i + len)
                val alias = NewsLexicon.entityAlias(words.joinToString(" ") { it.low }) ?: continue
                val ok = if (alias.exactCase != null) words.joinToString(" ") { it.raw } == alias.exactCase else words[0].capitalized
                if (!ok) continue
                if (len > matched) { s.entities += alias.canonical; entityLen = len }
                break
            }
            val span = maxOf(matched, entityLen)
            if (span > 0) {
                for (k in i until i + span) s.covered[k] = true
                if (matched >= entityLen) for (k in i until i + matched) placeSpan[k] = true
                i += span
            } else i++
        }
        val stems = tokens.map { it.stem }
        val lows = tokens.map { it.low }
        i = 0
        while (i < tokens.size) {
            if (placeSpan[i] || tokens[i].isNumber) { i++; continue }
            val hit = NewsLexicon.conceptAt(lows, stems, i)
            if (hit == null || (hit.length == 1 && (lows[i] in NewsLexicon.enStop || lows[i] in NewsLexicon.swStop))) { i++; continue }
            s.concepts += i to hit
            for (k in i until i + hit.length) s.covered[k] = true
            i += hit.length
        }
        return s
    }

    /** Lowercase host without www/m/amp, no fragment, no tracking parameters (utm_*, fbclid, gclid, ref, ...), no trailing slash, /amp removed. */
    fun canonicalUrl(url: String): String {
        val trimmed = url.trim()
        val uri = runCatching { URI(if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(trimmed)) trimmed else "https://$trimmed") }.getOrNull()
            ?: return trimmed
        val host = uri.host?.let(NewsLexicon::lower)?.removeSuffix(".") ?: return trimmed
        val cleanHost = host.replace(Regex("^(?:www\\d?|m|mobile|amp)\\."), "")
        val scheme = NewsLexicon.lower(uri.scheme ?: "https").let { if (it == "http") "https" else it }
        val port = if (uri.port == -1 || uri.port == 80 || uri.port == 443) "" else ":${uri.port}"
        var path = uri.rawPath.orEmpty().replace(Regex("/{2,}"), "/")
        path = path.replace(Regex("/amp/?$"), "").replace(Regex("\\.amp(?=\\.html?$)"), "").trimEnd('/')
        val params = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.mapNotNull { p ->
            val key = NewsLexicon.lower(URLDecoder.decode(p.substringBefore('='), "UTF-8"))
            val value = URLDecoder.decode(p.substringAfter('=', ""), "UTF-8")
            val drop = key.startsWith("utm_") || key.startsWith("at_") || key in setOf("fbclid", "gclid", "dclid", "msclkid", "mc_cid", "mc_eid",
                "ref", "ref_src", "ref_url", "cmpid", "ocid", "igshid", "amp", "_ga", "yclid", "spm", "s_cid", "cid_source") ||
                (key == "outputtype" && NewsLexicon.lower(value) == "amp")
            if (drop) null else key to value
        }.sortedWith(compareBy({ it.first }, { it.second }))
        val query = if (params.isEmpty()) "" else "?" + params.joinToString("&") {
            URLEncoder.encode(it.first, "UTF-8") + if (it.second.isEmpty()) "" else "=" + URLEncoder.encode(it.second, "UTF-8") }
        return "$scheme://$cleanHost$port$path$query"
    }

    /** "sw" or "en" (mixed text picks the majority). */
    fun detectLanguage(text: String): String {
        val words = tokenize(text).filter { !it.isNumber }.map { it.low }
        val sw = NewsLexicon.swScore(words)
        val en = NewsLexicon.enScore(words)
        if (sw != en) return if (sw > en) "sw" else "en"
        if (words.size >= 3 && words.count { it.last() in "aeiou" } >= words.size * 0.85) return "sw"
        return "en"
    }

    fun features(input: ArticleInput): ArticleFeatures {
        val title = neutralHeadline(input.title)
        val language = input.language?.takeIf { it == "en" || it == "sw" } ?: detectLanguage(title + ". " + input.text)
        val titleScan = scan(title)
        val bodyTokens = tokenize(input.text)
        val bodyScan = scan(bodyTokens)

        val counts = HashMap<String, Double>()
        fun addScan(scan: Scan, weight: Double) {
            scan.concepts.forEach { (_, hit) -> counts.merge(hit.concept, weight, Double::plus) }
            scan.tokens.forEachIndexed { k, t ->
                if (scan.covered[k] || t.isNumber || t.low.length < 3 || t.low in NewsLexicon.enStop || t.low in NewsLexicon.swStop || !t.low[0].isLetter()) return@forEachIndexed
                counts.merge("~" + t.stem, weight, Double::plus)
            }
        }
        addScan(titleScan, 2.0); addScan(bodyScan, 1.0)
        val concepts = counts.mapValues { 1.0 + ln(it.value) }.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key }).take(80)
            .associate { it.key to Math.round(it.value * 1000) / 1000.0 }

        val entities = LinkedHashSet<String>()
        entities += titleScan.entities; entities += bodyScan.entities
        entities += capitalizedNames(bodyScan, entities)
        val places = LinkedHashSet<String>().apply { addAll(titleScan.places); addAll(bodyScan.places) }

        val typeScores = HashMap<String, Double>()
        fun typeFrom(scan: Scan, weight: Double) = scan.concepts.groupingBy { it.second.concept }.eachCount().forEach { (concept, n) ->
            NewsLexicon.typeTriggers[concept]?.forEach { (type, w) -> typeScores.merge(type, w * weight * minOf(n, 3), Double::plus) }
        }
        typeFrom(titleScan, 2.0); typeFrom(bodyScan, 1.0)
        val best = typeScores.maxOfOrNull { it.value } ?: 0.0
        val eventType = if (best < 1.0) "OTHER" else NewsLexicon.typePriority.first { (typeScores[it] ?: 0.0) >= best - 1e-9 }

        val topics = LinkedHashSet<String>()
        counts.keys.forEach { NewsLexicon.conceptTopics[it]?.let(topics::addAll) }
        entities.forEach { NewsLexicon.entityTopics[it]?.let(topics::addAll) }
        NewsLexicon.typeTopics[eventType]?.let(topics::addAll)
        val regions = places.mapNotNull { NewsLexicon.places[it]?.region }.toSet()
        if ("tanzania" in regions) { topics += "tanzania"; topics += "east_africa" }
        if ("east_africa" in regions) topics += "east_africa"
        if (regions.isNotEmpty() && regions.none { it == "tanzania" || it == "east_africa" }) topics += "global"

        val lowered = NewsLexicon.lower(title + " " + input.text.take(300))
        val urgent = Regex("\\b(breaking|urgent|just in|live updates|live:|dharura|taarifa ya haraka|hivi punde|habari za hivi punde)\\b|^live\\b")
            .containsMatchIn(lowered) || NewsLexicon.lower(input.title).startsWith("live")
        val allConcepts = counts.keys
        val hazard = setOf("rain", "weather", "storm", "disease", "fire", "flood", "earthquake", "landslide", "water", "accident", "road",
            "emergency", "outbreak", "cholera", "heat", "lightning", "lake", "terror", "electricity")
        val text = NewsLexicon.lower(title + " " + input.text)
        val publicSafety = allConcepts.any { it in setOf("evacuate", "outbreak", "storm", "flood", "earthquake", "landslide", "cholera", "ebola", "marburg", "emergency", "recall") } ||
            Regex("travel advisory|boil water|stay indoors|tsunami|shelter in place|state of emergency").containsMatchIn(text) ||
            ("warning" in allConcepts && allConcepts.any { it in hazard })
        val facts = NewsFactExtractor.extract(title, input.text, input.id)
        val fingerprint = if (bodyTokens.size >= 30) simhash(input.text) else simhash(input.title + " " + input.text)
        return ArticleFeatures(language, concepts, entities, places, eventType, topics, facts, contentType(input), agency(input), fingerprint, urgent, publicSafety)
    }

    /** Wire agency credited in the text, else the source's own agency. */
    internal fun agency(input: ArticleInput): String? {
        val t = input.title + "\n" + input.text
        return when {
            Regex("\\(Reuters\\)|\\bReuters (reported|reports|said|news agency)\\b|[—–-]\\s*Reuters\\b|\\bReuters\\s*[—–-]|\\bcopyright reuters|\\bshirika la habari la reuters", RegexOption.IGNORE_CASE).containsMatchIn(t) -> "REUTERS"
            Regex("\\(AP\\)|[—–-]\\s*AP\\b|\\bAssociated Press\\b|\\bAP reported\\b").containsMatchIn(t) -> "AP"
            Regex("\\(AFP\\)|[—–-]\\s*AFP\\b|\\bAgence France[- ]Presse\\b|\\bAFP (reported|reports|said)\\b|\\bshirika la habari la ufaransa", RegexOption.IGNORE_CASE).containsMatchIn(t) -> "AFP"
            Regex("\\(Xinhua\\)|\\bXinhua (reported|reports|said|news agency)\\b").containsMatchIn(t) -> "XINHUA"
            else -> input.sourceAgency?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        }
    }

    /** Multiword capitalized names from body text that the alias table does not know (e.g. "Juma Ally Mrisho"). */
    private fun capitalizedNames(scan: Scan, known: Set<String>): List<String> {
        val tokens = scan.tokens
        val out = ArrayList<String>()
        val connectors = setOf("es", "wa", "of", "de", "bin", "la", "van", "al", "da", "von", "binti")
        val knownWords = known.flatMap { it.split(' ') }.toSet()
        var i = 0
        while (i < tokens.size) {
            if (!tokens[i].capitalized || scan.covered[i] || tokens[i].isNumber) { i++; continue }
            var j = i
            while (j + 1 < tokens.size && !tokens[j + 1].start && !scan.covered[j + 1] &&
                (tokens[j + 1].capitalized || (tokens[j + 1].low in connectors && j + 2 < tokens.size && tokens[j + 2].capitalized && !scan.covered[j + 2]))) j++
            var run = tokens.subList(i, j + 1).toList()
            fun weak(t: Tok) = t.low in NewsLexicon.enStop || t.low in NewsLexicon.swStop || t.low in NewsLexicon.titleWords || t.low in connectors ||
                t.raw.length > 1 && t.raw.all { it.isUpperCase() } && t.raw.length > 6
            while (run.isNotEmpty() && weak(run.first())) run = run.drop(1)
            while (run.isNotEmpty() && weak(run.last())) run = run.dropLast(1)
            val name = run.joinToString(" ") { it.low }
            if (run.size >= 2 && run.none { NewsLexicon.placeAlias(it.low) != null } && !run.all { it.low in knownWords } && name !in known)
                out += NewsLexicon.entityAlias(name)?.canonical ?: name
            i = j + 1
        }
        // Acronyms (TANESCO, DAWASA, NHIF) the alias table does not cover.
        val caps = tokens.count { it.raw.length > 1 && it.raw.all { c -> !c.isLetter() || c.isUpperCase() } }
        if (caps < tokens.size * 0.3) tokens.forEachIndexed { k, t ->
            if (!scan.covered[k] && t.raw.length in 2..8 && Regex("[A-Z][A-Z0-9&]+").matches(t.raw) && t.raw !in NewsLexicon.capsNoise &&
                NewsLexicon.placeAlias(t.low) == null) out += t.low
        }
        return out.distinct()
    }

    /** 64-bit simhash of normalized word shingles. */
    fun simhash(text: String): Long {
        val words = Regex("[\\p{L}\\p{N}]+").findAll(NewsLexicon.lower(text)).map { it.value }.toList()
        val shingles = if (words.size >= 3) words.windowed(3) { it.joinToString(" ") } else words
        if (shingles.isEmpty()) return 0L
        val v = IntArray(64)
        for (s in shingles) {
            val h = hash64(s)
            for (bit in 0 until 64) if ((h ushr bit) and 1L == 1L) v[bit]++ else v[bit]--
        }
        var out = 0L
        for (bit in 0 until 64) if (v[bit] > 0) out = out or (1L shl bit)
        return out
    }
    internal fun hash64(s: String): Long {
        var h = -0x340d631b7bdddcdbL
        for (b in s.toByteArray(Charsets.UTF_8)) { h = h xor (b.toLong() and 0xff); h *= 0x100000001b3L }
        h = h xor (h ushr 33); h *= -0xae502812aa7333L; h = h xor (h ushr 33); h *= -0x3b314601e57a13adL; h = h xor (h ushr 33)
        return h
    }

    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /** Neutral, informative headline: drops "BREAKING:", "WATCH:", emoji, clickbait punctuation, shouting caps. */
    fun neutralHeadline(title: String): String {
        var t = title.replace(Regex("[\\p{So}\\p{Cs}\\x{FE0F}\\x{200D}]"), " ")
        val prefix = Regex("^\\s*(?:\\[[^\\]]{1,20}]\\s*|\\((?:video|photos?|picha|watch)\\)\\s*|(?:breaking(?: news)?|watch|live|video|photos?|update[ds]?|just in|exclusive|developing|habari mpya|tazama|sikiliza|mubashara|dharura|picha|hivi punde)\\s*[:|\\-–—]\\s*)+", RegexOption.IGNORE_CASE)
        t = t.replace(prefix, "")
        t = t.replace(Regex("\\s*[\\[(](?:video|photos?|picha|watch|tazama)[\\])]", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\s+\\|\\s+[^|]{1,40}$"), "")
        t = t.replace(Regex("\\b(?:you won'?t believe|shocking|must see|must watch|this is why|here'?s why)\\b[:!]?\\s*", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("!+"), "").replace(Regex("\\?{2,}"), "?").replace(Regex("\\.{3,}|…"), "")
        val letters = t.filter { it.isLetter() }
        if (letters.length > 10 && letters.count { it.isUpperCase() } > letters.length * 0.7) {
            t = t.split(' ').joinToString(" ") { w ->
                val bare = w.trim { !it.isLetterOrDigit() }
                if (bare.length in 2..6 && (bare in NewsLexicon.capsNoise || NewsLexicon.entityAlias(NewsLexicon.lower(bare)) != null)) w
                else NewsLexicon.lower(w)
            }
            t = t.trim().replaceFirstChar { it.uppercase() }
            // Restore capitals of known places and entities after lowering.
            t = t.split(' ').joinToString(" ") { w ->
                val bare = NewsLexicon.lower(w.trim { !it.isLetter() })
                if (bare.isNotEmpty() && (NewsLexicon.placeAlias(bare) != null || NewsLexicon.entityAlias(bare) != null) && bare !in NewsLexicon.ambiguousPlaces)
                    w.replaceFirstChar { it.uppercase() } else w
            }
        } else {
            t = t.split(' ').joinToString(" ") { w ->
                val bare = w.trim { !it.isLetter() }
                if (bare.length >= 7 && bare.all { it.isUpperCase() } && NewsLexicon.entityAlias(NewsLexicon.lower(bare)) == null)
                    w.lowercase().replaceFirstChar { it.uppercase() } else w
            }
        }
        return t.replace(Regex("\\s+"), " ").trim().trim(':', '-', '|', '–', '—', ' ').ifBlank { title.trim() }
    }

    /** NEWS, OPINION, ANALYSIS, SATIRE, ADVERTISEMENT, SOCIAL_POST, OFFICIAL_STATEMENT from title/text/url cues. */
    fun contentType(input: ArticleInput): String {
        if (input.social) return if (input.sourceOfficial) "OFFICIAL_STATEMENT" else "SOCIAL_POST"
        val title = NewsLexicon.lower(input.title)
        val url = NewsLexicon.lower(input.url)
        val head = NewsLexicon.lower(input.text.take(200))
        fun prefixed(vararg cues: String) = cues.any { cue -> Regex("^\\s*(?:\\[)?${Regex.escape(cue)}\\s*(?:[:|\\]\\-–—]|$)").containsMatchIn(title) }
        fun inUrl(vararg segs: String) = segs.any { url.contains("/$it/") || url.contains("/$it-") || url.endsWith("/$it") }
        return when {
            prefixed("sponsored", "advertorial", "paid content", "partner content", "promoted", "tangazo", "advertisement", "brand studio") ||
                inUrl("sponsored", "advertorial", "partner-content", "brand-studio", "matangazo") ||
                Regex("^(sponsored|advertisement|paid (post|content)|this content is (paid|sponsored))").containsMatchIn(head) -> "ADVERTISEMENT"
            prefixed("satire") || inUrl("satire") || Regex("\\b(satire|satirical)\\b").containsMatchIn(head) -> "SATIRE"
            prefixed("opinion", "op-ed", "oped", "editorial", "commentary", "column", "letter to the editor", "letters", "maoni", "tahariri", "makala") ||
                inUrl("opinion", "opinions", "editorial", "editorials", "commentary", "columnists", "columns", "op-ed", "maoni", "tahariri") -> "OPINION"
            prefixed("analysis", "explainer", "explained", "uchambuzi", "news analysis", "fact check", "in depth") ||
                inUrl("analysis", "explainer", "explainers", "uchambuzi", "in-depth") -> "ANALYSIS"
            input.sourceOfficial || prefixed("press release", "statement", "taarifa kwa umma", "taarifa kwa vyombo vya habari") ||
                Regex("^(press release|taarifa kwa (umma|vyombo vya habari))").containsMatchIn(head) -> "OFFICIAL_STATEMENT"
            else -> "NEWS"
        }
    }
}
