package com.hellfire.ass.guard

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.room.withTransaction
import com.hellfire.ass.data.SourceEvent
import java.security.MessageDigest
import com.hellfire.ass.HellFireApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Shared by the service and the one-shot disarm worker; the Room sequence is transactional across processes. */
class GuardCycle(private val app: HellFireApp) {
    private val dao get() = app.database.guard()
    suspend fun heartbeat(): Boolean {
        val state = dao.nextHeartbeat() ?: return false
        val config = dao.config() ?: error("GUARD_CONFIG_MISSING")
        val battery = app.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(-1, 100)
        val payload = JSONObject().put("session_id", state.sessionId).put("sequence", state.sequence)
            .put("timestamp", System.currentTimeMillis() / 1000).put("armed", state.armed).put("active", state.active)
            .put("battery", battery).put("network", if (online(app)) "online" else "offline").toString().toByteArray()
        val response = try { GuardRelay.post(app, "heartbeat", payload, config.relayUrl) } finally { payload.fill(0) }
        check(response.getBoolean("accepted")) { "GUARD_HEARTBEAT_REJECTED" }
        dao.acknowledged(state.sessionId, state.sequence, System.currentTimeMillis())
        if (response.getBoolean("active") && state.armed) dao.activate("dead_man", System.currentTimeMillis())
        return true
    }
    suspend fun capture(sensors: GuardSensors, cameraAllowed: Boolean) {
        val state = dao.state() ?: return
        if (!GuardLogic.capture(state)) return
        check(dao.evidenceBytes() < GuardLogic.MAX_EVIDENCE_BYTES) { "GUARD_LOCAL_STORAGE_FULL" }
        val errors = JSONArray()
        val fix = try { sensors.location() }
        catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
            errors.put("LOCATION_TIMEOUT"); null
        } catch (_: Exception) { errors.put("LOCATION_UNAVAILABLE"); null }
        val images = if (cameraAllowed) sensors.images(errors) else JSONArray().also { errors.put("CAMERA_TAP_TO_RESUME") }
        val id = UUID.randomUUID().toString()
        val body = JSONObject().put("id", id).put("session_id", state.sessionId).put("timestamp", System.currentTimeMillis() / 1000)
            .put("trigger", state.trigger).put("location", fix ?: JSONObject.NULL).put("images", images).put("errors", errors).toString().toByteArray()
        try {
            // Re-check after sensors return so biometric disarm takes effect during a capture too.
            if (dao.state()?.let { it.armed && it.sessionId == state.sessionId } == true) {
                val encrypted = app.cipher.encrypt(body)
                check(dao.evidenceBytes() + encrypted.size <= GuardLogic.MAX_EVIDENCE_BYTES) { "GUARD_LOCAL_STORAGE_FULL" }
                val capturedAt = System.currentTimeMillis()
                val metadata = JSONObject().put("evidence_id", id).put("session_id", state.sessionId).toString().toByteArray()
                app.database.withTransaction {
                    // Normalized provenance first, without searchable photos or general inference jobs.
                    app.database.events().insert(SourceEvent(id = id, source = "theft_guard", externalId = id,
                        packageName = app.packageName, occurredAt = capturedAt, receivedAt = capturedAt, kind = "GUARD_EVIDENCE",
                        payloadHash = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) },
                        encryptedPayload = app.cipher.encrypt(metadata), contentAvailable = false, contentRedacted = true,
                        securitySensitive = true, processingStatus = "guard_only"))
                    dao.evidence(GuardEvidence(id, state.sessionId, capturedAt, encrypted))
                }
                metadata.fill(0)
            }
        } finally { body.fill(0) }
    }
    suspend fun upload() {
        val state = dao.state() ?: return
        if (!state.armed) return
        val config = dao.config() ?: return
        for (row in dao.pending(state.sessionId)) {
            if (dao.state()?.armed != true) return
            try {
                val plaintext = app.cipher.decrypt(row.encryptedPayload)
                val reply = try { GuardRelay.post(app, "evidence", plaintext, config.relayUrl) } finally { plaintext.fill(0) }
                check(reply.getBoolean("accepted"))
                dao.upload(row.id, System.currentTimeMillis(), null)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { dao.upload(row.id, null, "UPLOAD_UNCONFIRMED"); throw IllegalStateException("GUARD_UPLOAD_UNCONFIRMED") }
        }
    }
    companion object {
        fun online(context: Context): Boolean {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            return manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        }
    }
}
