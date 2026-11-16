package com.hellfire.ass.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Only an explicit pick is persisted. Search results live in the picker, never in the database. */
data class ContactChoice(val displayName: String, val lookupKey: String? = null, val handles: List<String> = emptyList(), val personKey: String? = null)
data class ContactMatch(val id: Long, val lookupKey: String, val displayName: String, val detail: String = "")
enum class ContactPresence { PRESENT, MISSING, NO_ACCESS, UNAVAILABLE }

class PhoneContacts(private val context: Context) {
    companion object { const val LIMIT = 30 }
    fun granted(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    private val resolver get() = context.contentResolver

    /** Bounded provider query, including number/email data. A blank search never reads the address book. */
    suspend fun search(text: String): List<ContactMatch> = withContext(Dispatchers.IO) {
        if (!granted() || text.isBlank()) return@withContext emptyList()
        val pattern = "%" + text.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val digits = text.filter(Char::isDigit)
        val phonePattern = if (digits.length >= 3) "%${digits.takeLast(9)}%" else pattern
        val selection = "${ContactsContract.Data.MIMETYPE} IN (?, ?, ?) AND (${ContactsContract.Data.DISPLAY_NAME_PRIMARY} LIKE ? ESCAPE '\\' OR ${ContactsContract.Data.DATA1} LIKE ? ESCAPE '\\' OR (${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.Data.DATA4} LIKE ?))"
        val args = arrayOf(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, pattern, pattern,
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, phonePattern)
        val uri = ContactsContract.Data.CONTENT_URI.buildUpon().appendQueryParameter(ContactsContract.LIMIT_PARAM_KEY, LIMIT.toString()).build()
        try {
            val found = linkedMapOf<Long, ContactMatch>()
            resolver.query(uri, arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.Data.LOOKUP_KEY, ContactsContract.Data.DISPLAY_NAME_PRIMARY,
                ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1), selection, args, "${ContactsContract.Data.DISPLAY_NAME_PRIMARY} COLLATE NOCASE")?.use { rows ->
                var read = 0
                while (read++ < LIMIT && rows.moveToNext()) {
                    val id = rows.getLong(0)
                    val lookup = rows.getString(1) ?: continue
                    val detail = if (rows.getString(3) == ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE) "" else rows.getString(4).orEmpty()
                    found[id] = ContactMatch(id, lookup, rows.getString(2).orEmpty().ifBlank { "Unnamed contact" }, detail)
                }
            } ?: error("Contacts provider is unavailable")
            found.values.toList()
        } catch (_: SecurityException) { emptyList() }
    }

    /** Re-resolve the lookup key before reading just this contact's phone numbers and emails. */
    suspend fun pick(match: ContactMatch): ContactChoice? = withContext(Dispatchers.IO) {
        if (!granted()) return@withContext null
        try {
            val current = resolve(match.lookupKey) ?: return@withContext null
            val id = current.id
            val handles = mutableListOf<String>()
            resolver.query(ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data.DATA1),
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} IN (?, ?)",
                arrayOf(id.toString(), ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE), null)?.use { rows ->
                while (rows.moveToNext()) rows.getString(0)?.takeIf { it.isNotBlank() }?.let(handles::add)
            } ?: error("Contacts provider is unavailable")
            ContactChoice(current.displayName, current.lookupKey, handles.distinct())
        } catch (_: SecurityException) { null }
    }

    suspend fun presence(lookupKey: String): ContactPresence = withContext(Dispatchers.IO) {
        if (!granted()) return@withContext ContactPresence.NO_ACCESS
        try {
            if (resolve(lookupKey) == null) ContactPresence.MISSING else ContactPresence.PRESENT
        } catch (_: SecurityException) { ContactPresence.NO_ACCESS }
        catch (_: Exception) { ContactPresence.UNAVAILABLE }
    }
    private fun resolve(key: String): ContactMatch? {
        val uri = ContactsContract.Contacts.CONTENT_LOOKUP_URI.buildUpon().appendPath(key).build()
        val rows = resolver.query(uri, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), null, null, null)
            ?: error("Contacts provider is unavailable")
        return rows.use {
            if (it.moveToFirst()) ContactMatch(it.getLong(0), it.getString(1), it.getString(2).orEmpty().ifBlank { "Unnamed contact" }) else null
        }
    }
}
