package com.hellfire.ass.sharing

import java.io.File
import java.io.RandomAccessFile

data class Mp4Scan(val identifying: List<String>, val creationTimes: List<Pair<String, Long>>, val wellFormed: Boolean)

data class Mp4Strip(val bytes: ByteArray, val scan: Mp4Scan)

object Mp4Privacy {
    private val containers = setOf("moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "mvex", "moof", "traf", "udta")
    private val timed = setOf("mvhd", "tkhd", "mdhd")

    fun scan(bytes: ByteArray): Mp4Scan = scanRefs(parse(bytes, 0, bytes.size), bytes)

    fun strip(bytes: ByteArray): Mp4Strip {
        val rewritten = rewrite(parse(bytes, 0, bytes.size), bytes)
        return Mp4Strip(rewritten, scan(rewritten))
    }

    fun stripFile(file: File): Mp4Scan {
        val bytes = if (file.length() <= 32L * 1024 * 1024) file.readBytes() else return stripLarge(file)
        val stripped = strip(bytes)
        if (!clean(stripped.scan)) return stripped.scan
        file.writeBytes(stripped.bytes)
        return stripped.scan
    }

    fun clean(scan: Mp4Scan) = scan.wellFormed && scan.identifying.isEmpty() && scan.creationTimes.all { it.second == 0L }

    private fun stripLarge(file: File): Mp4Scan {
        val input = RandomAccessFile(file, "r")
        val refs = input.use { parseFile(it, 0, it.length()) }
        val out = File(file.parentFile, file.name + ".strip")
        try {
            RandomAccessFile(file, "r").use { source ->
                out.outputStream().buffered().use { dest -> writeRefs(refs, source, dest) }
            }
            val scan = scanFile(out)
            if (!clean(scan)) {
                out.delete()
                return scan
            }
            if (!out.renameTo(file)) {
                out.copyTo(file, overwrite = true)
                out.delete()
            }
            return scan
        } catch (error: Exception) {
            out.delete()
            return Mp4Scan(listOf("rewrite:${error.javaClass.simpleName}"), emptyList(), false)
        }
    }

    private data class Ref(val type: String, val offset: Long, val size: Long, val header: Int, val children: List<Ref>?)

    private fun parse(bytes: ByteArray, start: Int, end: Int): List<Ref> {
        val refs = mutableListOf<Ref>()
        var cursor = start
        while (cursor + 8 <= end) {
            val size32 = uint(bytes, cursor)
            val type = typeAt(bytes, cursor + 4)
            val header = if (size32 == 1L) 16 else 8
            val size = when (size32) {
                0L -> (end - cursor).toLong()
                1L -> if (cursor + 16 <= end) long(bytes, cursor + 8) else return refs
                else -> size32
            }
            if (size < header || cursor + size > end) return refs + Ref("truncated", cursor.toLong(), 0, 8, null)
            val payloadStart = cursor + header
            val payloadEnd = cursor + size.toInt()
            val children = if (type in containers) parse(bytes, payloadStart, payloadEnd) else null
            refs += Ref(type, cursor.toLong(), size, header, children)
            cursor += size.toInt()
        }
        return refs
    }

    private fun parseFile(file: RandomAccessFile, start: Long, end: Long): List<Ref> {
        val refs = mutableListOf<Ref>()
        var cursor = start
        while (cursor + 8 <= end) {
            file.seek(cursor)
            val header = ByteArray(16)
            val read = file.read(header, 0, minOf(16, (end - cursor).toInt()))
            if (read < 8) break
            val size32 = uint(header, 0)
            val type = typeAt(header, 4)
            val headerSize = if (size32 == 1L) 16 else 8
            val size = when (size32) {
                0L -> end - cursor
                1L -> if (read >= 16) long(header, 8) else return refs
                else -> size32
            }
            if (size < headerSize || cursor + size > end) return refs + Ref("truncated", cursor, 0, 8, null)
            val children = if (type in containers && size <= 32L * 1024 * 1024) {
                val payload = ByteArray((size - headerSize).toInt())
                file.seek(cursor + headerSize)
                file.readFully(payload)
                parse(payload, 0, payload.size).map { shift(it, cursor + headerSize) }
            } else null
            refs += Ref(type, cursor, size, headerSize, children)
            cursor += size
        }
        return refs
    }

    private fun shift(ref: Ref, base: Long): Ref = Ref(ref.type, base + ref.offset, ref.size, ref.header, ref.children?.map { shift(it, base) })

    private fun scanRefs(refs: List<Ref>, source: ByteArray): Mp4Scan {
        val identifying = mutableListOf<String>()
        val times = mutableListOf<Pair<String, Long>>()
        var formed = refs.none { it.type == "truncated" }
        fun walk(nodes: List<Ref>, prefix: String) {
            nodes.forEach { node ->
                val here = if (prefix.isEmpty()) node.type else "$prefix/${node.type}"
                if (forbidden(node.type)) identifying += here
                if (node.type in timed) times += here to readCreation(slice(source, node))
                if (node.type == "truncated") formed = false
                node.children?.let { walk(it, here) }
            }
        }
        walk(refs, "")
        return Mp4Scan(identifying, times, formed && refs.isNotEmpty())
    }

    private fun rewrite(refs: List<Ref>, source: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        refs.forEach { ref ->
            if (forbidden(ref.type) || ref.type == "truncated") return@forEach
            val payload = when {
                ref.children != null -> rewrite(ref.children, source)
                ref.type in timed -> zeroTimes(slice(source, ref))
                else -> slice(source, ref)
            }
            writeBox(out, ref.type, payload)
        }
        return out.toByteArray()
    }

    private fun slice(source: ByteArray, ref: Ref): ByteArray {
        val start = (ref.offset + ref.header).toInt()
        val end = (ref.offset + ref.size).toInt()
        if (start < 0 || end > source.size || end < start) return ByteArray(0)
        return source.copyOfRange(start, end)
    }

    private fun writeRefs(refs: List<Ref>, source: RandomAccessFile, dest: java.io.OutputStream) {
        refs.forEach { ref ->
            if (forbidden(ref.type) || ref.type == "truncated") return@forEach
            if (ref.children != null || ref.type in timed) {
                val payload = if (ref.children != null) {
                    val nested = java.io.ByteArrayOutputStream()
                    writeRefs(ref.children, source, nested)
                    nested.toByteArray()
                } else {
                    val raw = ByteArray((ref.size - ref.header).toInt())
                    source.seek(ref.offset + ref.header)
                    source.readFully(raw)
                    zeroTimes(raw)
                }
                writeBox(dest, ref.type, payload)
            } else {
                streamBox(source, dest, ref)
            }
        }
    }

    private fun scanFile(file: File): Mp4Scan = RandomAccessFile(file, "r").use { scanBytes(it) }

    private fun scanBytes(file: RandomAccessFile): Mp4Scan {
        val identifying = mutableListOf<String>()
        val times = mutableListOf<Pair<String, Long>>()
        var formed = true
        fun walk(start: Long, end: Long, prefix: String) {
            var cursor = start
            while (cursor + 8 <= end) {
                file.seek(cursor)
                val header = ByteArray(16)
                val read = file.read(header, 0, minOf(16, (end - cursor).toInt()))
                if (read < 8) break
                val size32 = uint(header, 0)
                val type = typeAt(header, 4)
                val headerSize = if (size32 == 1L) 16 else 8
                val size = when (size32) {
                    0L -> end - cursor
                    1L -> if (read >= 16) long(header, 8) else { formed = false; return }
                    else -> size32
                }
                if (size < headerSize || cursor + size > end) { formed = false; return }
                val here = if (prefix.isEmpty()) type else "$prefix/$type"
                if (forbidden(type)) identifying += here
                if (type in timed) {
                    val payload = ByteArray(minOf(24, (size - headerSize).toInt()))
                    file.seek(cursor + headerSize)
                    file.readFully(payload)
                    times += here to readCreation(payload)
                }
                if (type in containers) walk(cursor + headerSize, cursor + size, here)
                cursor += size
            }
        }
        walk(0, file.length(), "")
        return Mp4Scan(identifying, times, formed && file.length() > 8)
    }

    private fun streamBox(source: RandomAccessFile, dest: java.io.OutputStream, ref: Ref) {
        source.seek(ref.offset)
        val buffer = ByteArray(65536)
        var left = ref.size
        while (left > 0) {
            val n = source.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) break
            dest.write(buffer, 0, n)
            left -= n
        }
    }

    private fun writeBox(dest: java.io.OutputStream, type: String, payload: ByteArray) {
        val size = 8 + payload.size
        val header = ByteArray(8)
        putInt(header, 0, size)
        type.padEnd(4, ' ').take(4).toByteArray(Charsets.ISO_8859_1).copyInto(header, 4)
        dest.write(header)
        dest.write(payload)
    }

    fun zeroTimes(payload: ByteArray): ByteArray {
        val out = payload.copyOf()
        if (out.isEmpty()) return out
        val version = out[0].toInt() and 0xFF
        if (version == 0 && out.size >= 12) {
            putInt(out, 4, 0)
            putInt(out, 8, 0)
        } else if (version == 1 && out.size >= 20) {
            putLong(out, 4, 0)
            putLong(out, 12, 0)
        }
        return out
    }

    fun readCreation(payload: ByteArray): Long {
        if (payload.isEmpty()) return -1
        return when (payload[0].toInt() and 0xFF) {
            0 -> if (payload.size >= 8) uint(payload, 4) else -1
            1 -> if (payload.size >= 12) long(payload, 4) else -1
            else -> -1
        }
    }

    private fun forbidden(type: String): Boolean {
        if (type == "udta" || type == "meta" || type == "uuid" || type == "ilst") return true
        if (type.isNotEmpty() && type[0].code == 0xA9) return true
        val trimmed = type.trim()
        return trimmed == "xyz" || type == "lxyz" || type == "auth" || type == "titl"
    }

    private fun typeAt(bytes: ByteArray, offset: Int) = bytes.copyOfRange(offset, offset + 4).toString(Charsets.ISO_8859_1)

    private fun uint(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or (bytes[offset + 3].toLong() and 0xFF)

    private fun long(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return value
    }

    private fun putInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun putLong(bytes: ByteArray, offset: Int, value: Long) {
        for (i in 7 downTo 0) bytes[offset + (7 - i)] = (value ushr (i * 8)).toByte()
    }
}
