package com.hellfire.ass.data

import androidx.room.*

/** One AI request attempt: task, provider, model, time, tokens and estimated cost. Never prompts or answers (§6). */
@Entity(tableName = "ai_usage", indices = [Index("at")])
data class AiUsage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val requestId: String,
    val at: Long,
    val task: String,
    val provider: String,
    val model: String,
    val result: String,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val microUsd: Long,
)

@Dao
interface AiUsageDao {
    @Insert suspend fun insert(value: AiUsage)
    @Query("SELECT * FROM ai_usage WHERE at >= :start AND at < :end ORDER BY at") suspend fun period(start: Long, end: Long): List<AiUsage>
    @Query("SELECT COUNT(*) FROM ai_usage") suspend fun count(): Int
}
