package com.akouo.feature.audiobooks.data

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Extracts chapter marks from an .m4b (MP4) stream by locating the Nero chapter box at
 * moov/udta/chpl — written by ffmpeg and most audiobook tools by default. Files carrying
 * only the QuickTime chapter-track variant (tref/chap) are out of scope for v1 and fall
 * back to a single full-length chapter at import time.
 *
 * Forward-only on purpose: ContentResolver streams from SAF can't seek, so the walker
 * skips past boxes (including a possibly multi-hundred-MB mdat) rather than seeking.
 * MP4 box wire format: [u32 size][4cc type][payload]; size==1 → u64 size follows;
 * size==0 → box extends to end of file.
 */
object Mp4ChapterParser {

    data class Chapter(val title: String, val startMs: Long)

    private val CONTAINERS = setOf("moov", "udta")

    fun parse(input: InputStream): List<Chapter> =
        try {
            DataInputStream(input.buffered()).use { walkBoxes(it, Long.MAX_VALUE) }
        } catch (_: IOException) {
            emptyList()
        }

    /** Reads boxes until [limit] payload bytes are consumed; returns chapters if chpl found. */
    private fun walkBoxes(stream: DataInputStream, limit: Long): List<Chapter> {
        var remaining = limit
        while (remaining >= 8) {
            val size32: Long
            val type: String
            try {
                size32 = stream.readInt().toLong() and 0xFFFFFFFFL
                type = String(ByteArray(4).also { stream.readFully(it) }, Charsets.US_ASCII)
            } catch (_: EOFException) {
                return emptyList() // clean end of stream at a box boundary
            }
            var headerLen = 8L
            val boxSize = when (size32) {
                1L -> {
                    headerLen = 16L
                    stream.readLong()
                }
                0L -> remaining // "to end of file/container"
                else -> size32
            }
            val payload = boxSize - headerLen
            if (payload < 0) return emptyList() // corrupt; bail quietly

            when {
                type == "chpl" -> return readChpl(stream)
                type in CONTAINERS -> {
                    val found = walkBoxes(stream, payload)
                    if (found.isNotEmpty()) return found
                }
                else -> skipFully(stream, payload)
            }
            remaining -= boxSize
        }
        if (remaining in 1..7) skipFully(stream, remaining)
        return emptyList()
    }

    /**
     * ffmpeg layout (byte-verified against a real ffmpeg file): u8 version(=1), u24 flags,
     * u32 reserved, u8 count, then per chapter: u64 start time in 100-nanosecond units,
     * u8 title length, UTF-8 title bytes.
     */
    private fun readChpl(stream: DataInputStream): List<Chapter> {
        val version = stream.readUnsignedByte()
        skipFully(stream, 3) // flags
        if (version != 1) return emptyList()
        skipFully(stream, 4) // reserved
        val count = stream.readUnsignedByte()
        val chapters = ArrayList<Chapter>(count)
        repeat(count) {
            val start100ns = stream.readLong()
            val titleLen = stream.readUnsignedByte()
            val title = ByteArray(titleLen).also { stream.readFully(it) }
            chapters.add(Chapter(String(title, Charsets.UTF_8), start100ns / 10_000))
        }
        return chapters
    }

    private fun skipFully(stream: InputStream, bytes: Long) {
        var left = bytes
        while (left > 0) {
            val skipped = stream.skip(left)
            if (skipped > 0) {
                left -= skipped
            } else {
                if (stream.read() == -1) throw EOFException("EOF while skipping box payload")
                left--
            }
        }
    }
}
