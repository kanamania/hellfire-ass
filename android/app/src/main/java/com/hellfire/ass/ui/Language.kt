package com.hellfire.ass.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.theme.Hf
import java.util.Locale

object UiLanguage {
    var code by mutableStateOf("en")
    val locale get() = Locale.forLanguageTag(if (code == "sw") "sw-TZ" else "en-TZ")
    fun context(base: Context): Context = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(UiLanguage.locale) })
    fun set(context: Context, value: String) {
        require(value in setOf("en", "sw"))
        context.getSharedPreferences("ui", 0).edit().putString("language", value).apply()
        code = value
        if (android.os.Build.VERSION.SDK_INT >= 33) context.getSystemService(android.app.LocaleManager::class.java).applicationLocales = android.os.LocaleList.forLanguageTags(value)
    }
}

@Composable
fun tr(text: String): String = UiLabels.id(text)?.let { LocalContext.current.getString(it) } ?: text

@Composable
fun LanguagePage() {
    val app = LocalEnv.current.app
    PushedPage(tr("Language"), breadcrumb = tr("Settings")) {
        Text(tr("Interface and voice language"), style = Hf.type.cardTitle, color = Hf.colors.onSurface)
        OptionCard("English", "English · Tanzania", UiLanguage.code == "en", { UiLanguage.set(app, "en") })
        OptionCard("Kiswahili", "Kiswahili · Tanzania", UiLanguage.code == "sw", { UiLanguage.set(app, "sw") })
        Note(if (UiLanguage.code == "sw") "Maandishi ya chanzo yanabaki kama yalivyopokelewa. Baadhi ya maelezo ya kiufundi yanaendelea kuwa kwa Kiingereza. Utambuzi wa sauti unategemea lugha zilizowekwa kwenye simu."
            else "Source text stays in its original language. Advanced technical details remain in English. Speech recognition depends on languages available on the phone.")
    }
}
