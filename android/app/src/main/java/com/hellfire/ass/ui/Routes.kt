package com.hellfire.ass.ui

import androidx.compose.ui.res.stringResource
import com.hellfire.ass.R

import androidx.compose.runtime.Composable
import com.hellfire.ass.ui.ask.AskTab
import com.hellfire.ass.ui.ask.MemoryPage
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.home.HomeScreen
import com.hellfire.ass.ui.inbox.*
import com.hellfire.ass.ui.money.*
import com.hellfire.ass.ui.tasks.*
import com.hellfire.ass.ui.theme.HfIcons

/** Content of the five tabs. */
@Composable
fun TabContent(tab: MainTab) {
    when (tab) {
        MainTab.Home -> HomeScreen()
        MainTab.Money -> MoneyScreen()
        MainTab.Ask -> AskTab()
        MainTab.Tasks -> TasksTab()
        MainTab.Inbox -> InboxTab()
    }
}

/** Content of a pushed screen. */
@Composable
fun RouteContent(route: Route) {
    val env = LocalEnv.current
    val nav = LocalNav.current
    val a = env.actions
    when (route.screen) {
        Screen.Skills -> com.hellfire.ass.ui.settings.SkillsPage()
        Screen.Recovery -> com.hellfire.ass.ui.settings.RecoveryPage()
        Screen.GuidedSetup -> com.hellfire.ass.ui.settings.GuidedSetupPage()
        Screen.Privacy -> com.hellfire.ass.ui.settings.PrivacyPage()
        Screen.Setup -> com.hellfire.ass.ui.settings.SetupScreen()
        Screen.Guard -> com.hellfire.ass.guard.GuardPage()
        Screen.Settings -> com.hellfire.ass.ui.settings.SettingsHub()
        Screen.Permissions -> com.hellfire.ass.ui.settings.PermissionsPage()
        Screen.CloudAi -> PushedPage(if (route.arg == "budget") "Monthly AI budget" else "Cloud AI", breadcrumb = "Settings") { AiSettingsScreen(env.app, budgetOnly = route.arg == "budget") }
        Screen.LocalModels -> PushedPage(stringResource(R.string.ui_on_device_models), breadcrumb = "Settings") { SectionCard("Language model and offline reading") { LocalAiScreen(env.app) }; ModelExtractionSection() }
        Screen.Recordings -> PushedPage(stringResource(R.string.ui_recordings), breadcrumb = "Settings") {
            MediaScreen(env.app, env.permissions, a.onMicrophone)
            SectionCard("60-second microphone test") { MicTestSection(env.app, env.permissions, a.onMicrophone, a.onSettings) }
        }
        Screen.MediaItem -> PushedPage("Recording", breadcrumb = "Inbox") { MediaScreen(env.app, env.permissions, a.onMicrophone, route.arg) }
        Screen.Backup -> PushedPage(stringResource(R.string.ui_encrypted_backup), breadcrumb = "Settings") { BackupScreen(env.app) }
        Screen.Device -> com.hellfire.ass.ui.settings.DevicePage()
        Screen.Performance -> com.hellfire.ass.ui.PerfScreen()
        Screen.WebAccess -> com.hellfire.ass.ui.settings.WebAccessPage()
        Screen.Reminders -> RemindersPage()
        Screen.Email -> EmailPage()
        Screen.WhatsApp -> WhatsAppPage()
        Screen.Memory -> MemoryPage(route.arg)
        Screen.Accounts -> AccountsPage(focusAdd = route.arg == "add")
        Screen.Transaction -> TransactionPage(route.arg)
        Screen.Source -> SourcePage(route.arg)
        Screen.Task -> TaskPage(route.arg)
        Screen.Message -> MessagePage(route.arg)
        Screen.Budgets -> BudgetsPage()
        Screen.Widgets -> com.hellfire.ass.ui.settings.WidgetsPage()
        Screen.Reports -> com.hellfire.ass.ui.reports.ReportsHub()
        Screen.ReportMoney -> com.hellfire.ass.ui.reports.MoneyReport(route.arg)
        Screen.ReportCategory -> com.hellfire.ass.ui.reports.CategoryReport(route.arg)
        Screen.ReportAccounts -> com.hellfire.ass.ui.reports.AccountsReport()
        Screen.ReportStatement -> com.hellfire.ass.ui.reports.StatementReport(route.arg)
        Screen.ReportCapture -> com.hellfire.ass.ui.reports.CaptureReport(route.arg)
        Screen.FixSms -> com.hellfire.ass.ui.reports.FixSmsPage(route.arg)
        Screen.ReportTasks -> com.hellfire.ass.ui.reports.TasksReport()
        Screen.ReportMessages -> com.hellfire.ass.ui.reports.MessagesReport()
        Screen.ReportMedia -> com.hellfire.ass.ui.reports.MediaReport()
        Screen.ReportAi -> com.hellfire.ass.ui.reports.AiReport()
        Screen.Language -> LanguagePage()
        Screen.Reconcile -> ReconcilePage()
        Screen.Review -> ReviewPage()
        Screen.Share -> SharePage()
        Screen.Brief -> com.hellfire.ass.ui.insights.BriefPage()
        Screen.Waiting -> com.hellfire.ass.ui.insights.WaitingPage()
        Screen.People -> com.hellfire.ass.ui.insights.PeoplePage()
        Screen.Person -> com.hellfire.ass.ui.insights.PersonPage(route.arg)
        Screen.Debts -> com.hellfire.ass.ui.insights.DebtsPage()
        Screen.Bills -> com.hellfire.ass.ui.insights.BillsPage()
        Screen.Templates -> com.hellfire.ass.ui.templates.TemplatesPage()
        Screen.TemplateEdit -> com.hellfire.ass.ui.templates.TemplateEditPage(route.arg)
        Screen.Library -> com.hellfire.ass.ui.library.LibraryPage(route)
        Screen.MediaDetail -> com.hellfire.ass.ui.library.MediaDetailPage(route)
        Screen.Player -> com.hellfire.ass.ui.player.PlayerScreen(com.hellfire.ass.library.LibraryRoutes.id(route), com.hellfire.ass.library.LibraryRoutes.startMs(route))
        Screen.UniversalInbox -> com.hellfire.ass.ui.drawer.UniversalInboxPage()
        Screen.InboxItem -> com.hellfire.ass.ui.drawer.InboxItemPage(com.hellfire.ass.library.LibraryRoutes.id(route))
        Screen.Search -> com.hellfire.ass.ui.intelligence.SearchScreen(route)
        Screen.ShareWithAi -> com.hellfire.ass.ui.sharing.ShareWithAiPage(route)
        Screen.ProcessingQueue -> com.hellfire.ass.ui.intelligence.ProcessingQueueScreen()
        Screen.SyncDashboard -> com.hellfire.ass.ui.sync.SyncDashboardPage()
        Screen.BackupDashboard -> PushedPage(stringResource(R.string.ui_backup_dashboard), breadcrumb = "Settings") { BackupDashboardScreen(env.app) }
        Screen.StatusDashboard -> PushedPage(stringResource(R.string.ui_status_dashboard), breadcrumb = "Settings") { StatusDashboardScreen(env.app) }
        Screen.MediaSettings -> com.hellfire.ass.ui.intelligence.MediaSettingsScreen()
        Screen.News -> com.hellfire.ass.ui.news.NewsScreen()
        Screen.NewsReader -> com.hellfire.ass.ui.news.NewsReaderPage(route.arg)
        Screen.NewsStory -> com.hellfire.ass.ui.news.NewsStoryPage(route.arg)
        Screen.NewsSettings -> com.hellfire.ass.ui.news.NewsSettingsPage()
        Screen.NewsSources -> com.hellfire.ass.ui.news.NewsSourcesPage()
        Screen.NewsInterests -> com.hellfire.ass.ui.news.NewsInterestsPage()
        Screen.NewsHealth -> com.hellfire.ass.ui.news.NewsHealthPage()
        else -> PushedPage(route.screen.name) { Note("This screen is not available yet.") }
    }
}
