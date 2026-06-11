package com.orator.feature.podcasts.data.search

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeProvider(
    override val name: String,
    private val result: Result<List<PodcastSearchResult>>,
) : SearchProvider {
    var calls = 0
    override suspend fun search(term: String): Result<List<PodcastSearchResult>> {
        calls++
        return result
    }
}

private val A_RESULT = PodcastSearchResult("Show", null, "https://x/f.xml", null)

class CompositeSearchProviderTest {

    @Test
    fun `primary success short-circuits`() = runBlocking {
        val primary = FakeProvider("PI", Result.success(listOf(A_RESULT)))
        val fallback = FakeProvider("iTunes", Result.success(emptyList()))

        val answer = CompositeSearchProvider(primary, fallback).search("x").getOrThrow()

        assertEquals("PI", answer.provider)
        assertEquals(1, answer.results.size)
        assertEquals(0, fallback.calls)
    }

    @Test
    fun `primary failure falls through to fallback`() = runBlocking {
        val primary = FakeProvider("PI", Result.failure(IllegalStateException("not configured")))
        val fallback = FakeProvider("iTunes", Result.success(listOf(A_RESULT)))

        val answer = CompositeSearchProvider(primary, fallback).search("x").getOrThrow()

        assertEquals("iTunes", answer.provider)
    }

    @Test
    fun `both failing fails`() = runBlocking {
        val composite = CompositeSearchProvider(
            FakeProvider("PI", Result.failure(IllegalStateException("a"))),
            FakeProvider("iTunes", Result.failure(IllegalStateException("b"))),
        )
        assertTrue(composite.search("x").isFailure)
    }

    @Test
    fun `duplicate feed urls are collapsed`() = runBlocking {
        val primary = FakeProvider("PI", Result.success(listOf(A_RESULT, A_RESULT.copy(title = "Dupe"))))
        val answer = CompositeSearchProvider(primary, FakeProvider("iTunes", Result.success(emptyList())))
            .search("x").getOrThrow()
        assertEquals(1, answer.results.size)
    }
}
