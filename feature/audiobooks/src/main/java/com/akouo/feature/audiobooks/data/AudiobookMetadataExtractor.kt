package com.akouo.feature.audiobooks.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

data class ExtractedMetadata(
    val title: String?,
    val author: String?,
    val durationMs: Long,
    val coverBytes: ByteArray?,
)

/** Reads embedded tags from an audio document. Interface exists for JVM-side fakes. */
interface AudiobookMetadataExtractor {
    fun extract(uri: Uri): ExtractedMetadata
}

class MmrMetadataExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
) : AudiobookMetadataExtractor {

    override fun extract(uri: Uri): ExtractedMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            ExtractedMetadata(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                author = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L,
                coverBytes = retriever.embeddedPicture,
            )
        } catch (e: Exception) {
            // Unreadable/corrupt file: import proceeds with filename-derived metadata.
            ExtractedMetadata(title = null, author = null, durationMs = 0, coverBytes = null)
        } finally {
            retriever.release()
        }
    }
}
