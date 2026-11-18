package com.hellfire.ass

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import com.hellfire.ass.ui.UiLanguage
import com.hellfire.ass.memory.VectorMath
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LanguageContextTest {
    @Test fun localizedResourcesKeepTheirActivityOwner() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val previous = UiLanguage.code
        try {
            UiLanguage.code = "sw"
            val localized = UiLanguage.context(controller.get())
            assertEquals("sw", localized.resources.configuration.locales[0].language)
            var owner: Context = localized
            while (owner is ContextWrapper && owner !== controller.get()) owner = owner.baseContext
            assertSame(controller.get(), owner)
        } finally { UiLanguage.code = previous; controller.pause().stop().destroy() }
    }

    @Test fun normalizedVectorsRejectNonfiniteValuesAndHandleLargeFiniteCoordinates() {
        assertTrue(VectorMath.normalize(floatArrayOf(Float.POSITIVE_INFINITY)).isEmpty())
        assertTrue(VectorMath.normalize(floatArrayOf(Float.NaN)).isEmpty())
        val actual = VectorMath.normalize(floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE))
        assertEquals(2, actual.size)
        assertEquals(0.70710677f, actual[0], 0.000001f)
    }
}
