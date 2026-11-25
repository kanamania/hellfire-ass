package com.hellfire.ass.guard

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Entity(tableName = "guard_config")
data class GuardConfig(@PrimaryKey val id: String = "owner", val relayUrl: String, val createdAt: Long, val updatedAt: Long)

@Entity(tableName = "guard_state")
data class GuardState(
    @PrimaryKey val id: String = "owner", val sessionId: String = UUID.randomUUID().toString(),
    val armed: Boolean = false, val active: Boolean = false, val trigger: String = "manual_test",
    val sequence: Long = 0, val pendingDisarm: Boolean = false, val needsResume: Boolean = false,
    val status: String = "OFF", val lastHeartbeatAt: Long? = null, val simFingerprint: String? = null,
    val createdAt: Long, val updatedAt: Long,
)

@Entity(tableName = "guard_evidence")
data class GuardEvidence(@PrimaryKey val id: String, val sessionId: String, val createdAt: Long,
    val encryptedPayload: ByteArray, val uploadedAt: Long? = null, val error: String? = null)

data class GuardEvidenceSummary(val id: String, val createdAt: Long, val uploadedAt: Long?, val error: String?)

@Dao
abstract class GuardDao {
    @Query("SELECT * FROM guard_config WHERE id='owner'") abstract suspend fun config(): GuardConfig?
    @Upsert abstract suspend fun config(value: GuardConfig)
    @Query("SELECT * FROM guard_state WHERE id='owner'") abstract suspend fun state(): GuardState?
    @Query("SELECT * FROM guard_state WHERE id='owner'") abstract fun observe(): Flow<GuardState?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) protected abstract suspend fun put(value: GuardState)
    @Query("UPDATE guard_state SET armed=0,active=0,pendingDisarm=1,needsResume=0,status='DISARM_PENDING_RELAY',updatedAt=:now WHERE id='owner' AND armed=1")
    abstract suspend fun disarm(now: Long): Int
    @Query("UPDATE guard_state SET active=1,trigger=:trigger,updatedAt=:now WHERE id='owner' AND armed=1 AND active=0")
    abstract suspend fun activate(trigger: String, now: Long): Int
    @Query("UPDATE guard_state SET status=:status,needsResume=:resume,updatedAt=:now WHERE id='owner'")
    abstract suspend fun status(status: String, resume: Boolean, now: Long)
    @Query("UPDATE guard_state SET sequence=sequence+1 WHERE id='owner'") protected abstract suspend fun increment()
    @Query("UPDATE guard_state SET lastHeartbeatAt=:now,pendingDisarm=CASE WHEN armed=0 THEN 0 ELSE pendingDisarm END WHERE id='owner' AND sessionId=:session AND sequence=:sequence")
    abstract suspend fun acknowledged(session: String, sequence: Long, now: Long)
    @Query("UPDATE guard_state SET status='OFF' WHERE id='owner' AND armed=0 AND pendingDisarm=0") abstract suspend fun settled()
    @Query("UPDATE guard_state SET simFingerprint=:fingerprint WHERE id='owner'") abstract suspend fun sim(fingerprint: String)
    @Insert abstract suspend fun evidence(value: GuardEvidence)
    @Query("SELECT * FROM guard_evidence WHERE uploadedAt IS NULL AND sessionId=:session ORDER BY createdAt,id LIMIT 3")
    abstract suspend fun pending(session: String): List<GuardEvidence>
    @Query("SELECT id,createdAt,uploadedAt,error FROM guard_evidence ORDER BY createdAt DESC LIMIT 20") abstract fun evidenceLog(): Flow<List<GuardEvidenceSummary>>
    @Query("SELECT COALESCE(SUM(length(encryptedPayload)),0) FROM guard_evidence") abstract suspend fun evidenceBytes(): Long
    @Query("UPDATE guard_evidence SET uploadedAt=:time,error=:error WHERE id=:id") abstract suspend fun upload(id: String, time: Long?, error: String?)

    @Transaction
    open suspend fun arm(now: Long) {
        val old = state()
        check(old?.armed != true && old?.pendingDisarm != true) { "Disarm the previous session and confirm relay delivery first" }
        put(GuardState(armed = true, status = "STARTING", createdAt = now, updatedAt = now))
    }
    @Transaction
    open suspend fun nextHeartbeat(): GuardState? {
        val current = state() ?: return null
        if (!current.armed && !current.pendingDisarm) return null
        increment()
        return state()
    }
}

object GuardLogic {
    const val PERIOD_MS = 60_000L
    const val MAX_EVIDENCE_BYTES = 50L * 1024 * 1024
    fun unlockTrigger(armed: Boolean, failures: Int) = armed && failures >= 3
    fun simChanged(armed: Boolean, baseline: String?, current: String?) = armed && baseline != null && current != null && baseline != current
    fun capture(state: GuardState?) = state?.armed == true && state.active
    fun bootMode(armed: Boolean, backgroundLocation: Boolean) = when {
        !armed -> "OFF"
        backgroundLocation -> "LOCATION_ONLY"
        else -> "TAP_TO_RESUME"
    }
}

object GuardSchema {
    val tables = listOf("guard_config", "guard_state", "guard_evidence")
    fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS guard_config (id TEXT NOT NULL PRIMARY KEY, relayUrl TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS guard_state (id TEXT NOT NULL PRIMARY KEY, sessionId TEXT NOT NULL, armed INTEGER NOT NULL, active INTEGER NOT NULL, trigger TEXT NOT NULL, sequence INTEGER NOT NULL, pendingDisarm INTEGER NOT NULL, needsResume INTEGER NOT NULL, status TEXT NOT NULL, lastHeartbeatAt INTEGER, simFingerprint TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS guard_evidence (id TEXT NOT NULL PRIMARY KEY, sessionId TEXT NOT NULL, createdAt INTEGER NOT NULL, encryptedPayload BLOB NOT NULL, uploadedAt INTEGER, error TEXT)")
    }
}
