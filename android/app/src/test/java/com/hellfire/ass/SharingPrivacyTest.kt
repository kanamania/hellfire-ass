package com.hellfire.ass

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.hellfire.ass.library.ShareProfile
import com.hellfire.ass.sharing.ApprovedShare
import com.hellfire.ass.sharing.CaptionStyle
import com.hellfire.ass.sharing.ClipRanges
import com.hellfire.ass.sharing.ImageOrientation
import com.hellfire.ass.sharing.ImagePrivacyScan
import com.hellfire.ass.sharing.ImageRedaction
import com.hellfire.ass.sharing.ImageSanitizer
import com.hellfire.ass.sharing.MetadataExport
import com.hellfire.ass.sharing.Mp4Privacy
import com.hellfire.ass.sharing.PcmMute
import com.hellfire.ass.sharing.RedactMode
import com.hellfire.ass.sharing.RedactRect
import com.hellfire.ass.sharing.SensitiveText
import com.hellfire.ass.sharing.ShareFacts
import com.hellfire.ass.sharing.ShareFormats
import com.hellfire.ass.sharing.ShareHandoff
import com.hellfire.ass.sharing.ShareTemplates
import com.hellfire.ass.sharing.AnalysisFact
import com.hellfire.ass.sharing.SegmentFact
import com.hellfire.ass.sharing.TranscriptFact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.CRC32

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharingPrivacyTest {
    @Test fun `jpeg and png fixtures lose exif xmp and text chunks`() {
        val dir = File.createTempFile("share", "dir").apply { delete(); mkdirs() }
        try {
            val dirtyJpeg = File(dir, "in.jpg")
            val jpeg = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.BLUE) }
            dirtyJpeg.outputStream().use { jpeg.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            jpeg.recycle()
            ExifInterface(dirtyJpeg.absolutePath).apply {
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, "6/1,48/1,0/1")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "S")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "39/1,12/1,0/1")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
                setAttribute(ExifInterface.TAG_MAKE, "SecretDevice")
                setAttribute(ExifInterface.TAG_ARTIST, "Owner")
                saveAttributes()
            }
            dirtyJpeg.writeBytes(insertJpegMarker(dirtyJpeg.readBytes(), 0xE1, "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta>secret</x:xmpmeta>".toByteArray()))
            dirtyJpeg.writeBytes(insertJpegMarker(dirtyJpeg.readBytes(), 0xFE, "private comment".toByteArray()))
            val before = dirtyJpeg.readBytes()
            assertTrue(ImagePrivacyScan.identifying(ImagePrivacyScan.scan(before)).any { it.field == "APP1" })
            assertTrue(ImagePrivacyScan.scan(before).any { it.field == "COM" })
            val out = File(dir, "image.jpg")
            val result = ImageSanitizer.sanitize(dirtyJpeg, out, "image/jpeg")
            assertEquals(before.toList(), dirtyJpeg.readBytes().toList())
            assertTrue(result.message, result.ok)
            assertEquals("VERIFIED", result.verification)
            assertTrue(out.isFile)
            assertTrue(ImagePrivacyScan.identifying(ImagePrivacyScan.scan(out.readBytes())).isEmpty())
            val exif = ExifInterface(out.absolutePath)
            assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
            assertNull(exif.getAttribute(ExifInterface.TAG_ARTIST))
            assertFalse(out.readBytes().toString(Charsets.ISO_8859_1).contains("SecretDevice"))
            assertFalse(out.readBytes().toString(Charsets.ISO_8859_1).contains("xmpmeta"))

            val dirtyPng = File(dir, "in.png")
            val png = Bitmap.createBitmap(6, 6, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.RED) }
            dirtyPng.outputStream().use { png.compress(Bitmap.CompressFormat.PNG, 100, it) }
            png.recycle()
            dirtyPng.writeBytes(insertPngChunk(dirtyPng.readBytes(), "tEXt", "Comment\u0000home address".toByteArray()))
            dirtyPng.writeBytes(insertPngChunk(dirtyPng.readBytes(), "eXIf", byteArrayOf(1, 2, 3, 4)))
            dirtyPng.writeBytes(insertPngChunk(dirtyPng.readBytes(), "iTXt", "Author\u0000\u0000\u0000\u0000\u0000Owner".toByteArray()))
            assertTrue(ImagePrivacyScan.scan(dirtyPng.readBytes()).any { it.field == "tEXt" })
            val pngOut = File(dir, "image.png")
            val pngResult = ImageSanitizer.sanitize(dirtyPng, pngOut, "image/png")
            assertTrue(pngResult.message, pngResult.ok)
            val pngHits = ImagePrivacyScan.scan(pngOut.readBytes())
            assertTrue(pngHits.none { it.field in setOf("tEXt", "iTXt", "eXIf", "zTXt") })
            assertFalse(pngOut.readBytes().toString(Charsets.ISO_8859_1).contains("home address"))

            val garbage = File(dir, "bad.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val failed = File(dir, "bad-out.jpg")
            val fail = ImageSanitizer.sanitize(garbage, failed, "image/jpeg")
            assertFalse(fail.ok)
            assertFalse(failed.exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun `orientation is baked into pixels and redaction covers a region`() {
        val source = Bitmap.createBitmap(2, 4, Bitmap.Config.ARGB_8888)
        source.setPixel(0, 0, Color.RED)
        val turned = ImageOrientation.bake(source, ExifInterface.ORIENTATION_ROTATE_90)
        assertEquals(4, turned.width)
        assertEquals(2, turned.height)
        val covered = ImageRedaction.apply(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.WHITE) },
            listOf(RedactRect(0f, 0f, 1f, 1f)), RedactMode.COVER)
        assertEquals(Color.BLACK, covered.getPixel(5, 5))
    }

    @Test fun `mp4 box scanner finds and strips udta meta location and creation time`() {
        val xyz = box(byteArrayOf(0xA9.toByte(), 'x'.code.toByte(), 'y'.code.toByte(), 'z'.code.toByte()), "secret-place".toByteArray())
        val udta = box("udta", xyz)
        val meta = box("meta", "owner-device".toByteArray())
        val mvhd = box("mvhd", ByteArray(24).also { putInt(it, 4, 12345); putInt(it, 8, 99) })
        val moov = box("moov", mvhd + udta + meta)
        val tree = box("ftyp", "isom".toByteArray()) + moov
        val before = Mp4Privacy.scan(tree)
        assertTrue(before.identifying.any { it.endsWith("udta") })
        assertTrue(before.identifying.any { it.endsWith("meta") })
        assertTrue(before.identifying.any { it.contains("xyz") || it[it.lastIndex].code == 0xA9 || it.contains("©") })
        assertTrue(before.creationTimes.any { it.second == 12345L })
        val stripped = Mp4Privacy.strip(tree)
        assertTrue(stripped.scan.identifying.toString(), Mp4Privacy.clean(stripped.scan))
        assertTrue(stripped.scan.creationTimes.all { it.second == 0L })
        assertFalse(stripped.bytes.toString(Charsets.ISO_8859_1).contains("secret-place"))
        assertFalse(stripped.bytes.toString(Charsets.ISO_8859_1).contains("owner-device"))
    }

    @Test fun `caption uses only stored facts and export options assemble reviewed text`() {
        val facts = ShareFacts("VIDEO", 12_000, 640, 360, ClipRanges.valid(1000, 4000, 12_000),
            listOf(AnalysisFact("scenes", "whole file summary", false, mapOf("title" to listOf("Door"), "summary" to listOf("A red door")))),
            listOf(SegmentFact(1000, 4000, "Door", "Opens"), SegmentFact(8000, 9000, "Outside", "Street")),
            listOf(TranscriptFact(1200, 1800, "call me at 0712345678")),
            listOf("Home"), listOf("door"), emptyList(), "stored summary", 1)
        val draft = ShareTemplates.fromFacts(facts, CaptionStyle.FACTUAL)
        assertEquals(ShareTemplates.STORED, draft.source)
        assertTrue(draft.caption.contains("Door"))
        assertTrue(draft.caption.contains("Opens"))
        assertFalse(draft.caption.contains("Outside"))
        assertFalse(draft.caption.contains("whole file summary"))
        assertFalse(draft.caption.contains("IMG_"))
        assertFalse(draft.caption.contains("anonymous", ignoreCase = true))
        assertFalse(draft.hashtags.contains("#door"))
        assertFalse(draft.hashtags.contains("#home"))
        assertEquals(1, draft.timestamps.size)
        assertTrue(draft.notice.contains("stored facts"))
        val empty = ShareTemplates.fromFacts(facts.copy(analyses = emptyList(), segments = emptyList(), transcript = emptyList(), categories = emptyList(), tags = emptyList(), storedSummary = ""), CaptionStyle.SHORT)
        assertTrue(empty.notice.contains("Nothing was invented"))

        val approved = ApprovedShare(draft.title, draft.shortSummary, draft.detailedSummary, draft.caption, draft.categories, draft.tags, draft.hashtags,
            draft.timestamps, draft.transcriptExcerpts, CaptionStyle.FACTUAL, ShareProfile.MEDIA_ONLY, facts.range, false, draft.source)
        assertEquals("", MetadataExport.assemble(approved).text)
        assertNull(MetadataExport.assemble(approved).sidecarJson)
        val caption = MetadataExport.assemble(approved.copy(profile = ShareProfile.MEDIA_CAPTION))
        assertEquals(draft.caption, caption.text)
        assertNull(caption.sidecarJson)
        val categories = MetadataExport.assemble(approved.copy(profile = ShareProfile.MEDIA_CATEGORIES, categories = listOf("Home")))
        assertTrue(categories.text.contains("Home"))
        assertFalse(categories.text.contains("call me"))
        val full = MetadataExport.assemble(approved.copy(profile = ShareProfile.MEDIA_FULL_METADATA, includeTranscript = true))
        assertTrue(full.text.contains("0712345678"))
        val json = MetadataExport.assemble(approved.copy(profile = ShareProfile.MEDIA_JSON_SIDECAR, includeTranscript = false))
        assertNotNull(json.sidecarJson)
        assertTrue(json.sidecarJson!!.contains("Door"))
        assertFalse(json.sidecarJson!!.contains("0712345678"))
        assertFalse(json.sidecarJson!!.contains("GPS"))
        assertTrue(json.sidecarJson!!.contains("does not mean a post was published"))
        assertEquals("image/jpeg", ShareFormats.plan("image/heic", "IMAGE").mime)
        assertEquals("video/mp4", ShareFormats.plan("video/x-matroska", "VIDEO").mime)
        assertEquals("audio/mp4", ShareFormats.plan("audio/amr", "AUDIO").mime)
        assertFalse(ShareFormats.plan("application/pdf", "DOCUMENT").supported)
    }

    @Test fun `clip ranges reject invalid ends and mute math stays inside the clip`() {
        assertNull(ClipRanges.valid(5, 5, 10))
        assertNull(ClipRanges.valid(-1, 4, 10))
        assertNull(ClipRanges.fromBookmark(100, null, 1000))
        assertEquals(10, ClipRanges.valid(2, 20, 10)!!.endMs)
        assertNull(ClipRanges.fromLoop(null, 4, 10))
        val clip = ClipRanges.valid(1000, 4000, 9000)!!
        assertEquals(200L to 800L, ClipRanges.clipRelative(clip, 1200, 1800))
        assertNull(ClipRanges.clipRelative(clip, 5000, 6000))
        assertTrue(ClipRanges.overlaps(clip, 900, 1100))
        assertFalse(ClipRanges.overlaps(clip, 4000, 5000))
        assertEquals("phone", SensitiveText.kind("call +255712345678 now"))
        assertEquals("email", SensitiveText.kind("a.b@example.co"))
        assertEquals("address", SensitiveText.kind("12 Mtaa road"))
        assertNull(SensitiveText.kind("a red door"))
        assertNotNull(SensitiveText.suggest("a@b.co", 1, 2, 3, 4))
        assertNull(SensitiveText.suggest("door", 1, 2, 3, 4))
    }

    @Test fun `pcm mute zeroes only selected ranges`() {
        val rate = 1000
        val pcm = ByteArray(20) { 0x11 }
        val muted = PcmMute.zero16(pcm, rate, 1, listOf(2L to 5L))
        assertEquals(pcm.copyOfRange(0, 4).toList(), muted.copyOfRange(0, 4).toList())
        assertTrue(muted.copyOfRange(4, 10).all { it == 0.toByte() })
        assertEquals(pcm.copyOfRange(10, 20).toList(), muted.copyOfRange(10, 20).toList())
        val stereo = ByteArray(16) { 7 }
        val out = PcmMute.zero16(stereo, 4, 2, listOf(0L to 1L))
        assertTrue(out.copyOfRange(0, 4).all { it == 0.toByte() })
        assertEquals(7, out[4].toInt())
    }

    @Test fun `share intent grants read access and does not claim a post`() {
        val one = Uri.parse("content://com.hellfire.ass.share/share/image.jpg")
        val two = Uri.parse("content://com.hellfire.ass.share/share/image-2.jpg")
        val send = ShareHandoff.send(listOf(one), "image/jpeg", "Door")
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("image/jpeg", send.type)
        assertEquals("Door", send.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(one, send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(1, send.clipData!!.itemCount)
        val multi = ShareHandoff.send(listOf(one, two), "image/*", "")
        assertEquals(Intent.ACTION_SEND_MULTIPLE, multi.action)
        assertEquals(2, multi.clipData!!.itemCount)
        assertFalse(multi.hasExtra(Intent.EXTRA_TEXT))
        val chooser = ShareHandoff.chooser(listOf(one), "image/jpeg", "Door")
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val inner = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, inner.action)
        assertFalse(chooser.toUri(0).contains("published"))
    }

    private fun insertJpegMarker(jpeg: ByteArray, marker: Int, payload: ByteArray): ByteArray {
        var i = 2
        while (i + 1 < jpeg.size) {
            if (jpeg[i] == 0xFF.toByte() && jpeg[i + 1] == 0xDA.toByte()) {
                val segment = ByteArray(4 + payload.size)
                segment[0] = 0xFF.toByte()
                segment[1] = marker.toByte()
                val length = payload.size + 2
                segment[2] = (length shr 8).toByte()
                segment[3] = length.toByte()
                payload.copyInto(segment, 4)
                return jpeg.copyOfRange(0, i) + segment + jpeg.copyOfRange(i, jpeg.size)
            }
            i++
        }
        error("JPEG has no scan")
    }

    private fun insertPngChunk(png: ByteArray, type: String, data: ByteArray): ByteArray {
        val chunk = ByteArray(12 + data.size)
        putInt(chunk, 0, data.size)
        type.toByteArray().copyInto(chunk, 4)
        data.copyInto(chunk, 8)
        val crc = CRC32()
        crc.update(type.toByteArray())
        crc.update(data)
        putInt(chunk, 8 + data.size, crc.value.toInt())
        val iend = png.indexOfSlice("IEND".toByteArray())
        val at = iend - 4
        return png.copyOfRange(0, at) + chunk + png.copyOfRange(at, png.size)
    }

    private fun box(type: String, payload: ByteArray): ByteArray = box(type.toByteArray(Charsets.ISO_8859_1), payload)

    private fun box(type: ByteArray, payload: ByteArray): ByteArray {
        val out = ByteArray(8 + payload.size)
        putInt(out, 0, out.size)
        type.copyInto(out, 4)
        payload.copyInto(out, 8)
        return out
    }

    private fun putInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun ByteArray.indexOfSlice(needle: ByteArray): Int {
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return i
        }
        error("chunk missing")
    }
}
