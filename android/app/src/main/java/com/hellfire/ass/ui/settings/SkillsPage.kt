package com.hellfire.ass.ui.settings

import androidx.compose.ui.res.stringResource
import com.hellfire.ass.R

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
import com.hellfire.ass.OwnerError
import com.hellfire.ass.events.NotificationNormalizer
import com.hellfire.ass.events.NotificationSnapshot
import com.hellfire.ass.finance.ReviewChoices

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
    val context = androidx.compose.ui.platform.LocalContext.current
    fun run(action: suspend () -> Unit) { scope.launch {
        busy = true
        try { withContext(Dispatchers.IO) { action() }; message = context.getString(R.string.ui_skill_settings_saved) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = when (e) { is OwnerError -> context.getString(e.text); is IllegalArgumentException -> e.message ?: "Check the skill fields."; else -> context.getString(R.string.ui_could_not_save_check_storage_and_try) } }
        finally { busy = false }
    } }
    PushedPage(stringResource(R.string.ui_ai_skills), breadcrumb = "Settings") {
        message?.let { Banner(it) }
        Note(stringResource(R.string.ui_skills_guide_suggestions_and_local))
        Note(stringResource(R.string.ui_everyday_corrections_create_suggestions))
        PrimaryButton(stringResource(R.string.ui_add_skill), { editing = "new" }, enabled = !busy)
        if (skills.isEmpty()) Note(stringResource(R.string.ui_no_skills_yet_add_one_or_review))
        HfTextField(search, { search = it; visible = 50 }, stringResource(R.string.ui_search_skills))
        val filtered = skills.filter { search.isBlank() || it.name.contains(search, true) || it.reason.contains(search, true) || it.trigger.contains(search, true) }
        filtered.take(visible).forEach { skill ->
            val count = counts.firstOrNull { it.skillId == skill.id }?.count ?: 0
            val history by remember(skill.id, expanded, historyLimit) { if (expanded == skill.id) app.database.skills().historyFlow(skill.id, historyLimit) else kotlinx.coroutines.flow.flowOf(emptyList()) }.collectAsStateWithLifecycle(emptyList())
            SectionCard(skill.name) {
                Note(stringResource(R.string.ui_status) + ": " + if (skill.state == "PENDING") stringResource(R.string.ui_needs_review) else if (skill.state == "ACTIVE") stringResource(R.string.ui_enabled) else stringResource(R.string.ui_disabled) + " · " + stringResource(R.string.ui_revision) + " ${skill.revision} · " + stringResource(R.string.ui_uses) + " $count · " + stringResource(R.string.ui_updated) + " ${Fmt.dayTime(skill.updatedAt)}")
                if (skill.kind == "PAYMENT_DEFAULT") {
                    val choice = org.json.JSONObject(skill.advice)
                    Text(stringResource(R.string.ui_account) + ": " + (savedAccounts.firstOrNull { it.id == choice.optString("account") }?.label ?: "Account unavailable") + " · " + stringResource(R.string.ui_category) + ": " + categoryLabel(choice.optString("category")))
                } else if (skill.kind.startsWith("IGNORE_")) Note(stringResource(R.string.ui_this_exact_wording_will_not_create_the)) else Text(skill.advice)
                Note(stringResource(R.string.ui_why_created) + ": " + skillReason(skill.reason))
                Note(stringResource(R.string.ui_when_it_applies) + ": ${if (skill.kind.startsWith("IGNORE_")) stringResource(R.string.ui_exact_repeated_text_from_the_original) else skill.trigger.replace('|', ' ')}")
                if (skill.sourceId != null) SecondaryButton(stringResource(R.string.ui_original_evidence), { nav.push(Screen.Source, skill.sourceId) })
                SecondaryButton(stringResource(R.string.ui_edit_skill), { editing = skill.id }, enabled = !busy)
                SecondaryButton(if (skill.state == "ACTIVE") stringResource(R.string.ui_disable) else stringResource(R.string.ui_approve_and_enable), {
                    run { app.skills.save(skill.copy(state = if (skill.state == "ACTIVE") "DISABLED" else "ACTIVE")) }
                }, enabled = !busy)
                QuietButton(stringResource(R.string.ui_remove_skill), { deleting = skill }, enabled = !busy)
                QuietButton(stringResource(R.string.ui_usage_history) + " ($count)", { expanded = if (expanded == skill.id) null else skill.id; historyLimit = 100 })
                if (expanded == skill.id) {
                    if (history.isEmpty()) Note(stringResource(R.string.ui_this_skill_has_not_been_used))
                    history.forEach { use ->
                        Note("${Fmt.full(use.occurredAt)} · ${use.context} · revision ${use.revision}\n${use.detail}")
                        if (use.sourceId != null) QuietButton(stringResource(R.string.ui_open_where_used), { nav.push(Screen.Source, use.sourceId) })
                        else Note("${use.context} · ${use.recordId}")
                    }
                    if (count > history.size) SecondaryButton(stringResource(R.string.ui_load_older_uses), { historyLimit += 100 })
                }
            }
        }
        if (filtered.size > visible) SecondaryButton(stringResource(R.string.ui_load_more_skills), { visible += 50 })
    }
    if (deleting != null) ConfirmDialog(stringResource(R.string.ui_remove_skill_2), stringResource(R.string.ui_this_removes_the_skill_and_its_usage), stringResource(R.string.ui_remove),
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
                Text(stringResource(R.string.ui_edit_skill))
                HfTextField(name, { name = it.take(120) }, stringResource(R.string.ui_skill_name))
                val kindLabels = mapOf("GUIDANCE" to stringResource(R.string.ui_personal_guidance), "PAYMENT_DEFAULT" to stringResource(R.string.ui_payment_choices))
                if (old == null) ChoiceField(stringResource(R.string.ui_skill_type), kind, kindLabels.keys.toList(), { kindLabels[it].orEmpty() }, { kind = it })
                if (kind == "GUIDANCE") Note(stringResource(R.string.ui_all_meaningful_topic_words_must_match))
                if (kind == "GUIDANCE") HfTextField(trigger, { trigger = it.take(300) }, stringResource(R.string.ui_related_words_or_topic))
                if (kind.startsWith("IGNORE_")) {
                    Note("Source app: $sourcePackage")
                    HfTextField(matchedText, { matchedText = it.take(1000) }, stringResource(R.string.ui_exact_text_to_recognize), singleLine = false)
                }
                if (kind == "PAYMENT_DEFAULT") {
                    Note(trigger.substringBefore("|") + " · " + trigger.substringAfter(":"))
                    ChoiceField(stringResource(R.string.ui_use_sender_or_merchant_from), candidates.firstOrNull { it.id == pickedCandidate }, candidates, { "${it.provider} · ${it.merchant ?: it.evidence.take(45)}" }, { candidate ->
                        pickedCandidate = candidate.id; scope.launch { trigger = withContext(Dispatchers.IO) { ReviewChoices.key(app, candidate) } }
                    })
                    ChoiceField(stringResource(R.string.ui_account), accounts.firstOrNull { it.id == account }, accounts, { it.label }, { account = it.id })
                    ChipPicker(Categories, category, { categoryLabel(it) }) { category = it }
                    Note(stringResource(R.string.ui_choose_a_real_reviewed_or_pending))
                } else HfTextField(advice, { advice = it.take(2000) }, stringResource(R.string.ui_what_ai_should_learn), singleLine = false)
                HfTextField(reason, { reason = it.take(1000) }, stringResource(R.string.ui_reason), singleLine = false)
                PrimaryButton(stringResource(R.string.ui_save_and_enable), {
                    run {
                        val match = if (kind.startsWith("IGNORE_")) AiSkills.exact(matchScope, matchedText) else trigger
                        val instruction = if (kind == "PAYMENT_DEFAULT") org.json.JSONObject().put("account", account).put("category", category).toString() else advice
                        val raw = NotificationSnapshot("skill", "owner.skill", 0, 0, fields = mapOf("text" to listOf(name, reason, if (kind == "GUIDANCE") advice else "")))
                        val safe = NotificationNormalizer.normalize(raw, app.packageName)!!
                        if (safe.securitySensitive || safe.contentRedacted) throw OwnerError(R.string.ui_remove_authentication_content)
                        app.skills.save((old ?: AiSkill(UUID.randomUUID().toString(), name, kind, match, instruction, reason, null)).copy(name = name, trigger = match, advice = instruction, reason = reason, state = "ACTIVE"))
                        listOf("name", "trigger", "advice", "reason").forEach { PrivateDrafts.write(app, "skill-$id-$it", "") }
                        editing = null
                    }
                }, enabled = !busy && name.isNotBlank() && (if (kind.startsWith("IGNORE_")) matchScope.isNotBlank() && matchedText.isNotBlank() else trigger.length >= 2) && reason.isNotBlank() && (kind == "PAYMENT_DEFAULT" && account.isNotBlank() || advice.isNotBlank()))
            }
        }
    }
}
