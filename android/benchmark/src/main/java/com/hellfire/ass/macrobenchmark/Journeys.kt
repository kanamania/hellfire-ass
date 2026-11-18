package com.hellfire.ass.macrobenchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector

internal const val TARGET = "com.hellfire.ass.benchmark"

/** Requires the separate benchmark app's English setup to be completed, offline, with app lock off. */
internal fun MacrobenchmarkScope.awaitHome() {
    check(device.wait(Until.hasObject(By.desc("Settings")), 30_000)) {
        "Finish setup in HellFire Ass benchmark, use English, turn app lock off, and return Home."
    }
}

internal fun MacrobenchmarkScope.browseNews() {
    val home = UiScrollable(UiSelector().scrollable(true))
    check(home.scrollTextIntoView("News")) { "News entry did not appear" }
    checkNotNull(device.findObject(By.text("News"))).click()
    check(device.wait(Until.hasObject(By.desc("News settings")), 10_000)) { "News did not open" }
    for (tab in listOf("Evening", "Morning")) {
        checkNotNull(device.wait(Until.findObject(By.text(tab)), 10_000)).click()
        device.waitForIdle()
    }
    device.pressBack()
    awaitHome()
    UiScrollable(UiSelector().scrollable(true)).scrollToBeginning(10)
}

internal fun MacrobenchmarkScope.tabsAndLibrary() {
    for (tab in listOf("Money", "Ask", "Tasks", "Inbox", "Home")) {
        val control = device.wait(Until.findObject(By.text(tab)), 10_000)
        checkNotNull(control) { "Missing tab: $tab" }.click()
        device.waitForIdle()
    }
    checkNotNull(device.wait(Until.findObject(By.text("Library")), 10_000)).click()
    check(device.wait(Until.hasObject(By.text("File name")), 10_000)) { "Library did not open" }
    repeat(3) {
        device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
            device.displayWidth / 2, device.displayHeight / 3, 20)
        device.waitForIdle()
    }
    device.pressBack()
    awaitHome()
}
