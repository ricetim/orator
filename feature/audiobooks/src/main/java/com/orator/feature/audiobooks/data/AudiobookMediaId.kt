package com.orator.feature.audiobooks.data

/**
 * Encodes which (book, file-in-queue) a Media3 MediaItem represents, so the position
 * listener can route service callbacks back to a Room row. Format: "audiobook/<bookId>/<fileIndex>".
 */
object AudiobookMediaId {
    private const val PREFIX = "audiobook"

    data class Parsed(val bookId: String, val fileIndex: Int)

    fun encode(bookId: String, fileIndex: Int): String = "$PREFIX/$bookId/$fileIndex"

    fun parse(mediaId: String): Parsed? {
        val parts = mediaId.split('/')
        if (parts.size != 3 || parts[0] != PREFIX) return null
        val index = parts[2].toIntOrNull() ?: return null
        return Parsed(bookId = parts[1], fileIndex = index)
    }
}
