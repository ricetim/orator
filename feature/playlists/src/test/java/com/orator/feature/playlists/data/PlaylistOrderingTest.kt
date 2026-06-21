package com.orator.feature.playlists.data

import com.orator.core.database.PlaylistItemPosition
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistOrderingTest {

    @Test fun `reindex assigns dense 10-step positions`() {
        assertEquals(
            listOf(PlaylistItemPosition(5, 10), PlaylistItemPosition(9, 20)),
            PlaylistOrdering.reindex(listOf(5L, 9L)),
        )
    }

    @Test fun `moveToTop promotes the chosen id, others keep relative order`() {
        assertEquals(listOf(3L, 1L, 2L), PlaylistOrdering.moveToTop(listOf(1L, 2L, 3L), id = 3L))
    }

    @Test fun `moveToTop of the current top is a no-op order`() {
        assertEquals(listOf(1L, 2L, 3L), PlaylistOrdering.moveToTop(listOf(1L, 2L, 3L), id = 1L))
    }

    @Test fun `moveToTop of an absent id leaves the list unchanged`() {
        assertEquals(listOf(1L, 2L, 3L), PlaylistOrdering.moveToTop(listOf(1L, 2L, 3L), id = 9L))
    }

    @Test fun `move shifts an id from one index to another`() {
        assertEquals(listOf(2L, 3L, 1L), PlaylistOrdering.move(listOf(1L, 2L, 3L), from = 0, to = 2))
        assertEquals(listOf(3L, 1L, 2L), PlaylistOrdering.move(listOf(1L, 2L, 3L), from = 2, to = 0))
    }

    @Test fun `move with out-of-range or equal indices is a no-op`() {
        assertEquals(listOf(1L, 2L, 3L), PlaylistOrdering.move(listOf(1L, 2L, 3L), from = 1, to = 1))
        assertEquals(listOf(1L, 2L, 3L), PlaylistOrdering.move(listOf(1L, 2L, 3L), from = 0, to = 5))
    }

    @Test fun `remove drops an id`() {
        assertEquals(listOf(1L, 3L), PlaylistOrdering.remove(listOf(1L, 2L, 3L), id = 2L))
    }
}
