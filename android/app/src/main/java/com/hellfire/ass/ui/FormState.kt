package com.hellfire.ass.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import com.hellfire.ass.contacts.ContactChoice
import com.hellfire.ass.ui.tasks.TaskDraft
import org.json.JSONArray

val ContactChoiceStateSaver = listSaver<MutableState<ContactChoice?>, String>(
    save = { state -> state.value?.let { listOf(it.displayName, it.lookupKey.orEmpty(), JSONArray(it.handles).toString(), it.personKey.orEmpty()) }.orEmpty() },
    restore = { values -> mutableStateOf(if (values.isEmpty()) null else ContactChoice(values[0], values[1].ifEmpty { null },
        JSONArray(values[2]).let { a -> (0 until a.length()).map { a.getString(it) } }, values[3].ifEmpty { null })) },
)
val TaskDraftStateSaver = listSaver<MutableState<TaskDraft?>, String>(
    save = { state -> state.value?.let { listOf(it.id.orEmpty(), it.title, it.dueAt.toString(), it.party, it.direction, it.amount?.toString().orEmpty(),
        it.kind, it.repeatMonthly.toString(), it.personKey.orEmpty(), it.sourceId.orEmpty(), it.repeatRule, it.alertProfile, it.snoozeMinutes.toString()) }.orEmpty() },
    restore = { v -> mutableStateOf(if (v.isEmpty()) null else TaskDraft(v[0].ifEmpty { null }, v[1], v[2].toLong(), v[3], v[4], v[5].toLongOrNull(),
        v[6], v[7].toBoolean(), v[8].ifEmpty { null }, v[9].ifEmpty { null }, v[10], v[11], v[12].toInt())) },
)
