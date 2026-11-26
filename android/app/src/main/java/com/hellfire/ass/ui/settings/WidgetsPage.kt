package com.hellfire.ass.ui.settings

import androidx.compose.ui.res.stringResource
import com.hellfire.ass.R

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import com.hellfire.ass.ui.*
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.money.Categories
import com.hellfire.ass.ui.money.ChipPicker
import com.hellfire.ass.widgets.WidgetPrefs
import com.hellfire.ass.widgets.AskReceiver
import com.hellfire.ass.widgets.AskShortcutReceiver
import com.hellfire.ass.widgets.BriefingReceiver
import com.hellfire.ass.widgets.BudgetReceiver
import com.hellfire.ass.widgets.CaptureHealthReceiver
import com.hellfire.ass.widgets.CashShortcutReceiver
import com.hellfire.ass.widgets.MoneyTodayReceiver
import com.hellfire.ass.widgets.NoteShortcutReceiver
import com.hellfire.ass.widgets.PromisesReceiver
import com.hellfire.ass.widgets.RecordReceiver
import com.hellfire.ass.widgets.RepliesReceiver
import com.hellfire.ass.widgets.ReviewReceiver
import com.hellfire.ass.widgets.WalletsReceiver

private val pinnable = listOf(
    "Daily briefing" to BriefingReceiver::class.java, "Money today" to MoneyTodayReceiver::class.java,
    "Promises" to PromisesReceiver::class.java, "Wallets" to WalletsReceiver::class.java,
    "Replies waiting" to RepliesReceiver::class.java, "Ask HellFire" to AskReceiver::class.java,
    "Record" to RecordReceiver::class.java, "Nightly review" to ReviewReceiver::class.java,
    "Budget" to BudgetReceiver::class.java, "Capture health" to CaptureHealthReceiver::class.java,
    "Ask by voice" to AskShortcutReceiver::class.java, "Add cash" to CashShortcutReceiver::class.java,
    "Quick note" to NoteShortcutReceiver::class.java,
)

/** Settings › Home screen widgets (not drawn): privacy for amounts and previews, and the Budget widget's category. */
@Composable
fun WidgetsPage() {
    val app = LocalEnv.current.app
    var hideAll by remember { mutableStateOf(WidgetPrefs.hideAll(app)) }
    val shows = remember { mutableStateMapOf<String, Boolean>().apply { WidgetPrefs.widgets.forEach { (id, _) -> put(id, WidgetPrefs.shows(app, id)) } } }
    var budget by remember { mutableStateOf(WidgetPrefs.budgetCategory(app)) }
    PushedPage(stringResource(R.string.ui_home_screen_widgets), breadcrumb = "Settings") {
        Note("Anyone holding your unlocked phone can see the home screen. Amounts and message previews start hidden (•••••). Widgets never send or post anything; replies, names and reviews open here for your approval.")
        HfCard {
            SwitchRow("Hide all amounts", hideAll, { hideAll = it; WidgetPrefs.setHideAll(app, it); app.requestWidgetRefresh() },
                subtitle = "Overrides every widget below")
        }
        GroupLabel("Show amounts and previews on")
        HfCard {
            WidgetPrefs.widgets.forEachIndexed { i, (id, label) ->
                if (i > 0) ListDivider(16.dp)
                SwitchRow(label, shows[id] == true, { shows[id] = it; WidgetPrefs.setShows(app, id, it); app.requestWidgetRefresh() },
                    subtitle = if (id == "wallets") "Wallet balances still need a tap and hide again after a minute" else null, enabled = !hideAll)
            }
        }
        SectionCard("Budget widget") {
            Note("Category shown on the Budget widget. Set limits in Money › Budgets.")
            ChipPicker(Categories.filter { it != "Income" }, budget, { categoryLabel(it) }) { budget = it; WidgetPrefs.setBudgetCategory(app, it); app.requestWidgetRefresh() }
        }
        GroupLabel("Add to home screen")
        val manager = remember { android.appwidget.AppWidgetManager.getInstance(app) }
        HfCard {
            pinnable.forEachIndexed { i, (label, receiver) ->
                if (i > 0) ListDivider(16.dp)
                ListRow(label, trailing = {
                    QuietButton("Add", {
                        if (manager.isRequestPinAppWidgetSupported) manager.requestPinAppWidget(android.content.ComponentName(app, receiver), null, null)
                    }, enabled = manager.isRequestPinAppWidgetSupported)
                })
            }
        }
        if (!manager.isRequestPinAppWidgetSupported) Note("This launcher does not let apps add widgets. Long-press the home screen and choose Widgets instead.")
        Note("Widgets refresh when your payments, tasks, messages or recordings change, not on a timer. Each one shows when it was last updated. The Record widget asks for consent the first time in a session and always shows the recording notification.")
    }
}
