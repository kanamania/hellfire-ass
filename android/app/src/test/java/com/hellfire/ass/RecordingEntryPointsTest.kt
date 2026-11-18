package com.hellfire.ass

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import com.hellfire.ass.audio.MicrophoneService
import com.hellfire.ass.audio.MicrophoneStatus
import com.hellfire.ass.security.PayloadCipher
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RecordingEntryPointsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun app(): HellFireApp = HellFireApp().also {
        ReflectionHelpers.callInstanceMethod<Unit>(it, "attach", ReflectionHelpers.ClassParameter.from(Context::class.java, context))
        ReflectionHelpers.setField(it, "cipher\$delegate", lazyOf(PayloadCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())))
    }
    private fun start(app: HellFireApp) = Intent(app, MicrophoneService::class.java).setAction(MicrophoneService.ACTION_START)
    @After fun reset() { MicrophoneStatus.state.value = "Idle" }

    @Test fun deniedPermissionDoesNotCreateAForegroundServiceRequest() {
        val app = app()
        shadowOf(context).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertFalse(MicrophoneService.requestStart(app, start(app)))
        assertTrue(MicrophoneStatus.state.value.startsWith("Permission missing"))
        assertNull(shadowOf(context).nextStartedService)
    }

    @Test fun consentAndExclusionChecksHappenBeforeARequest() {
        val app = app()
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val monitoring = start(app).putExtra("monitoring", true)
        assertFalse(MicrophoneService.requestStart(app, monitoring))
        app.secrets.save("recording-exclusions", JSONObject().put("people", "Synthetic Person").put("places", ""))
        try {
            monitoring.putExtra("notice", true).putExtra("participants", "Synthetic Person")
            assertFalse(MicrophoneService.requestStart(app, monitoring))
            assertTrue(MicrophoneStatus.state.value.startsWith("Monitoring blocked"))
            assertNull(shadowOf(context).nextStartedService)
        } finally { app.secrets.remove("recording-exclusions") }
    }

    @Test fun blockedNotificationChannelDoesNotCreateARequest() {
        val app = app()
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(MicrophoneService.CHANNEL, "Synthetic", NotificationManager.IMPORTANCE_NONE))
        assertFalse(MicrophoneService.requestStart(app, start(app)))
        assertTrue(MicrophoneStatus.state.value.startsWith("Recording channel blocked"))
        assertNull(shadowOf(context).nextStartedService)
    }
}
