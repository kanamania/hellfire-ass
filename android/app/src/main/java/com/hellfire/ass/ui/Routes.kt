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
import com.hellfire.ass.guard.GuardPage
import com.hellfire.ass.library.LibraryRoutes
import com.hellfire.ass.ui.drawer.InboxItemPage
import com.hellfire.ass.ui.drawer.UniversalInboxPage
import com.hellfire.ass.ui.insights.BillsPage
import com.hellfire.ass.ui.insights.BriefPage
import com.hellfire.ass.ui.insights.DebtsPage
import com.hellfire.ass.ui.insights.PeoplePage
import com.hellfire.ass.ui.insights.PersonPage
import com.hellfire.ass.ui.insights.WaitingPage
import com.hellfire.ass.ui.intelligence.MediaSettingsScreen
import com.hellfire.ass.ui.intelligence.ProcessingQueueScreen
import com.hellfire.ass.ui.intelligence.SearchScreen
import com.hellfire.ass.ui.library.LibraryPage
import com.hellfire.ass.ui.library.MediaDetailPage
import com.hellfire.ass.ui.news.NewsHealthPage
import com.hellfire.ass.ui.news.NewsInterestsPage
import com.hellfire.ass.ui.news.NewsReaderPage
import com.hellfire.ass.ui.news.NewsScreen
import com.hellfire.ass.ui.news.NewsSettingsPage
import com.hellfire.ass.ui.news.NewsSourcesPage
import com.hellfire.ass.ui.news.NewsStoryPage
import com.hellfire.ass.ui.player.PlayerScreen
import com.hellfire.ass.ui.reports.AccountsReport
import com.hellfire.ass.ui.reports.AiReport
import com.hellfire.ass.ui.reports.CaptureReport
import com.hellfire.ass.ui.reports.CategoryReport
import com.hellfire.ass.ui.reports.FixSmsPage
import com.hellfire.ass.ui.reports.MediaReport
import com.hellfire.ass.ui.reports.MessagesReport
import com.hellfire.ass.ui.reports.MoneyReport
import com.hellfire.ass.ui.reports.ReportsHub
import com.hellfire.ass.ui.reports.StatementReport
import com.hellfire.ass.ui.reports.TasksReport
import com.hellfire.ass.ui.settings.DevicePage
import com.hellfire.ass.ui.settings.GuidedSetupPage
import com.hellfire.ass.ui.settings.PermissionsPage
import com.hellfire.ass.ui.settings.PrivacyPage
import com.hellfire.ass.ui.settings.RecoveryPage
import com.hellfire.ass.ui.settings.SettingsHub
import com.hellfire.ass.ui.settings.SetupScreen
import com.hellfire.ass.ui.settings.SkillsPage
import com.hellfire.ass.ui.settings.WebAccessPage
import com.hellfire.ass.ui.settings.WidgetsPage
import com.hellfire.ass.ui.sharing.ShareWithAiPage
import com.hellfire.ass.ui.sync.SyncDashboardPage
import com.hellfire.ass.ui.templates.TemplateEditPage
import com.hellfire.ass.ui.templates.TemplatesPage

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
        Screen.Skills -> SkillsPage()
        Screen.Recovery -> RecoveryPage()
        Screen.GuidedSetup -> GuidedSetupPage()
        Screen.Privacy -> PrivacyPage()
        Screen.Setup -> SetupScreen()
        Screen.Guard -> GuardPage()
        Screen.Settings -> SettingsHub()
        Screen.Permissions -> PermissionsPage()
        Screen.CloudAi -> PushedPage(if (route.arg == "budget") "Monthly AI budget" else "Cloud AI", breadcrumb = "Settings") { AiSettingsScreen(env.app, budgetOnly = route.arg == "budget") }
        Screen.LocalModels -> PushedPage(stringResource(R.string.ui_on_device_models), breadcrumb = "Settings") { SectionCard("Language model and offline reading") { LocalAiScreen(env.app) }; ModelExtractionSection() }
        Screen.Recordings -> PushedPage(stringResource(R.string.ui_recordings), breadcrumb = "Settings") {
            MediaScreen(env.app, env.permissions, a.onMicrophone)
            SectionCard("60-second microphone test") { MicTestSection(env.app, env.permissions, a.onMicrophone, a.onSettings) }
        }
        Screen.MediaItem -> PushedPage("Recording", breadcrumb = "Inbox") { MediaScreen(env.app, env.permissions, a.onMicrophone, route.arg) }
        Screen.Backup -> PushedPage(stringResource(R.string.ui_encrypted_backup), breadcrumb = "Settings") { BackupScreen(env.app) }
        Screen.Device -> DevicePage()
        Screen.Performance -> PerfScreen()
        Screen.WebAccess -> WebAccessPage()
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
        Screen.Widgets -> WidgetsPage()
        Screen.Reports -> ReportsHub()
        Screen.ReportMoney -> MoneyReport(route.arg)
        Screen.ReportCategory -> CategoryReport(route.arg)
        Screen.ReportAccounts -> AccountsReport()
        Screen.ReportStatement -> StatementReport(route.arg)
        Screen.ReportCapture -> CaptureReport(route.arg)
        Screen.FixSms -> FixSmsPage(route.arg)
        Screen.ReportTasks -> TasksReport()
        Screen.ReportMessages -> MessagesReport()
        Screen.ReportMedia -> MediaReport()
        Screen.ReportAi -> AiReport()
        Screen.Language -> LanguagePage()
        Screen.Reconcile -> ReconcilePage()
        Screen.Review -> ReviewPage()
        Screen.Share -> SharePage()
        Screen.Brief -> BriefPage()
        Screen.Waiting -> WaitingPage()
        Screen.People -> PeoplePage()
        Screen.Person -> PersonPage(route.arg)
        Screen.Debts -> DebtsPage()
        Screen.Bills -> BillsPage()
        Screen.Templates -> TemplatesPage()
        Screen.TemplateEdit -> TemplateEditPage(route.arg)
        Screen.Library -> LibraryPage(route)
        Screen.MediaDetail -> MediaDetailPage(route)
        Screen.Player -> PlayerScreen(LibraryRoutes.id(route), LibraryRoutes.startMs(route))
        Screen.UniversalInbox -> UniversalInboxPage()
        Screen.InboxItem -> InboxItemPage(LibraryRoutes.id(route))
        Screen.Search -> SearchScreen(route)
        Screen.ShareWithAi -> ShareWithAiPage(route)
        Screen.ProcessingQueue -> ProcessingQueueScreen()
        Screen.SyncDashboard -> SyncDashboardPage()
        Screen.BackupDashboard -> PushedPage(stringResource(R.string.ui_backup_dashboard), breadcrumb = "Settings") { BackupDashboardScreen(env.app) }
        Screen.StatusDashboard -> PushedPage(stringResource(R.string.ui_status_dashboard), breadcrumb = "Settings") { StatusDashboardScreen(env.app) }
        Screen.MediaSettings -> MediaSettingsScreen()
        Screen.News -> NewsScreen()
        Screen.NewsReader -> NewsReaderPage(route.arg)
        Screen.NewsStory -> NewsStoryPage(route.arg)
        Screen.NewsSettings -> NewsSettingsPage()
        Screen.NewsSources -> NewsSourcesPage()
        Screen.NewsInterests -> NewsInterestsPage()
        Screen.NewsHealth -> NewsHealthPage()
        else -> PushedPage(route.screen.name) { Note("This screen is not available yet.") }
    }
}
