package com.hellfire.ass.ui

import androidx.compose.ui.res.stringResource
import com.hellfire.ass.R

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
    PushedPage(stringResource(R.string.ui_daily_review), breadcrumb = "Home") {
        Text("$total left · ${reviewed} reviewed this visit", style = Hf.type.cardTitle, color = Hf.colors.onSurface)
        message?.let { Banner(it) }
        Note(stringResource(R.string.ui_payments_tasks_and_replies_in_one_place))
        when {
            payments.isNotEmpty() && (proposed.minOfOrNull { it.dueAt } ?: Long.MAX_VALUE) > System.currentTimeMillis() + 86_400_000L -> {
                val next = payments.minBy { it.occurredAt }
                SectionCard(stringResource(R.string.ui_next_payment)) {
                    Text(candidateTitle(next, aliases.firstOrNull { it.provider == next.provider && it.number == next.merchant }), style = Hf.type.rowTitle, color = Hf.colors.onSurface)
                    EvidenceCard("Source message", next.evidence.take(800))
                    PrimaryButton(stringResource(R.string.ui_review_payment), { paymentId = next.id })
                    QuietButton(stringResource(R.string.ui_choose_another_payment), { nav.tab(MainTab.Money, "review") })
                }
            }
            proposed.isNotEmpty() -> SectionCard(stringResource(R.string.ui_next_task)) {
                val next = proposed.minBy { it.dueAt }
                Text(next.title, color = Hf.colors.onSurface)
                Note("Suggested: ${Fmt.full(next.dueAt)}")
                PrimaryButton(stringResource(R.string.ui_review_task), { task = TaskDraft.of(next) })
                QuietButton(stringResource(R.string.ui_not_a_task), { scope.launch { withContext(Dispatchers.IO) { com.hellfire.ass.tasks.TaskScheduler.action(app, next.id, "REJECTED"); app.skills.feedbackFor(app, "IGNORE_TASK", next.sourceId, "You rejected this task in Daily review. Review this rule before using it.") }; reviewed++ } })
            }
            pendingDrafts.isNotEmpty() -> SectionCard(stringResource(R.string.ui_next_reply)) {
                val next = pendingDrafts.minBy { it.createdAt }
                Text("To ${next.recipient}", color = Hf.colors.onSurface)
                Note(next.body.take(400))
                PrimaryButton(stringResource(R.string.ui_review_reply_in_inbox), { nav.tab(MainTab.Inbox, "draft:${next.id}") })
            }
            skillCount > 0 -> SectionCard(stringResource(R.string.ui_review_ai_skills)) {
                Note(stringResource(R.string.ui_learned_from_your_corrections) + " · $skillCount")
                PrimaryButton(stringResource(R.string.ui_review_ai_skills), { nav.push(Screen.Skills) })
            }
            else -> SectionCard(stringResource(R.string.ui_youre_caught_up)) { Note(stringResource(R.string.ui_there_are_no_payments_task_proposals_or)) }
        }
        if (payments.size > 1) SecondaryButton(stringResource(R.string.ui_set_account_category_for_pending), { batch = true })
        if (nightly.isNotEmpty()) SectionCard(stringResource(R.string.ui_cash_check_in)) {
            Note("Anything you spent in cash and have not logged? ${nightly.size} daily check-in(s) are still open.")
            PrimaryButton(stringResource(R.string.ui_add_cash), { nav.tab(MainTab.Money, "cash") })
            SecondaryButton(stringResource(R.string.ui_i_have_finished_these_cash_check_ins), {
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
        Text(stringResource(R.string.ui_suggest_account_and_category), style = Hf.type.cardTitle, color = Hf.colors.onSurface)
        ChoiceField(stringResource(R.string.ui_account), accounts.firstOrNull { it.id == account }, accounts, { it.label }, { account = it.id })
        ChipPicker(Categories, category, { categoryLabel(it) }) { category = it }
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
