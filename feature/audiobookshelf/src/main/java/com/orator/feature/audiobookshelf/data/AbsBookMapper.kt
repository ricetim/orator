package com.orator.feature.audiobookshelf.data

import com.orator.core.database.BookEntity
import com.orator.core.database.SourceKind
import com.orator.core.model.BookOrigin

object AbsBookMapper {
    fun toBook(item: AbsLibraryItem, serverId: String, baseUrl: String): BookEntity {
        val md = item.media.metadata
        val multi = item.media.numAudioFiles > 1
        val series = md.seriesName?.substringBefore(",")?.trim()?.takeIf { it.isNotBlank() } // first series; a comma IN a series name truncates (ABS flat-string limitation, detail resolver corrects)
        return BookEntity(
            id = "abs:${item.id}",
            title = md.title.ifBlank { item.id },
            author = md.authorName,
            coverPath = AbsUrl.endpoint(baseUrl, "api/items/${item.id}/cover"),
            sourceUri = "",                                   // filled lazily on first play (Chunk 4)
            sourceKind = if (multi) SourceKind.MULTI_FILE else SourceKind.SINGLE_FILE,
            durationMs = (item.media.duration * 1000).toLong(),
            addedAtUtc = item.addedAt ?: 0L,                  // 0 = server omitted; resolved by AbsCatalogReconciler
            origin = BookOrigin.ABS,
            serverId = serverId,
            absItemId = item.id,
            series = series,
        )
    }
}
