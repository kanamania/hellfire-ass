package com.hellfire.ass

import android.app.Application
import androidx.test.runner.AndroidJUnitRunner
import com.hellfire.ass.ai.ModelUpdates

/** Runs only against disposable test packages; never resets an owner's installed app. */
class PredeviceRunner : AndroidJUnitRunner() {
    override fun callApplicationOnCreate(app: Application) {
        check(app.packageName.endsWith(".emulator") || app.packageName.endsWith(".devicetest"))
        ModelUpdates.suppressAutomaticDownload(app)
        (app as HellFireApp).secrets.save(com.hellfire.ass.news.NewsRepository.SETTINGS,
            org.json.JSONObject().put("enabled", false))
        app.getSharedPreferences("media", 0).edit().putBoolean("analysis_paused", true).commit()
        app.getSharedPreferences("ui", 0).edit().putString("language", "en").commit()
        super.callApplicationOnCreate(app)
    }
}
