package com.hellfire.ass.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()

    @Test fun cold() = startup(StartupMode.COLD)
    @Test fun warm() = startup(StartupMode.WARM)
    @Test fun hot() = startup(StartupMode.HOT)

    private fun startup(mode: StartupMode) = benchmark.measureRepeated(
        packageName = TARGET,
        metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = mode,
        iterations = 10,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        awaitHome()
    }

    @Test fun navigationAndLibrary() = benchmark.measureRepeated(
        packageName = TARGET,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(warmupIterations = 3),
        iterations = 10,
        setupBlock = { pressHome(); startActivityAndWait(); awaitHome() },
    ) { tabsAndLibrary() }

    @Test fun newsNavigation() = benchmark.measureRepeated(
        packageName = TARGET,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(warmupIterations = 3),
        iterations = 10,
        setupBlock = { pressHome(); startActivityAndWait(); awaitHome() },
    ) { browseNews() }
}
