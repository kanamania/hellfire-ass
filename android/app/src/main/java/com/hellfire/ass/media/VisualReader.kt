package com.hellfire.ass.media

import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.*
import android.os.storage.StorageManager
import com.hellfire.ass.HellFireApp
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

object VisualReader {
    suspend fun image(app: HellFireApp, item: MediaItem): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        app.media.reader(item).use { BitmapFactory.decodeStream(it.stream(), null, bounds) }
        require(bounds.outWidth in 1..100000 && bounds.outHeight in 1..100000) { "UNSUPPORTED_IMAGE" }
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 2048) inSampleSize *= 2
        }
        val bitmap = app.media.reader(item).use { BitmapFactory.decodeStream(it.stream(), null, options) } ?: error("UNSUPPORTED_IMAGE")
        return LocalOcr.read(bitmap)
    }
    suspend fun pdf(app: HellFireApp, item: MediaItem, start: Int, accept: suspend (Int, String) -> Unit): Boolean {
        val reader = app.media.reader(item)
        val thread = HandlerThread("encrypted-pdf").apply { start() }
        try {
            val descriptor = app.getSystemService(StorageManager::class.java).openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY, object : ProxyFileDescriptorCallback() {
                    override fun onGetSize() = reader.size
                    override fun onRead(offset: Long, size: Int, data: ByteArray): Int = maxOf(0, reader.readAt(offset, data, 0, size))
                    override fun onRelease() { reader.close() }
                }, Handler(thread.looper))
            descriptor.use { PdfRenderer(it).use { pdf ->
                require(pdf.pageCount <= 1000) { "PDF_EXCEEDS_1000_PAGES" }
                val end = minOf(start + 5, pdf.pageCount)
                for (index in start until end) {
                    currentCoroutineContext().ensureActive()
                    val bitmap = pdf.openPage(index).use { page ->
                        val scale = 2048.0 / maxOf(page.width, page.height)
                        Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also { image ->
                            image.eraseColor(Color.WHITE); page.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                    accept(index, LocalOcr.read(bitmap))
                }
                return end >= pdf.pageCount
            } }
        } finally { reader.close(); thread.quitSafely() }
    }
    suspend fun video(app: HellFireApp, item: MediaItem, accept: suspend (Long, String) -> Unit) {
        val source = EncryptedMediaSource(app.media.reader(item))
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: error("VIDEO_DURATION_UNAVAILABLE")
            require(duration > 0)
            // At most twenty keyframes. This is a sampled index, never a claim to cover every frame.
            val step = maxOf(30_000L, duration / 20)
            var at = 0L
            while (at < duration) {
                currentCoroutineContext().ensureActive()
                retriever.getScaledFrameAtTime(at * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1280, 720)?.let { bitmap ->
                    accept(at, LocalOcr.read(bitmap))
                }
                at += step
            }
        } finally { retriever.release(); source.close() }
    }
}
