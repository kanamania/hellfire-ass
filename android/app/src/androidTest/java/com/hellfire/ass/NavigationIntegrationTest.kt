package com.hellfire.ass

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class NavigationIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun tabsLibraryAndSettingsRemainNavigable() {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithContentDescription("Settings").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("Finish setup later").fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText("Finish setup later").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("Finish setup later").performScrollTo().performClick()
        }
        for (tab in listOf("Money", "Ask", "Tasks", "Inbox", "Home")) {
            compose.onNodeWithText(tab, useUnmergedTree = true).performClick()
            compose.waitForIdle()
        }
        compose.onNodeWithText("Library", useUnmergedTree = true).performClick()
        compose.onNodeWithText("File name").assertExists()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onAllNodesWithText("Settings", useUnmergedTree = true).onFirst().assertExists()
    }
}
