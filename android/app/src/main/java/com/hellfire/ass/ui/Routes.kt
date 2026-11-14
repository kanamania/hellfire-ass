package com.hellfire.ass.ui

import androidx.compose.material3.HorizontalDivider
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
        Screen.Setup -> PushedPage("Set up HellFire") {
            LegacySetup(env.permissions, a.onRole, a.onSettings, a.onMicrophone, a.onSmsEnable, a.onSmsDisable)
            PrimaryButton("Continue", { env.app.getSharedPreferences("ui", 0).edit().putBoolean("setup_done", true).apply(); nav.pop() })
        }
        Screen.Settings -> PushedPage("Settings") {
            ListCard {
                ListRow("Permissions and capture", icon = HfIcons.Lock, chevron = true, onClick = { nav.push(Screen.Permissions) })
                ListDivider()
                ListRow("Reminders", icon = HfIcons.Alarm, chevron = true, onClick = { nav.push(Screen.Reminders) })
                ListDivider()
                ListRow("Cloud AI", icon = HfIcons.Cloud, tone = Tone.Info, chevron = true, onClick = { nav.push(Screen.CloudAi) })
                ListDivider()
                ListRow("On-device models", icon = HfIcons.Chip, tone = Tone.Success, chevron = true, onClick = { nav.push(Screen.LocalModels) })
                ListDivider()
                ListRow("Email", icon = HfIcons.Mail, tone = Tone.Info, chevron = true, onClick = { nav.push(Screen.Email) })
                ListDivider()
                ListRow("WhatsApp", icon = HfIcons.Chat, tone = Tone.Success, chevron = true, onClick = { nav.push(Screen.WhatsApp) })
                ListDivider()
                ListRow("Memory and proposals", icon = HfIcons.Key, tone = Tone.Neutral, chevron = true, onClick = { nav.push(Screen.Memory) })
                ListDivider()
                ListRow("Recordings", icon = HfIcons.Waveform, tone = Tone.Neutral, chevron = true, onClick = { nav.push(Screen.Recordings) })
                ListDivider()
                ListRow("Encrypted backup", icon = HfIcons.Key, tone = Tone.Neutral, chevron = true, onClick = { nav.push(Screen.Backup) })
                ListDivider()
                ListRow("This device", icon = HfIcons.Phone, tone = Tone.Neutral, chevron = true, onClick = { nav.push(Screen.Device) })
            }
        }
        Screen.Permissions -> PushedPage("Permissions and capture") {
            LegacySetup(env.permissions, a.onRole, a.onSettings, a.onMicrophone, a.onSmsEnable, a.onSmsDisable)
            HorizontalDivider()
            LegacyCapture(env.app, env.permissions, a.onMicrophone, a.onSettings)
        }
        Screen.CloudAi -> PushedPage("Cloud AI") { AiSettingsScreen(env.app) }
        Screen.LocalModels -> PushedPage("On-device models") { LocalAiScreen(env.app); ModelExtractionSection() }
        Screen.Recordings -> PushedPage("Recordings") { MediaScreen(env.app, env.permissions, a.onMicrophone) }
        Screen.MediaItem -> PushedPage("Recording", breadcrumb = "Inbox") { MediaScreen(env.app, env.permissions, a.onMicrophone, route.arg) }
        Screen.Backup -> PushedPage("Encrypted backup") { BackupScreen(env.app) }
        Screen.Device -> PushedPage("This device") {
            LegacyDiagnostics(env.permissions)
            SecondaryButton("Test the assistant session", a.onSession)
        }
        Screen.Reminders -> RemindersPage()
        Screen.Email -> EmailPage()
        Screen.WhatsApp -> WhatsAppPage()
        Screen.Memory -> MemoryPage(route.arg)
        Screen.Accounts -> AccountsPage()
        Screen.Transaction -> TransactionPage(route.arg)
        Screen.Source -> SourcePage(route.arg)
        Screen.Task -> TaskPage(route.arg)
        Screen.Message -> MessagePage(route.arg)
        else -> PushedPage(route.screen.name) { Note("This screen is not available yet.") }
    }
}
