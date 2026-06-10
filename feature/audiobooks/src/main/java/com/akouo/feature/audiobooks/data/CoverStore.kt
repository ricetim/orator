package com.akouo.feature.audiobooks.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/**
 * Extracted cover art lives in app-internal storage, not the user's SAF folder — it is a
 * regenerable cache derived from the audio files, not user data (architecture §6 applies
 * the shared-folder rule to downloaded/user-owned bytes).
 */
class CoverStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun save(bookId: String, bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty()) return null
        val dir = File(context.filesDir, "covers").apply { mkdirs() }
        val file = File(dir, "$bookId.jpg")
        file.writeBytes(bytes)
        return file.absolutePath
    }
}
