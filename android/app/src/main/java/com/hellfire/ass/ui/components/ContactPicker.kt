package com.hellfire.ass.ui.components

import androidx.compose.ui.res.stringResource
import com.hellfire.ass.R

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.hellfire.ass.contacts.*
import com.hellfire.ass.ui.LocalEnv
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun contactsSettings(context: android.content.Context) {
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
}

/** Free text remains usable before, during and after contact permission denial. */
@Composable
fun ContactPersonField(value: String, onValueChange: (String) -> Unit, label: String, suggestions: List<String> = emptyList(), onPick: (ContactChoice) -> Unit) {
    var open by remember { mutableStateOf(false) }
    AutocompleteField(value, onValueChange, label, suggestions, onPick = { onValueChange(it) })
    SecondaryButton(stringResource(R.string.ui_choose_contact), { open = true })
    if (open) ContactPicker(value, { open = false }) { chosen -> onPick(chosen); open = false }
}

@Composable
private fun ContactPicker(initial: String, onDismiss: () -> Unit, onPick: (ContactChoice) -> Unit) {
    val context = LocalContext.current
    val provider = remember(context) { PhoneContacts(context) }
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(provider.granted()) }
    var query by remember { mutableStateOf(initial) }
    var savedDetails by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var savedPeople by remember { mutableStateOf<List<PersonContact>>(emptyList()) }
    var matches by remember { mutableStateOf<List<ContactMatch>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = provider.granted() }
    LaunchedEffect(query, granted) {
        matches = emptyList(); error = null
        savedDetails = withContext(Dispatchers.IO) { (context.applicationContext as com.hellfire.ass.HellFireApp).database.people().all().groupBy { it.personKey ?: com.hellfire.ass.insights.People.namedKey(it.name) }
            .mapValues { (_, aliases) -> aliases.take(2).joinToString { com.hellfire.ass.insights.Handles.label(it.handle) } } }
        savedPeople = withContext(Dispatchers.IO) { (context.applicationContext as com.hellfire.ass.HellFireApp).database.contacts().all().filter { query.isNotBlank() && it.displayName.contains(query.trim(), true) }.take(30) }
        if (!granted || query.isBlank()) { loading = false; return@LaunchedEffect }
        loading = true
        delay(250)
        try { matches = provider.search(query); granted = provider.granted() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "Contacts could not be read. You can still use a typed name." }
        finally { loading = false }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.ui_choose_a_person)) }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HfTextField(query, { query = it }, stringResource(R.string.ui_name_number_or_email))
            savedPeople.forEach { person ->
                SecondaryButton(person.displayName + " · " + (savedDetails[person.personKey] ?: "saved person"), {
                    onPick(ContactChoice(person.displayName, person.lookupKey, personKey = person.personKey))
                })
            }
            if (!granted) {
                Text("Allow contacts to find people already synced on this phone, including Google contacts. Only the person you pick is saved, with their numbers and emails to join your messages.")
                SecondaryButton(stringResource(R.string.ui_allow_contacts), { permission.launch(Manifest.permission.READ_CONTACTS) })
                Text("If access is denied, type a name. To allow it later: App settings › Permissions › Contacts.")
                QuietButton(stringResource(R.string.ui_app_settings), { contactsSettings(context) })
            } else {
                Text(when { loading -> "Searching…"; query.isBlank() -> "Type to search contacts on this phone."; matches.isEmpty() -> "No matching contacts. You can use the typed name."; else -> "Pick a contact. Up to ${PhoneContacts.LIMIT} results; refine your search if needed." })
                matches.forEach { match ->
                    SecondaryButton(match.displayName + match.detail.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(), {
                        scope.launch {
                            try {
                                val picked = provider.pick(match)
                                if (picked != null) onPick(picked)
                                else { granted = provider.granted(); error = "This contact is no longer available. You can use a typed name." }
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { error = "Contact could not be read. You can use a typed name." }
                        }
                    }, Modifier.fillMaxWidth())
                }
            }
            error?.let { Text(it) }
        }
    }, confirmButton = {
        PrimaryButton(stringResource(R.string.ui_use_typed_name), { onPick(ContactChoice(query.trim())) }, enabled = query.isNotBlank() && query.trim().length <= 200)
    }, dismissButton = { QuietButton(stringResource(R.string.ui_cancel), onDismiss) })
}

/** Does not request access or erase the owner's saved name when a contact disappears. */
@Composable
fun SavedContactStatus(key: String?) {
    val app = LocalEnv.current.app
    var version by remember { mutableIntStateOf(0) }
    var text by remember(key) { mutableStateOf<String?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { version++ }
    LaunchedEffect(key, version) {
        text = key?.let {
            val contact = withContext(Dispatchers.IO) { app.database.contacts().find(it) } ?: return@let null
            val lookup = contact.lookupKey ?: return@let contact.displayName
            contact.displayName + when (PhoneContacts(app).presence(lookup)) {
                ContactPresence.MISSING -> " · not in contacts"
                ContactPresence.NO_ACCESS -> " · contacts access denied"
                ContactPresence.UNAVAILABLE -> " · contacts unavailable"
                ContactPresence.PRESENT -> " · in contacts"
            }
        }
    }
    text?.let { Note(it) }
}

@Composable
fun ContactsPermissionRow() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(PhoneContacts(context).granted()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = PhoneContacts(context).granted() }
    ListCard {
        ListRow("Contacts", subtitle = (if (granted) "Granted" else "Denied") + " · choose a person from contacts synced on this phone",
            chevron = true, onClick = { contactsSettings(context) })
    }
    Note("Contacts access is requested only when you open a contact picker. You can always type a name. Manage access in App settings › Permissions › Contacts.")
}
