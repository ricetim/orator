package com.orator.feature.playlists.data

import com.orator.core.model.MediaRef
import com.orator.core.model.MediaType
import com.orator.core.playback.ids.AudiobookMediaId
import com.orator.core.playback.ids.PodcastMediaId

/**
 * Decides whether the player's currently-loaded (encoded) mediaId corresponds to [ref]. Used by
 * the controller to detect when the user has played something outside the active playlist. For an
 * audiobook the encoded id carries a file index (`audiobook/<id>/<fileIndex>`) which is IGNORED,
 * so a multi-file book stays matched across its internal file→file transitions.
 */
object MediaRefMatch {
    fun matches(ref: MediaRef, encodedMediaId: String?): Boolean {
        if (encodedMediaId.isNullOrBlank()) return false
        return when (ref.type) {
            MediaType.PODCAST -> PodcastMediaId.parse(encodedMediaId) == ref.id
            MediaType.AUDIOBOOK -> AudiobookMediaId.parse(encodedMediaId)?.bookId == ref.id
        }
    }
}
