package com.hellfire.ass

import android.app.Application
import android.util.AtomicFile
import com.hellfire.ass.ai.*
import com.hellfire.ass.security.PayloadCipher
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AiSettingsTest {
    @get:Rule val directory = TemporaryFolder()
    private fun cipher() = PayloadCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())

    @Test fun `all provider keys routing prices and usage persist only as ciphertext`() {
        val file = directory.newFile("settings.enc"); file.delete()
        val cipher = cipher()
        val store = EncryptedAiStore(AtomicFile(file)) { cipher }
        assertFalse(store.read().cloudEnabled)
        val config = ProviderConfig(true, "synthetic-secret-never-commit-real-key", "claude-fixture", listOf(ModelInfo("claude-fixture")), mapOf("claude-fixture" to ModelPrice(1_000_000, 2_000_000)))
        val state = store.update { it.copy(cloudEnabled = true, monthlyCap = 10_000_000,
            providers = mapOf(ProviderId.CLAUDE to config), routes = mapOf(AiTask.SUMMARY to listOf(ModelRef(ProviderId.CLAUDE, "claude-fixture"), OfflineProvider.ref)),
            usage = mapOf("2026-11/CLAUDE/claude-fixture" to UsageTotal(2, 30, 10, 50)),
            audit = listOf(AiAudit("synthetic-id", 1L, AiTask.SUMMARY, ProviderId.CLAUDE, "claude-fixture", "SUCCESS", 30, 10, 50))) }
        val persisted = EncryptedAiStore(AtomicFile(file)) { cipher }.read()
        assertEquals(state, persisted)
        assertFalse(file.readText().contains(config.apiKey)); assertFalse(file.readText().contains("claude-fixture"))
        assertFalse(state.toString().contains(config.apiKey))
    }
    @Test fun `corrupt ciphertext and lost device key fail closed`() {
        val file = directory.newFile("settings.enc"); file.delete()
        val wrongCipher = cipher()
        val store = EncryptedAiStore(AtomicFile(file)) { wrongCipher }
        val cipher = cipher()
        EncryptedAiStore(AtomicFile(file)) { cipher }.update { it.copy(cloudEnabled = true) }
        try { store.read(); fail("Wrong key must fail") } catch (e: AiException) { assertEquals(AiError.STORAGE, e.error) }
        file.writeBytes(byteArrayOf(1, 2))
        try { EncryptedAiStore(AtomicFile(file)) { cipher }.read(); fail("Corruption must fail") }
        catch (e: AiException) { assertEquals(AiError.STORAGE, e.error) }
    }
    @Test fun `deleting a key persists across settings reopen`() {
        val file = directory.newFile("settings.enc"); file.delete(); val cipher = cipher()
        val store = EncryptedAiStore(AtomicFile(file)) { cipher }
        store.update { it.copy(providers = mapOf(ProviderId.GROK to ProviderConfig(apiKey = "synthetic-secret"))) }
        store.update { it.copy(providers = mapOf(ProviderId.GROK to it.config(ProviderId.GROK).copy(apiKey = ""))) }
        assertEquals("", EncryptedAiStore(AtomicFile(file)) { cipher }.read().config(ProviderId.GROK).apiKey)
    }
}
