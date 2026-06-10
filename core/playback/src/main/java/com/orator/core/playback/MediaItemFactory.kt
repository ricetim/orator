package com.orator.core.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.orator.core.model.MediaType

/**
 * Single place where a feature's PlayableItem becomes a Media3 MediaItem. Carries MediaType in
 * MediaMetadata.mediaType so service-side policy (smart rewind, history) can recover it without
 * parsing feature-owned mediaId strings. Clip windows become ClippingConfiguration: Media3 then
 * handles seeking/duration/transitions inside the clip natively, and every reported position is
 * clip-relative (stored as-is; see the Phase 3 plan's Orientation section).
 */
object MediaItemFactory {

    fun from(item: PlayableItem, mediaType: MediaType): MediaItem {
        val builder = MediaItem.Builder()
            .setMediaId(item.mediaId)
            .setUri(item.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.title)
                    .setArtist(item.artist)
                    .setMediaType(
                        when (mediaType) {
                            MediaType.AUDIOBOOK -> MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER
                            MediaType.PODCAST -> MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE
                        },
                    )
                    .build(),
            )
        if (item.clipStartMs > 0 || item.clipEndMs != null) {
            val clip = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(item.clipStartMs.coerceAtLeast(0))
            item.clipEndMs?.let { clip.setEndPositionMs(it) }
            builder.setClippingConfiguration(clip.build())
        }
        return builder.build()
    }

    /** Inverse of the mediaType mapping above; null for items we didn't build. */
    fun mediaTypeOf(metadata: MediaMetadata): MediaType? = when (metadata.mediaType) {
        MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER -> MediaType.AUDIOBOOK
        MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE -> MediaType.PODCAST
        else -> null
    }
}
