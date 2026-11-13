package com.hellfire.ass.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(tableName = "event_processing", foreignKeys = [ForeignKey(
    entity = SourceEvent::class, parentColumns = ["id"], childColumns = ["eventId"], onDelete = ForeignKey.CASCADE,
)])
data class EventProcessing(
    @PrimaryKey val eventId: String,
    val state: String = "PENDING",
    val label: String? = null,
    val attempts: Int = 0,
    val updatedAt: Long,
    val error: String? = null,
)

@Entity(tableName = "capture_audit")
data class CaptureAudit(
    @PrimaryKey val source: String,
    val lastCapturedAt: Long? = null,
    val captured: Int = 0,
    val failures: Int = 0,
    val dropped: Int = 0,
    val lastError: String? = null,
)

@Entity(tableName = "notification_policy")
data class NotificationPolicy(@PrimaryKey val packageName: String, val processEnabled: Boolean = true)

data class QueueSummary(val state: String, val count: Int)
data class AppProcessingPolicy(val packageName: String, val processEnabled: Boolean)
