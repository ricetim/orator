package com.orator.feature.podcasts.data

import com.orator.core.database.EpisodeEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class NewEpisodeIdsTest {

    private fun ep(id: String) =
        EpisodeEntity(id = id, podcastId = "p", title = id, pubDateUtc = 0, enclosureUrl = "u")

    @Test fun `keeps only ids whose rowid is not -1, in order`() {
        val es = listOf(ep("a"), ep("b"), ep("c"))
        assertEquals(listOf("a", "c"), NewEpisodeIds.from(es, listOf(5L, -1L, 7L)))
    }

    @Test fun `empty when all are duplicates`() {
        val es = listOf(ep("a"), ep("b"))
        assertEquals(emptyList<String>(), NewEpisodeIds.from(es, listOf(-1L, -1L)))
    }

    @Test fun `empty inputs yield empty`() {
        assertEquals(emptyList<String>(), NewEpisodeIds.from(emptyList(), emptyList()))
    }
}
