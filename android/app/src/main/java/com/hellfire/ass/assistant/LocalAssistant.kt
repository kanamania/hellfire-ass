package com.hellfire.ass.assistant

import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class LocalAssistant(private val clock: Clock = Clock.systemUTC()) {
    fun answer(query: String): String = when (query.trim().lowercase(Locale.ROOT)) {
        "hello", "hellfire", "hi" -> "Hello. I'm HellFire. This Phase 0 response works offline."
        "habari", "hujambo" -> "Habari! Mimi ni HellFire. Jaribio hili linafanya kazi bila intaneti."
        "what time is it?", "what time is it", "time", "saa ngapi" ->
            "Dar es Salaam: " + DateTimeFormatter.ofPattern("HH:mm, d MMM uuuu", Locale.ENGLISH)
                .withZone(ZoneId.of("Africa/Dar_es_Salaam")).format(clock.instant())
        "", "help", "msaada" -> "Try hello, habari, or time. This is a local test assistant; no model is loaded."
        else -> "This Phase 0 assistant can answer hello, habari, or time. Personal search is not implemented yet."
    }
}
