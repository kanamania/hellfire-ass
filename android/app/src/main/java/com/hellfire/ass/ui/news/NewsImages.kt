package com.hellfire.ass.ui.news

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.hellfire.ass.ui.theme.Hf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** One media link from an article's mediaJson. Links only; nothing is downloaded for keeping or rehosted. */
data class NewsMedia(val type: String, val url: String, val credit: String?)

/**
 * Tiny https-only image loader for story cards: in-memory LRU (no disk cache), size caps and downsampling.
 * Images are shown from the publisher's own URL with a credit line.
 */
object NewsImages {
    const val MAX_BYTES = 3 * 1024 * 1024
    const val MAX_SIDE = 1280
    private const val CACHE_BYTES = 12 * 1024 * 1024

    private val cache by lazy { object : LruCache<String, Bitmap>(CACHE_BYTES) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount } }

    /** https only, a real host, no credentials in the URL. */
    fun accept(url: String): Boolean = runCatching {
        val u = URI(url.trim())
        url.length <= 2048 && u.scheme?.lowercase() == "https" && !u.host.isNullOrBlank() && u.rawUserInfo == null
    }.getOrDefault(false)

    /** Power-of-two sample size so the longer side ends up at most [maxSide]. */
    fun sampleSize(width: Int, height: Int, maxSide: Int = MAX_SIDE): Int {
        var sample = 1
        while (maxOf(width, height) / sample > maxSide) sample *= 2
        return sample
    }

    fun media(mediaJson: String): List<NewsMedia> = runCatching {
        val array = JSONArray(mediaJson)
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            NewsMedia(o.optString("type").uppercase(), o.optString("url"), o.optString("credit").takeIf { it.isNotBlank() })
                .takeIf { it.url.isNotBlank() }
        }
    }.getOrDefault(emptyList())

    suspend fun load(url: String): Bitmap? {
        if (!accept(url)) return null
        cache.get(url)?.let { return it }
        return try { withContext(Dispatchers.IO) { fetch(url) }?.also { cache.put(url, it) } }
        catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }

    private fun fetch(url: String): Bitmap? {
        val connection = URL(url).openConnection() as? HttpsURLConnection ?: return null
        try {
            connection.connectTimeout = 8_000; connection.readTimeout = 10_000
            connection.instanceFollowRedirects = true // HttpsURLConnection never follows a redirect to plain http
            connection.setRequestProperty("Accept", "image/*")
            if (connection.responseCode != 200) return null
            if (connection.contentType?.startsWith("image/") != true) return null
            if (connection.contentLengthLong > MAX_BYTES) return null
            val bytes = connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_BYTES) return null
                }
                out.toByteArray()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } finally { connection.disconnect() }
    }
}

/** First story image with its credit; renders nothing until (and unless) the image loads. */
@Composable
fun NewsImage(url: String, publisher: String, modifier: Modifier = Modifier) {
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) { bitmap = NewsImages.load(url) }
    val b = bitmap ?: return
    Column(modifier) {
        Image(b.asImageBitmap(), null, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop)
        Text("Image: $publisher", style = Hf.type.caption, color = Hf.colors.muted)
    }
}
