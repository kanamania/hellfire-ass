package com.hellfire.ass.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.withTransaction
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.money.*
import com.hellfire.ass.ui.tasks.*
import com.hellfire.ass.ui.theme.Hf
import kotlinx.coroutines.*

/** One queue; each payment and reminder still uses its existing explicit approval sheet. */
@Composable
fun ReviewPage() {
    val app = LocalEnv.current.app
    val skillCount by remember { app.database.skills().pendingCount() }.collectAsStateWithLifecycle(0)
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val payments by remember { app.database.finances().candidates() }.collectAsStateWithLifecycle(emptyList())
    val tasks by remember { app.database.tasks().tasks() }.collectAsStateWithLifecycle(emptyList())
    val drafts by remember { app.database.communications().drafts() }.collectAsStateWithLifecycle(emptyList())
    val accounts by remember { app.database.finances().accounts() }.collectAsStateWithLifecycle(emptyList())
    val aliases by remember { app.database.finances().aliases() }.collectAsStateWithLifecycle(emptyList())
    val nightly by remember { app.database.tasks().reviews() }.collectAsStateWithLifecycle(emptyList())
    val proposed = tasks.filter { it.state == "REVIEW" }
    val pendingDrafts = drafts.filter { it.state == "DRAFT" }
    val total = payments.size + proposed.size + pendingDrafts.size + skillCount
    var reviewed by rememberSaveable { mutableIntStateOf(0) }
    var paymentId by rememberSaveable { mutableStateOf<String?>(null) }
    val payment = payments.firstOrNull { it.id == paymentId }
    var task by rememberSaveable(saver = TaskDraftStateSaver) { mutableStateOf<TaskDraft?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var batch by remember { mutableStateOf(false) }
    var account by remember { mutableStateOf<String?>(null) }
    var category by remember { mutableStateOf<String?>(null) }
    PushedPage(com.hellfire.ass.ui.tr("Daily review"), breadcrumb = "Home") {
        Text("$total left · ${reviewed} reviewed this visit", style = Hf.type.cardTitle, color = Hf.colors.onSurface)
        message?.let { Banner(it) }
        Note(com.hellfire.ass.ui.tr("Payments, tasks and replies in one place. Nothing is posted, scheduled or sent without its own approval."))
        when {
            payments.isNotEmpty() && (proposed.minOfOrNull { it.dueAt } ?: Long.MAX_VALUE) > System.currentTimeMillis() + 86_400_000L -> {
                val next = payments.minBy { it.occurredAt }
                SectionCard(com.hellfire.ass.ui.tr("Next · payment")) {
                    Text(candidateTitle(next, aliases.firstOrNull { it.provider == next.provider && it.number == next.merchant }), style = Hf.type.rowTitle, color = Hf.colors.onSurface)
                    EvidenceCard("Source message", next.evidence.take(800))
                    PrimaryButton(com.hellfire.ass.ui.tr("Review payment"), { paymentId = next.id })
                    QuietButton(com.hellfire.ass.ui.tr("Choose another payment"), { nav.tab(MainTab.Money, "review") })
                }
            }
            proposed.isNotEmpty() -> SectionCard(com.hellfire.ass.ui.tr("Next · task")) {
                val next = proposed.minBy { it.dueAt }
                Text(next.title, color = Hf.colors.onSurface)
                Note("Suggested: ${Fmt.full(next.dueAt)}")
                PrimaryButton(com.hellfire.ass.ui.tr("Review task"), { task = TaskDraft.of(next) })
                QuietButton(com.hellfire.ass.ui.tr("Not a task"), { scope.launch { withContext(Dispatchers.IO) { com.hellfire.ass.tasks.TaskScheduler.action(app, next.id, "REJECTED"); app.skills.feedbackFor(app, "IGNORE_TASK", next.sourceId, "You rejected this task in Daily review. Review this rule before using it.") }; reviewed++ } })
            }
            pendingDrafts.isNotEmpty() -> SectionCard(com.hellfire.ass.ui.tr("Next · reply")) {
                val next = pendingDrafts.minBy { it.createdAt }
                Text("To ${next.recipient}", color = Hf.colors.onSurface)
                Note(next.body.take(400))
                PrimaryButton(com.hellfire.ass.ui.tr("Review reply in Inbox"), { nav.tab(MainTab.Inbox, "draft:${next.id}") })
            }
            skillCount > 0 -> SectionCard(tr("Review AI skills")) {
                Note(tr("Learned from your corrections") + " · $skillCount")
                PrimaryButton(tr("Review AI skills"), { nav.push(Screen.Skills) })
            }
            else -> SectionCard(com.hellfire.ass.ui.tr("You're caught up")) { Note(com.hellfire.ass.ui.tr("There are no payments, task proposals or drafts waiting for review.")) }
        }
        if (payments.size > 1) SecondaryButton(com.hellfire.ass.ui.tr("Set account/category for pending payments…"), { batch = true })
        if (nightly.isNotEmpty()) SectionCard(com.hellfire.ass.ui.tr("Cash check-in")) {
            Note("Anything you spent in cash and have not logged? ${nightly.size} daily check-in(s) are still open.")
            PrimaryButton(com.hellfire.ass.ui.tr("Add cash"), { nav.tab(MainTab.Money, "cash") })
            SecondaryButton(com.hellfire.ass.ui.tr("I have finished these cash check-ins"), {
                scope.launch {
                    withContext(Dispatchers.IO) { nightly.forEach { app.database.tasks().completeReview(it.day, System.currentTimeMillis()) } }
                    message = "Cash check-ins completed. Payments waiting for confirmation remain in the queue."
                }
            })
        }
    }
    payment?.let { p -> key(p.id) { CandidateReviewSheet(p, accounts, aliases, "") { message = it; if (it != null) reviewed++; paymentId = null } } }
    task?.let { t -> TaskEditSheet(t) { message = it; if (it != null) reviewed++; task = null } }
    if (batch) HfBottomSheet({ batch = false }) {
        Text(com.hellfire.ass.ui.tr("Suggest account and category"), style = Hf.type.cardTitle, color = Hf.colors.onSurface)
        ChoiceField("Account", accounts.firstOrNull { it.id == account }, accounts, { it.label }, { account = it.id })
        ChipPicker(Categories, category) { category = it }
        Note("Applies to the ${payments.size} payments listed below. Amounts, dates and evidence stay unchanged. Each payment still needs confirmation.")
        if (payments.size > 50) Note("Showing the first 50; the suggestions apply to all ${payments.size} pending payments.")
        payments.take(50).forEach { Note("${Fmt.amount(it.amount)} · ${it.evidence.take(60)}") }
        PrimaryButton("Apply suggestions to ${payments.size} payments", {
            val ids = payments.map { it.id }
            scope.launch {
                withContext(Dispatchers.IO) { app.database.withTransaction { ids.forEach { id ->
                    app.database.finances().suggestReview(id, account, category)
                } } }
                batch = false; message = "Suggestions saved. Review each payment before posting."
            }
        }, enabled = account != null || category != null)
    }
}
