package com.hellfire.ass.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.PermissionStatus
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.theme.HellFireTheme
import com.hellfire.ass.ui.theme.Hf
import com.hellfire.ass.ui.theme.HfIcons

enum class MainTab(val label: String, val icon: ImageVector) {
    Home("Home", HfIcons.Home), Money("Money", HfIcons.Money), Ask("Ask", HfIcons.Spark),
    Tasks("Tasks", HfIcons.Task), Inbox("Inbox", HfIcons.Inbox),
}

/** Screens pushed over the tabs: back arrow, no bottom bar. */
enum class Screen {
    Setup, Settings, Permissions, CloudAi, LocalModels, Email, WhatsApp, Backup, Device, Recordings, Reminders, Memory, Widgets, WebAccess,
    Accounts, Budgets, Transaction, Source, Task, Message, MediaItem,
    Reports, ReportMoney, ReportCategory, ReportAccounts, ReportStatement, ReportCapture, FixSms, ReportTasks, ReportMessages, ReportMedia, ReportAi,
    Brief, Waiting, People, Person, Debts, Bills, Templates, TemplateEdit, Review, Share, Reconcile, Language, Skills, Recovery, GuidedSetup, Privacy,
    // Media intelligence (schema v18). Args are built and parsed only through com.hellfire.ass.library.LibraryRoutes.
    Library, MediaDetail, Player, UniversalInbox, InboxItem, Search, ShareWithAi, ProcessingQueue, SyncDashboard, BackupDashboard, StatusDashboard, MediaSettings,
}

/** A pushed screen plus one string argument (an id, or an encoded filter). */
data class Route(val screen: Screen, val arg: String = "")

/** Callbacks into the activity for Android permission and settings flows. */
data class PermissionActions(
    val onRole: () -> Unit, val onSettings: (String) -> Unit, val onMicrophone: () -> Unit, val onSession: () -> Unit,
    val onNotifications: () -> Unit, val onSmsEnable: () -> Unit, val onSmsDisable: () -> Unit,
    val onAppLockChange: (Boolean) -> Unit,
)

class AppEnv(val app: HellFireApp, val permissions: PermissionStatus, val actions: PermissionActions)

class Nav(private val stack: SnapshotStateList<Route>, private val tabState: MutableState<MainTab>) {
    /** One-shot request for the visible screen, e.g. "cash" to open the Add cash sheet. Consumed by the screen. */
    var action by mutableStateOf<String?>(null)
    val tab get() = tabState.value
    val top get() = stack.lastOrNull()
    fun push(route: Route) { stack.add(route) }
    fun push(screen: Screen, arg: String = "") = push(Route(screen, arg))
    fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    fun tab(tab: MainTab, action: String? = null) { stack.clear(); tabState.value = tab; this.action = action }
    fun consume(expected: String): Boolean = (action == expected).also { if (it) action = null }
    fun consumePrefix(prefix: String): String? = action?.takeIf { it.startsWith(prefix) }?.also { action = null }?.removePrefix(prefix)

    /** Deep links from widgets, notifications and the assist gesture: hellfire://host/path. */
    fun open(uri: Uri) {
        val parts = uri.pathSegments.orEmpty()
        when (uri.host) {
            "home" -> tab(MainTab.Home)
            "review" -> { tab(MainTab.Home); push(Screen.Review) }
            "share" -> { tab(MainTab.Home); push(Screen.Share) }
            "brief" -> { tab(MainTab.Home); push(Screen.Brief) }
            "templates" -> { tab(MainTab.Home); push(Screen.Templates) }
            "people" -> { tab(MainTab.Home); push(Screen.People) }
            "waiting" -> { tab(MainTab.Inbox); push(Screen.Waiting) }
            "debts" -> { tab(MainTab.Money); push(Screen.Debts) }
            "bills" -> { tab(MainTab.Money); push(Screen.Bills) }
            "ask" -> tab(MainTab.Ask, if (uri.getQueryParameter("voice") == "1") "voice" else null)
            "money" -> when (parts.firstOrNull()) {
                "cash" -> tab(MainTab.Money, "cash")
                "review" -> tab(MainTab.Money, "review")
                "accounts" -> { tab(MainTab.Money); push(Screen.Accounts) }
                "budgets" -> { tab(MainTab.Money); push(Screen.Budgets) }
                else -> tab(MainTab.Money)
            }
            "tasks" -> tab(MainTab.Tasks)
            "task" -> { tab(MainTab.Tasks); parts.firstOrNull()?.let { push(Screen.Task, it) } }
            "inbox" -> tab(MainTab.Inbox)
            "draft" -> tab(MainTab.Inbox, parts.firstOrNull()?.let { "draft:$it" })
            "message" -> { tab(MainTab.Inbox); parts.firstOrNull()?.let { push(Screen.Message, it) } }
            "note" -> { tab(MainTab.Ask); push(Screen.Memory, "new") }
            "settings" -> { tab(MainTab.Home); push(Screen.Settings); when (parts.firstOrNull()) { "permissions" -> push(Screen.Permissions); "recordings" -> push(Screen.Recordings) } }
            "report" -> { tab(MainTab.Home); push(Screen.Reports)
                when (parts.firstOrNull()) {
                    "money" -> uri.getQueryParameter("category")?.let { push(Screen.ReportCategory, it) } ?: push(Screen.ReportMoney)
                    else -> {}
                } }
        }
    }
}

val LocalNav = staticCompositionLocalOf<Nav> { error("No Nav") }
val LocalEnv = staticCompositionLocalOf<AppEnv> { error("No AppEnv") }

private val routeSaver = listSaver<SnapshotStateList<Route>, String>(
    save = { list -> list.map { "${it.screen.name}\u0001${it.arg}" } },
    restore = { saved -> saved.mapNotNull { s -> runCatching { Route(Screen.valueOf(s.substringBefore('\u0001')), s.substringAfter('\u0001')) }.getOrNull() }.toMutableStateList() },
)

/** Root of the redesigned app: storage gate, 5-tab scaffold, pushed screens and deep links. */
@Composable
fun HellFireRoot(app: HellFireApp, permissions: PermissionStatus, actions: PermissionActions, initialTab: MainTab,
    deepLink: Uri?, onDeepLinkHandled: () -> Unit, message: String?, onMessageShown: () -> Unit) {
    val storage by app.storageStatus.collectAsStateWithLifecycle()
    val draftError by PrivateDrafts.error.collectAsStateWithLifecycle()
    HellFireTheme {
        if (storage != "READY") {
            Surface(Modifier.fillMaxSize(), color = Hf.colors.background) {
                Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("HellFire Ass", style = Hf.type.screenTitle, color = Hf.colors.onSurface)
                    Text(if (storage == "STARTING") "Opening encrypted local storage…" else "Encrypted storage is unavailable. Check free storage and device unlock/key availability. Existing files have been retained; no plaintext fallback or automatic reset was used.",
                        style = Hf.type.body, color = Hf.colors.onSurface)
                    if (storage != "STARTING") PrimaryButton("Retry local storage", { app.initializeStorage() })
                }
            }
            return@HellFireTheme
        }
        val tabState = rememberSaveable { mutableStateOf(initialTab) }
        val stack = rememberSaveable(saver = routeSaver) {
            val firstRun = !app.getSharedPreferences("ui", 0).getBoolean("setup_done", false)
            if (firstRun) mutableStateListOf(Route(Screen.GuidedSetup)) else mutableStateListOf()
        }
        val nav = remember { Nav(stack, tabState) }
        val env = AppEnv(app, permissions, actions)
        LaunchedEffect(message) { if (message != null) { kotlinx.coroutines.delay(6000); onMessageShown() } }
        LaunchedEffect(deepLink) { if (deepLink != null) { nav.open(deepLink); onDeepLinkHandled() } }
        val route = stack.lastOrNull()
        BackHandler(enabled = route != null) { nav.pop() }
        BackHandler(enabled = route == null && tabState.value != MainTab.Home) { nav.tab(MainTab.Home) }
        CompositionLocalProvider(LocalNav provides nav, LocalEnv provides env) {
            Scaffold(
                containerColor = Hf.colors.background,
                contentWindowInsets = WindowInsets.safeDrawing,
                bottomBar = { if (route == null) HellFireBottomBar(tabState.value) { nav.tab(it) } },
            ) { inner ->
                Box(Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner).imePadding()) {
                    key(tabState.value, route, stack.size) {
                        if (route == null) TabContent(tabState.value) else RouteContent(route)
                    }
                    draftError?.let { Banner(it, Tone.Danger, HfIcons.Info, Modifier.align(Alignment.TopCenter).padding(16.dp)) }
                    if (message != null) Banner(message, Tone.Info, HfIcons.Info,
                        Modifier.align(Alignment.TopCenter).padding(horizontal = ScreenPadding, vertical = 8.dp).clickableRow(onMessageShown))
                    com.hellfire.ass.ui.drawer.DrawerHost()
                }
            }
        }
    }
}

/** Standard scrolling page body with screen padding and section gaps. */
@Composable
fun ScrollPage(modifier: Modifier = Modifier, bottomInset: Dp = 24.dp, gap: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = ScreenPadding, end = ScreenPadding, top = 12.dp, bottom = bottomInset),
        verticalArrangement = Arrangement.spacedBy(gap), content = content,
    )
}

/** Pushed page with the back arrow top bar. */
@Composable
fun PushedPage(title: String, breadcrumb: String? = null, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    val nav = LocalNav.current
    ScrollPage {
        PushedTopBar(title, onBack = { nav.pop() }, breadcrumb = breadcrumb, actions = actions)
        content()
    }
}

@Composable
private fun HellFireBottomBar(current: MainTab, onSelect: (MainTab) -> Unit) {
    val c = Hf.colors
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(
        Modifier.fillMaxWidth().background(c.surface).drawBehind {
            drawLine(c.outline, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
        },
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 6.dp, end = 6.dp, top = 6.dp).selectableGroup(),
            horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.Top,
        ) {
            MainTab.entries.forEach { t ->
                val on = t == current
                val tint = if (on) c.primary else c.muted
                Column(
                    Modifier.weight(1f).heightIn(min = 52.dp)
                        .selectable(selected = on, role = Role.Tab, interactionSource = null, indication = null) { onSelect(t) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // The 54 dp Ask button is raised 20 dp: it occupies a 34 dp slot and overflows above the bar.
                    if (t == MainTab.Ask) Box(Modifier.size(width = 56.dp, height = 34.dp)) {
                        Box(
                            Modifier.align(Alignment.BottomCenter).offset(y = (-10).dp).requiredSize(54.dp)
                                .shadow(14.dp, CircleShape, ambientColor = c.primary, spotColor = c.primary)
                                .clip(CircleShape).background(c.primary),
                            contentAlignment = Alignment.Center,
                        ) { Icon(t.icon, null, Modifier.size(22.dp), tint = c.onPrimary) }
                    }
                    else Box(
                        Modifier.size(width = 56.dp, height = 30.dp).clip(CircleShape).background(if (on) c.primaryContainer else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) { Icon(t.icon, null, Modifier.size(22.dp), tint = tint) }
                    Text(tr(t.label), style = Hf.type.caption, color = tint)
                }
            }
        }
        Spacer(Modifier.height(maxOf(navInset, 20.dp)))
    }
}
