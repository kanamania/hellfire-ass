package com.hellfire.ass.macrobenchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run only after collecting and installing a real Baseline Profile; missing profiles fail explicitly. */
@RunWith(AndroidJUnit4::class)
class ProfileStartupBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()

    @Test fun coldWithProfile() {
        check(BuildConfig.HAS_APP_PROFILE_RULES) { "Generate and package app Baseline Profile rules before this comparison." }
        benchmark.measureRepeated(
        packageName = TARGET,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require),
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = { pressHome() },
        ) { startActivityAndWait(); awaitHome() }
    }
}
