package com.hellfire.ass.ui

import androidx.compose.ui.res.stringResource
import com.hellfire.ass.R

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
    fun context(base: Context): Context = android.view.ContextThemeWrapper(base, 0).apply {
        // Keep the Activity in the wrapper chain for Compose result registries and drag permissions.
        applyOverrideConfiguration(Configuration(base.resources.configuration).apply { setLocale(UiLanguage.locale) })
    }
    fun set(context: Context, value: String) {
        require(value in setOf("en", "sw"))
        context.getSharedPreferences("ui", 0).edit().putString("language", value).apply()
        code = value
        if (android.os.Build.VERSION.SDK_INT >= 33) context.getSystemService(android.app.LocaleManager::class.java).applicationLocales = android.os.LocaleList.forLanguageTags(value)
    }
}

@Composable
fun LanguagePage() {
    val app = LocalEnv.current.app
    PushedPage(stringResource(R.string.ui_language), breadcrumb = stringResource(R.string.ui_settings)) {
        Text(stringResource(R.string.ui_interface_and_voice_language), style = Hf.type.cardTitle, color = Hf.colors.onSurface)
        OptionCard("English", "English · Tanzania", UiLanguage.code == "en", { UiLanguage.set(app, "en") })
        OptionCard("Kiswahili", "Kiswahili · Tanzania", UiLanguage.code == "sw", { UiLanguage.set(app, "sw") })
        Note(if (UiLanguage.code == "sw") "Maandishi ya chanzo yanabaki kama yalivyopokelewa. Baadhi ya maelezo ya kiufundi yanaendelea kuwa kwa Kiingereza. Utambuzi wa sauti unategemea lugha zilizowekwa kwenye simu."
            else "Source text stays in its original language. Advanced technical details remain in English. Speech recognition depends on languages available on the phone.")
    }
}

/** Labels for spending categories stored on entries; a name the owner typed shows as stored. */
@Composable
fun categoryLabel(category: String): String = when (category) {
    "Food" -> stringResource(R.string.ui_food); "Groceries" -> stringResource(R.string.ui_groceries)
    "Transport" -> stringResource(R.string.ui_transport); "Fuel" -> stringResource(R.string.ui_fuel)
    "Utilities" -> stringResource(R.string.ui_utilities); "Rent" -> stringResource(R.string.ui_rent)
    "Business" -> stringResource(R.string.ui_business); "Family" -> stringResource(R.string.ui_family)
    "Airtime" -> stringResource(R.string.ui_airtime); "Income" -> stringResource(R.string.ui_income)
    "Fees" -> stringResource(R.string.ui_fees); "Own transfer" -> stringResource(R.string.ui_own_transfer)
    "Other" -> stringResource(R.string.ui_other)
    else -> category
}

/** "English" / "Kiswahili" choices, named in the interface language. */
@Composable
fun languageLabel(name: String): String = when (name) {
    "English" -> stringResource(R.string.ui_english); "Kiswahili" -> stringResource(R.string.ui_kiswahili); else -> name
}

/** Why a skill was suggested; reasons are stored with the skill, so older rows keep their English text as stored. */
@Composable
fun skillReason(reason: String): String = when (reason) {
    "You chose Remember my account and category after reviewing a payment." -> stringResource(R.string.ui_you_chose_remember_my_account_and)
    "You confirmed this account/category while reviewing a payment. Review before applying to later matches." -> stringResource(R.string.ui_you_confirmed_this_account_category)
    "You marked this captured item as not a payment. An exact-match rule can avoid repeating this false suggestion." -> stringResource(R.string.ui_you_marked_this_captured_item_as_not_a)
    "You marked this proposal as not a task. Review this exact-match rule before using it." -> stringResource(R.string.ui_you_marked_this_proposal_as_not_a_task)
    "You rejected this task in Daily review. Review this rule before using it." -> stringResource(R.string.ui_you_rejected_this_task_in_daily_review)
    "You corrected this memory. Review whether the correction should guide future related answers." -> stringResource(R.string.ui_you_corrected_this_memory_review_whether)
    "You saved this source excerpt for future reference. Review whether it should guide related answers." -> stringResource(R.string.ui_you_saved_this_source_excerpt_for_future)
    else -> reason
}
