package com.orator.core.playback

import androidx.media3.common.MediaMetadata
import com.orator.core.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaItemFactoryTest {

    private val item = PlayableItem(
        mediaId = "audiobook/abc/0",
        uri = "content://x/file.m4b",
        title = "Book",
        artist = "Author",
    )

    @Test
    fun `maps identity, metadata and media type`() {
        val mi = MediaItemFactory.from(item, MediaType.AUDIOBOOK)
        assertEquals("audiobook/abc/0", mi.mediaId)
        assertEquals("Book", mi.mediaMetadata.title.toString())
        assertEquals("Author", mi.mediaMetadata.artist.toString())
        assertEquals(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER, mi.mediaMetadata.mediaType)

        val pod = MediaItemFactory.from(item, MediaType.PODCAST)
        assertEquals(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE, pod.mediaMetadata.mediaType)
    }

    @Test
    fun `no clip fields means no clipping configuration`() {
        val mi = MediaItemFactory.from(item, MediaType.AUDIOBOOK)
        assertEquals(0L, mi.clippingConfiguration.startPositionMs)
        assertEquals(androidx.media3.common.C.TIME_END_OF_SOURCE, mi.clippingConfiguration.endPositionMs)
    }

    @Test
    fun `clip fields map to ClippingConfiguration`() {
        val clipped = MediaItemFactory.from(
            item.copy(clipStartMs = 25_000, clipEndMs = 1_800_000),
            MediaType.PODCAST,
        )
        assertEquals(25_000L, clipped.clippingConfiguration.startPositionMs)
        assertEquals(1_800_000L, clipped.clippingConfiguration.endPositionMs)
    }

    @Test
    fun `media type round-trips back out of metadata`() {
        val mi = MediaItemFactory.from(item, MediaType.PODCAST)
        assertEquals(MediaType.PODCAST, MediaItemFactory.mediaTypeOf(mi.mediaMetadata))
        assertEquals(null, MediaItemFactory.mediaTypeOf(MediaMetadata.EMPTY))
    }
}
