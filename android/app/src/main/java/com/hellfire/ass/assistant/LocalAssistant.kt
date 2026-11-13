package com.hellfire.ass.assistant

import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class LocalAssistant(private val clock: Clock = Clock.systemUTC()) {
    fun answer(query: String): String = when (query.trim().lowercase(Locale.ROOT)) {
        "hello", "hellfire", "hi" -> "Hello. I'm HellFire. I can search saved evidence and help review tasks and spending offline."
        "habari", "hujambo" -> "Habari! Mimi ni HellFire. Jaribio hili linafanya kazi bila intaneti."
        "what time is it?", "what time is it", "time", "saa ngapi" ->
            "Dar es Salaam: " + DateTimeFormatter.ofPattern("HH:mm, d MMM uuuu", Locale.ENGLISH)
                .withZone(ZoneId.of("Africa/Dar_es_Salaam")).format(clock.instant())
        "", "help", "msaada" -> "Try hello, habari, or time. For saved evidence, ask spending today, tasks tomorrow, morning brief, or search <words>."
        else -> "Ask spending today, balances, tasks tomorrow, morning brief, or search <words>."
    }
}
