package com.hellfire.ass.contacts

import androidx.room.*
import com.hellfire.ass.communications.ActionAudit
import com.hellfire.ass.data.EventDatabase
import com.hellfire.ass.insights.Handles
import com.hellfire.ass.insights.People
import com.hellfire.ass.insights.PersonAlias
import java.util.UUID

@Entity(tableName = "person_contact")
data class PersonContact(@PrimaryKey val personKey: String, val lookupKey: String?, val displayName: String, val updatedAt: Long)

@Dao
interface ContactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(value: PersonContact)
    @Query("SELECT * FROM person_contact WHERE personKey = :key") suspend fun find(key: String): PersonContact?
    @Query("SELECT * FROM person_contact ORDER BY displayName") suspend fun all(): List<PersonContact>
    @Query("SELECT * FROM person_contact WHERE lookupKey = :lookup LIMIT 1") suspend fun byLookup(lookup: String): PersonContact?
    @Query("SELECT * FROM person_contact WHERE LOWER(TRIM(displayName)) = LOWER(TRIM(:name))") suspend fun byName(name: String): List<PersonContact>
    @Query("DELETE FROM person_contact WHERE personKey = :key") suspend fun delete(key: String)
}

/** Stable identities retain old keys; display names never force two selected contacts to merge. */
class ContactLinks(private val db: EventDatabase) {
    suspend fun save(choice: ContactChoice, extraHandles: Collection<String> = emptyList()): String = db.withTransaction {
        val name = choice.displayName.trim()
        require(name.isNotEmpty() && name.length <= 200)
        val candidates = db.contacts().byName(name)
        val selected = choice.personKey?.let { requireNotNull(db.contacts().find(it)) { "Saved person no longer exists" } }
            ?: choice.lookupKey?.let { db.contacts().byLookup(it) }
        require(choice.lookupKey != null || selected != null || candidates.size <= 1) { "Several people have this name. Choose the saved person or contact." }
        val linkedLookup = choice.lookupKey?.let { db.contacts().byLookup(it) }
        require(selected == null || linkedLookup == null || selected.personKey == linkedLookup.personKey) { "This contact belongs to another saved person. Use Merge people to combine records." }
        val key = selected?.personKey ?: if (choice.lookupKey == null && candidates.size == 1) candidates.single().personKey
            else if (candidates.isEmpty() && db.contacts().find(People.namedKey(name)) == null) People.namedKey(name) else "person:" + UUID.randomUUID()

        val incoming = (choice.handles.mapNotNull(Handles::key) + extraHandles).distinct()
        val aliases = db.people().all()
        require(incoming.none { handle -> aliases.any { it.handle == handle && (it.personKey ?: People.namedKey(it.name)) != key } }) { "A number or address already belongs to another person. Use Merge or Separate to change its links." }
        require(choice.personKey != null || choice.lookupKey != null || incoming.isEmpty() || candidates.isEmpty() ||
            incoming.any { handle -> aliases.any { it.handle == handle && (it.personKey ?: People.namedKey(it.name)) == key } }) { "Choose the saved person explicitly to link this new number, or use a distinct name." }
        val now = System.currentTimeMillis()
        val previous = db.contacts().find(key)
        // Typing an existing name preserves its saved contact link.
        val value = PersonContact(key, choice.lookupKey ?: previous?.lookupKey, name, now)
        db.contacts().save(value)
        incoming.forEach { db.people().save(PersonAlias(it, name, now, key)) }
        audit(key, "OWNER_PERSON_CONTACT", key)
        key
    }

    suspend fun task(id: String, key: String?) = db.withTransaction {
        require(key == null || db.contacts().find(key) != null)
        val old = requireNotNull(db.tasks().task(id))
        if (old.personKey != key) {
            db.tasks().setPerson(id, key, System.currentTimeMillis())
            audit(id, "OWNER_TASK_PERSON", "${old.personKey.orEmpty()} -> ${key.orEmpty()}")
        }
    }

    suspend fun ledger(id: String, key: String?) = db.withTransaction {
        require(key == null || db.contacts().find(key) != null)
        val old = requireNotNull(db.finances().entry(id))
        if (old.personKey != key) {
            db.finances().setPerson(id, key)
            audit(id, "OWNER_LEDGER_PERSON", "${old.personKey.orEmpty()} -> ${key.orEmpty()}")
        }
    }

    /** Naming an existing conversation also retains the manually linked records under its new name. */
    suspend fun namePerson(oldKey: String, choice: ContactChoice, handles: Collection<String>, oldName: String): String = db.withTransaction {
        val oldHandles = db.people().all().filter { (it.personKey ?: People.namedKey(it.name)) == oldKey }.map { it.handle }
        val existing = db.contacts().find(oldKey)
        require(existing == null || choice.personKey == null || choice.personKey == existing.personKey) { "Use Merge people to combine two saved people." }
        val selected = choice.copy(personKey = existing?.personKey ?: choice.personKey, lookupKey = choice.lookupKey ?: existing?.lookupKey)
        val key = save(selected, handles + oldHandles)
        if (key != oldKey) {
            db.tasks().all().filter { it.personKey == oldKey }.forEach { task(it.id, key) }
            db.finances().allEntries().filter { it.personKey == oldKey }.forEach { ledger(it.id, key) }
        }
        if (db.contacts().byName(oldName).size <= 1) db.debts().linkLegacy(oldName, oldKey)
        db.debts().renamePerson(oldKey, key, choice.displayName.trim())
        audit(key, "OWNER_PERSON_RENAME", key)
        key
    }

    suspend fun merge(from: String, into: String) = db.withTransaction {
        require(from != into)
        requireNotNull(db.contacts().find(from))
        val target = requireNotNull(db.contacts().find(into))
        db.people().all().filter { (it.personKey ?: People.namedKey(it.name)) == from }.forEach {
            db.people().save(it.copy(name = target.displayName, personKey = into))
        }
        db.tasks().all().filter { it.personKey == from }.forEach { task(it.id, into) }
        db.finances().allEntries().filter { it.personKey == from }.forEach { ledger(it.id, into) }
        db.debts().renamePerson(from, into, target.displayName)
        db.contacts().delete(from)
        audit(from, "OWNER_PERSON_MERGE", into)
    }

    /** Move the selected handle and source-linked records to a separate identity, preserving all money. */
    suspend fun separate(from: String, handle: String, name: String, tasks: Set<String> = emptySet(), payments: Set<String> = emptySet(), debts: Set<String> = emptySet()): String = db.withTransaction {
        val alias = requireNotNull(db.people().all().firstOrNull { it.handle == handle && (it.personKey ?: People.namedKey(it.name)) == from })
        require(name.isNotBlank() && name.length <= 200)
        val key = "person:" + UUID.randomUUID()
        db.contacts().save(PersonContact(key, null, name.trim(), System.currentTimeMillis()))
        db.people().save(alias.copy(name = name.trim(), personKey = key))
        tasks.forEach { id -> require(db.tasks().task(id)?.personKey == from); task(id, key) }
        payments.forEach { id -> require(db.finances().entry(id)?.personKey == from); ledger(id, key) }
        debts.forEach { id -> require(db.debts().all().any { it.id == id && it.personKey == from }); db.debts().setPerson(id, key, name.trim()) }
        audit(from, "OWNER_PERSON_SEPARATE", key)
        key
    }
    private suspend fun audit(id: String, action: String, result: String) = db.communications().audit(
        ActionAudit(UUID.randomUUID().toString(), id, action, result, System.currentTimeMillis()))
}

/** Old backups have names but no stable alias/debt key. Normalize Unicode with the same rule as new identities. */
object ContactSchema {
    fun backfill(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.query("SELECT handle, name, updatedAt FROM person_alias WHERE personKey IS NULL").use { rows ->
            while (rows.moveToNext()) {
                val key = People.namedKey(rows.getString(1))
                db.execSQL("UPDATE person_alias SET personKey = ? WHERE handle = ?", arrayOf(key, rows.getString(0)))
                db.execSQL("INSERT OR IGNORE INTO person_contact (personKey, lookupKey, displayName, updatedAt) VALUES (?, NULL, ?, ?)", arrayOf(key, rows.getString(1), rows.getLong(2)))
            }
        }
        db.query("SELECT id, person, createdAt FROM debt_entry WHERE personKey IS NULL").use { rows ->
            while (rows.moveToNext()) {
                val key = People.namedKey(rows.getString(1))
                db.execSQL("UPDATE debt_entry SET personKey = ? WHERE id = ?", arrayOf(key, rows.getString(0)))
                db.execSQL("INSERT OR IGNORE INTO person_contact (personKey, lookupKey, displayName, updatedAt) VALUES (?, NULL, ?, ?)", arrayOf(key, rows.getString(1), rows.getLong(2)))
            }
        }
    }
}
