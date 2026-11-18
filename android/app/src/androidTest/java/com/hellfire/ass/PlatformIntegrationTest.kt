package com.hellfire.ass

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.hellfire.ass.data.EventDatabase
import com.hellfire.ass.data.SourceEvent
import com.hellfire.ass.media.WhisperNative
import com.hellfire.ass.security.BackupCrypto
import com.hellfire.ass.security.PayloadCipher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/** Actual Android native libraries and Keystore; synthetic data only. */
class PlatformIntegrationTest {
    private inline fun <T> EventDatabase.useDb(block: (EventDatabase) -> T): T = try { block(this) } finally { close() }
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HellFireApp

    @Test fun keystoreAuthenticationRejectsTamperedCiphertext() {
        val cipher = PayloadCipher.deviceCipher()
        val plain = "Synthetic Kiswahili: habari za asubuhi".toByteArray()
        val envelope = cipher.encrypt(plain, "fixture".toByteArray())
        assertArrayEquals(plain, PayloadCipher.deviceCipher().decrypt(envelope, "fixture".toByteArray()))
        envelope[envelope.lastIndex] = (envelope.last().toInt() xor 1).toByte()
        assertTrue(runCatching { cipher.decrypt(envelope, "fixture".toByteArray()) }.isFailure)
    }

    @Test fun nativeSqlcipherPersistsEncryptedRowsAndRejectsWrongKey() = runBlocking {
        System.loadLibrary("sqlcipher")
        val name = "fixture-${UUID.randomUUID()}.db"
        val password = "synthetic-test-key-only".toByteArray()
        fun open(key: ByteArray) = Room.databaseBuilder(app, EventDatabase::class.java, name)
            .openHelperFactory(SupportOpenHelperFactory(key)).build()
        try {
            open(password.copyOf()).useDb { db ->
                db.events().insert(SourceEvent("fixture", externalId = "fixture", packageName = "fixture.invalid", occurredAt = 1,
                    receivedAt = 2, kind = "SYNTHETIC", payloadHash = "fixture", encryptedPayload = byteArrayOf(1, 2, 3),
                    contentAvailable = true, contentRedacted = false, securitySensitive = false))
                assertEquals("ok", db.openHelper.writableDatabase.query("PRAGMA integrity_check").use { it.moveToFirst(); it.getString(0) })
            }
            val prefix = app.getDatabasePath(name).inputStream().use { input -> ByteArray(16).also { input.read(it) } }
            assertFalse(prefix.contentEquals("SQLite format 3\u0000".toByteArray()))
            open(password.copyOf()).useDb { assertNotNull(it.events().find("fixture")) }
            open("wrong-fixture-key".toByteArray()).useDb { db ->
                assertTrue(runCatching { db.openHelper.writableDatabase }.isFailure)
            }
        } finally { app.deleteDatabase(name) }
    }

    @Test fun portableCryptoRejectsWrongPasswordAndTruncation() {
        val salt = ByteArray(16) { it.toByte() }
        val cipher = BackupCrypto.derive("synthetic passphrase".toCharArray(), salt)
        val payload = cipher.encrypt("fixture archive".toByteArray())
        assertArrayEquals("fixture archive".toByteArray(), BackupCrypto.derive("synthetic passphrase".toCharArray(), salt).decrypt(payload))
        assertTrue(runCatching { BackupCrypto.derive("different passphrase".toCharArray(), salt).decrypt(payload) }.isFailure)
        assertTrue(runCatching { cipher.decrypt(payload.copyOf(payload.size - 1)) }.isFailure)
    }

    @Test fun whisperLoadsRealNativeBridgeAndRejectsInvalidModel() {
        val file = File(app.cacheDir, "invalid-fixture.ggml").apply { writeBytes(ByteArray(128)) }
        try {
            if (BuildConfig.EMULATOR_BUILD) {
                // This reaches the real x86 JNI model parser, not a substitute implementation.
                val error = runCatching { WhisperNative.open(file.path) }.exceptionOrNull()
                assertTrue(error is IllegalStateException)
                assertFalse(error!!.message.orEmpty().contains("CPU features"))
            } else {
                // Unsupported handset CPUs may correctly fail the ARM instruction guard.
                assertTrue(runCatching { WhisperNative.open(file.path) }.isFailure)
            }
        } finally { file.delete() }
    }

    @Test fun semanticRuntimeReportsPinnedAbiLimitation() {
        assertEquals(!BuildConfig.EMULATOR_BUILD, app.semantic.supported)
    }

    @Test fun automaticTelemetryComponentsAreAbsent() {
        val info = app.packageManager.getPackageInfo(app.packageName,
            android.content.pm.PackageManager.GET_PROVIDERS or android.content.pm.PackageManager.GET_SERVICES)
        assertFalse(info.providers.orEmpty().any { it.name == "ai.onnxruntime.TelemetryInitializer" })
        assertFalse(info.services.orEmpty().any { it.name == "com.google.android.datatransport.runtime.backends.TransportBackendDiscovery" })
    }
}
