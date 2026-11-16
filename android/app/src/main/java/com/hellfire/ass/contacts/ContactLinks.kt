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
    @Query("DELETE FROM person_contact WHERE personKey = :key") suspend fun delete(key: String)
}

/** Name-based identity matches the existing person_alias/debt/merchant model. All changes are owner-confirmed and audited. */
class ContactLinks(private val db: EventDatabase) {
    suspend fun save(choice: ContactChoice, extraHandles: Collection<String> = emptyList()): String = db.withTransaction {
        val name = choice.displayName.trim()
        require(name.isNotEmpty() && name.length <= 200)
        val key = People.namedKey(name)
        val now = System.currentTimeMillis()
        val previous = db.contacts().find(key)
        // Typing an existing name preserves its saved contact link.
        val value = PersonContact(key, choice.lookupKey ?: previous?.lookupKey, name, now)
        db.contacts().save(value)
        (choice.handles.mapNotNull(Handles::key) + extraHandles).distinct().forEach { db.people().save(PersonAlias(it, name, now)) }
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
        val oldHandles = db.people().all().filter { People.namedKey(it.name) == oldKey }.map { it.handle }
        val selected = if (choice.lookupKey == null) choice.copy(lookupKey = db.contacts().find(oldKey)?.lookupKey) else choice
        val key = save(selected, handles + oldHandles)
        if (key != oldKey) {
            db.tasks().all().filter { it.personKey == oldKey }.forEach { task(it.id, key) }
            db.finances().allEntries().filter { it.personKey == oldKey }.forEach { ledger(it.id, key) }
            db.debts().rename(oldName, choice.displayName.trim())
            db.contacts().delete(oldKey)
            audit(oldKey, "OWNER_PERSON_RENAME", key)
        }
        key
    }
    private suspend fun audit(id: String, action: String, result: String) = db.communications().audit(
        ActionAudit(UUID.randomUUID().toString(), id, action, result, System.currentTimeMillis()))
}
