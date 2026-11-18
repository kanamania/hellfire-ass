package com.hellfire.ass

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** Fixed-density, synthetic component screenshot. Production windows keep FLAG_SECURE. */
class ComponentRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun bilingualComponentsMatchRecordedEmulatorGolden() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                HellFireTheme(dark = false) {
                    Column(Modifier.requiredSize(320.dp, 270.dp).background(Hf.colors.background).padding(16.dp).testTag("fixture"),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("HellFire Ass")
                        HfCard(padding = PaddingValues(12.dp)) {
                            Text("Synthetic fixture / Jaribio")
                            Note("No personal data / Hakuna taarifa binafsi")
                        }
                        PrimaryButton("Continue / Endelea", {})
                        SecondaryButton("Cancel / Ghairi", {})
                    }
                }
            }
        }
        compose.onNodeWithText("Continue / Endelea").assertHasClickAction()
        compose.onNodeWithText("Cancel / Ghairi").assertHasClickAction()
        val actual = compose.onNodeWithTag("fixture").captureToImage().asAndroidBitmap()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (InstrumentationRegistry.getArguments().getString("recordGoldens") == "true") {
            val file = File(instrumentation.targetContext.getExternalFilesDir(null), "goldens/components-light.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { assertTrue(actual.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            return
        }
        val expected = instrumentation.context.assets.open("goldens/components-light.png").use(BitmapFactory::decodeStream)
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        val a = IntArray(actual.width * actual.height)
        val e = IntArray(a.size)
        actual.getPixels(a, 0, actual.width, 0, 0, actual.width, actual.height)
        expected.getPixels(e, 0, expected.width, 0, 0, expected.width, expected.height)
        val changed = a.indices.count { i -> listOf(0, 8, 16, 24).any { shift -> abs(((a[i] ushr shift) and 255) - ((e[i] ushr shift) and 255)) > 8 } }
        assertTrue("Screenshot differs at $changed/${a.size} pixels", changed <= a.size / 100)
    }
}
