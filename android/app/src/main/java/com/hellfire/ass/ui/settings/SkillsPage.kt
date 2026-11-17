package com.hellfire.ass.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import com.hellfire.ass.memory.*
import com.hellfire.ass.ui.*
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.money.ChipPicker
import com.hellfire.ass.ui.money.Categories
import kotlinx.coroutines.*
import java.util.UUID

@Composable
fun SkillsPage() {
    val app = LocalEnv.current.app
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val skills by remember { app.database.skills().watch() }.collectAsStateWithLifecycle(emptyList())
    val savedAccounts by remember { app.database.finances().accounts() }.collectAsStateWithLifecycle(emptyList())
    val counts by remember { app.database.skills().counts() }.collectAsStateWithLifecycle(emptyList())
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<AiSkill?>(null) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var visible by rememberSaveable { mutableIntStateOf(50) }
    var historyLimit by rememberSaveable { mutableIntStateOf(100) }
    var busy by remember { mutableStateOf(false) }
    fun run(action: suspend () -> Unit) { scope.launch {
        busy = true
        try { withContext(Dispatchers.IO) { action() }; message = "Skill settings saved." }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = if (e is IllegalArgumentException) e.message ?: "Check the skill fields." else "Could not save. Check storage and try again." }
        finally { busy = false }
    } }
    PushedPage(tr("AI skills"), breadcrumb = "Settings") {
        message?.let { Banner(it) }
        Note(tr("Skills guide suggestions and local answers. They cannot send messages, post money or grant permissions."))
        Note(tr("Everyday corrections create suggestions for review. Only active skills affect future decisions."))
        PrimaryButton(tr("Add skill"), { editing = "new" }, enabled = !busy)
        if (skills.isEmpty()) Note(tr("No skills yet. Add one, or review suggestions after correcting a payment, task or memory."))
        HfTextField(search, { search = it; visible = 50 }, "Search skills")
        val filtered = skills.filter { search.isBlank() || it.name.contains(search, true) || it.reason.contains(search, true) || it.trigger.contains(search, true) }
        filtered.take(visible).forEach { skill ->
            val count = counts.firstOrNull { it.skillId == skill.id }?.count ?: 0
            val history by remember(skill.id, expanded, historyLimit) { if (expanded == skill.id) app.database.skills().historyFlow(skill.id, historyLimit) else kotlinx.coroutines.flow.flowOf(emptyList()) }.collectAsStateWithLifecycle(emptyList())
            SectionCard(skill.name) {
                Note(tr("Status") + ": " + tr(if (skill.state == "PENDING") "Needs review" else if (skill.state == "ACTIVE") "Enabled" else "Disabled") + " · " + tr("Revision") + " ${skill.revision} · " + tr("Uses") + " $count · " + tr("Updated") + " ${Fmt.dayTime(skill.updatedAt)}")
                if (skill.kind == "PAYMENT_DEFAULT") {
                    val choice = org.json.JSONObject(skill.advice)
                    Text(tr("Account") + ": " + (savedAccounts.firstOrNull { it.id == choice.optString("account") }?.label ?: "Account unavailable") + " · " + tr("Category") + ": " + tr(choice.optString("category")))
                } else if (skill.kind.startsWith("IGNORE_")) Note(tr("This exact wording will not create the same task or payment suggestion again.")) else Text(skill.advice)
                Note(tr("Why created") + ": " + tr(skill.reason))
                Note(tr("When it applies") + ": ${if (skill.kind.startsWith("IGNORE_")) tr("Exact repeated text from the original source app") else skill.trigger.replace('|', ' ')}")
                if (skill.sourceId != null) SecondaryButton(tr("Original evidence"), { nav.push(Screen.Source, skill.sourceId) })
                SecondaryButton(tr("Edit skill"), { editing = skill.id }, enabled = !busy)
                SecondaryButton(if (skill.state == "ACTIVE") tr("Disable") else tr("Approve and enable"), {
                    run { app.skills.save(skill.copy(state = if (skill.state == "ACTIVE") "DISABLED" else "ACTIVE")) }
                }, enabled = !busy)
                QuietButton(tr("Remove skill"), { deleting = skill }, enabled = !busy)
                QuietButton(tr("Usage history") + " ($count)", { expanded = if (expanded == skill.id) null else skill.id; historyLimit = 100 })
                if (expanded == skill.id) {
                    if (history.isEmpty()) Note(tr("This skill has not been used."))
                    history.forEach { use ->
                        Note("${Fmt.full(use.occurredAt)} · ${use.context} · revision ${use.revision}\n${use.detail}")
                        if (use.sourceId != null) QuietButton(tr("Open where used"), { nav.push(Screen.Source, use.sourceId) })
                        else Note("${use.context} · ${use.recordId}")
                    }
                    if (count > history.size) SecondaryButton(tr("Load older uses"), { historyLimit += 100 })
                }
            }
        }
        if (filtered.size > visible) SecondaryButton(tr("Load more skills"), { visible += 50 })
    }
    if (deleting != null) ConfirmDialog(tr("Remove skill?"), tr("This removes the skill and its usage history. Original evidence stays saved."), tr("Remove"),
        onConfirm = { val id = deleting!!.id; deleting = null; run { app.database.skills().delete(id); listOf("name", "trigger", "advice", "reason").forEach { PrivateDrafts.write(app, "skill-$id-$it", "") } } }, onDismiss = { deleting = null }, destructive = true)
    editing?.let { id ->
        val old = skills.firstOrNull { it.id == id }
        key(id) {
            var name by rememberPrivateDraft("skill-$id-name", old?.name.orEmpty())
            var trigger by rememberPrivateDraft("skill-$id-trigger", old?.trigger.orEmpty())
            var advice by rememberPrivateDraft("skill-$id-advice", old?.advice.orEmpty())
            var reason by rememberPrivateDraft("skill-$id-reason", old?.reason ?: "Added by you to guide future related decisions.")
            var kind by rememberSaveable { mutableStateOf(old?.kind ?: "GUIDANCE") }
            var account by rememberSaveable { mutableStateOf(old?.takeIf { it.kind == "PAYMENT_DEFAULT" }?.let { org.json.JSONObject(it.advice).optString("account") }.orEmpty()) }
            var category by rememberSaveable { mutableStateOf(old?.takeIf { it.kind == "PAYMENT_DEFAULT" }?.let { org.json.JSONObject(it.advice).optString("category") } ?: "Other") }
            var matchedText by rememberSaveable { mutableStateOf("") }
            var matchScope by rememberSaveable { mutableStateOf("") }
            var sourcePackage by rememberSaveable { mutableStateOf("") }
            var pickedCandidate by rememberSaveable { mutableStateOf<String?>(null) }
            val candidates by remember { app.database.finances().recentCandidates() }.collectAsStateWithLifecycle(emptyList())
            LaunchedEffect(id) {
                if (kind.startsWith("IGNORE_") && matchedText.isBlank()) withContext(Dispatchers.IO) {
                    old?.sourceId?.let { source -> app.database.events().find(source)?.let { event ->
                        sourcePackage = event.packageName
                        matchScope = app.skills.scopeFor(app, source)
                        val fields = EventText.fields(app, event)
                        matchedText = listOf("title", "text", "big_text", "text_lines", "sub_text", "messages").flatMap { fields[it].orEmpty() }.joinToString("\n").let { if (kind == "IGNORE_FINANCE") it.replace("\"", "") else it }.take(1000)
                    } }
                }
            }
            val accounts by remember { app.database.finances().accounts() }.collectAsStateWithLifecycle(emptyList())
            HfBottomSheet({ editing = null }) {
                Text(tr("Edit skill"))
                HfTextField(name, { name = it.take(120) }, "Skill name")
                val kindLabels = mapOf("GUIDANCE" to tr("Personal guidance"), "PAYMENT_DEFAULT" to tr("Payment choices"))
                if (old == null) ChoiceField("Skill type", kind, kindLabels.keys.toList(), { kindLabels[it].orEmpty() }, { kind = it })
                if (kind == "GUIDANCE") Note(tr("All meaningful topic words must match. Skills cannot override source facts."))
                if (kind == "GUIDANCE") HfTextField(trigger, { trigger = it.take(300) }, "Related words or topic")
                if (kind.startsWith("IGNORE_")) {
                    Note("Source app: $sourcePackage")
                    HfTextField(matchedText, { matchedText = it.take(1000) }, "Exact text to recognize", singleLine = false)
                }
                if (kind == "PAYMENT_DEFAULT") {
                    Note(trigger.substringBefore("|") + " · " + trigger.substringAfter(":"))
                    ChoiceField("Use sender or merchant from", candidates.firstOrNull { it.id == pickedCandidate }, candidates, { "${it.provider} · ${it.merchant ?: it.evidence.take(45)}" }, { candidate ->
                        pickedCandidate = candidate.id; scope.launch { trigger = withContext(Dispatchers.IO) { com.hellfire.ass.finance.ReviewChoices.key(app, candidate) } }
                    })
                    ChoiceField("Account", accounts.firstOrNull { it.id == account }, accounts, { it.label }, { account = it.id })
                    ChipPicker(Categories, category) { category = it }
                    Note(com.hellfire.ass.ui.tr("Choose a real reviewed or pending payment to identify the sender/merchant. The skill suggests choices; you still confirm each payment."))
                } else HfTextField(advice, { advice = it.take(2000) }, "What AI should learn", singleLine = false)
                HfTextField(reason, { reason = it.take(1000) }, "Reason", singleLine = false)
                PrimaryButton(tr("Save and enable"), {
                    run {
                        val match = if (kind.startsWith("IGNORE_")) AiSkills.exact(matchScope, matchedText) else trigger
                        val instruction = if (kind == "PAYMENT_DEFAULT") org.json.JSONObject().put("account", account).put("category", category).toString() else advice
                        val raw = com.hellfire.ass.events.NotificationSnapshot("skill", "owner.skill", 0, 0, fields = mapOf("text" to listOf(name, reason, if (kind == "GUIDANCE") advice else "")))
                        val safe = com.hellfire.ass.events.NotificationNormalizer.normalize(raw, app.packageName)!!
                        require(!safe.securitySensitive && !safe.contentRedacted) { "Remove authentication content." }
                        app.skills.save((old ?: AiSkill(UUID.randomUUID().toString(), name, kind, match, instruction, reason, null)).copy(name = name, trigger = match, advice = instruction, reason = reason, state = "ACTIVE"))
                        listOf("name", "trigger", "advice", "reason").forEach { PrivateDrafts.write(app, "skill-$id-$it", "") }
                        editing = null
                    }
                }, enabled = !busy && name.isNotBlank() && (if (kind.startsWith("IGNORE_")) matchScope.isNotBlank() && matchedText.isNotBlank() else trigger.length >= 2) && reason.isNotBlank() && (kind == "PAYMENT_DEFAULT" && account.isNotBlank() || advice.isNotBlank()))
            }
        }
    }
}
