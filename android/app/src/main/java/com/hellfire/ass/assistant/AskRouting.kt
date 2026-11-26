package com.hellfire.ass.assistant

import com.hellfire.ass.calendar.CalendarAgendaLogic

/** Intent decisions that sit between the fixed routers, kept free of Android types so they can be tested directly. */
object AskRouting {
    /** English news words always mean news; the Swahili "habari"/"taarifa" also mean "report" or "information". */
    private val explicitNews = Regex("\\b(news|headlines?|stories|story|breaking)\\b")
    private val tasks = Regex("\\b(tasks?|commitments?|ahadi|majukumu|reminders?|to-?dos?|kazi (?:za|zangu|zilizobaki))\\b")
    private val agenda = Regex("\\b(what do i have|what have i got|what's on|what is on|nina nini|nina kazi gani|nifanye nini)\\b")
    private val brief = Regex("\\b(brief|muhtasari)\\b")

    /** "nipe taarifa ya matumizi wiki hii" is a spending question, not news. */
    fun skipNews(lower: String): Boolean = !explicitNews.containsMatchIn(lower) &&
        (FinanceQuestion.isQuestion(lower) || tasks(lower) || CalendarAgendaLogic.question.containsMatchIn(lower))
    fun brief(lower: String): Boolean = brief.containsMatchIn(lower) && !FinanceQuestion.isQuestion(lower)
    fun tasks(lower: String): Boolean = tasks.containsMatchIn(lower) || agenda.containsMatchIn(lower)
    /** Task lists for a generic "what do I have" also show that window's appointments. */
    fun withAgenda(lower: String): Boolean = agenda.containsMatchIn(lower) || CalendarAgendaLogic.question.containsMatchIn(lower)
    /** A short "na ..."/"and ..." follow-up continues a money question only if it names no other subject. */
    fun financeFollowUp(lower: String): Boolean = FinanceQuestion.followUp(lower) && !tasks(lower) &&
        !CalendarAgendaLogic.question.containsMatchIn(lower) && !explicitNews.containsMatchIn(lower) && !brief.containsMatchIn(lower)

    const val CONTEXT_MILLIS = 10 * 60_000L
}
