package com.orator.feature.audiobookshelf.data

import com.orator.core.database.ChapterEntity

data class RemoteFile(val remoteUrl: String, val localName: String)
data class RewriteResult(val sourceUri: String, val chapters: List<ChapterEntity>)

/** Pure plan: distinct remote files to fetch, plus how to rewrite the book once they are local. */
data class AbsDownloadPlan(val files: List<RemoteFile>) {
    fun rewrite(
        chapters: List<ChapterEntity>,
        sourceUri: String,
        localByRemote: Map<String, String>,
    ): RewriteResult = RewriteResult(
        sourceUri = localByRemote[sourceUri] ?: sourceUri,
        chapters = chapters.map { it.copy(fileUri = localByRemote[it.fileUri] ?: it.fileUri) },
    )

    companion object {
        fun from(sourceUri: String, chapters: List<ChapterEntity>): AbsDownloadPlan {
            val distinct = (listOf(sourceUri) + chapters.map { it.fileUri }).distinct()
            return AbsDownloadPlan(
                distinct.mapIndexed { i, url -> RemoteFile(url, "track-%03d".format(i)) },
            )
        }
    }
}
