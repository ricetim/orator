package com.akouo.feature.audiobooks.data

/** A book found on disk, before any metadata extraction. */
sealed interface ScannedBook {
    val title: String
    val rootUri: String

    data class M4b(override val title: String, override val rootUri: String) : ScannedBook

    data class Mp3Collection(
        override val title: String,
        override val rootUri: String,
        val files: List<ScannedFile>,
    ) : ScannedBook
}

data class ScannedFile(val name: String, val uri: String)

/**
 * Walks a picked folder and finds books:
 *  - every .m4b file is a book, at any depth;
 *  - every directory whose DIRECT children include .mp3 files is a book of those files
 *    (natural-sorted); its subdirectories are not entered (v1 simplification — a
 *    CD1/CD2-style book without root-level mp3s shows up as two books).
 */
object AudiobookScanner {

    fun scan(root: DocumentNode): List<ScannedBook> {
        val books = mutableListOf<ScannedBook>()
        scanDirectory(root, books)
        return books
    }

    private fun scanDirectory(dir: DocumentNode, sink: MutableList<ScannedBook>) {
        val children = dir.children()

        children
            .filter { !it.isDirectory && it.name.endsWith(".m4b", ignoreCase = true) }
            .forEach { sink.add(ScannedBook.M4b(title = it.name.removeSuffix(".m4b"), rootUri = it.uri)) }

        val mp3s = children
            .filter { !it.isDirectory && it.name.endsWith(".mp3", ignoreCase = true) }
            .sortedWith(compareBy(NaturalOrder) { it.name })
        if (mp3s.isNotEmpty()) {
            sink.add(
                ScannedBook.Mp3Collection(
                    title = dir.name,
                    rootUri = dir.uri,
                    files = mp3s.map { ScannedFile(it.name, it.uri) },
                ),
            )
            return // claimed as a book; don't descend further
        }

        children.filter { it.isDirectory }.forEach { scanDirectory(it, sink) }
    }
}
