package com.hellfire.ass.sms

import android.content.ContentResolver
import android.provider.Telephony
import com.hellfire.ass.events.NotificationSnapshot
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Provider row IDs make broadcast-triggered reconciliation and history imports idempotent. */
data class SmsRecord(val rowId: Long, val address: String, val body: String, val date: Long,
    val dateSent: Long, val type: Int, val subscriptionId: Int) {
    fun snapshot(receivedAt: Long): NotificationSnapshot {
        require(type == Telephony.Sms.MESSAGE_TYPE_INBOX || type == Telephony.Sms.MESSAGE_TYPE_SENT)
        return NotificationSnapshot(
            key = "sms-provider:$rowId", packageName = "android.provider.Telephony.Sms",
            postedAt = date, receivedAt = receivedAt, source = "android_sms",
            kind = if (type == Telephony.Sms.MESSAGE_TYPE_INBOX) "INCOMING" else "OUTGOING",
            fields = mapOf("text" to listOf(body), "sender_hint" to listOf(address),
                "date_sent" to listOf(dateSent.toString()), "subscription_id" to listOf(subscriptionId.toString())),
        )
    }
}

object SmsProviderReader {
    const val PAGE_SIZE = 500
    val projection = arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
        Telephony.Sms.DATE, Telephony.Sms.DATE_SENT, Telephony.Sms.TYPE, Telephony.Sms.SUBSCRIPTION_ID)

    suspend fun readPage(resolver: ContentResolver, afterId: Long): List<SmsRecord> {
        require(afterId >= 0)
        val rows = ArrayList<SmsRecord>()
        val cursor = resolver.query(Telephony.Sms.CONTENT_URI, projection,
            "${Telephony.Sms._ID} > ? AND ${Telephony.Sms.TYPE} IN (?, ?)",
            arrayOf(afterId.toString(), Telephony.Sms.MESSAGE_TYPE_INBOX.toString(), Telephony.Sms.MESSAGE_TYPE_SENT.toString()),
            "${Telephony.Sms._ID} ASC") ?: error("SMS_PROVIDER_UNAVAILABLE")
        cursor.use {
            val indices = projection.map(it::getColumnIndexOrThrow)
            while (rows.size < PAGE_SIZE && it.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val row = SmsRecord(it.getLong(indices[0]), it.getString(indices[1]).orEmpty(),
                    it.getString(indices[2]).orEmpty(), it.getLong(indices[3]), it.getLong(indices[4]),
                    it.getInt(indices[5]), it.getInt(indices[6]))
                check(row.rowId > (rows.lastOrNull()?.rowId ?: afterId)) { "SMS_PROVIDER_ORDER_INVALID" }
                rows += row
            }
        }
        return rows
    }
}
