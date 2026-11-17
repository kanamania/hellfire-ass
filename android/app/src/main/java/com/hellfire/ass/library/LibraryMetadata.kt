package com.hellfire.ass.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.media.MediaItem
import org.json.JSONObject
import java.io.InputStream

object LibraryMetadata {
    private val gps = setOf(
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_PROCESSING_METHOD,
    )
    private val imageTags = listOf(
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
        ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_IMAGE_WIDTH, ExifInterface.TAG_IMAGE_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT,
    ) + gps

    fun exifJson(input: InputStream, locationGranted: Boolean): JSONObject {
        val exif = ExifInterface(input)
        val out = JSONObject()
        imageTags.forEach { tag ->
            if (!locationGranted && tag in gps) return@forEach
            exif.getAttribute(tag)?.let { out.put(tag, it) }
        }
        if (!locationGranted) out.put("location", "not read; permission not granted")
        return out
    }

    fun read(app: HellFireApp, item: MediaItem, locationGranted: Boolean): JSONObject {
        val json = item.originalMetadata?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        val kind = runCatching { ContentKind.valueOf(item.contentKind) }.getOrDefault(ContentKind.OTHER)
        if (kind == ContentKind.IMAGE) {
            runCatching {
                open(app, item).use { json.put("exif", exifJson(it, locationGranted)) }
            }.onFailure { json.put("exifError", "Could not read embedded image metadata") }
        }
        if (kind == ContentKind.AUDIO || kind == ContentKind.VIDEO) {
            val retriever = MediaMetadataRetriever()
            try {
                if (item.storage == StorageMode.EXTERNAL.name) retriever.setDataSource(app, Uri.parse(item.externalUri))
                else retriever.setDataSource(app.media.dataSource(item))
                val container = JSONObject()
                listOf(
                    MediaMetadataRetriever.METADATA_KEY_TITLE, MediaMetadataRetriever.METADATA_KEY_ARTIST,
                    MediaMetadataRetriever.METADATA_KEY_ALBUM, MediaMetadataRetriever.METADATA_KEY_DURATION,
                    MediaMetadataRetriever.METADATA_KEY_MIMETYPE, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
                ).forEach { key -> retriever.extractMetadata(key)?.let { container.put(key.toString(), it) } }
                json.put("container", container)
            } catch (_: Exception) {
                json.put("containerError", "Could not read container metadata")
            } finally {
                runCatching { retriever.release() }
            }
        }
        return json
    }

    fun dHash(app: HellFireApp, item: MediaItem): Long? {
        val bitmap = decode(app, item, 72) ?: return null
        return try { LibraryDiscovery.dHash(pixels(bitmap), bitmap.width, bitmap.height) } finally { bitmap.recycle() }
    }

    fun thumbnail(context: Context, app: HellFireApp, item: MediaItem): Bitmap? {
        if (item.storage == StorageMode.EXTERNAL.name && !item.externalUri.isNullOrBlank()) {
            val loaded = runCatching { context.contentResolver.loadThumbnail(Uri.parse(item.externalUri), Size(256, 256), null) }.getOrNull()
            if (loaded != null) return loaded
        }
        val kind = runCatching { ContentKind.valueOf(item.contentKind) }.getOrDefault(ContentKind.OTHER)
        if (kind == ContentKind.IMAGE) return decode(app, item, 256)
        if (kind == ContentKind.VIDEO) return frame(app, item)
        return null
    }

    fun decode(app: HellFireApp, item: MediaItem, maxEdge: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(app, item).use { BitmapFactory.decodeStream(it, null, bounds) }
        val sample = sample(bounds.outWidth, bounds.outHeight, maxEdge)
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        open(app, item).use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull()

    private fun frame(app: HellFireApp, item: MediaItem): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            if (item.storage == StorageMode.EXTERNAL.name) retriever.setDataSource(app, Uri.parse(item.externalUri))
            else retriever.setDataSource(app.media.dataSource(item))
            retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 256, 256)
        } catch (_: Exception) { null } finally { runCatching { retriever.release() } }
    }

    private fun open(app: HellFireApp, item: MediaItem): InputStream = app.media.openStream(item)

    private fun pixels(bitmap: Bitmap): IntArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels
    }

    private fun sample(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        val edge = maxOf(width, height)
        while (edge / sample > maxEdge * 2 && sample < 32) sample *= 2
        return sample
    }
}
