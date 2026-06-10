package com.orator.feature.audiobooks.data

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Opens an m4b document and reads its chpl chapters. Interface exists for JVM-side fakes. */
interface M4bChapterSource {
    fun chaptersOf(uri: Uri): List<Mp4ChapterParser.Chapter>
}

class ContentResolverM4bChapterSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : M4bChapterSource {
    override fun chaptersOf(uri: Uri): List<Mp4ChapterParser.Chapter> =
        context.contentResolver.openInputStream(uri)?.use(Mp4ChapterParser::parse).orEmpty()
}
