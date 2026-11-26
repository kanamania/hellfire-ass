package com.hellfire.ass.memory

import androidx.room.*
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.hellfire.ass.data.SourceEvent
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.security.MessageDigest

@Entity(tableName = "ai_skill", indices = [Index("sourceId")], foreignKeys = [ForeignKey(entity = SourceEvent::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.RESTRICT)])
data class AiSkill(@PrimaryKey val id: String, val name: String, val kind: String, val trigger: String, val advice: String,
    val reason: String, val sourceId: String?, val state: String = "PENDING", val revision: Int = 1,
    val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = System.currentTimeMillis())

@Entity(tableName = "skill_use", indices = [Index("skillId"), Index("sourceId"), Index(value = ["skillId", "revision", "context", "recordId"], unique = true)],
    foreignKeys = [ForeignKey(entity = AiSkill::class, parentColumns = ["id"], childColumns = ["skillId"], onDelete = ForeignKey.CASCADE), ForeignKey(entity = SourceEvent::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.RESTRICT)])
data class SkillUse(@PrimaryKey val id: String, val skillId: String, val revision: Int, val context: String, val recordId: String,
    val sourceId: String?, val detail: String, val occurredAt: Long = System.currentTimeMillis())

data class SkillUseCount(val skillId: String, val count: Long)

@Dao
interface SkillDao {
    @Query("SELECT * FROM ai_skill ORDER BY CASE state WHEN 'PENDING' THEN 0 ELSE 1 END, updatedAt DESC") fun watch(): Flow<List<AiSkill>>
    @Query("SELECT COUNT(*) FROM ai_skill WHERE state='PENDING'") fun pendingCount(): Flow<Int>
    @Query("SELECT * FROM ai_skill WHERE state='ACTIVE' ORDER BY updatedAt DESC") suspend fun active(): List<AiSkill>
    @Query("SELECT * FROM ai_skill WHERE id=:id") suspend fun find(id: String): AiSkill?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun propose(skill: AiSkill): Long
    @Upsert suspend fun save(skill: AiSkill)
    @Query("UPDATE ai_skill SET state='DISABLED', revision=revision+1, updatedAt=:now WHERE kind=:kind AND trigger=:trigger AND id!=:exceptId AND state='ACTIVE'") suspend fun replaceActive(kind: String, trigger: String, exceptId: String, now: Long)
    @Query("DELETE FROM ai_skill WHERE id=:id") suspend fun delete(id: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun use(value: SkillUse)
    @Query("SELECT skillId, COUNT(*) AS count FROM skill_use GROUP BY skillId") fun counts(): Flow<List<SkillUseCount>>
    @Query("SELECT * FROM skill_use WHERE skillId=:id ORDER BY occurredAt DESC LIMIT :limit") fun historyFlow(id: String, limit: Int): Flow<List<SkillUse>>
    @Query("SELECT * FROM skill_use WHERE skillId=:id ORDER BY occurredAt DESC LIMIT :limit") suspend fun history(id: String, limit: Int): List<SkillUse>
}

/** Skills are typed suggestions, never executable tools or authority to bypass approvals. */
class AiSkills(private val db: com.hellfire.ass.data.EventDatabase) {
    companion object {
        val kinds = listOf("GUIDANCE", "PAYMENT_DEFAULT", "IGNORE_TASK", "IGNORE_FINANCE")
        fun key(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        fun exact(packageName: String, text: String) = key(packageName + "|" + text.trim().lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " "))
        fun validate(skill: AiSkill) {
            require(skill.name.trim().length in 1..120 && skill.trigger.trim().length in 2..300 && skill.advice.trim().length in 1..2000 && skill.reason.trim().length in 1..1000)
            require(skill.kind in kinds && skill.state in setOf("ACTIVE", "PENDING", "DISABLED"))
            if (skill.kind == "GUIDANCE") {
                if (com.hellfire.ass.data.SearchText.keywords(skill.trigger).isEmpty()) throw com.hellfire.ass.OwnerError(com.hellfire.ass.R.string.ui_choose_meaningful_topic_words)
                val safe = com.hellfire.ass.events.NotificationNormalizer.normalize(com.hellfire.ass.events.NotificationSnapshot("skill", "owner.skill", 0, 0, fields = mapOf("text" to listOf(skill.name, skill.trigger, skill.advice, skill.reason))), "com.hellfire.ass")!!
                if (safe.securitySensitive || safe.contentRedacted) throw com.hellfire.ass.OwnerError(com.hellfire.ass.R.string.ui_remove_authentication_content_from_this)
            }
            if (skill.kind == "PAYMENT_DEFAULT") { val j = JSONObject(skill.advice); require(j.getString("account").isNotBlank() && j.getString("category").isNotBlank()) }
        }
    }
    suspend fun suggest(name: String, kind: String, trigger: String, advice: String, reason: String, sourceId: String?): String {
        val id = key("$kind|$trigger|$advice")
        val skill = AiSkill(id, name.take(120), kind, trigger, advice.take(2000), reason.take(1000), sourceId)
        validate(skill)
        sourceId?.let { require(db.events().find(it)?.let { source -> !source.securitySensitive && source.contentAvailable } == true) { "Skill evidence is unavailable or security-sensitive." } }
        db.skills().propose(skill); return id
    }
    suspend fun save(skill: AiSkill) = db.withTransaction {
        validate(skill)
        if (skill.kind == "PAYMENT_DEFAULT" && db.finances().account(JSONObject(skill.advice).getString("account")) == null) throw com.hellfire.ass.OwnerError(com.hellfire.ass.R.string.ui_choose_an_existing_account)
        val old = db.skills().find(skill.id)
        if (skill.state == "ACTIVE") db.skills().replaceActive(skill.kind, skill.trigger, skill.id, System.currentTimeMillis())
        db.skills().save(skill.copy(revision = (old?.revision ?: 0) + 1, updatedAt = System.currentTimeMillis()))
    }
    suspend fun record(skill: AiSkill, context: String, recordId: String, sourceId: String?, detail: String) {
        if (db.skills().find(skill.id)?.let { it.state == "ACTIVE" && it.revision == skill.revision } != true) return
        db.skills().use(SkillUse(key("${skill.id}|${skill.revision}|$context|$recordId"), skill.id, skill.revision, context, recordId, sourceId, detail.take(500)))
    }
    suspend fun payment(trigger: String, candidateId: String, sourceId: String, applied: Boolean = true): Pair<AiSkill, Pair<String, String>>? {
        val skill = db.skills().active().firstOrNull { it.kind == "PAYMENT_DEFAULT" && it.trigger == trigger } ?: return null
        val j = JSONObject(skill.advice)
        val value = j.getString("account") to j.getString("category")
        val account = db.finances().account(value.first) ?: return null
        if (applied) record(skill, "Payment suggestion", candidateId, sourceId, "Suggested ${account.label} / ${value.second} for review; no payment posted")
        return skill to value
    }
    suspend fun ignoredFor(app: com.hellfire.ass.HellFireApp, kind: String, sourceId: String, text: String): Boolean {
        if (db.skills().active().none { it.kind == kind }) return false
        val event = db.events().find(sourceId) ?: return false
        if (event.source.startsWith("owner_")) return false
        return ignored(kind, sourceId, text, scopeFor(app, sourceId))
    }
    suspend fun ignored(kind: String, sourceId: String, text: String, scope: String? = null): Boolean {
        val event = db.events().find(sourceId) ?: return false
        val trigger = exact(scope ?: event.packageName, text.take(1000))
        val skill = db.skills().active().firstOrNull { it.kind == kind && it.trigger == trigger } ?: return false
        record(skill, "Capture processing", sourceId, sourceId, "Repeated false suggestion suppressed; original source retained")
        return true
    }
    suspend fun scopeFor(app: com.hellfire.ass.HellFireApp, sourceId: String): String {
        val event = requireNotNull(db.events().find(sourceId))
        val fields = com.hellfire.ass.ui.EventText.fields(app, event)
        val payload = JSONObject(app.cipher.decrypt(event.encryptedPayload).toString(Charsets.UTF_8))
        return listOf(event.packageName, event.source, payload.optJSONObject("provenance")?.optString("account_id").orEmpty(),
            listOf("sender_hint", "recipient", "recipient_hint", "title").joinToString("|") { fields[it].orEmpty().joinToString() }).joinToString("|")
    }
    suspend fun feedbackFor(app: com.hellfire.ass.HellFireApp, kind: String, sourceId: String, reason: String) {
        val event = db.events().find(sourceId) ?: return
        val fields = com.hellfire.ass.ui.EventText.fields(app, event)
        var text = listOf("title", "text", "big_text", "text_lines", "sub_text", "messages").flatMap { fields[it].orEmpty() }.joinToString("\n")
        if (kind == "IGNORE_FINANCE") text = text.replace("\"", "")
        feedback(kind, sourceId, text, reason, scopeFor(app, sourceId))
    }
    suspend fun feedback(kind: String, sourceId: String, text: String, reason: String, scope: String? = null) {
        val event = db.events().find(sourceId) ?: return
        if (event.securitySensitive || !event.contentAvailable || text.isBlank()) return
        suggest(if (kind == "IGNORE_TASK") "Avoid this false task" else "Avoid this false payment", kind, exact(scope ?: event.packageName, text.take(1000)),
            "Do not suggest this exact normalized text from ${event.packageName} again.", reason, sourceId)
    }
    suspend fun guidance(query: String): List<AiSkill> {
        val terms = com.hellfire.ass.data.SearchText.keywords(query)
        return db.skills().active().filter { it.kind == "GUIDANCE" && com.hellfire.ass.data.SearchText.keywords(it.trigger).let { words -> words.isNotEmpty() && words.all { word -> word in terms } } }.take(5)
    }
}

object SkillSchema {
    fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS ai_skill (id TEXT NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL, trigger TEXT NOT NULL, advice TEXT NOT NULL, reason TEXT NOT NULL, sourceId TEXT, state TEXT NOT NULL, revision INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(sourceId) REFERENCES source_event(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_skill_sourceId ON ai_skill(sourceId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS skill_use (id TEXT NOT NULL, skillId TEXT NOT NULL, revision INTEGER NOT NULL, context TEXT NOT NULL, recordId TEXT NOT NULL, sourceId TEXT, detail TEXT NOT NULL, occurredAt INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(skillId) REFERENCES ai_skill(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(sourceId) REFERENCES source_event(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_skill_use_skillId ON skill_use(skillId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_skill_use_sourceId ON skill_use(sourceId)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_skill_use_skillId_revision_context_recordId ON skill_use(skillId, revision, context, recordId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS memory_revision (id TEXT NOT NULL, memoryId TEXT NOT NULL, text TEXT NOT NULL, changedAt INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(memoryId) REFERENCES memory_item(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_revision_memoryId ON memory_revision(memoryId)")
    }
}
