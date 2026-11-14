package com.hellfire.ass.ui

import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.theme.HfIcons

/** Content of the five tabs. */
@Composable
fun TabContent(tab: MainTab) {
    val env = LocalEnv.current
    val nav = LocalNav.current
    when (tab) {
        MainTab.Home -> ScrollPage {
            ScreenHeader("HellFire") { IconCircleButton(HfIcons.Settings, "Settings", { nav.push(Screen.Settings) }) }
        }
        MainTab.Money -> ScrollPage { ScreenHeader("Money"); FinanceScreen(env.app) }
        MainTab.Ask -> ScrollPage {
            ScreenHeader("Ask"); AssistantScreen(env.app, env.actions.onSession); HorizontalDivider(); LegacyAssistant(env.app, env.actions.onSession)
        }
        MainTab.Tasks -> ScrollPage { ScreenHeader("Tasks"); TasksScreen(env.app, env.actions.onSettings, env.actions.onNotifications) }
        MainTab.Inbox -> ScrollPage { ScreenHeader("Inbox"); CommunicationsScreen(env.app) }
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
                ListRow("Cloud AI", icon = HfIcons.Cloud, tone = Tone.Info, chevron = true, onClick = { nav.push(Screen.CloudAi) })
                ListDivider()
                ListRow("On-device models", icon = HfIcons.Chip, tone = Tone.Success, chevron = true, onClick = { nav.push(Screen.LocalModels) })
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
        Screen.LocalModels -> PushedPage("On-device models") { LocalAiScreen(env.app) }
        Screen.Recordings -> PushedPage("Recordings") { MediaScreen(env.app, env.permissions, a.onMicrophone) }
        Screen.Backup -> PushedPage("Encrypted backup") { BackupScreen(env.app) }
        Screen.Device -> PushedPage("This device") { LegacyDiagnostics(env.permissions) }
        else -> PushedPage(route.screen.name) { Note("This screen is not available yet.") }
    }
}
