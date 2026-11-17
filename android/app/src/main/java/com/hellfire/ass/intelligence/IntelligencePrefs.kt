package com.hellfire.ass.intelligence

import android.content.Context

class IntelligencePrefs(private val context: Context) {
    private val prefs = context.getSharedPreferences("intelligence", 0)
    var chargingOnly: Boolean
        get() = prefs.getBoolean("charging", true)
        set(value) { prefs.edit().putBoolean("charging", value).apply() }
    var idlePreference: Boolean
        get() = prefs.getBoolean("idle", false)
        set(value) { prefs.edit().putBoolean("idle", value).apply() }
    var minBattery: Int
        get() = prefs.getInt("minBattery", 20).coerceIn(5, 90)
        set(value) { prefs.edit().putInt("minBattery", value.coerceIn(5, 90)).apply() }
    var pauseAtModerate: Boolean
        get() = prefs.getBoolean("thermalModerate", false)
        set(value) { prefs.edit().putBoolean("thermalModerate", value).apply() }
    var storageNotLow: Boolean
        get() = prefs.getBoolean("storage", true)
        set(value) { prefs.edit().putBoolean("storage", value).apply() }
    var wifiRequired: Boolean
        get() = prefs.getBoolean("wifi", false)
        set(value) { prefs.edit().putBoolean("wifi", value).apply() }
    var maxConcurrent: Int
        get() = prefs.getInt("concurrency", 1).coerceIn(1, 3)
        set(value) { prefs.edit().putInt("concurrency", value.coerceIn(1, 3)).apply() }
    var transcribe: Boolean
        get() = prefs.getBoolean("transcribe", true)
        set(value) { prefs.edit().putBoolean("transcribe", value).apply() }
    var sampleMode: String
        get() = prefs.getString("sampling", SampleMode.BALANCED.name) ?: SampleMode.BALANCED.name
        set(value) { prefs.edit().putString("sampling", value).apply() }
    var speechLanguage: String
        get() = context.getSharedPreferences("media", 0).getString("language", "auto") ?: "auto"
        set(value) { context.getSharedPreferences("media", 0).edit().putString("language", value).apply() }

    fun mode(): SampleMode = runCatching { SampleMode.valueOf(sampleMode) }.getOrDefault(SampleMode.BALANCED)
    fun policy() = RunPolicy(chargingOnly, minBattery, pauseAtModerate, storageNotLow)
    fun genres(): List<String> = prefs.getStringSet("genres", emptySet()).orEmpty().sorted()
    fun addGenre(name: String) {
        val clean = name.trim().take(40)
        if (clean.isEmpty()) return
        prefs.edit().putStringSet("genres", genres().toSet() + clean).apply()
    }
    fun removeGenre(name: String) { prefs.edit().putStringSet("genres", genres().toSet() - name).apply() }
}
