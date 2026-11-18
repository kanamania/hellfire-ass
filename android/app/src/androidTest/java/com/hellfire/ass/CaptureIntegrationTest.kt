package com.hellfire.ass

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.service.voice.VoiceInteractionService
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.work.WorkManager
import com.hellfire.ass.assistant.HellFireVoiceService
import com.hellfire.ass.audio.MicrophoneService
import com.hellfire.ass.audio.MicrophoneStatus
import com.hellfire.ass.notifications.NotificationCaptureStatus
import com.hellfire.ass.sms.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.FileInputStream
import java.util.UUID

private fun captureApp(): HellFireApp {
    check(BuildConfig.EMULATOR_BUILD) { "Shell-granted capture tests are emulator-only" }
    return InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HellFireApp
}
private fun fixtureShell(command: String): String = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    .use { descriptor -> FileInputStream(descriptor.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) } }
private suspend fun readyCaptureApp(): HellFireApp = captureApp().also { withTimeout(30000) { it.storageStatus.first { value -> value == "READY" } } }

/** Run separately before ADB test grants. A denied permission is never reported as granted. */
class CapturePermissionTest {
    @Test fun deniedSmsAndMicrophoneStayUnavailable() = runBlocking {
        val app = readyCaptureApp()
        assertFalse(SmsCaptureSettings.canRead(app))
        assertEquals(PackageManager.PERMISSION_DENIED, app.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        assertFalse(NotificationCaptureStatus.connected.value)
        val before = app.database.events().count().first()
        try {
            SmsCaptureSettings.setEnabled(app, true)
            SmsSyncWorker.schedule(app)
            withTimeout(30000) { app.database.events().audits().first { rows -> rows.any { it.source == "android_sms" && it.lastError == "SMS_READ_PERMISSION_MISSING" } } }
            assertEquals(before, app.database.events().count().first())
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            try {
                scenario.onActivity { assertFalse(MicrophoneService.requestStart(it, Intent(it, MicrophoneService::class.java).setAction(MicrophoneService.ACTION_START))) }
                withTimeout(10000) { MicrophoneStatus.state.first { it.startsWith("Permission missing") } }
                assertFalse(MicrophoneStatus.active.value)
                assertFalse(MicrophoneStatus.busy.value)
            } finally { scenario.close() }
        } finally { SmsCaptureSettings.setEnabled(app, false); WorkManager.getInstance(app).cancelUniqueWork(SmsSyncWorker.WORK_NAME).result.get() }
    }
}

/** Stock Android behavior with explicit ADB test grants; never run this class on a physical device. */
class CaptureIntegrationTest {
    @Test fun realNotificationListenerCapturesTextAndWithholdsOtp() = runBlocking {
        val app = readyCaptureApp()
        // Instrumentation restarts the target process after the shell grant. Rebind after it is running.
        val listener = "${app.packageName}/com.hellfire.ass.notifications.HellFireNotificationListener"
        fixtureShell("cmd notification disallow_listener $listener")
        fixtureShell("cmd notification allow_listener $listener")
        android.service.notification.NotificationListenerService.requestRebind(
            ComponentName(app, com.hellfire.ass.notifications.HellFireNotificationListener::class.java))
        assertTrue("Permitted listener did not reconnect after process restart", withTimeoutOrNull(30000) { NotificationCaptureStatus.connected.first { it } } == true)
        val since = System.currentTimeMillis()
        // UiAutomation tokenizes commands directly; its argument values must not contain shell quotes/spaces.
        val marker = "HellFireCaptureFixture" + UUID.randomUUID().toString().replace("-", "")
        fixtureShell("cmd notification post -t SyntheticFixture hellfire-capture $marker")
        suspend fun posted() = app.database.events().searchBatch(Long.MAX_VALUE, "").filter {
            it.packageName == "com.android.shell" && it.kind == "POSTED" && it.receivedAt >= since
        }
        withTimeout(30000) {
            while (posted().none { app.cipher.decrypt(it.encryptedPayload).toString(Charsets.UTF_8).contains(marker) }) delay(100)
        }
        val prior = posted().map { it.id }.toSet()
        fixtureShell("cmd notification post -t OTP hellfire-otp 654321")
        withTimeout(30000) { while (posted().none { it.id !in prior }) delay(100) }
        val candidates = posted().filter { it.id !in prior }
        assertTrue(candidates.isNotEmpty())
        candidates.forEach { assertFalse(app.cipher.decrypt(it.encryptedPayload).toString(Charsets.UTF_8).contains("654321")) }
    }

    @Test fun virtualModemSmsFlowsThroughProviderAndDurableWorker() = runBlocking {
        val app = readyCaptureApp()
        assertTrue(SmsCaptureSettings.canRead(app))
        val row = SmsProviderReader.readPage(app.contentResolver, 0).last { it.body == "HellFire synthetic SMS fixture habari" }
        val normalized = com.hellfire.ass.events.NotificationNormalizer.normalize(row.snapshot(System.currentTimeMillis()), app.packageName)!!
        try {
            assertTrue(SmsCaptureSettings.setEnabled(app, true))
            SmsSyncWorker.schedule(app)
            withTimeout(30000) { while (app.database.events().find(normalized.id) == null) delay(100) }
            val event = app.database.events().find(normalized.id)!!
            assertEquals("android_sms", event.source)
            val fields = JSONObject(app.cipher.decrypt(event.encryptedPayload).toString(Charsets.UTF_8))
            assertEquals(row.rowId.toString(), fields.getJSONObject("provenance").getString("provider_row_id"))
            assertTrue(fields.getJSONObject("fields").getJSONArray("text").getString(0).contains("synthetic SMS fixture"))
            fun smsCount() = app.database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM source_event WHERE source = 'android_sms'")
                .use { it.moveToFirst(); it.getInt(0) }
            val count = smsCount()
            SmsSyncWorker.schedule(app)
            withTimeout(30000) { while (WorkManager.getInstance(app).getWorkInfosForUniqueWork(SmsSyncWorker.WORK_NAME).get().any { !it.state.isFinished }) delay(100) }
            assertEquals(count, smsCount())
        } finally { SmsCaptureSettings.setEnabled(app, false); WorkManager.getInstance(app).cancelUniqueWork(SmsSyncWorker.WORK_NAME).result.get() }
    }

    @Test fun foregroundMicrophonePrototypeRetainsOnlyEncryptedVirtualAudio() = runBlocking {
        val app = readyCaptureApp()
        assertEquals(PackageManager.PERMISSION_GRANTED, app.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        assertTrue(app.getSystemService(NotificationManager::class.java).areNotificationsEnabled())
        val directory = MicrophoneService.recordingDirectory(app)
        val before = directory.listFiles().orEmpty().map { it.name }.toSet()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { assertTrue(MicrophoneService.requestStart(it, Intent(it, MicrophoneService::class.java).setAction(MicrophoneService.ACTION_START))) }
            withTimeout(15000) { MicrophoneStatus.active.first { it } }
            assertTrue(app.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 41 })
            delay(1200)
            scenario.onActivity { it.startService(Intent(it, MicrophoneService::class.java).setAction(MicrophoneService.ACTION_STOP)) }
            withTimeout(15000) { MicrophoneStatus.busy.first { !it } }
            val newFiles = directory.listFiles().orEmpty().filter { it.name !in before }
            assertEquals(MicrophoneStatus.state.value, 1, newFiles.size)
            val envelope = newFiles.single().readBytes()
            assertTrue(envelope.size > 29)
            assertTrue(app.cipher.decrypt(envelope).size >= 1024)
            withTimeout(10000) { while (app.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 41 }) delay(100) }
        } finally {
            app.startService(Intent(app, MicrophoneService::class.java).setAction(MicrophoneService.ACTION_STOP))
            withTimeoutOrNull(15000) { MicrophoneStatus.busy.first { !it } }
            scenario.close()
            directory.listFiles().orEmpty().filter { it.name !in before }.forEach { it.delete() }
        }
    }

    @Test fun assistantRoleBindsRealServiceAndShowsRealSession() = runBlocking {
        val app = readyCaptureApp()
        val roles = app.getSystemService(RoleManager::class.java)
        assertTrue(roles.isRoleHeld(RoleManager.ROLE_ASSISTANT))
        withTimeout(30000) { while (!VoiceInteractionService.isActiveService(app, ComponentName(app, HellFireVoiceService::class.java))) delay(100) }
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { it.startLocalVoiceInteraction(android.os.Bundle()) }
            assertTrue(device.wait(Until.hasObject(By.text("HellFire Ass · offline assistant")), 30000))
            assertTrue(device.hasObject(By.desc("Question for HellFire")))
        } finally { scenario.onActivity { it.stopLocalVoiceInteraction() }; scenario.close() }
    }
}
