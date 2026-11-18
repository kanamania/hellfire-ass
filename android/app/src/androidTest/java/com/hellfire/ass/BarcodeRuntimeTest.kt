package com.hellfire.ass

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.hellfire.ass.media.LocalBarcodes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BarcodeRuntimeTest {
    @Test fun bundledQrRuntimeDecodesSyntheticImageOffline() = runBlocking {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val bitmap = assets.open("qr-public.png").use(BitmapFactory::decodeStream)
        try { assertEquals(listOf("https://example.invalid/public"), LocalBarcodes.read(bitmap)) }
        finally { bitmap.recycle() }
    }
}
