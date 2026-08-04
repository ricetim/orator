package com.orator.feature.audiobookshelf.data

import com.orator.core.database.BookEntity
import com.orator.core.database.SourceKind
import com.orator.core.model.BookOrigin
import com.orator.core.model.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Test

class AbsCatalogReconcilerTest {
    private fun abs(
        id: String, title: String, pos: Long = 0, dl: DownloadState = DownloadState.NONE,
        uri: String = "", added: Long = 0, desc: String? = null, series: String? = null,
    ) = BookEntity(
        id = id, title = title, author = null, coverPath = null, sourceUri = uri,
        sourceKind = SourceKind.SINGLE_FILE, durationMs = 0, positionMs = pos, addedAtUtc = added,
        origin = BookOrigin.ABS, serverId = "s", absItemId = id.removePrefix("abs:"),
        downloadState = dl, description = desc, series = series,
    )

    @Test fun `new items are inserted, missing items deleted, metadata refreshed`() {
        val existing = listOf(abs("abs:1", "Old Title", pos = 5000), abs("abs:2", "Gone"))
        val incoming = listOf(abs("abs:1", "New Title"), abs("abs:3", "Fresh"))
        val r = AbsCatalogReconciler.reconcile(existing, incoming)
        assertEquals("New Title", r.upserts.first { it.id == "abs:1" }.title)
        assertEquals(5000, r.upserts.first { it.id == "abs:1" }.positionMs)
        assertEquals(setOf("abs:1", "abs:3"), r.upserts.map { it.id }.toSet())
        assertEquals(listOf("abs:2"), r.deletes)
    }

    @Test fun `re-sync prefers the server addedAt`() {
        val existing = listOf(abs("abs:1", "T", added = 111))
        val incoming = listOf(abs("abs:1", "T", added = 999))    // server value present
        assertEquals(999, AbsCatalogReconciler.reconcile(existing, incoming).upserts.single().addedAtUtc)
    }

    @Test fun `re-sync keeps previous addedAt when server omits it`() {
        val existing = listOf(abs("abs:1", "T", added = 111))
        val incoming = listOf(abs("abs:1", "T", added = 0))      // 0 = server omitted
        assertEquals(111, AbsCatalogReconciler.reconcile(existing, incoming).upserts.single().addedAtUtc)
    }

    @Test fun `new item without server addedAt gets the injected now`() {
        val incoming = listOf(abs("abs:1", "T", added = 0))
        val r = AbsCatalogReconciler.reconcile(emptyList(), incoming, now = 12_345)
        assertEquals(12_345, r.upserts.single().addedAtUtc)
    }

    @Test fun `re-sync keeps lazily-resolved description and series when sync payload lacks them`() {
        val existing = listOf(abs("abs:1", "T", desc = "blurb", series = "Foundation #2"))
        val incoming = listOf(abs("abs:1", "T"))                 // minified sync: both null
        val merged = AbsCatalogReconciler.reconcile(existing, incoming).upserts.single()
        assertEquals("blurb", merged.description)
        assertEquals("Foundation #2", merged.series)
    }

    @Test fun `re-sync prefers fresh series from the server when present`() {
        val existing = listOf(abs("abs:1", "T", series = "Old #1"))
        val incoming = listOf(abs("abs:1", "T", series = "New #3"))
        assertEquals("New #3", AbsCatalogReconciler.reconcile(existing, incoming).upserts.single().series)
    }

    @Test fun `new item keeps its server addedAt`() {
        val incoming = listOf(abs("abs:1", "T", added = 777))
        assertEquals(777, AbsCatalogReconciler.reconcile(emptyList(), incoming, now = 12_345).upserts.single().addedAtUtc)
    }

    @Test fun `downloaded books keep their local sourceUri and download state`() {
        val existing = listOf(abs("abs:1", "T", dl = DownloadState.DOWNLOADED, uri = "content://local/1"))
        val incoming = listOf(abs("abs:1", "T", uri = ""))
        val r = AbsCatalogReconciler.reconcile(existing, incoming)
        val merged = r.upserts.single()
        assertEquals(DownloadState.DOWNLOADED, merged.downloadState)
        assertEquals("content://local/1", merged.sourceUri)
    }
}
