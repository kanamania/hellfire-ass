package com.hellfire.ass.tasks

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import com.hellfire.ass.Diagnostics
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.communications.IntegrationState
import com.hellfire.ass.library.SavedLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Place reminders (owner Priority 3, audit HF-064): one geofence per active "when I get to <place>" reminder,
 * around a place the owner saved with a map position. Android delivers the arrival; HellFire rings once.
 */
object Geofences {
    const val RADIUS_METERS = 150f
    data class Fence(val taskId: String, val latitude: Double, val longitude: Double, val until: Long)

    /** The saved place an owner named: exact label first, then a label containing the words. Needs coordinates. */
    fun place(places: List<SavedLocation>, name: String): SavedLocation? {
        val wanted = name.trim().lowercase(Locale.ROOT)
        val located = places.filter { it.latitude != null && it.longitude != null }
        return located.firstOrNull { it.label.trim().lowercase(Locale.ROOT) == wanted }
            ?: located.firstOrNull { it.label.lowercase(Locale.ROOT).contains(wanted) || wanted.contains(it.label.trim().lowercase(Locale.ROOT)) }
    }

    /** Pure: the geofences to register for these reminders. */
    fun fences(tasks: List<AssistantTask>, places: List<SavedLocation>, now: Long): List<Fence> = tasks.mapNotNull { task ->
        val id = task.condition?.takeIf { it.startsWith("ARRIVE:") }?.substringAfter(':')?.substringBefore('|') ?: return@mapNotNull null
        val place = places.firstOrNull { it.id == id } ?: return@mapNotNull null
        if (task.dueAt <= now || place.latitude == null || place.longitude == null) null else Fence(task.id, place.latitude, place.longitude, task.dueAt)
    }.take(90) // Android allows 100 geofences per app.

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(context, 78,
        Intent(context, GeofenceReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

    private fun allowed(context: Context) = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
        (Build.VERSION.SDK_INT < 29 || context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)

    /** Replaces the registered geofences with the current place reminders. */
    @SuppressLint("MissingPermission")
    suspend fun sync(app: HellFireApp) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val fences = fences(app.database.tasks().placeTasks(), app.database.inbox().locationsNow(), now)
        val client = LocationServices.getGeofencingClient(app)
        runCatching { Tasks.await(client.removeGeofences(pending(app))) }
        if (fences.isEmpty()) return@withContext
        if (!allowed(app)) {
            app.database.communications().integration(IntegrationState("PLACE_REMINDERS", "PERMISSION_MISSING", now, "ALLOW_LOCATION_ALL_THE_TIME"))
            return@withContext
        }
        val request = GeofencingRequest.Builder().setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER).addGeofences(fences.map {
            Geofence.Builder().setRequestId(it.taskId).setCircularRegion(it.latitude, it.longitude, RADIUS_METERS)
                .setExpirationDuration(it.until - now).setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER).build()
        }).build()
        try {
            Tasks.await(client.addGeofences(request, pending(app)))
            app.database.communications().integration(IntegrationState("PLACE_REMINDERS", "WATCHING", now))
        } catch (caught: Exception) {
            Diagnostics.warn(caught)
            app.database.communications().integration(IntegrationState("PLACE_REMINDERS", "FAILED", now, "GEOFENCE_REGISTRATION_FAILED"))
        }
    }
}

/** Arrival at a place reminder's place, from Android's geofencing. Not exported. */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError() || event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER) return
        val ids = event.triggeringGeofences.orEmpty().map { it.requestId }
        if (ids.isEmpty()) return
        val result = goAsync()
        val app = context.applicationContext as HellFireApp
        app.captureScope.launch {
            try { TaskScheduler.arrived(app, ids); Geofences.sync(app) }
            catch (caught: Exception) { Diagnostics.warn(caught) }
            finally { result.finish() }
        }
    }
}
