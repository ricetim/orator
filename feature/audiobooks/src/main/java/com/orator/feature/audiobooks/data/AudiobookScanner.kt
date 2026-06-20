package com.orator.feature.audiobooks.data

/** A book found on disk, before any metadata extraction. */
sealed interface ScannedBook {
    val title: String
    val rootUri: String

    /** Exactly one audio file; chapters come from its internal `chpl` (or whole-file). */
    data class SingleFile(override val title: String, override val rootUri: String) : ScannedBook

    /** Several audio files (natural-sorted); chapters are each file's `chpl` flattened in order. */
    data class MultiFile(
        override val title: String,
        override val rootUri: String,
        val files: List<ScannedFile>,
    ) : ScannedBook
}

data class ScannedFile(val name: String, val uri: String)

/**
 * Walks a picked folder. A directory that directly contains audio files is one book —
 * `SingleFile` when there's exactly one file, `MultiFile` (natural-sorted) when there are
 * several. Subdirectories are always scanned independently, so nested libraries
 * (`Author/Book/parts`) are found and a stray file never hides a subfolder's book.
 *
 * Limitation (v1): a book split across sibling subdirectories with no audio at the parent level
 * (CD1/CD2-style) scans as separate books.
 */
object AudiobookScanner {

    private val AUDIO_EXTS = listOf(".m4b", ".mp3")
    private fun isAudio(name: String) = AUDIO_EXTS.any { name.endsWith(it, ignoreCase = true) }

    fun scan(root: DocumentNode): List<ScannedBook> =
        mutableListOf<ScannedBook>().also { scanDirectory(root, it) }

    private fun scanDirectory(dir: DocumentNode, sink: MutableList<ScannedBook>) {
        val children = dir.children()
        val audio = children
            .filter { !it.isDirectory && isAudio(it.name) }
            .sortedWith(compareBy(NaturalOrder) { it.name })

        when {
            audio.size == 1 -> sink.add(
                ScannedBook.SingleFile(
                    title = audio[0].name.substringBeforeLast('.'),
                    rootUri = audio[0].uri,
                ),
            )
            audio.size >= 2 -> sink.add(
                ScannedBook.MultiFile(
                    title = dir.name,
                    rootUri = dir.uri,
                    files = audio.map { ScannedFile(it.name, it.uri) },
                ),
            )
        }

        // Always recurse so nested books are found and a stray file never hides subfolders.
        children.filter { it.isDirectory }.forEach { scanDirectory(it, sink) }
    }
}
