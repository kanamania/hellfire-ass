package com.hellfire.ass.macrobenchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val profile = BaselineProfileRule()

    @Test fun startup() = profile.collect(packageName = TARGET, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        awaitHome()
    }

    @Test fun navigationAndLibrary() = profile.collect(packageName = TARGET) {
        pressHome()
        startActivityAndWait()
        awaitHome()
        tabsAndLibrary()
        browseNews()
    }
}
