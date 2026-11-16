package com.hellfire.ass

import android.Manifest
import android.app.Application
import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.ContactsContract
import com.hellfire.ass.contacts.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PhoneContactsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var provider: SyntheticContacts
    private lateinit var contacts: PhoneContacts
    @Before fun setup() {
        provider = SyntheticContacts().apply { onCreate() }
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, provider)
        contacts = PhoneContacts(app)
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
        provider.add(7, "fixture-a", "Synthetic Asha", ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, "+255 712 345 678", "+255712345678")
        provider.add(7, "fixture-a", "Synthetic Asha", ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, "0687654321", "+255687654321")
        provider.add(7, "fixture-a", "Synthetic Asha", ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE, "asha@example.invalid")
    }
    @After fun close() { provider.db.close() }
    @Test fun `denied permission queries nothing and free text remains available`() = runBlocking {
        shadowOf(app).denyPermissions(Manifest.permission.READ_CONTACTS)
        assertFalse(contacts.granted())
        assertTrue(contacts.search("Asha").isEmpty())
        assertNull(contacts.pick(ContactMatch(7, "fixture-a", "Synthetic Asha")))
        assertEquals(ContactPresence.NO_ACCESS, contacts.presence("fixture-a"))
        assertEquals(0, provider.calls)
        assertEquals(ContactChoice("Synthetic Asha"), ContactChoice("Synthetic Asha", null, emptyList()))
    }
    @Test fun `search by name number or email runs off main thread without copying address book`() = runBlocking {
        assertTrue(contacts.search("  ").isEmpty())
        assertEquals(0, provider.calls)
        for (query in listOf("asha", "712345678", "0712345678", "+255 712", "asha@example.invalid")) {
            val matches = contacts.search(query)
            assertEquals(query, 1, matches.size)
            assertEquals("fixture-a", matches.single().lookupKey)
        }
        assertFalse(provider.queriedOnMain)
        assertTrue(contacts.search("Nobody").isEmpty())
    }
    @Test fun `only picked contact supplies every phone number and email`() = runBlocking {
        provider.add(9, "other", "Other fixture", ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, "0699999999")
        val picked = contacts.pick(contacts.search("Asha").single())!!
        assertEquals("fixture-a", picked.lookupKey)
        assertEquals("Synthetic Asha", picked.displayName)
        assertEquals(setOf("+255 712 345 678", "0687654321", "asha@example.invalid"), picked.handles.toSet())
        assertFalse("0699999999" in picked.handles)
    }
    @Test fun `result count is bounded and SQL metacharacters are literal`() = runBlocking {
        repeat(45) { provider.add(it + 100L, "fixture-$it", "Many $it", ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE, "many$it@example.invalid") }
        assertEquals(PhoneContacts.LIMIT, contacts.search("Many").size)
        assertEquals(PhoneContacts.LIMIT.toString(), provider.lastLimit)
        assertTrue(contacts.search("%_'").isEmpty())
        assertEquals(1, contacts.search("Asha").size)
    }
    @Test fun `deleted lookup is missing without changing the saved name and revoked access is distinct`() = runBlocking {
        val picked = contacts.pick(contacts.search("Asha").single())!!
        assertEquals(ContactPresence.PRESENT, contacts.presence(picked.lookupKey!!))
        provider.db.delete("data", "contact_id = ?", arrayOf("7"))
        assertEquals(ContactPresence.MISSING, contacts.presence(picked.lookupKey))
        assertNull(contacts.pick(ContactMatch(7, picked.lookupKey, picked.displayName)))
        assertEquals("Synthetic Asha", picked.displayName)
        shadowOf(app).denyPermissions(Manifest.permission.READ_CONTACTS)
        assertEquals(ContactPresence.NO_ACCESS, contacts.presence(picked.lookupKey))
    }
    @Test fun `revoked permission during provider query degrades to typed name without crashing`() = runBlocking {
        provider.reject = true
        assertTrue(contacts.search("Asha").isEmpty())
        assertNull(contacts.pick(ContactMatch(7, "fixture-a", "Synthetic Asha")))
        assertEquals(ContactPresence.NO_ACCESS, contacts.presence("fixture-a"))
    }

    @Test fun `lookup keys are URI segments and an unavailable provider is distinct from a deleted contact`() = runBlocking {
        val key = "fixture/a%key"
        provider.add(19, key, "Synthetic special", ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE, "special@example.invalid")
        assertEquals(ContactPresence.PRESENT, contacts.presence(key))
        assertEquals(key, contacts.pick(ContactMatch(19, key, "Synthetic special"))!!.lookupKey)
        provider.unavailable = true
        assertEquals(ContactPresence.UNAVAILABLE, contacts.presence(key))
        assertTrue(runCatching { contacts.search("special") }.isFailure)
    }

    /** SQLite-backed fake executes the production selection and limit against synthetic rows only. */
    class SyntheticContacts : ContentProvider() {
        lateinit var db: SQLiteDatabase
        var calls = 0
        var queriedOnMain = false
        var lastLimit: String? = null
        var reject = false
        var unavailable = false
        override fun onCreate(): Boolean {
            db = SQLiteDatabase.create(null)
            db.execSQL("CREATE TABLE data (contact_id INTEGER, lookup TEXT, display_name TEXT, mimetype TEXT, data1 TEXT, data4 TEXT)")
            return true
        }
        fun add(id: Long, lookup: String, name: String, mime: String, value: String, normalized: String? = null) {
            db.execSQL("INSERT INTO data VALUES (?, ?, ?, ?, ?, ?)", arrayOf(id, lookup, name, mime, value, normalized))
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
            lastLimit = uri.getQueryParameter(ContactsContract.LIMIT_PARAM_KEY)
            return queryRows(uri, projection, selection, selectionArgs, sortOrder, lastLimit)
        }
        private fun queryRows(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sort: String?, limit: String?): Cursor? {
            calls++
            queriedOnMain = queriedOnMain || (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            if (reject) throw SecurityException("Synthetic permission revocation")
            if (unavailable) return null
            if (uri.pathSegments.contains("lookup")) {
                val key = uri.pathSegments[2]
                val columns = projection ?: arrayOf("_id")
                val result = MatrixCursor(columns)
                db.rawQuery("SELECT DISTINCT contact_id, lookup, display_name FROM data WHERE lookup = ?", arrayOf(key)).use { rows ->
                    while (rows.moveToNext()) result.addRow(columns.map<String, Any?> { column -> when (column) {
                        "_id" -> rows.getLong(0); "lookup" -> rows.getString(1); "display_name" -> rows.getString(2); else -> error(column)
                    } })
                }
                return result
            }
            return db.query("data", projection, selection, selectionArgs, null, null, sort, limit)
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read-only fixture")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Read-only fixture")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Read-only fixture")
    }
}
