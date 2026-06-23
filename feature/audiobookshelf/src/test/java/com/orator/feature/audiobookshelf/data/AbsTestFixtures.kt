package com.orator.feature.audiobookshelf.data

import com.orator.core.database.BookEntity
import com.orator.core.database.ChapterEntity
import com.orator.core.database.SourceKind
import com.orator.core.model.BookOrigin
import com.orator.core.model.DownloadState

fun connectedStore(): AbsCredentialStore = AbsCredentialStore(object : SecureStringStore {
    val m = mutableMapOf<String, String>()
    override fun get(key: String) = m[key]
    override fun put(key: String, value: String) { m[key] = value }
    override fun clear() = m.clear()
}).apply { save(AbsServerConfig("https://abs.example.com", "https://abs.example.com", "u", "tok")) }

fun absBook(
    id: String,
    sourceUri: String = "",
    dl: DownloadState = DownloadState.NONE,
): BookEntity = BookEntity(
    id = id, title = id, author = null, coverPath = null, sourceUri = sourceUri,
    sourceKind = SourceKind.SINGLE_FILE, durationMs = 0, addedAtUtc = 0,
    origin = BookOrigin.ABS, absItemId = id.removePrefix("abs:"), downloadState = dl,
)

fun chapter(bookId: String, index: Int, fileUri: String): ChapterEntity =
    ChapterEntity(bookId = bookId, chapterIndex = index, title = "c$index", fileUri = fileUri, startMs = 0, durationMs = 1000)
