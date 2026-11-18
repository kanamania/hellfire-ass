package com.hellfire.ass.sharing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

data class MarkerHit(val container: String, val field: String, val detail: String = "")

data class ImageSanitizeResult(
    val ok: Boolean,
    val verification: String,
    val message: String,
    val findings: List<MarkerHit>,
    val width: Int = 0,
    val height: Int = 0,
    val sha256: String? = null,
    val mime: String? = null,
)

enum class RedactMode { BLUR, PIXELATE, COVER }

data class RedactRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun valid() = left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f && right > left && bottom > top
}

object ImagePrivacyScan {
    fun scan(bytes: ByteArray): List<MarkerHit> = when {
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> scanPng(bytes)
        bytes.size >= 12 && bytes.copyOfRange(0, 4).decodeToString() == "RIFF" && bytes.copyOfRange(8, 12).decodeToString() == "WEBP" -> scanWebp(bytes)
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> scanJpeg(bytes)
        else -> listOf(MarkerHit("FILE", "format", "Not a JPEG, PNG or WebP"))
    }

    fun identifying(hits: List<MarkerHit>) = hits.filter { it.field != "format" || it.container == "FILE" }.filter { hit ->
        hit.container == "FILE" || hit.field in setOf("APP1", "APP13", "COM", "XMP", "tEXt", "zTXt", "iTXt", "eXIf", "tIME", "EXIF", "XMP ")
    }

    private fun scanJpeg(bytes: ByteArray): List<MarkerHit> {
        val hits = mutableListOf<MarkerHit>()
        var i = 2
        while (i + 1 < bytes.size) {
            if (bytes[i] != 0xFF.toByte()) break
            while (i < bytes.size && bytes[i] == 0xFF.toByte()) i++
            if (i >= bytes.size) break
            val marker = bytes[i].toInt() and 0xFF
            i++
            if (marker == 0xD9 || marker == 0xDA) break
            if (marker == 0x01 || marker in 0xD0..0xD7) continue
            if (i + 1 >= bytes.size) break
            val length = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
            if (length < 2 || i + length > bytes.size) {
                hits += MarkerHit("JPEG", "truncated", "Marker length is invalid")
                break
            }
            val payload = bytes.copyOfRange(i + 2, i + length)
            val name = when (marker) {
                0xE1 -> "APP1"
                0xED -> "APP13"
                0xFE -> "COM"
                else -> null
            }
            if (name != null) hits += MarkerHit("JPEG", name, payloadLabel(payload))
            if (payloadLabel(payload) == "XMP" && name != "APP1") hits += MarkerHit("JPEG", "XMP")
            i += length
        }
        return hits
    }

    private fun payloadLabel(payload: ByteArray): String {
        val head = payload.take(64).toByteArray().toString(Charsets.ISO_8859_1)
        return when {
            head.startsWith("Exif") -> "Exif"
            head.contains("http://ns.adobe.com/xap") || head.contains("http://ns.adobe.com/xmp") -> "XMP"
            head.startsWith("Photoshop") -> "IPTC"
            else -> ""
        }
    }

    private fun scanPng(bytes: ByteArray): List<MarkerHit> {
        val hits = mutableListOf<MarkerHit>()
        var i = 8
        while (i + 8 <= bytes.size) {
            val length = readInt(bytes, i)
            if (length < 0 || i + 12L + length > bytes.size) {
                hits += MarkerHit("PNG", "truncated")
                break
            }
            val type = bytes.copyOfRange(i + 4, i + 8).toString(Charsets.ISO_8859_1)
            if (type in setOf("tEXt", "zTXt", "iTXt", "eXIf", "tIME")) hits += MarkerHit("PNG", type)
            if (type == "IEND") break
            i += 12 + length
        }
        return hits
    }

    private fun scanWebp(bytes: ByteArray): List<MarkerHit> {
        val hits = mutableListOf<MarkerHit>()
        var i = 12
        while (i + 8 <= bytes.size) {
            val type = bytes.copyOfRange(i, i + 4).toString(Charsets.ISO_8859_1)
            // RIFF/WebP chunk lengths are little-endian; PNG lengths are big-endian.
            val length = java.nio.ByteBuffer.wrap(bytes, i + 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            if (length < 0 || i + 8L + length > bytes.size) break
            if (type == "EXIF" || type == "XMP ") hits += MarkerHit("WEBP", type)
            i += 8 + length + (length and 1)
        }
        return hits
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return -1
        return ((bytes[offset].toInt() and 0xFF) shl 24) or ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)
    }
}

object ImageOrientation {
    fun bake(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.preScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.preScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return source
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }
}

object ImageRedaction {
    fun apply(source: Bitmap, rects: List<RedactRect>, mode: RedactMode): Bitmap {
        val base = if (source.isMutable && source.config != Bitmap.Config.HARDWARE) source else source.copy(Bitmap.Config.ARGB_8888, true)
        rects.filter { it.valid() }.forEach { rect ->
            val left = (rect.left * base.width).toInt().coerceIn(0, base.width - 1)
            val top = (rect.top * base.height).toInt().coerceIn(0, base.height - 1)
            val right = (rect.right * base.width).toInt().coerceIn(left + 1, base.width)
            val bottom = (rect.bottom * base.height).toInt().coerceIn(top + 1, base.height)
            when (mode) {
                RedactMode.COVER -> fill(base, left, top, right, bottom) { _, _ -> Color.BLACK }
                RedactMode.PIXELATE -> blocks(base, left, top, right, bottom, maxOf(2, (right - left) / 8))
                RedactMode.BLUR -> blocks(base, left, top, right, bottom, maxOf(2, (right - left) / 16), average = true)
            }
        }
        return base
    }

    private fun fill(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int, color: (Int, Int) -> Int) {
        for (y in top until bottom) for (x in left until right) bitmap.setPixel(x, y, color(x, y))
    }

    private fun blocks(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int, block: Int, average: Boolean = false) {
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val x2 = minOf(right, x + block)
                val y2 = minOf(bottom, y + block)
                val color = if (average) average(bitmap, x, y, x2, y2) else bitmap.getPixel(x, y)
                for (yy in y until y2) for (xx in x until x2) bitmap.setPixel(xx, yy, color)
                x += block
            }
            y += block
        }
    }

    private fun average(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Int {
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        for (y in top until bottom) for (x in left until right) {
            val pixel = bitmap.getPixel(x, y)
            r += Color.red(pixel); g += Color.green(pixel); b += Color.blue(pixel); n++
        }
        if (n == 0L) return Color.BLACK
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }
}

object ImageSanitizer {
    private val exifTags = listOf(
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_IMAGE_UNIQUE_ID, ExifInterface.TAG_USER_COMMENT, ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_SOFTWARE, ExifInterface.TAG_BODY_SERIAL_NUMBER, ExifInterface.TAG_CAMERA_OWNER_NAME,
        ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
    )

    fun sanitize(input: File, output: File, mime: String, rects: List<RedactRect> = emptyList(), mode: RedactMode = RedactMode.COVER): ImageSanitizeResult {
        check(input.absolutePath != output.absolutePath) { "Refusing to overwrite the original" }
        val inputBytes = input.readBytes()
        val originalHits = ImagePrivacyScan.scan(inputBytes)
        if (!recognized(inputBytes)) return fail(output, "The file is not a supported image. Nothing was shared.", originalHits)
        val decoded = decode(input) ?: return fail(output, "Could not decode the image. Nothing was shared.", originalHits)
        val oriented = ImageOrientation.bake(decoded.first, decoded.second)
        val redacted = if (rects.isEmpty()) oriented else ImageRedaction.apply(oriented, rects, mode)
        val encoded = encode(redacted, mime) ?: return fail(output, "Could not re-encode the image. Nothing was shared.", originalHits)
        if (redacted !== decoded.first && redacted !== oriented) redacted.recycle()
        if (oriented !== decoded.first) oriented.recycle()
        decoded.first.recycle()
        output.parentFile?.mkdirs()
        output.writeBytes(encoded.bytes)
        val scan = ImagePrivacyScan.identifying(ImagePrivacyScan.scan(encoded.bytes))
        val exifLeft = exifFindings(output)
        val preview = BitmapFactory.decodeFile(output.absolutePath)
        val playable = preview != null && preview.width > 0 && preview.height > 0
        preview?.recycle()
        val findings = originalHits.map { it.copy(detail = if (it.detail.isBlank()) "found in original" else it.detail) } + scan + exifLeft
        if (scan.isNotEmpty() || exifLeft.isNotEmpty() || !playable) {
            output.delete()
            return ImageSanitizeResult(false, "FAILED", "Verification found remaining metadata or the copy does not decode. Nothing was shared.", findings)
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(encoded.bytes).joinToString("") { "%02x".format(it) }
        val notes = mutableListOf("Embedded metadata was removed from this copy. Visible content can still identify people and places.")
        if (encoded.note.isNotBlank()) notes += encoded.note
        return ImageSanitizeResult(true, "VERIFIED", notes.joinToString(" "), findings, encoded.width, encoded.height, hash, encoded.mime)
    }

    private data class Encoded(val bytes: ByteArray, val mime: String, val width: Int, val height: Int, val note: String)

    private fun recognized(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return true
        if (bytes[0] == 0x89.toByte() && bytes.copyOfRange(1, 4).contentEquals("PNG".toByteArray())) return true
        if (bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) && bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray())) return true
        if (bytes.copyOfRange(0, 3).contentEquals("GIF".toByteArray())) return true
        if (bytes.copyOfRange(4, 8).contentEquals("ftyp".toByteArray())) {
            val brand = bytes.copyOfRange(8, 12).toString(Charsets.ISO_8859_1)
            return brand in setOf("heic", "heix", "heif", "mif1", "msf1", "avif")
        }
        return false
    }

    private fun decode(file: File): Pair<Bitmap, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth < 1 || bounds.outHeight < 1) return null
        val sample = sampleSize(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        val orientation = runCatching { ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED) }
            .getOrDefault(ExifInterface.ORIENTATION_UNDEFINED)
        return bitmap to orientation
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > 8192 || height / sample > 8192) sample *= 2
        return sample
    }

    private fun encode(bitmap: Bitmap, requested: String): Encoded? {
        val webp = requested == "image/webp" && android.os.Build.VERSION.SDK_INT >= 30
        val format = when {
            requested == "image/png" -> Bitmap.CompressFormat.PNG
            webp -> Bitmap.CompressFormat.WEBP_LOSSY
            else -> Bitmap.CompressFormat.JPEG
        }
        val mime = when {
            requested == "image/png" -> "image/png"
            webp -> "image/webp"
            else -> "image/jpeg"
        }
        val note = if (requested == "image/webp" && !webp) "WebP encoding is not available on this Android version; JPEG was written instead." else ""
        // A fresh SDR bitmap drops an Ultra HDR gain map rather than re-encoding its identifying XMP.
        val sdr = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(sdr).drawBitmap(bitmap, 0f, 0f, null)
        val stream = ByteArrayOutputStream()
        val compressed = sdr.compress(format, 90, stream)
        sdr.recycle()
        if (!compressed) return null
        return Encoded(stream.toByteArray(), mime, bitmap.width, bitmap.height, note)
    }

    private fun exifFindings(file: File): List<MarkerHit> {
        val exif = runCatching { ExifInterface(file.absolutePath) }.getOrNull() ?: return listOf(MarkerHit("EXIF", "unreadable"))
        val hits = exifTags.mapNotNull { tag -> exif.getAttribute(tag)?.takeIf { it.isNotBlank() }?.let { MarkerHit("EXIF", tag) } }
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
        val oriented = orientation != ExifInterface.ORIENTATION_UNDEFINED && orientation != ExifInterface.ORIENTATION_NORMAL
        return if (oriented) hits + MarkerHit("EXIF", ExifInterface.TAG_ORIENTATION) else hits
    }

    private fun fail(output: File, message: String, hits: List<MarkerHit>): ImageSanitizeResult {
        output.delete()
        return ImageSanitizeResult(false, "FAILED", message, hits)
    }
}
