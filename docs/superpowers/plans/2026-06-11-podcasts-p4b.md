# Phase 4b: Podcasts (discovery / transcripts / unsubscribe) Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Search podcasts (Podcast Index primary, iTunes fallback) and subscribe from results; cache and display Podcasting-2.0 transcripts; unsubscribe a show with full cache-tree cleanup.

**Architecture:** Everything new lives in `feature/podcasts` (the P4a plan-level decision keeps parsing/JSON out of `core:network`). Search providers inject the existing `OkHttpClient` behind a `SearchProvider` seam composed PI-first/iTunes-fallback; transcripts extend the existing parser → DAO → cache-writer → downloader seams; unsubscribe composes DAOs that already exist plus one new recursive tree delete. Room goes to v4 (destructive, pre-release policy).

**Tech Stack:** Kotlin, Hilt, Room 2.7.1, OkHttp 4.12.0 (existing), `org.json`, `MessageDigest` SHA-1, MockWebServer (test-only, already in the catalog). **Zero new artifacts.**

**Spec:** `docs/superpowers/specs/2026-06-11-podcasts-p4b-design.md` (binding, including its "Plan-level decisions").

**Conventions (P1–P4a):** `./gradlew` only; Room JVM tests = Robolectric + `runBlocking`; `org.json`/`XmlPullParser`/`HtmlCompat` in tests ⇒ `@RunWith(RobolectricTestRunner::class)` + `@Config(sdk = [34])`; feature modules never depend on other features; placeholder UI with centered menus; Read files before editing; commit per task with the given message; **never print or commit the Podcast Index credentials** (they live only in gitignored `local.properties`).

**File map:**

```
feature/podcasts/build.gradle.kts            (modify: buildConfig=true, buildConfigFields, +mockwebserver)
feature/podcasts/src/main/java/com/orator/feature/podcasts/
    data/search/SearchProvider.kt            (PodcastSearchResult, SearchProvider, SearchAnswer)
    data/search/ItunesSearchProvider.kt
    data/search/PodcastIndexSearchProvider.kt
    data/search/CompositeSearchProvider.kt
    data/search/SearchModule.kt              (Hilt @Provides)
    data/TranscriptText.kt                   (pure VTT/SRT/JSON/plain → text)
    data/TranscriptFetcher.kt
    data/RssParser.kt                        (modify: podcast:transcript)
    data/EpisodeCacheWriter.kt               (modify: public writeEpisodeFile, mime map, deleteShowDir)
    data/EpisodeDownloader.kt                (modify: auto transcript fetch on success)
    data/PodcastRepository.kt                (modify: transcript fields in upsert; unsubscribe)
    SearchViewModel.kt  SearchScreen.kt
    EpisodeDetailViewModel.kt                (modify: transcript flows + actions)
    EpisodeDetailScreen.kt                   (modify: Get transcript + text block)
    PodcastDetailViewModel.kt                (modify: onUnsubscribe)
    PodcastDetailScreen.kt                   (modify: Unsubscribe + confirm)
    PodcastListScreen.kt                     (modify: Search button)
    PodcastsFeatureEntry.kt                  (modify: search route + unsubscribe nav)
    PodcastsRoutes.kt                        (modify: PodcastSearchRoute)
core/database/.../EpisodeEntity.kt           (modify: transcriptUrl/Type/Path)
core/database/.../EpisodeDao.kt              (modify: updateMetadata, +updateTranscriptPath)
core/database/.../OratorDatabase.kt          (modify: version = 4)
feature/podcasts/src/test/...                (one test class per new unit; fixture updates)
```

---

## Chunk 1: discovery search

### Task 1: BuildConfig credentials plumbing

**Files:**
- Modify: `feature/podcasts/build.gradle.kts`

- [ ] **Step 1: Read the file, then apply these changes**

At the very top of the file, the import line ONLY:

```kotlin
import java.util.Properties
```

Then **after the `plugins {}` block and before `android {}`** (Gradle KTS forbids any
statement other than imports/buildscript above `plugins {}` — putting this higher fails
script compilation):

```kotlin
// Podcast Index credentials live in gitignored local.properties; blank when absent so
// the PI provider reports "not configured" and search falls through to iTunes.
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
```

Inside `android { defaultConfig { ... } }` add:

```kotlin
        buildConfigField(
            "String",
            "PODCASTINDEX_KEY",
            "\"${localProps.getProperty("podcastindex.apiKey", "")}\"",
        )
        buildConfigField(
            "String",
            "PODCASTINDEX_SECRET",
            "\"${localProps.getProperty("podcastindex.apiSecret", "")}\"",
        )
```

Inside `android { buildFeatures { ... } }` add (AGP 8.7 has BuildConfig generation OFF by default):

```kotlin
        buildConfig = true
```

In `dependencies`, next to the other test deps:

```kotlin
    testImplementation(libs.okhttp.mockwebserver)
```

- [ ] **Step 2: Verify** — Run: `./gradlew :feature:podcasts:assembleDebug` — BUILD SUCCESSFUL. Then confirm generated fields exist (without printing values):

```bash
grep -c "PODCASTINDEX" feature/podcasts/build/generated/source/buildConfig/debug/com/orator/feature/podcasts/BuildConfig.java
```

Expected: `2`

- [ ] **Step 3: Commit**

```bash
git add feature/podcasts/build.gradle.kts
git commit -m "build: BuildConfig credentials plumbing for Podcast Index"
```

### Task 2: SearchProvider seam + ItunesSearchProvider

**Files:**
- Create: `feature/podcasts/src/main/java/com/orator/feature/podcasts/data/search/SearchProvider.kt`, `ItunesSearchProvider.kt`
- Test: `feature/podcasts/src/test/java/com/orator/feature/podcasts/data/search/ItunesSearchProviderTest.kt`

- [ ] **Step 1: Write the failing tests** (Robolectric for `org.json`; MockWebServer injected via the `baseUrl` constructor seam)

```kotlin
package com.orator.feature.podcasts.data.search

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ItunesSearchProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: ItunesSearchProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = ItunesSearchProvider(OkHttpClient(), baseUrl = server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `maps results and drops rows without a feed url`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"resultCount":3,"results":[
                  {"collectionName":"Show A","artistName":"Jane","feedUrl":"https://x/a.xml","artworkUrl600":"https://x/a.jpg"},
                  {"collectionName":"No Feed","artistName":"Bob"},
                  {"collectionName":"Show B","feedUrl":"https://x/b.xml"}
                ]}""",
            ),
        )

        val results = provider.search("test").getOrThrow()

        assertEquals(2, results.size)
        assertEquals("Show A", results[0].title)
        assertEquals("Jane", results[0].author)
        assertEquals("https://x/a.xml", results[0].feedUrl)
        assertEquals("https://x/a.jpg", results[0].artworkUrl)
        assertEquals("https://x/b.xml", results[1].feedUrl)
        val request = server.takeRequest()
        assertTrue(request.path!!.contains("media=podcast"))
        assertTrue(request.path!!.contains("term=test"))
    }

    @Test
    fun `http error maps to failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(provider.search("x").isFailure)
    }
}
```

- [ ] **Step 2: Run to verify failure** — `./gradlew :feature:podcasts:testDebugUnitTest --tests "*ItunesSearchProviderTest"` — FAIL (unresolved references).

- [ ] **Step 3: Implement**

`SearchProvider.kt`:

```kotlin
package com.orator.feature.podcasts.data.search

/** Everything discovery yields; subscribing reuses PodcastRepository.subscribe(feedUrl). */
data class PodcastSearchResult(
    val title: String,
    val author: String?,
    val feedUrl: String,
    val artworkUrl: String?,
)

/** PI rejects requests without a User-Agent; sent on every search request for symmetry. */
internal const val SEARCH_USER_AGENT = "Orator/0.1 (Android podcast player)"

interface SearchProvider {
    val name: String
    suspend fun search(term: String): Result<List<PodcastSearchResult>>
}
```

`ItunesSearchProvider.kt`:

```kotlin
package com.orator.feature.podcasts.data.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/** Keyless fallback; the only required field is feedUrl — rows without one are useless. */
class ItunesSearchProvider(
    private val client: OkHttpClient,
    private val baseUrl: String = "https://itunes.apple.com",
) : SearchProvider {

    override val name: String = "iTunes"

    override suspend fun search(term: String): Result<List<PodcastSearchResult>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "$baseUrl/search?media=podcast&limit=25&term=" +
                    URLEncoder.encode(term, "UTF-8")
                val request = Request.Builder().url(url)
                    .header("User-Agent", SEARCH_USER_AGENT)
                    .build()
                client.newCall(request).execute().use { response ->
                    require(response.isSuccessful) { "HTTP ${response.code}" }
                    val results = JSONObject(response.body?.string().orEmpty())
                        .optJSONArray("results") ?: return@use emptyList()
                    buildList {
                        for (i in 0 until results.length()) {
                            val row = results.getJSONObject(i)
                            val feedUrl = row.optString("feedUrl")
                                .takeIf { it.isNotBlank() } ?: continue
                            add(
                                PodcastSearchResult(
                                    title = row.optString("collectionName"),
                                    author = row.optString("artistName").takeIf { it.isNotBlank() },
                                    feedUrl = feedUrl,
                                    artworkUrl = row.optString("artworkUrl600").takeIf { it.isNotBlank() },
                                ),
                            )
                        }
                    }
                }
            }
        }
}
```

- [ ] **Step 4: Run tests** — 2 PASS.

- [ ] **Step 5: Commit**

```bash
git add feature/podcasts/src
git commit -m "feat: SearchProvider seam + keyless iTunes search fallback"
```

### Task 3: PodcastIndexSearchProvider

**Files:**
- Create: `feature/podcasts/src/main/java/com/orator/feature/podcasts/data/search/PodcastIndexSearchProvider.kt`
- Test: `feature/podcasts/src/test/java/com/orator/feature/podcasts/data/search/PodcastIndexSearchProviderTest.kt`

- [ ] **Step 1: Failing tests**

```kotlin
package com.orator.feature.podcasts.data.search

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PodcastIndexSearchProviderTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun provider(key: String = "k", secret: String = "s") = PodcastIndexSearchProvider(
        client = OkHttpClient(),
        key = key,
        secret = secret,
        baseUrl = server.url("/").toString().trimEnd('/'),
        epochSeconds = { 1_780_000_000L },
    )

    @Test
    fun `sends documented auth headers`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"true","feeds":[]}"""))

        provider().search("test").getOrThrow()

        val request = server.takeRequest()
        assertEquals("k", request.getHeader("X-Auth-Key"))
        assertEquals("1780000000", request.getHeader("X-Auth-Date"))
        // Literal SHA-1 of the ASCII string "ks1780000000" (printf 'ks1780000000' | sha1sum)
        // — a hardcoded digest catches a wrong algorithm/hex bug that asserting
        // sha1Hex-against-itself would miss.
        assertEquals(
            "34c56d23e1f97c9bf0c5124359b44069755fc2a6",
            request.getHeader("Authorization"),
        )
        assertEquals(SEARCH_USER_AGENT, request.getHeader("User-Agent"))
        assertTrue(request.path!!.contains("/api/1.0/search/byterm"))
    }

    @Test
    fun `maps feeds and drops rows without url`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"status":"true","feeds":[
                  {"title":"Show A","author":"Jane","url":"https://x/a.xml","artwork":"https://x/a.jpg"},
                  {"title":"No Url","author":"Bob"},
                  {"title":"Show B","url":"https://x/b.xml","image":"https://x/b.jpg"}
                ]}""",
            ),
        )

        val results = provider().search("x").getOrThrow()

        assertEquals(2, results.size)
        assertEquals("https://x/a.jpg", results[0].artworkUrl)
        assertEquals("https://x/b.jpg", results[1].artworkUrl) // falls back to "image"
    }

    @Test
    fun `blank credentials fail fast without a request`() = runBlocking {
        val result = provider(secret = "").search("x")
        assertTrue(result.isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `401 maps to failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        assertTrue(provider().search("x").isFailure)
    }
}
```

- [ ] **Step 2: Run to verify failure.**

- [ ] **Step 3: Implement**

```kotlin
package com.orator.feature.podcasts.data.search

import com.orator.feature.podcasts.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Podcast Index `search/byterm` with the documented auth scheme: SHA-1(key+secret+date) in
 * the Authorization header. ToS note: this is the ONLY PI endpoint the app calls, always
 * user-initiated — refreshes go straight to publishers' feeds, never through PI.
 */
class PodcastIndexSearchProvider(
    private val client: OkHttpClient,
    private val key: String = BuildConfig.PODCASTINDEX_KEY,
    private val secret: String = BuildConfig.PODCASTINDEX_SECRET,
    private val baseUrl: String = "https://api.podcastindex.org",
    private val epochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : SearchProvider {

    override val name: String = "Podcast Index"

    override suspend fun search(term: String): Result<List<PodcastSearchResult>> =
        withContext(Dispatchers.IO) {
            if (key.isBlank() || secret.isBlank()) {
                return@withContext Result.failure(IllegalStateException("Podcast Index not configured"))
            }
            runCatching {
                val date = epochSeconds().toString()
                val url = "$baseUrl/api/1.0/search/byterm?max=25&q=" +
                    URLEncoder.encode(term, "UTF-8")
                val request = Request.Builder().url(url)
                    .header("X-Auth-Key", key)
                    .header("X-Auth-Date", date)
                    .header("Authorization", sha1Hex(key + secret + date))
                    .header("User-Agent", SEARCH_USER_AGENT)
                    .build()
                client.newCall(request).execute().use { response ->
                    require(response.isSuccessful) { "HTTP ${response.code}" }
                    val feeds = JSONObject(response.body?.string().orEmpty())
                        .optJSONArray("feeds") ?: return@use emptyList()
                    buildList {
                        for (i in 0 until feeds.length()) {
                            val row = feeds.getJSONObject(i)
                            val feedUrl = row.optString("url").takeIf { it.isNotBlank() } ?: continue
                            add(
                                PodcastSearchResult(
                                    title = row.optString("title"),
                                    author = row.optString("author").takeIf { it.isNotBlank() },
                                    feedUrl = feedUrl,
                                    artworkUrl = row.optString("artwork").takeIf { it.isNotBlank() }
                                        ?: row.optString("image").takeIf { it.isNotBlank() },
                                ),
                            )
                        }
                    }
                }
            }
        }

    companion object {
        fun sha1Hex(input: String): String =
            MessageDigest.getInstance("SHA-1").digest(input.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
```

- [ ] **Step 4: Run tests** — 4 PASS.

- [ ] **Step 5: Commit**

```bash
git add feature/podcasts/src
git commit -m "feat: Podcast Index search with SHA-1 auth headers"
```

### Task 4: CompositeSearchProvider + Hilt wiring

**Files:**
- Create: `feature/podcasts/src/main/java/com/orator/feature/podcasts/data/search/CompositeSearchProvider.kt`, `SearchModule.kt`
- Test: `feature/podcasts/src/test/java/com/orator/feature/podcasts/data/search/CompositeSearchProviderTest.kt`

- [ ] **Step 1: Failing tests** (plain JUnit — fakes, no Android types)

```kotlin
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
}
```

- [ ] **Step 2: Run to verify failure.**

- [ ] **Step 3: Implement**

`CompositeSearchProvider.kt`:

```kotlin
package com.orator.feature.podcasts.data.search

/** Which provider answered + its results; placeholder UI shows the provider as a diagnostic. */
data class SearchAnswer(val provider: String, val results: List<PodcastSearchResult>)

/** PI first; any failure (including not-configured) silently falls through to iTunes. */
class CompositeSearchProvider(
    private val primary: SearchProvider,
    private val fallback: SearchProvider,
) {
    // distinctBy: feedUrl is the UI's LazyColumn key, and providers can return duplicate rows.
    suspend fun search(term: String): Result<SearchAnswer> =
        primary.search(term).fold(
            onSuccess = {
                Result.success(SearchAnswer(primary.name, it.distinctBy(PodcastSearchResult::feedUrl)))
            },
            onFailure = {
                fallback.search(term).map { results ->
                    SearchAnswer(fallback.name, results.distinctBy(PodcastSearchResult::feedUrl))
                }
            },
        )
}
```

`SearchModule.kt`:

```kotlin
package com.orator.feature.podcasts.data.search

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SearchModule {

    @Provides
    @Singleton
    fun provideCompositeSearchProvider(client: OkHttpClient): CompositeSearchProvider =
        CompositeSearchProvider(
            primary = PodcastIndexSearchProvider(client),
            fallback = ItunesSearchProvider(client),
        )
}
```

- [ ] **Step 4: Run all search tests** — `./gradlew :feature:podcasts:testDebugUnitTest --tests "com.orator.feature.podcasts.data.search.*"` — 9 PASS.

- [ ] **Step 5: Commit**

```bash
git add feature/podcasts/src
git commit -m "feat: composite search (Podcast Index primary, iTunes fallback)"
```

### Task 5: Search screen + navigation

**Files:**
- Create: `feature/podcasts/src/main/java/com/orator/feature/podcasts/SearchViewModel.kt`, `SearchScreen.kt`
- Modify: `PodcastsRoutes.kt`, `PodcastsFeatureEntry.kt`, `PodcastListScreen.kt`

ViewModel/screen are thin glue over tested units — no dedicated unit tests (P2–P4a convention).

- [ ] **Step 1: `PodcastsRoutes.kt`** — add (note: NOT under `podcasts/…` — `"podcasts/search"` would be shadowed by the `podcasts/{podcastId}` pattern):

```kotlin
internal const val PodcastSearchRoute = "podcast-search"
```

- [ ] **Step 2: `SearchViewModel.kt`**

```kotlin
package com.orator.feature.podcasts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orator.feature.podcasts.data.PodcastRepository
import com.orator.feature.podcasts.data.search.CompositeSearchProvider
import com.orator.feature.podcasts.data.search.PodcastSearchResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchProvider: CompositeSearchProvider,
    private val repository: PodcastRepository,
) : ViewModel() {

    data class UiState(
        val searching: Boolean = false,
        val provider: String? = null,
        val results: List<PodcastSearchResult> = emptyList(),
        val error: String? = null,
        val subscribedFeeds: Set<String> = emptySet(),
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun onSearch(term: String) {
        if (term.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(searching = true, error = null)
            searchProvider.search(term.trim()).fold(
                onSuccess = { answer ->
                    _state.value = _state.value.copy(
                        searching = false,
                        provider = answer.provider,
                        results = answer.results,
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        searching = false,
                        error = "Search failed: ${e.message}",
                    )
                },
            )
        }
    }

    fun onSubscribe(result: PodcastSearchResult) {
        viewModelScope.launch {
            repository.subscribe(result.feedUrl).onSuccess {
                _state.value = _state.value.copy(
                    subscribedFeeds = _state.value.subscribedFeeds + result.feedUrl,
                )
            }
        }
    }
}
```

- [ ] **Step 3: `SearchScreen.kt`** (placeholder style: centered, `collectAsStateWithLifecycle`)

```kotlin
package com.orator.feature.podcasts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SearchScreen(viewModel: SearchViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var term by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Search podcasts")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = term,
                onValueChange = { term = it },
                label = { Text("Search term") },
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { viewModel.onSearch(term) }) { Text("Search") }
        }
        if (state.searching) Text("Searching…")
        state.provider?.let { Text("via $it") }
        state.error?.let { Text(it) }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(state.results, key = { it.feedUrl }) { result ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(result.title)
                        Text(result.author ?: "")
                    }
                    val subscribed = result.feedUrl in state.subscribedFeeds
                    OutlinedButton(
                        onClick = { viewModel.onSubscribe(result) },
                        enabled = !subscribed,
                    ) {
                        Text(if (subscribed) "Subscribed" else "Subscribe")
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 4: Wire navigation** — in `PodcastsFeatureEntry.register` add:

```kotlin
        navGraphBuilder.composable(PodcastSearchRoute) {
            SearchScreen()
        }
```

In `PodcastListScreen`: add parameter `onOpenSearch: () -> Unit,` after `onOpenPlayer`, and add a button to the action row (`Row(horizontalArrangement = Arrangement.Center)`):

```kotlin
            OutlinedButton(onClick = onOpenSearch) { Text("Search") }
```

In `PodcastsFeatureEntry`, pass `onOpenSearch = { navController.navigate(PodcastSearchRoute) }` to `PodcastListScreen` (its only call site).

- [ ] **Step 5: Build + commit**

Run: `./gradlew assembleDebug` — BUILD SUCCESSFUL.

```bash
git add feature/podcasts/src
git commit -m "feat: podcast search screen with subscribe-from-results"
```

---

## Chunk 2: transcripts

### Task 6: DB v4 — transcript columns

**Files:**
- Modify: `core/database/src/main/java/com/orator/core/database/EpisodeEntity.kt`, `EpisodeDao.kt`, `OratorDatabase.kt`
- Modify (tests): `core/database/src/test/java/com/orator/core/database/EpisodeDaoTest.kt`
- Modify (call site compiles in Task 8): `feature/podcasts/.../data/PodcastRepository.kt` — NOT in this task; `updateMetadata` keeps compiling because the new params get defaults? No — Room DAO methods cannot have defaults. The call site MUST be updated in the same commit. See Step 4.

- [ ] **Step 1: `EpisodeEntity.kt`** — append three fields:

```kotlin
    /** Podcasting-2.0 transcript: URL+type from the feed; path set once fetched. */
    val transcriptUrl: String? = null,
    val transcriptType: String? = null,
    val transcriptPath: String? = null,
```

- [ ] **Step 2: `EpisodeDao.kt`** — extend `updateMetadata` (SQL + signature) and add the path setter. Replace the whole `updateMetadata` block with:

```kotlin
    /**
     * Refresh metadata for an existing row WITHOUT touching positionMs/audioPath/
     * lastPlayedAtMs/transcriptPath. durationMs only improves: a 0 from the feed never
     * erases a known value.
     */
    @Query(
        "UPDATE episodes SET title = :title, pubDateUtc = :pubDateUtc, enclosureUrl = :enclosureUrl, " +
            "showNotesHtml = :showNotesHtml, " +
            "transcriptUrl = :transcriptUrl, transcriptType = :transcriptType, " +
            "durationMs = CASE WHEN :durationMs > 0 THEN :durationMs ELSE durationMs END " +
            "WHERE id = :id",
    )
    suspend fun updateMetadata(
        id: String,
        title: String,
        pubDateUtc: Long,
        enclosureUrl: String,
        showNotesHtml: String?,
        transcriptUrl: String?,
        transcriptType: String?,
        durationMs: Long,
    )

    @Query("UPDATE episodes SET transcriptPath = :path WHERE id = :id")
    suspend fun updateTranscriptPath(id: String, path: String?)
```

- [ ] **Step 3: `OratorDatabase.kt`** — `version = 4` (destructive migration already configured; devices wipe once more).

- [ ] **Step 4: Update call sites and tests**

`PodcastRepository.upsertEpisodes` (read the file first): the `EpisodeEntity(...)` construction stays (new fields default to null at insert — refresh fills them via updateMetadata after Task 7 adds parsing; for now pass-through), and the `updateMetadata(...)` call gains `transcriptUrl = e.transcriptUrl, transcriptType = e.transcriptType,` after `showNotesHtml`.

`EpisodeDaoTest.kt`: the two existing `updateMetadata` calls gain `transcriptUrl = null, transcriptType = null,` after `showNotesHtml`. Add one new test:

```kotlin
    @Test
    fun `updateMetadata sets transcript url and type but never transcriptPath`() = runBlocking {
        dao.insertIgnore(listOf(episode("e1")))
        dao.updateTranscriptPath("e1", "content://t/transcript.vtt")

        dao.updateMetadata(
            id = "e1", title = "Ep e1", pubDateUtc = 0, enclosureUrl = "https://x/e1.mp3",
            showNotesHtml = null, transcriptUrl = "https://x/t.vtt", transcriptType = "text/vtt",
            durationMs = 0,
        )

        val row = dao.getById("e1")!!
        assertEquals("https://x/t.vtt", row.transcriptUrl)
        assertEquals("text/vtt", row.transcriptType)
        assertEquals("content://t/transcript.vtt", row.transcriptPath)
    }
```

- [ ] **Step 5: Run** — `./gradlew :core:database:testDebugUnitTest :feature:podcasts:testDebugUnitTest` — all PASS.

- [ ] **Step 6: Commit**

```bash
git add core/database/src feature/podcasts/src
git commit -m "feat: Room v4 with episode transcript columns, refresh-safe"
```

### Task 7: RssParser — podcast:transcript

**Files:**
- Modify: `feature/podcasts/src/main/java/com/orator/feature/podcasts/data/RssParser.kt`
- Modify: `feature/podcasts/src/test/resources/full.xml`
- Test: extend `RssParserTest.kt`

- [ ] **Step 1: Extend the fixture** — in `full.xml`, inside the "Episode Two" item (after the enclosure line) add two transcript candidates so preference is exercised:

```xml
      <podcast:transcript url="https://example.com/ep2.json" type="application/json"/>
      <podcast:transcript url="https://example.com/ep2.vtt" type="text/vtt"/>
```

(The root element doesn't declare the `podcast:` namespace — that's deliberate: namespaces are
not processed, matching real-world prefix variance.)

- [ ] **Step 2: Failing tests** — add to `RssParserTest`:

```kotlin
    @Test
    fun `picks the preferred transcript by type`() {
        val items = RssParser.parse(load("full.xml"))!!.items
        assertEquals("https://example.com/ep2.vtt", items[0].transcriptUrl) // vtt beats json
        assertEquals("text/vtt", items[0].transcriptType)
        assertNull(items[1].transcriptUrl)
    }
```

- [ ] **Step 3: Run to verify failure** (unresolved `transcriptUrl`).

- [ ] **Step 4: Implement** — in `RssParser.kt`:

`ParsedItem` gains:

```kotlin
    val transcriptUrl: String? = null,
    val transcriptType: String? = null,
```

Item-state additions: `var iTranscripts = mutableListOf<Pair<String, String?>>()` reset in the
`"item" ->` branch (`iTranscripts = mutableListOf()`), plus an item-level tag case:

```kotlin
                            "transcript" -> parser.getAttributeValue(null, "url")
                                ?.takeIf { it.isNotBlank() }
                                ?.let { iTranscripts.add(it to parser.getAttributeValue(null, "type")) }
```

On item end, when building `ParsedItem`:

```kotlin
                            val transcript = pickTranscript(iTranscripts)
                            ...
                                transcriptUrl = transcript?.first,
                                transcriptType = transcript?.second,
```

And the preference helper (object-level):

```kotlin
    /** Spec order: vtt > srt/subrip > plain > json; unknown types only when nothing better. */
    private val TRANSCRIPT_PREFERENCE =
        listOf("text/vtt", "application/srt", "application/x-subrip", "text/plain", "application/json")

    private fun pickTranscript(candidates: List<Pair<String, String?>>): Pair<String, String?>? =
        candidates.minByOrNull { (_, type) ->
            TRANSCRIPT_PREFERENCE.indexOf(type.orEmpty())
                .let { if (it == -1) TRANSCRIPT_PREFERENCE.size else it }
        }
```

`PodcastRepository.upsertEpisodes` `EpisodeEntity(...)` construction gains
`transcriptUrl = item.transcriptUrl, transcriptType = item.transcriptType,`.

- [ ] **Step 5: Run** — all RssParser + repository tests PASS.

- [ ] **Step 6: Commit**

```bash
git add feature/podcasts/src
git commit -m "feat: parse podcast:transcript with type preference"
```

### Task 8: TranscriptText (pure converter)

**Files:**
- Create: `feature/podcasts/src/main/java/com/orator/feature/podcasts/data/TranscriptText.kt`
- Test: `feature/podcasts/src/test/java/com/orator/feature/podcasts/data/TranscriptTextTest.kt`

- [ ] **Step 1: Failing tests** (Robolectric — JSON branch uses `org.json`)

```kotlin
package com.orator.feature.podcasts.data

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscriptTextTest {

    @Test
    fun `vtt keeps cue text and strips timing header and voice tags`() {
        val vtt = """
            WEBVTT

            00:00:01.000 --> 00:00:04.000
            <v Jane>Hello there.</v>

            2
            00:00:04.000 --> 00:00:06.000
            General Kenobi.
        """.trimIndent()
        assertEquals("Hello there.\nGeneral Kenobi.", TranscriptText.render(vtt, "text/vtt"))
    }

    @Test
    fun `srt drops indices and timing lines`() {
        val srt = """
            1
            00:00:01,000 --> 00:00:04,000
            First line.

            2
            00:00:04,000 --> 00:00:06,000
            Second line.
        """.trimIndent()
        assertEquals("First line.\nSecond line.", TranscriptText.render(srt, "application/srt"))
    }

    @Test
    fun `json concatenates segment bodies`() {
        val json = """{"version":"1.0","segments":[
            {"speaker":"Jane","startTime":0,"endTime":4,"body":"Hello there."},
            {"speaker":"Ben","startTime":4,"endTime":6,"body":"General Kenobi."}]}"""
        assertEquals("Hello there. General Kenobi.", TranscriptText.render(json, "application/json"))
    }

    @Test
    fun `plain text passes through`() {
        assertEquals("just words", TranscriptText.render("  just words  ", "text/plain"))
    }

    @Test
    fun `unknown type sniffs vtt by header`() {
        val vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHi."
        assertEquals("Hi.", TranscriptText.render(vtt, null))
    }
}
```

- [ ] **Step 2: Run to verify failure.**

- [ ] **Step 3: Implement**

```kotlin
package com.orator.feature.podcasts.data

import org.json.JSONObject

/**
 * Best-effort plain-text rendering of Podcasting-2.0 transcript files for the placeholder
 * viewer. Lossy by design (drops timing/speakers); real transcript UX is a UI-phase concern.
 */
object TranscriptText {

    private val TAGS = Regex("<[^>]+>")

    fun render(raw: String, type: String?): String = when {
        type?.contains("vtt") == true || raw.trimStart().startsWith("WEBVTT") -> cues(raw)
        type?.contains("srt") == true || type?.contains("subrip") == true -> cues(raw)
        type?.contains("json") == true || raw.trimStart().startsWith("{") -> json(raw)
        else -> raw.trim()
    }

    /**
     * Shared VTT/SRT walk: a "-->" line opens a cue; following non-blank lines are its text;
     * blank closes it. Everything outside cues (headers, indices, NOTE blocks) is dropped.
     */
    private fun cues(raw: String): String {
        val out = StringBuilder()
        var inCue = false
        var lineHasText = false
        for (line in raw.lines()) {
            val text = line.trim()
            when {
                text.contains("-->") -> {
                    if (lineHasText) out.append('\n')
                    inCue = true
                    lineHasText = false
                }
                text.isEmpty() -> inCue = false
                inCue -> {
                    if (lineHasText) out.append(' ')
                    out.append(TAGS.replace(text, ""))
                    lineHasText = true
                }
            }
        }
        return out.toString().trim()
    }

    private fun json(raw: String): String = try {
        val segments = JSONObject(raw).optJSONArray("segments")
        if (segments == null) {
            raw.trim()
        } else {
            buildString {
                for (i in 0 until segments.length()) {
                    val body = segments.getJSONObject(i).optString("body").trim()
                    if (body.isNotEmpty()) {
                        if (isNotEmpty()) append(' ')
                        append(body)
                    }
                }
            }
        }
    } catch (_: Exception) {
        raw.trim()
    }
}
```

- [ ] **Step 4: Run tests** — 5 PASS. (If the VTT test fails on newline placement, trace `cues`
with the fixture by hand and fix the implementation, not the expectation: expected output is
one line per cue, single-spaced within a cue.)

- [ ] **Step 5: Commit**

```bash
git add feature/podcasts/src
git commit -m "feat: transcript-to-text converter for vtt/srt/json/plain"
```

### Task 9: Cache-writer API + TranscriptFetcher + downloader hook

**Files:**
- Modify: `feature/podcasts/src/main/java/com/orator/feature/podcasts/data/EpisodeCacheWriter.kt`
- Create: `feature/podcasts/src/main/java/com/orator/feature/podcasts/data/TranscriptFetcher.kt`
- Modify: `feature/podcasts/src/main/java/com/orator/feature/podcasts/data/EpisodeDownloader.kt`
- Test: `feature/podcasts/src/test/java/com/orator/feature/podcasts/data/TranscriptFetcherTest.kt`

- [ ] **Step 1: `EpisodeCacheWriter` changes**

Extend the mime `when` in `writeBytes` (extension-matched so the provider never renames — the
P4a lesson):

```kotlin
            val mime = when {
                name.endsWith(".jpg") -> "image/jpeg"
                name.endsWith(".json") -> "application/json"
                name.endsWith(".html") -> "text/html"
                name.endsWith(".vtt") -> "text/vtt"
                name.endsWith(".srt") -> "application/x-subrip"
                name.endsWith(".txt") -> "text/plain"
                else -> "application/octet-stream"
            }
```

Add a public file writer (below `writeEpisode`):

```kotlin
    /** Writes one extra file into the episode's dir (transcripts); null on any failure. */
    suspend fun writeEpisodeFile(
        podcast: PodcastEntity,
        episode: EpisodeEntity,
        name: String,
        bytes: ByteArray,
    ): DocumentFile? = try {
        episodeDir(podcast, episode, create = true)?.let { dir ->
            writeBytes(dir, name, bytes)
            dir.findFile(name)
        }
    } catch (_: Exception) {
        null
    }
```

- [ ] **Step 2: Failing tests for the fetcher's pure part + repository-level behavior**

`TranscriptFetcherTest.kt` (Robolectric + in-memory Room + MockWebServer; the SAF write no-ops
without a granted folder, so assert the DB/result behavior — file placement is device-verified):

```kotlin
package com.orator.feature.podcasts.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.orator.core.database.EpisodeEntity
import com.orator.core.database.OratorDatabase
import com.orator.core.database.PodcastEntity
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscriptFetcherTest {

    private lateinit var db: OratorDatabase
    private lateinit var server: MockWebServer
    private lateinit var fetcher: TranscriptFetcher

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, OratorDatabase::class.java)
            .allowMainThreadQueries().build()
        server = MockWebServer()
        server.start()
        fetcher = TranscriptFetcher(
            client = OkHttpClient(),
            podcastDao = db.podcastDao(),
            episodeDao = db.episodeDao(),
            cacheWriter = EpisodeCacheWriter(context, PodcastsFolderStore(context)),
        )
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    private fun seed(transcriptUrl: String?, transcriptPath: String? = null) = runBlocking {
        db.podcastDao().insertIgnore(
            PodcastEntity(
                id = "p1", feedUrl = "https://x/f.xml", title = "Show", author = null,
                description = null, artworkUrl = null, subscribedAtUtc = 0,
            ),
        )
        db.episodeDao().insertIgnore(
            listOf(
                EpisodeEntity(
                    id = "e1", podcastId = "p1", title = "Ep", pubDateUtc = 0,
                    enclosureUrl = "https://x/e.mp3",
                    transcriptUrl = transcriptUrl, transcriptType = "text/vtt",
                ),
            ),
        )
        transcriptPath?.let { db.episodeDao().updateTranscriptPath("e1", it) }
    }

    @Test
    fun `no transcript url fails fast without a request`() = runBlocking {
        seed(transcriptUrl = null)
        assertTrue(fetcher.fetch("e1").isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `fetchIfAvailable skips when already fetched`() = runBlocking {
        seed(transcriptUrl = server.url("/t.vtt").toString(), transcriptPath = "content://x/t.vtt")
        fetcher.fetchIfAvailable("e1")
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `no granted cache folder maps to failure`() = runBlocking {
        seed(transcriptUrl = server.url("/t.vtt").toString())
        server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("WEBVTT\n\n00:00.000 --> 00:01.000\nHi"))

        val result = fetcher.fetch("e1")

        assertTrue(result.isFailure) // SAF tree absent in tests; the write returns null
        assertEquals(null, db.episodeDao().getById("e1")!!.transcriptPath)
    }

    @Test
    fun `extension follows type then url`() {
        assertEquals("vtt", TranscriptFetcher.transcriptExt("text/vtt", "https://x/t"))
        assertEquals("srt", TranscriptFetcher.transcriptExt("application/x-subrip", "https://x/t"))
        assertEquals("json", TranscriptFetcher.transcriptExt("application/json", "https://x/t"))
        assertEquals("txt", TranscriptFetcher.transcriptExt("text/plain", "https://x/t"))
        assertEquals("srt", TranscriptFetcher.transcriptExt(null, "https://x/t.srt?a=1"))
        assertEquals("txt", TranscriptFetcher.transcriptExt(null, "https://x/t"))
    }
}
```

- [ ] **Step 3: Run to verify failure.**

- [ ] **Step 4: Implement `TranscriptFetcher.kt`**

```kotlin
package com.orator.feature.podcasts.data

import com.orator.core.database.EpisodeDao
import com.orator.core.database.PodcastDao
import com.orator.feature.podcasts.data.search.SEARCH_USER_AGENT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads an episode's Podcasting-2.0 transcript into its cache-tree dir and records the
 * path. Called automatically after a successful audio download and on demand from the episode
 * screen. Failures never affect the audio download result.
 */
@Singleton
class TranscriptFetcher @Inject constructor(
    private val client: OkHttpClient,
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
    private val cacheWriter: EpisodeCacheWriter,
) {

    /** Outcome of the most recent attempt, for the episode screen's status line. */
    private val _lastEvent = MutableStateFlow<String?>(null)
    val lastEvent: StateFlow<String?> = _lastEvent.asStateFlow()

    /** Auto path: silently no-ops when there is nothing to fetch or it is already fetched. */
    suspend fun fetchIfAvailable(episodeId: String) {
        val episode = episodeDao.getById(episodeId) ?: return
        if (episode.transcriptUrl == null || episode.transcriptPath != null) return
        fetch(episodeId)
    }

    suspend fun fetch(episodeId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val result = runCatching {
            val episode = episodeDao.getById(episodeId)
                ?: error("unknown episode")
            val url = episode.transcriptUrl ?: error("episode has no transcript")
            val podcast = podcastDao.getById(episode.podcastId) ?: error("unknown podcast")

            val bytes = client.newCall(
                Request.Builder().url(url).header("User-Agent", SEARCH_USER_AGENT).build(),
            ).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }
                response.body?.bytes() ?: error("empty body")
            }

            val name = "transcript.${transcriptExt(episode.transcriptType, url)}"
            val file = cacheWriter.writeEpisodeFile(podcast, episode, name, bytes)
                ?: error("no cache folder granted")
            episodeDao.updateTranscriptPath(episodeId, file.uri.toString())
        }
        _lastEvent.value = result.fold(
            onSuccess = { "Transcript saved" },
            onFailure = { "Transcript failed: ${it.message}" },
        )
        result
    }

    companion object {
        fun transcriptExt(type: String?, url: String): String {
            when {
                type == null -> Unit
                type.contains("vtt") -> return "vtt"
                type.contains("srt") || type.contains("subrip") -> return "srt"
                type.contains("json") -> return "json"
                type.contains("text/plain") -> return "txt"
            }
            val ext = url.substringBefore('?').substringAfterLast('/')
                .substringAfterLast('.', missingDelimiterValue = "")
            return if (ext in setOf("vtt", "srt", "json", "txt")) ext else "txt"
        }
    }
}
```

- [ ] **Step 5: Downloader hook** — in `EpisodeDownloader`: add constructor param
`private val transcriptFetcher: TranscriptFetcher,` (after `cacheWriter`) and in `enqueue`'s
fold add the auto-fetch on success:

```kotlin
            _lastEvent.value = download(episodeId).fold(
                onSuccess = {
                    transcriptFetcher.fetchIfAvailable(episodeId)
                    "Download complete"
                },
                ...
```

(Hilt constructs both singletons; no cycle — TranscriptFetcher does not reference the downloader.)

- [ ] **Step 6: Run** — `./gradlew :feature:podcasts:testDebugUnitTest` — all PASS.

- [ ] **Step 7: Commit**

```bash
git add feature/podcasts/src
git commit -m "feat: transcript fetcher with auto-fetch after downloads"
```

### Task 10: Episode screen — Get transcript + viewer

**Files:**
- Modify: `feature/podcasts/src/main/java/com/orator/feature/podcasts/EpisodeDetailViewModel.kt`, `EpisodeDetailScreen.kt`

- [ ] **Step 1: ViewModel additions** (read the file first)

Constructor gains `@ApplicationContext private val context: Context,` and
`private val transcriptFetcher: TranscriptFetcher,` (imports:
`android.content.Context`, `android.net.Uri`, `dagger.hilt.android.qualifiers.ApplicationContext`,
`com.orator.feature.podcasts.data.TranscriptFetcher`, `com.orator.feature.podcasts.data.TranscriptText`,
`kotlinx.coroutines.Dispatchers`, `kotlinx.coroutines.withContext`).

New members:

```kotlin
    /** Rendered transcript text once a file exists; null until fetched. */
    val transcript: StateFlow<String?> = episodeDao.observeById(episodeId)
        .map { e ->
            val path = e?.transcriptPath ?: return@map null
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(Uri.parse(path))
                        ?.use { it.readBytes().decodeToString() }
                }.getOrNull()?.let { TranscriptText.render(it, e.transcriptType) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val transcriptEvent: StateFlow<String?> = transcriptFetcher.lastEvent

    fun onGetTranscript() {
        viewModelScope.launch { transcriptFetcher.fetch(episodeId) }
    }
```

- [ ] **Step 2: Screen additions** — collect `transcript` and `transcriptEvent`; below the
`downloadEvent?.let { Text(it) }` line add:

```kotlin
        transcriptEvent?.let { Text(it) }
        if (e.transcriptUrl != null && e.transcriptPath == null) {
            OutlinedButton(onClick = viewModel::onGetTranscript) { Text("Get transcript") }
        }
```

And after the show-notes block (still inside the scrollable Column):

```kotlin
        transcript?.let { text ->
            Text("Transcript", style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
```

(`transcript` here is the collected state variable: `val transcript by viewModel.transcript.collectAsStateWithLifecycle()` — rename the local in the `let` to avoid shadowing if needed.)

- [ ] **Step 3: Build + full feature tests** — `./gradlew :feature:podcasts:testDebugUnitTest assembleDebug` — PASS/SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add feature/podcasts/src
git commit -m "feat: transcript fetch button and plain-text viewer on episode screen"
```

---

## Chunk 3: unsubscribe + close-out

### Task 11: Unsubscribe (repository + tree delete)

**Files:**
- Modify: `feature/podcasts/src/main/java/com/orator/feature/podcasts/data/EpisodeCacheWriter.kt`, `PodcastRepository.kt`
- Test: extend `PodcastRepositoryTest.kt`

- [ ] **Step 1: Failing tests** — add to `PodcastRepositoryTest`:

```kotlin
    @Test
    fun `unsubscribe removes podcast and episodes`() = runBlocking {
        responses[FEED_A] = FetchResult.Success(rss("Show A", "g1" to "One", "g2" to "Two"), null, null)
        val id = repository.subscribe(FEED_A).getOrThrow()

        repository.unsubscribe(id)

        assertEquals(0, db.podcastDao().getAll().size)
        assertEquals(0, db.episodeDao().latestForPodcast(id, 10).size)
    }

    @Test
    fun `unsubscribe is idempotent`() = runBlocking {
        responses[FEED_A] = FetchResult.Success(rss("Show A", "g1" to "One"), null, null)
        val id = repository.subscribe(FEED_A).getOrThrow()
        repository.unsubscribe(id)
        repository.unsubscribe(id) // second call must not throw
        assertEquals(0, db.podcastDao().getAll().size)
    }
```

- [ ] **Step 2: Run to verify failure** (unresolved `unsubscribe`).

- [ ] **Step 3: Implement**

`EpisodeCacheWriter` — add below `writeEpisodeFile`:

```kotlin
    /** Removes the show's whole tree dir (downloads included). Best-effort like all tree ops. */
    suspend fun deleteShowDir(podcast: PodcastEntity) = bestEffort {
        showDir(podcast, create = false)?.delete()
    }
```

`PodcastRepository` — add below `refreshAll`:

```kotlin
    /** User decision: deletes everything, downloads included (UI confirms first). */
    suspend fun unsubscribe(podcastId: String) = withContext(Dispatchers.IO) {
        val podcast = podcastDao.getById(podcastId) ?: return@withContext
        episodeDao.deleteForPodcast(podcastId)
        podcastDao.delete(podcastId)
        cacheWriter.deleteShowDir(podcast) // after DB: rows must go even if the tree op fails
    }
```

- [ ] **Step 4: Run** — repository tests PASS (10 total).

- [ ] **Step 5: Commit**

```bash
git add feature/podcasts/src
git commit -m "feat: unsubscribe with full cache-tree cleanup"
```

### Task 12: Unsubscribe UI

**Files:**
- Modify: `feature/podcasts/src/main/java/com/orator/feature/podcasts/PodcastDetailViewModel.kt`, `PodcastDetailScreen.kt`, `PodcastsFeatureEntry.kt`

- [ ] **Step 1: ViewModel** — constructor gains `private val repository: PodcastRepository,`
(import `com.orator.feature.podcasts.data.PodcastRepository`); add:

```kotlin
    fun onUnsubscribe(onDone: () -> Unit) {
        viewModelScope.launch {
            repository.unsubscribe(podcastId)
            onDone()
        }
    }
```

- [ ] **Step 2: Screen** — `PodcastDetailScreen` gains an `onUnsubscribed: () -> Unit` parameter.
Add imports `androidx.compose.runtime.mutableStateOf`, `remember`, `setValue` and below the
speed `SettingRow` insert:

```kotlin
        var confirmingUnsubscribe by remember { mutableStateOf(false) }
        if (confirmingUnsubscribe) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Really unsubscribe? Deletes downloads.")
                OutlinedButton(onClick = { viewModel.onUnsubscribe(onUnsubscribed) }) { Text("Yes") }
                OutlinedButton(onClick = { confirmingUnsubscribe = false }) { Text("No") }
            }
        } else {
            OutlinedButton(onClick = { confirmingUnsubscribe = true }) { Text("Unsubscribe") }
        }
```

- [ ] **Step 3: Entry** — pass `onUnsubscribed = { navController.popBackStack() }` at the
`PodcastDetailScreen` call site.

- [ ] **Step 4: Build + commit**

Run: `./gradlew assembleDebug test` — BUILD SUCCESSFUL, all PASS.

```bash
git add feature/podcasts/src
git commit -m "feat: unsubscribe button with inline confirmation"
```

### Task 13: Device verification + close-out

- [ ] **Step 1: Full suite + install** — `./gradlew test` then `./gradlew installDebug`
(wireless adb; `~/Android/Sdk/platform-tools/adb`). Report build times (standing instruction).
DB v4 wipes the device library: re-pick folders and re-import the OPML
(`/sdcard/Download/podcasts.opml` is already on the phone).

- [ ] **Step 2: Manual checklist** (user drives; wait for results)

1. Podcasts → Search → search a term → results appear with "via Podcast Index".
2. Subscribe from a result → button flips to "Subscribed"; show appears in the list.
3. Temporarily blank `podcastindex.apiSecret=` in `local.properties`, `installDebug`, search again → "via iTunes" (fallback). Restore the secret, reinstall.
4. Subscribe to a Podcasting-2.0 show with transcripts (search "Podcasting 2.0" — or any Buzzsprout-hosted show) → open a recent episode → "Get transcript" → "Transcript saved" → transcript text renders below show notes.
5. `adb shell find /sdcard/OratorTest/Podcasts -name "transcript.*"` shows the file in the episode dir.
6. Download an episode that has a transcript → transcript arrives automatically with the download.
7. Unsubscribe a show (one with a downloaded episode) → confirm → back on the list, show gone; `adb shell ls` confirms its folder is gone.
8. Regression: stream an episode; clips/speed/smart-rewind still behave (P4a re-check).

- [ ] **Step 3: Tick this plan's checkboxes + write the "Execution notes" section.**

- [ ] **Step 4: `docs/architecture.md`** — mark roadmap row 4b ✅ with the verified date; status
line → "Next: Phase 5 (playlists)".

- [ ] **Step 5: Push + PR**

```bash
git push -u origin phase-4b-podcasts
gh pr create --title "Phase 4b: podcasts — discovery, transcripts, unsubscribe" --body "..."
```

---

## Execution notes (deviations from the written plan)

(filled in during execution)
