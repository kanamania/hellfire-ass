package com.hellfire.ass.drawer

/** Literal strings from a PDF byte stream. This is not OCR and does not claim a full text extract. */
object PdfText {
    fun literals(bytes: ByteArray, limit: Int = 8_000): String {
        if (bytes.isEmpty()) return ""
        val raw = bytes.toString(Charsets.ISO_8859_1)
        val found = Regex("""\((?:\\.|[^\\)]){2,240}\)""").findAll(raw).map { unescape(it.value.removeSurrounding("(", ")")) }
            .filter { text -> text.count { it.isLetter() } >= 2 }
            .take(200)
        return found.joinToString(" ").replace(Regex("""\s+"""), " ").trim().take(limit)
    }

    private fun unescape(value: String): String = value
        .replace("\\n", " ").replace("\\r", " ").replace("\\t", " ")
        .replace("\\(", "(").replace("\\)", ")").replace("\\\\", "\\")
}
