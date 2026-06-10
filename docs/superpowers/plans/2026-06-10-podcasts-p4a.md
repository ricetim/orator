# Phase 4a: Podcasts (subscribe / cache / play) Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Subscribe to podcast feeds (pasted URL or OPML import), cache metadata in Room + a human-readable SAF tree, stream or download episodes, and play them through the existing Phase 3 player with per-show intro/outro clips, per-show speed, resume, and tappable show-note timestamps.

**Architecture:** Two new modules. `core:network` holds an OkHttp singleton and a conditional-GET `FeedFetcher`. `feature:podcasts` holds hand-rolled RSS/OPML parsers (XmlPullParser), a repository that upserts by namespaced GUID without disturbing positions, a thin SAF cache writer, a sequential downloader, and the playback glue (queue builder + position/speed listeners) that reuses Phase 3 seams unchanged. Room goes to v3 (destructive migration, like v2).

**Tech Stack:** Kotlin, Hilt, Room 2.7.1, Media3 1.5.1 (untouched), OkHttp 4.12.0 (the only new runtime dependency), MockWebServer (test only), Robolectric, XmlPullParser, org.json.

**Spec:** `docs/superpowers/specs/2026-06-10-podcasts-p4a-design.md` (read it first; the "Plan-level decisions" section and the SAF-performance amendment are binding).

**Conventions you must follow (established in P1–P3):**
- Always `./gradlew` (wrapper), never system gradle. `ANDROID_HOME=~/Android/Sdk`.
- Room-backed JVM tests use Robolectric + `runBlocking` (NOT `runTest` — Room's transaction executor deadlocks under the test dispatcher).
- Feature modules depend only on `core:*`, never on other features.
- Placeholder UI: menus/button groups centered on screen (user preference).
- Read files with the Read tool before editing them.
- The user's real OPML at `local/podcasts.opml` contains private auth tokens. NEVER commit it, copy it into fixtures, or print its URLs. All fixtures in this plan are synthetic.
- Commit after every task with the exact message given.

**File map (created files):**

```
gradle/libs.versions.toml                 (modify: okhttp, mockwebserver, coroutines-android)
settings.gradle.kts                       (modify: +2 includes)
app/build.gradle.kts                      (modify: +feature:podcasts)
core/network/build.gradle.kts
core/network/src/main/java/com/orator/core/network/NetworkModule.kt
core/network/src/main/java/com/orator/core/network/FeedFetcher.kt
core/network/src/test/java/com/orator/core/network/FeedFetcherTest.kt
core/database/.../PodcastEntity.kt  PodcastDao.kt  EpisodeEntity.kt  EpisodeDao.kt
core/database/.../OratorDatabase.kt       (modify: v3)
core/database/.../DatabaseModule.kt       (modify: +2 providers)
core/database/src/test/.../PodcastDaoTest.kt  EpisodeDaoTest.kt
core/navigation/.../CommonRoutes.kt       (modify: +Podcasts)
feature/podcasts/build.gradle.kts  + .gitignore
feature/podcasts/src/main/java/com/orator/feature/podcasts/
    PodcastsRoutes.kt  PodcastsFeatureEntry.kt  PodcastsFeatureModule.kt
    PodcastListViewModel.kt  PodcastListScreen.kt
    PodcastDetailViewModel.kt  PodcastDetailScreen.kt
    EpisodeDetailViewModel.kt  EpisodeDetailScreen.kt
    data/RssParser.kt  OpmlParser.kt  PodcastIds.kt  PodcastMediaId.kt
    data/CacheNames.kt  CacheJson.kt  PodcastsFolderStore.kt  EpisodeCacheWriter.kt
    data/PodcastRepository.kt  EpisodeDownloader.kt  EpisodeQueueBuilder.kt
    data/PodcastPositionListener.kt  EpisodeSpeedOverrideListener.kt  ShowNotes.kt
feature/podcasts/src/test/java/com/orator/feature/podcasts/data/   (one test file per unit)
feature/podcasts/src/test/resources/  full.xml  minimal.xml  broken-items.xml  feeds.opml
feature/audiobooks/.../AudiobookListScreen.kt + AudiobooksFeatureEntry.kt  (modify: Podcasts button)
```

---

## Chunk 1: core:network + Room v3

### Task 1: Version catalog + core:network module scaffold

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `settings.gradle.kts`
- Create: `core/network/build.gradle.kts`, `core/network/.gitignore`
- Create: `core/network/src/main/java/com/orator/core/network/NetworkModule.kt`

- [ ] **Step 1: Add catalog entries**

In `gradle/libs.versions.toml` add under `[versions]`:

```toml
okhttp = "4.12.0"
```

Under `[libraries]`:

```toml
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
okhttp-mockwebserver = { group = "com.squareup.okhttp3", name = "mockwebserver", version.ref = "okhttp" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
```

- [ ] **Step 2: Register the module**

In `settings.gradle.kts` add after the `:core:database` include:

```kotlin
include(":core:network")
```

- [ ] **Step 3: Create `core/network/.gitignore`** containing exactly:

```
/build
```

- [ ] **Step 4: Create `core/network/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.orator.core.network"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

- [ ] **Step 5: Create `NetworkModule.kt`**

```kotlin
package com.orator.core.network

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
}
```

- [ ] **Step 6: Verify it builds**

Run: `./gradlew :core:network:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add gradle/libs.versions.toml settings.gradle.kts core/network
git commit -m "feat: core:network module with OkHttp singleton (only new P4a dependency)"
```

### Task 2: FeedFetcher (conditional GET)

**Files:**
- Create: `core/network/src/main/java/com/orator/core/network/FeedFetcher.kt`
- Test: `core/network/src/test/java/com/orator/core/network/FeedFetcherTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.orator.core.network

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FeedFetcherTest {

    private lateinit var server: MockWebServer
    private lateinit var fetcher: FeedFetcher

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        fetcher = FeedFetcher(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `success returns body and validators`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody("<rss/>")
                .setHeader("ETag", "\"v1\"")
                .setHeader("Last-Modified", "Wed, 10 Jun 2026 00:00:00 GMT"),
        )

        val result = fetcher.fetch(server.url("/feed").toString())

        result as FetchResult.Success
        assertEquals("<rss/>", result.body)
        assertEquals("\"v1\"", result.etag)
        assertEquals("Wed, 10 Jun 2026 00:00:00 GMT", result.lastModified)
    }

    @Test
    fun `sends conditional headers and maps 304`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(304))

        val result = fetcher.fetch(
            server.url("/feed").toString(),
            etag = "\"v1\"",
            lastModified = "Wed, 10 Jun 2026 00:00:00 GMT",
        )

        assertEquals(FetchResult.NotModified, result)
        val recorded = server.takeRequest()
        assertEquals("\"v1\"", recorded.getHeader("If-None-Match"))
        assertEquals("Wed, 10 Jun 2026 00:00:00 GMT", recorded.getHeader("If-Modified-Since"))
    }

    @Test
    fun `http error maps to failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = fetcher.fetch(server.url("/feed").toString())

        assertTrue(result is FetchResult.Failure)
    }

    @Test
    fun `unreachable host maps to failure not exception`() = runBlocking {
        server.shutdown()

        val result = fetcher.fetch(server.url("/feed").toString())

        assertTrue(result is FetchResult.Failure)
    }

    @Test
    fun `malformed url maps to failure`() = runBlocking {
        val result = fetcher.fetch("not a url")
        result as FetchResult.Failure
        assertNull(null) // reaches here without throwing
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:network:testDebugUnitTest --tests "com.orator.core.network.FeedFetcherTest"`
Expected: FAIL — `Unresolved reference: FeedFetcher`

- [ ] **Step 3: Implement `FeedFetcher.kt`**

```kotlin
package com.orator.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface FetchResult {
    data class Success(val body: String, val etag: String?, val lastModified: String?) : FetchResult
    data object NotModified : FetchResult
    data class Failure(val message: String) : FetchResult
}

/**
 * One conditional GET. Sends stored validators so unchanged feeds answer 304 with no body —
 * refreshing all subscriptions stays cheap. No parsing here; callers own the body format.
 */
@Singleton
class FeedFetcher @Inject constructor(private val client: OkHttpClient) {

    suspend fun fetch(
        url: String,
        etag: String? = null,
        lastModified: String? = null,
    ): FetchResult = withContext(Dispatchers.IO) {
        val request = try {
            Request.Builder().url(url).apply {
                etag?.let { header("If-None-Match", it) }
                lastModified?.let { header("If-Modified-Since", it) }
            }.build()
        } catch (e: IllegalArgumentException) {
            return@withContext FetchResult.Failure("bad URL: ${e.message}")
        }
        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 304 -> FetchResult.NotModified
                    response.isSuccessful -> FetchResult.Success(
                        body = response.body?.string().orEmpty(),
                        etag = response.header("ETag"),
                        lastModified = response.header("Last-Modified"),
                    )
                    else -> FetchResult.Failure("HTTP ${response.code}")
                }
            }
        } catch (e: IOException) {
            FetchResult.Failure(e.message ?: e.javaClass.simpleName)
        }
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :core:network:testDebugUnitTest --tests "com.orator.core.network.FeedFetcherTest"`
Expected: 5 tests PASS

- [ ] **Step 5: Commit**

```bash
git add core/network/src
git commit -m "feat: FeedFetcher with conditional GET and failure mapping"
```

### Task 3: Room v3 — podcast + episode tables

**Files:**
- Create: `core/database/src/main/java/com/orator/core/database/PodcastEntity.kt`, `PodcastDao.kt`, `EpisodeEntity.kt`, `EpisodeDao.kt`
- Modify: `core/database/src/main/java/com/orator/core/database/OratorDatabase.kt`
- Modify: `core/database/src/main/java/com/orator/core/database/DatabaseModule.kt`
- Test: `core/database/src/test/java/com/orator/core/database/PodcastDaoTest.kt`, `EpisodeDaoTest.kt`

- [ ] **Step 1: Create `PodcastEntity.kt`**

```kotlin
package com.orator.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One subscribed feed. [clipIntroMs]/[clipOutroMs] are the per-show auto-skip windows fed into
 * PlayableItem clips. [etag]/[lastModified] are HTTP validators for cheap conditional refresh.
 */
@Entity(tableName = "podcasts")
data class PodcastEntity(
    @PrimaryKey val id: String,
    val feedUrl: String,
    val title: String,
    val author: String?,
    val description: String?,
    val artworkUrl: String?,
    val subscribedAtUtc: Long,
    val lastRefreshUtc: Long = 0,
    val etag: String? = null,
    val lastModified: String? = null,
    val clipIntroMs: Long = 0,
    val clipOutroMs: Long = 0,
    /** Per-show speed; null = fall back to per-type/global defaults. */
    val speedOverride: Float? = null,
)
```

- [ ] **Step 2: Create `EpisodeEntity.kt`**

```kotlin
package com.orator.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One episode. [id] is hash(podcastId + guid) — GUIDs are only unique within a feed.
 * [durationMs] is ALWAYS the original unclipped timeline (0 = unknown); [positionMs] is
 * clip-relative (the Phase 3 invariant). [showNotesHtml] lives in the DB so the UI never
 * waits on network or SAF; the cache tree mirrors it for recent/downloaded episodes.
 */
@Entity(tableName = "episodes")
data class EpisodeEntity(
    @PrimaryKey val id: String,
    val podcastId: String,
    val title: String,
    val pubDateUtc: Long,
    val durationMs: Long = 0,
    val enclosureUrl: String,
    val showNotesHtml: String? = null,
    /** Content URI of the downloaded audio; null = stream from [enclosureUrl]. */
    val audioPath: String? = null,
    val positionMs: Long = 0,
    /** Wall-clock of the last position ping; drives cold-start smart rewind. */
    val lastPlayedAtMs: Long = 0,
)
```

- [ ] **Step 3: Create `PodcastDao.kt`**

```kotlin
package com.orator.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PodcastDao {
    /** Returns -1 when the podcast already exists (subscribe is idempotent). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(podcast: PodcastEntity): Long

    @Query("SELECT * FROM podcasts ORDER BY title")
    fun observeAll(): Flow<List<PodcastEntity>>

    @Query("SELECT * FROM podcasts WHERE id = :id")
    fun observeById(id: String): Flow<PodcastEntity?>

    @Query("SELECT * FROM podcasts WHERE id = :id")
    suspend fun getById(id: String): PodcastEntity?

    @Query("SELECT * FROM podcasts")
    suspend fun getAll(): List<PodcastEntity>

    @Query(
        "UPDATE podcasts SET title = :title, author = :author, description = :description, " +
            "artworkUrl = :artworkUrl, lastRefreshUtc = :refreshedAtUtc, etag = :etag, " +
            "lastModified = :lastModified WHERE id = :id",
    )
    suspend fun updateFeedMeta(
        id: String,
        title: String,
        author: String?,
        description: String?,
        artworkUrl: String?,
        refreshedAtUtc: Long,
        etag: String?,
        lastModified: String?,
    )

    @Query("UPDATE podcasts SET lastRefreshUtc = :refreshedAtUtc WHERE id = :id")
    suspend fun touchRefresh(id: String, refreshedAtUtc: Long)

    @Query("UPDATE podcasts SET clipIntroMs = :introMs, clipOutroMs = :outroMs WHERE id = :id")
    suspend fun updateClips(id: String, introMs: Long, outroMs: Long)

    @Query("UPDATE podcasts SET speedOverride = :speed WHERE id = :id")
    suspend fun updateSpeedOverride(id: String, speed: Float?)

    @Query("DELETE FROM podcasts WHERE id = :id")
    suspend fun delete(id: String)
}
```

- [ ] **Step 4: Create `EpisodeDao.kt`**

```kotlin
package com.orator.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EpisodeDao {
    /** Insert new rows only; existing rows are untouched (positions/downloads survive refresh). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(episodes: List<EpisodeEntity>)

    /**
     * Refresh metadata for an existing row WITHOUT touching positionMs/audioPath/lastPlayedAtMs.
     * durationMs only improves: a 0 from the feed never erases a known value.
     */
    @Query(
        "UPDATE episodes SET title = :title, pubDateUtc = :pubDateUtc, enclosureUrl = :enclosureUrl, " +
            "showNotesHtml = :showNotesHtml, " +
            "durationMs = CASE WHEN :durationMs > 0 THEN :durationMs ELSE durationMs END " +
            "WHERE id = :id",
    )
    suspend fun updateMetadata(
        id: String,
        title: String,
        pubDateUtc: Long,
        enclosureUrl: String,
        showNotesHtml: String?,
        durationMs: Long,
    )

    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId ORDER BY pubDateUtc DESC")
    fun observeForPodcast(podcastId: String): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId ORDER BY pubDateUtc DESC LIMIT :limit")
    suspend fun latestForPodcast(podcastId: String, limit: Int): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun getById(id: String): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE id = :id")
    fun observeById(id: String): Flow<EpisodeEntity?>

    @Query("UPDATE episodes SET positionMs = :positionMs, lastPlayedAtMs = :lastPlayedAtMs WHERE id = :id")
    suspend fun updateProgress(id: String, positionMs: Long, lastPlayedAtMs: Long)

    /** The duration-backfill rule from the spec, enforced in SQL: never overwrite a known duration. */
    @Query("UPDATE episodes SET durationMs = :durationMs WHERE id = :id AND durationMs = 0")
    suspend fun backfillDuration(id: String, durationMs: Long)

    @Query("UPDATE episodes SET audioPath = :audioPath WHERE id = :id")
    suspend fun updateAudioPath(id: String, audioPath: String?)

    @Query("DELETE FROM episodes WHERE podcastId = :podcastId")
    suspend fun deleteForPodcast(podcastId: String)
}
```

- [ ] **Step 5: Bump `OratorDatabase.kt` to v3**

Add both entities to the `entities` array, set `version = 3`, and add:

```kotlin
    abstract fun podcastDao(): PodcastDao
    abstract fun episodeDao(): EpisodeDao
```

- [ ] **Step 6: Add providers in `DatabaseModule.kt`**

```kotlin
    @Provides
    fun providePodcastDao(db: OratorDatabase): PodcastDao = db.podcastDao()

    @Provides
    fun provideEpisodeDao(db: OratorDatabase): EpisodeDao = db.episodeDao()
```

- [ ] **Step 7: Write DAO tests** (mirror `HistoryDaoTest`: Robolectric, in-memory DB, `runBlocking`)

`PodcastDaoTest.kt`: `insertIgnore` returns -1 on duplicate id and leaves the original row intact; `updateClips` round-trips; `updateSpeedOverride` sets and clears.

`EpisodeDaoTest.kt`:
- `insertIgnore` then `insertIgnore` again with changed title → title unchanged (positions survive refresh by construction).
- `updateMetadata` changes title/notes but leaves `positionMs`/`audioPath` untouched; passing `durationMs = 0` does not erase an existing duration; passing `durationMs > 0` updates it.
- `backfillDuration` writes when duration is 0 and is a no-op when already known.
- `observeForPodcast` orders by pubDate DESC; `latestForPodcast` respects the limit.

Use a helper:

```kotlin
private fun podcast(id: String = "p1") = PodcastEntity(
    id = id, feedUrl = "https://x/feed.xml", title = "Show", author = null,
    description = null, artworkUrl = null, subscribedAtUtc = 0,
)

private fun episode(id: String, podcastId: String = "p1", pubDate: Long = 0) = EpisodeEntity(
    id = id, podcastId = podcastId, title = "Ep $id", pubDateUtc = pubDate,
    enclosureUrl = "https://x/$id.mp3",
)
```

- [ ] **Step 8: Run the database tests**

Run: `./gradlew :core:database:testDebugUnitTest`
Expected: all PASS (new tests + existing ones — v3 bump must not break them)

- [ ] **Step 9: Commit**

```bash
git add core/database/src
git commit -m "feat: Room v3 with podcast + episode tables, refresh-safe upsert split"
```

---

## Chunk 2: feature:podcasts scaffold + parsers

### Task 4: Module scaffold + navigation stub

**Files:**
- Create: `feature/podcasts/build.gradle.kts`, `feature/podcasts/.gitignore`
- Create: `feature/podcasts/src/main/java/com/orator/feature/podcasts/PodcastsRoutes.kt`, `PodcastsFeatureEntry.kt`, `PodcastsFeatureModule.kt`
- Modify: `settings.gradle.kts`, `app/build.gradle.kts`
- Modify: `core/navigation/src/main/java/com/orator/core/navigation/CommonRoutes.kt`

- [ ] **Step 1: `settings.gradle.kts`** — add `include(":feature:podcasts")` after the `:feature:settings` line.

- [ ] **Step 2: `app/build.gradle.kts`** — add `implementation(project(":feature:podcasts"))` next to the other feature deps.

- [ ] **Step 3: `feature/podcasts/.gitignore`** containing `/build`.

- [ ] **Step 4: `feature/podcasts/build.gradle.kts`** — copy of `feature/audiobooks/build.gradle.kts` with `namespace = "com.orator.feature.podcasts"` and one extra dependency block line: `implementation(project(":core:network"))` and `implementation(libs.okhttp)` (the downloader streams with the client directly). Keep the test deps (junit, robolectric, test.core, coroutines-test) and `isIncludeAndroidResources = true`.

- [ ] **Step 5: `CommonRoutes.kt`** — add `const val Podcasts = "podcasts"` to the object (read the file first; it has Player/Settings/History).

- [ ] **Step 6: `PodcastsRoutes.kt`**

```kotlin
package com.orator.feature.podcasts

import com.orator.core.navigation.CommonRoutes

const val PodcastsRoute = CommonRoutes.Podcasts

internal const val PodcastDetailRoutePattern = "podcasts/{podcastId}"
internal fun podcastDetailRoute(podcastId: String) = "podcasts/$podcastId"

internal const val EpisodeDetailRoutePattern = "podcasts/episode/{episodeId}"
internal fun episodeDetailRoute(episodeId: String) = "podcasts/episode/$episodeId"
```

- [ ] **Step 7: `PodcastsFeatureEntry.kt`** — registers the list route with a temporary `Text("Podcasts")` composable (real screens land in Chunk 5; the entry exists now so the module participates in DI/nav from day one):

```kotlin
package com.orator.feature.podcasts

import androidx.compose.material3.Text
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.orator.core.navigation.FeatureEntry
import javax.inject.Inject

class PodcastsFeatureEntry @Inject constructor() : FeatureEntry {

    override val route: String = PodcastsRoute

    override fun register(navGraphBuilder: NavGraphBuilder, navController: NavController) {
        navGraphBuilder.composable(PodcastsRoute) {
            Text("Podcasts")
        }
    }
}
```

- [ ] **Step 8: `PodcastsFeatureModule.kt`**

```kotlin
package com.orator.feature.podcasts

import com.orator.core.navigation.FeatureEntry
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
interface PodcastsFeatureModule {

    @Binds
    @IntoSet
    fun bindFeatureEntry(entry: PodcastsFeatureEntry): FeatureEntry
}
```

- [ ] **Step 9: Verify** — Run: `./gradlew assembleDebug` — Expected: BUILD SUCCESSFUL.

- [ ] **Step 10: Commit**

```bash
git add settings.gradle.kts app/build.gradle.kts feature/podcasts core/navigation
git commit -m "feat: register feature:podcasts module with stub navigation entry"
```

### Task 5: RssParser

**Files:**
- Create: `feature/podcasts/src/main/java/com/orator/feature/podcasts/data/RssParser.kt`
- Create fixtures: `feature/podcasts/src/test/resources/full.xml`, `minimal.xml`, `broken-items.xml`
- Test: `feature/podcasts/src/test/java/com/orator/feature/podcasts/data/RssParserTest.kt`

- [ ] **Step 1: Write the fixtures** (synthetic — resemblance to real feeds is in structure only)

`full.xml` — exercises everything:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"
     xmlns:content="http://purl.org/rss/1.0/modules/content/">
  <channel>
    <title>Test Show &amp; Friends</title>
    <itunes:author>Jane Host</itunes:author>
    <description>A show about tests.</description>
    <itunes:image href="https://example.com/cover.jpg"/>
    <item>
      <title>Episode Two</title>
      <guid isPermaLink="false">ep-2</guid>
      <pubDate>Tue, 09 Jun 2026 08:00:00 +0000</pubDate>
      <itunes:duration>01:02:03</itunes:duration>
      <enclosure url="https://example.com/ep2.mp3" length="123" type="audio/mpeg"/>
      <description>plain notes</description>
      <content:encoded><![CDATA[<p>Rich notes with a timestamp 12:34 inside.</p>]]></content:encoded>
    </item>
    <item>
      <title>Episode One</title>
      <guid>ep-1</guid>
      <pubDate>Mon, 01 Jun 2026 08:00:00 GMT</pubDate>
      <itunes:duration>1830</itunes:duration>
      <enclosure url="https://example.com/ep1.mp3" type="audio/mpeg"/>
      <description><![CDATA[<b>Show notes</b> for one.]]></description>
    </item>
  </channel>
</rss>
```

`minimal.xml` — no itunes namespace at all, no guid, no duration, no pubDate:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0">
  <channel>
    <title>Bare Show</title>
    <item>
      <title>Only Episode</title>
      <enclosure url="https://example.com/only.mp3" type="audio/mpeg"/>
    </item>
  </channel>
</rss>
```

`broken-items.xml` — three items: one valid, one with no enclosure (skip), one with no title (skip):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0">
  <channel>
    <title>Mixed Show</title>
    <item>
      <title>Good One</title>
      <guid>good</guid>
      <enclosure url="https://example.com/good.mp3" type="audio/mpeg"/>
    </item>
    <item>
      <title>No Enclosure</title>
      <guid>bad-1</guid>
    </item>
    <item>
      <guid>bad-2</guid>
      <enclosure url="https://example.com/untitled.mp3" type="audio/mpeg"/>
    </item>
  </channel>
</rss>
```

- [ ] **Step 2: Write the failing tests**

```kotlin
package com.orator.feature.podcasts.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RssParserTest {

    private fun load(name: String): String =
        checkNotNull(javaClass.classLoader).getResourceAsStream(name)!!
            .readBytes().decodeToString()

    @Test
    fun `parses channel metadata and items`() {
        val feed = RssParser.parse(load("full.xml"))!!

        assertEquals("Test Show & Friends", feed.title)
        assertEquals("Jane Host", feed.author)
        assertEquals("A show about tests.", feed.description)
        assertEquals("https://example.com/cover.jpg", feed.artworkUrl)
        assertEquals(2, feed.items.size)
    }

    @Test
    fun `parses durations in both formats`() {
        val items = RssParser.parse(load("full.xml"))!!.items
        assertEquals(((1 * 60 + 2) * 60 + 3) * 1000L, items[0].durationMs) // 01:02:03
        assertEquals(1830_000L, items[1].durationMs)                        // bare seconds
    }

    @Test
    fun `prefers content-encoded over description for show notes`() {
        val items = RssParser.parse(load("full.xml"))!!.items
        assertTrue(items[0].showNotesHtml!!.contains("Rich notes"))
        assertTrue(items[1].showNotesHtml!!.contains("<b>Show notes</b>"))
    }

    @Test
    fun `parses rfc1123 pubDates`() {
        val items = RssParser.parse(load("full.xml"))!!.items
        assertTrue(items[0].pubDateUtc > items[1].pubDateUtc)
        assertTrue(items[0].pubDateUtc > 0)
    }

    @Test
    fun `minimal feed parses with defaults`() {
        val feed = RssParser.parse(load("minimal.xml"))!!
        val item = feed.items.single()
        assertNull(item.guid)
        assertEquals(0L, item.durationMs)
        assertEquals(0L, item.pubDateUtc)
        assertEquals("https://example.com/only.mp3", item.enclosureUrl)
    }

    @Test
    fun `skips items missing title or enclosure without aborting feed`() {
        val feed = RssParser.parse(load("broken-items.xml"))!!
        assertEquals(1, feed.items.size)
        assertEquals("Good One", feed.items[0].title)
    }

    @Test
    fun `garbage input returns null instead of throwing`() {
        assertNull(RssParser.parse("this is not xml at all <<<"))
        assertNull(RssParser.parse("<html><body>404</body></html>"))
    }
}
```

- [ ] **Step 3: Run to verify failure**

Run: `./gradlew :feature:podcasts:testDebugUnitTest --tests "com.orator.feature.podcasts.data.RssParserTest"`
Expected: FAIL — `Unresolved reference: RssParser`

- [ ] **Step 4: Implement `RssParser.kt`**

Notes for the implementer: namespaces are NOT processed (`FEATURE_PROCESS_NAMESPACES` stays false) — tag names arrive qualified like `itunes:duration`, and we match on the local part after `:` so any prefix works. `readText()` must tolerate nested markup inside description tags by concatenating TEXT/CDSECT events until the matching END_TAG. XmlPullParser on the JVM (unit tests) uses the kxml2 implementation bundled with Robolectric/Android — add `testImplementation(libs.robolectric)` is already present; the parser itself uses `org.xmlpull.v1.XmlPullParserFactory` which resolves on both JVM and device.

```kotlin
package com.orator.feature.podcasts.data

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ParsedFeed(
    val title: String,
    val author: String?,
    val description: String?,
    val artworkUrl: String?,
    val items: List<ParsedItem>,
)

data class ParsedItem(
    val guid: String?,
    val title: String,
    val pubDateUtc: Long,
    val durationMs: Long,
    val enclosureUrl: String,
    val enclosureType: String?,
    val showNotesHtml: String?,
)

/**
 * Hand-rolled, tolerant RSS 2.0 parser (the chpl lesson: real-world data is messy — skip bad
 * items, never abort the feed). Namespace prefixes vary by feed, so tags are matched on the
 * local name after ':'.
 */
object RssParser {

    fun parse(xml: String): ParsedFeed? = try {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(xml.reader())

        var channelTitle: String? = null
        var author: String? = null
        var description: String? = null
        var artworkUrl: String? = null
        val items = mutableListOf<ParsedItem>()
        var inItem = false

        // item-in-progress fields
        var iGuid: String? = null
        var iTitle: String? = null
        var iPubDate = 0L
        var iDuration = 0L
        var iEnclosureUrl: String? = null
        var iEnclosureType: String? = null
        var iDescription: String? = null
        var iContentEncoded: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.substringAfterLast(':')
                    if (inItem) {
                        when (name) {
                            "title" -> iTitle = readText(parser)
                            "guid" -> iGuid = readText(parser)?.takeIf { it.isNotBlank() }
                            "pubDate" -> iPubDate = parseDate(readText(parser))
                            "duration" -> iDuration = parseDuration(readText(parser))
                            "enclosure" -> {
                                iEnclosureUrl = parser.getAttributeValue(null, "url")
                                iEnclosureType = parser.getAttributeValue(null, "type")
                            }
                            "description" -> iDescription = readText(parser)
                            "encoded" -> iContentEncoded = readText(parser)
                        }
                    } else {
                        when (name) {
                            "item" -> {
                                inItem = true
                                iGuid = null; iTitle = null; iPubDate = 0L; iDuration = 0L
                                iEnclosureUrl = null; iEnclosureType = null
                                iDescription = null; iContentEncoded = null
                            }
                            "title" -> if (channelTitle == null) channelTitle = readText(parser)
                            "author" -> if (author == null) author = readText(parser)
                            "description" -> if (description == null) description = readText(parser)
                            "image" -> parser.getAttributeValue(null, "href")
                                ?.let { artworkUrl = it } // <itunes:image href=.../>
                            "url" -> if (artworkUrl == null) artworkUrl = readText(parser) // <image><url>
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name.substringAfterLast(':') == "item" && inItem) {
                        inItem = false
                        val title = iTitle
                        val enclosure = iEnclosureUrl
                        if (!title.isNullOrBlank() && !enclosure.isNullOrBlank()) {
                            items += ParsedItem(
                                guid = iGuid,
                                title = title,
                                pubDateUtc = iPubDate,
                                durationMs = iDuration,
                                enclosureUrl = enclosure,
                                enclosureType = iEnclosureType,
                                showNotesHtml = iContentEncoded ?: iDescription,
                            )
                        }
                    }
                }
            }
            event = parser.next()
        }

        channelTitle?.takeIf { it.isNotBlank() }?.let { t ->
            ParsedFeed(t, author, description, artworkUrl, items)
        }
    } catch (e: Exception) {
        null // garbage in, null out — callers report a per-feed failure
    }

    /** Concatenates TEXT/CDSECT until the element closes; tolerates nested tags inside. */
    private fun readText(parser: XmlPullParser): String? {
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.TEXT -> sb.append(parser.text)
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return sb.toString().trim().ifEmpty { null }
            }
        }
        return sb.toString().trim().ifEmpty { null }
    }

    private val RFC1123 = DateTimeFormatter.RFC_1123_DATE_TIME
    private val RFC1123_LENIENT =
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss zzz", Locale.US)

    private fun parseDate(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        for (fmt in listOf(RFC1123, RFC1123_LENIENT)) {
            try {
                return ZonedDateTime.parse(text.trim(), fmt).toInstant().toEpochMilli()
            } catch (_: Exception) { /* try next */ }
        }
        return 0L
    }

    /** itunes:duration is "HH:MM:SS", "MM:SS", or bare seconds. */
    private fun parseDuration(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        val parts = text.trim().split(':')
        return try {
            when (parts.size) {
                1 -> parts[0].toLong() * 1000
                2 -> (parts[0].toLong() * 60 + parts[1].toLong()) * 1000
                3 -> ((parts[0].toLong() * 60 + parts[1].toLong()) * 60 + parts[2].toLong()) * 1000
                else -> 0L
            }
        } catch (_: NumberFormatException) {
            0L
        }
    }
}
```

- [ ] **Step 5: Run tests**

Run: `./gradlew :feature:podcasts:testDebugUnitTest --tests "com.orator.feature.podcasts.data.RssParserTest"`
Expected: 7 tests PASS. If `XmlPullParserFactory` fails to resolve on the JVM, add `testImplementation("net.sf.kxml:kxml2:2.3.0")` to the feature build file and note it as a test-only dependency.

- [ ] **Step 6: Commit**

```bash
git add feature/podcasts/src
git commit -m "feat: tolerant hand-rolled RSS parser with synthetic fixtures"
```

### Task 6: OpmlParser

**Files:**
- Create: `feature/podcasts/src/main/java/com/orator/feature/podcasts/data/OpmlParser.kt`
- Create fixture: `feature/podcasts/src/test/resources/feeds.opml`
- Test: `feature/podcasts/src/test/java/com/orator/feature/podcasts/data/OpmlParserTest.kt`

- [ ] **Step 1: Fixture `feeds.opml`** (synthetic; includes nesting and a non-feed outline):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<opml version="2.0">
  <head><title>Subscriptions</title></head>
  <body>
    <outline text="News" title="News">
      <outline text="Show A" type="rss" xmlUrl="https://example.com/a.xml"/>
      <outline text="Show B" type="rss" xmlUrl="https://example.com/b.xml"/>
    </outline>
    <outline text="Show C" type="rss" xmlUrl="https://example.com/c.xml"/>
    <outline text="Just a folder label"/>
  </body>
</opml>
```

- [ ] **Step 2: Failing tests**

```kotlin
package com.orator.feature.podcasts.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpmlParserTest {

    private fun load(name: String): String =
        checkNotNull(javaClass.classLoader).getResourceAsStream(name)!!
            .readBytes().decodeToString()

    @Test
    fun `extracts all feeds regardless of nesting`() {
        val feeds = OpmlParser.parse(load("feeds.opml"))
        assertEquals(3, feeds.size)
        assertEquals("Show A", feeds[0].title)
        assertEquals("https://example.com/a.xml", feeds[0].xmlUrl)
        assertEquals("https://example.com/c.xml", feeds[2].xmlUrl)
    }

    @Test
    fun `garbage returns empty list`() {
        assertTrue(OpmlParser.parse("not xml").isEmpty())
    }
}
```

- [ ] **Step 3: Run to verify failure** — same gradle command pattern, expect `Unresolved reference: OpmlParser`.

- [ ] **Step 4: Implement `OpmlParser.kt`**

```kotlin
package com.orator.feature.podcasts.data

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

data class OpmlFeed(val title: String, val xmlUrl: String)

/** Any <outline> with an xmlUrl is a feed, at any nesting depth; everything else is a folder. */
object OpmlParser {

    fun parse(xml: String): List<OpmlFeed> = try {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(xml.reader())
        val feeds = mutableListOf<OpmlFeed>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "outline") {
                val url = parser.getAttributeValue(null, "xmlUrl")
                if (!url.isNullOrBlank()) {
                    val title = parser.getAttributeValue(null, "title")
                        ?: parser.getAttributeValue(null, "text")
                        ?: url
                    feeds += OpmlFeed(title, url)
                }
            }
            event = parser.next()
        }
        feeds
    } catch (e: Exception) {
        emptyList()
    }
}
```

- [ ] **Step 5: Run tests** — expect 2 PASS.

- [ ] **Step 6: Commit**

```bash
git add feature/podcasts/src
git commit -m "feat: OPML parser extracting feeds at any nesting depth"
```

### Task 7: PodcastIds + PodcastMediaId

**Files:**
- Create: `feature/podcasts/src/main/java/com/orator/feature/podcasts/data/PodcastIds.kt`, `PodcastMediaId.kt`
- Test: `feature/podcasts/src/test/java/com/orator/feature/podcasts/data/PodcastIdsTest.kt`

- [ ] **Step 1: Failing tests**

```kotlin
package com.orator.feature.podcasts.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PodcastIdsTest {

    @Test
    fun `ids are deterministic and url-safe`() {
        val a = PodcastIds.podcastId("https://example.com/feed.xml")
        assertEquals(a, PodcastIds.podcastId("https://example.com/feed.xml"))
        assertEquals(16, a.length)
        assertTrue(a.all { it.isDigit() || it in 'a'..'f' })
    }

    @Test
    fun `same guid in different podcasts yields different episode ids`() {
        val e1 = PodcastIds.episodeId("pod-a", "ep-1")
        val e2 = PodcastIds.episodeId("pod-b", "ep-1")
        assertNotEquals(e1, e2)
    }

    @Test
    fun `media id round-trips`() {
        val id = PodcastIds.episodeId("pod-a", "ep-1")
        assertEquals(id, PodcastMediaId.parse(PodcastMediaId.encode(id)))
    }

    @Test
    fun `media id rejects foreign ids`() {
        assertNull(PodcastMediaId.parse("audiobook/book1/0"))
        assertNull(PodcastMediaId.parse("garbage"))
    }
}
```

(Add `import org.junit.Assert.assertTrue`.)

- [ ] **Step 2: Run to verify failure.**

- [ ] **Step 3: Implement**

`PodcastIds.kt`:

```kotlin
package com.orator.feature.podcasts.data

import java.security.MessageDigest

/**
 * Stable ids. Episode ids are namespaced by podcast because RSS GUIDs are only unique
 * within one feed (spec plan-level decision).
 */
object PodcastIds {

    fun podcastId(feedUrl: String): String = sha256Hex(feedUrl).take(16)

    fun episodeId(podcastId: String, guidOrEnclosureUrl: String): String =
        sha256Hex("$podcastId|$guidOrEnclosureUrl").take(16)

    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
```

`PodcastMediaId.kt` (same shape as `AudiobookMediaId`):

```kotlin
package com.orator.feature.podcasts.data

/** Routes service callbacks (position, speed) back to an episode row. Format: "podcast/<episodeId>". */
object PodcastMediaId {
    private const val PREFIX = "podcast"

    fun encode(episodeId: String): String = "$PREFIX/$episodeId"

    fun parse(mediaId: String): String? {
        val parts = mediaId.split('/')
        if (parts.size != 2 || parts[0] != PREFIX || parts[1].isBlank()) return null
        return parts[1]
    }
}
```

- [ ] **Step 4: Run tests** — expect 4 PASS.

- [ ] **Step 5: Commit**

```bash
git add feature/podcasts/src
git commit -m "feat: namespaced podcast/episode ids and mediaId codec"
```

---

## Chunk 3: cache tree + repository

### Task 8: CacheNames + CacheJson (pure, fully tested)

**Files:**
- Create: `feature/podcasts/src/main/java/com/orator/feature/podcasts/data/CacheNames.kt`, `CacheJson.kt`
- Test: `feature/podcasts/src/test/java/com/orator/feature/podcasts/data/CacheNamesTest.kt`, `CacheJsonTest.kt`

- [ ] **Step 1: Failing tests**

`CacheNamesTest.kt` (plain JUnit, no Robolectric):

```kotlin
package com.orator.feature.podcasts.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CacheNamesTest {

    @Test
    fun `sanitizes illegal filename characters`() {
        assertEquals("What_s Up_ Doc_", CacheNames.sanitize("What's Up? Doc:"))
        assertEquals("a_b_c", CacheNames.sanitize("a/b\\c"))
    }

    @Test
    fun `trims trailing dots and spaces and caps length`() {
        assertEquals("ends", CacheNames.sanitize("ends. . ."))
        assertEquals(80, CacheNames.sanitize("x".repeat(200)).length)
    }

    @Test
    fun `blank becomes untitled`() {
        assertEquals("untitled", CacheNames.sanitize("  "))
    }

    @Test
    fun `episode dir name is date-prefixed`() {
        // 2026-06-09T08:00Z
        assertEquals(
            "2026-06-09 - My Episode",
            CacheNames.episodeDirName(1_780_905_600_000L, "My Episode"),
        )
    }

    @Test
    fun `unknown date prefixes with 0000`() {
        assertEquals("0000-00-00 - Mystery", CacheNames.episodeDirName(0L, "Mystery"))
    }

    @Test
    fun `collision suffix appends short id`() {
        assertEquals("Show [abcd]", CacheNames.withIdSuffix("Show", "abcdef0123456789"))
    }
}
```

NOTE for implementer: verify the epoch constant actually formats to `2026-06-09` in UTC before
trusting the test (`Instant.ofEpochMilli(1_780_905_600_000L)` — adjust the constant if needed,
not the format).

`CacheJsonTest.kt` (Robolectric — `org.json` is an Android API; annotate `@RunWith(RobolectricTestRunner::class)` and `@Config(sdk = [34])` like `MediaItemFactoryTest`):

```kotlin
package com.orator.feature.podcasts.data

import com.orator.core.database.EpisodeEntity
import com.orator.core.database.PodcastEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CacheJsonTest {

    @Test
    fun `show json round-trips key fields`() {
        val podcast = PodcastEntity(
            id = "p1", feedUrl = "https://x/feed.xml", title = "Show",
            author = "Jane", description = "About", artworkUrl = "https://x/c.jpg",
            subscribedAtUtc = 5,
        )
        val parsed = JSONObject(CacheJson.showJson(podcast))
        assertEquals("Show", parsed.getString("title"))
        assertEquals("https://x/feed.xml", parsed.getString("feedUrl"))
        assertEquals("Jane", parsed.getString("author"))
    }

    @Test
    fun `episode json includes guid source id and enclosure`() {
        val episode = EpisodeEntity(
            id = "e1", podcastId = "p1", title = "Ep", pubDateUtc = 7,
            durationMs = 1000, enclosureUrl = "https://x/e.mp3",
        )
        val parsed = JSONObject(CacheJson.episodeJson(episode))
        assertEquals("e1", parsed.getString("id"))
        assertEquals("https://x/e.mp3", parsed.getString("enclosureUrl"))
        assertEquals(1000, parsed.getLong("durationMs"))
    }
}
```

- [ ] **Step 2: Run to verify failure.**

- [ ] **Step 3: Implement**

`CacheNames.kt`:

```kotlin
package com.orator.feature.podcasts.data

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Filesystem naming for the human-readable cache tree. Pure functions, no I/O. */
object CacheNames {

    private val ILLEGAL = Regex("""[\\/:*?"<>|\p{Cntrl}]""")
    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)

    fun sanitize(name: String): String {
        val cleaned = ILLEGAL.replace(name, "_").trim().trimEnd('.', ' ').take(80).trim()
        return cleaned.ifBlank { "untitled" }
    }

    fun episodeDirName(pubDateUtc: Long, title: String): String {
        val prefix = if (pubDateUtc > 0) DATE.format(Instant.ofEpochMilli(pubDateUtc)) else "0000-00-00"
        return "$prefix - ${sanitize(title)}"
    }

    /** Disambiguates colliding sanitized names ("Show" vs "Show?") deterministically. */
    fun withIdSuffix(base: String, id: String): String = "$base [${id.take(4)}]"
}
```

`CacheJson.kt`:

```kotlin
package com.orator.feature.podcasts.data

import com.orator.core.database.EpisodeEntity
import com.orator.core.database.PodcastEntity
import org.json.JSONObject

/** Pretty-printed JSON for the cache tree. org.json ships with Android — no dependency. */
object CacheJson {

    fun showJson(podcast: PodcastEntity): String = JSONObject().apply {
        put("id", podcast.id)
        put("title", podcast.title)
        put("feedUrl", podcast.feedUrl)
        putOpt("author", podcast.author)
        putOpt("description", podcast.description)
        putOpt("artworkUrl", podcast.artworkUrl)
        put("subscribedAtUtc", podcast.subscribedAtUtc)
    }.toString(2)

    fun episodeJson(episode: EpisodeEntity): String = JSONObject().apply {
        put("id", episode.id)
        put("title", episode.title)
        put("pubDateUtc", episode.pubDateUtc)
        put("durationMs", episode.durationMs)
        put("enclosureUrl", episode.enclosureUrl)
    }.toString(2)
}
```

- [ ] **Step 4: Run both test classes** — expect 8 PASS total.

- [ ] **Step 5: Commit**

```bash
git add feature/podcasts/src
git commit -m "feat: cache tree naming + pretty-printed JSON builders"
```

### Task 9: PodcastsFolderStore + EpisodeCacheWriter

**Files:**
- Create: `feature/podcasts/src/main/java/com/orator/feature/podcasts/data/PodcastsFolderStore.kt`, `EpisodeCacheWriter.kt`

The writer is thin SAF I/O around the pure helpers from Task 8; its logic was tested there and
the tree itself is device-verified in Chunk 6. No unit tests for this task (DocumentFile against
a real tree URI cannot be exercised meaningfully on the JVM).

- [ ] **Step 1: `PodcastsFolderStore.kt`** (clone of `AudiobooksPrefs`, different store name)

```kotlin
package com.orator.feature.podcasts.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.podcastsDataStore by preferencesDataStore(name = "podcasts")
private val KEY_TREE_URI = stringPreferencesKey("tree_uri")

/** Remembers the SAF base folder the user granted for the podcast cache tree. */
@Singleton
class PodcastsFolderStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val treeUri: Flow<String?> = context.podcastsDataStore.data.map { it[KEY_TREE_URI] }

    suspend fun setTreeUri(uri: String) {
        context.podcastsDataStore.edit { it[KEY_TREE_URI] = uri }
    }
}
```

- [ ] **Step 2: `EpisodeCacheWriter.kt`**

```kotlin
package com.orator.feature.podcasts.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.orator.core.database.EpisodeEntity
import com.orator.core.database.PodcastEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes the human-readable mirror: Podcasts/<Show>/show.json + cover + episodes/<date - title>/.
 * Per the spec amendment, episode dirs are only written for the latest N per show plus downloaded
 * episodes — SAF ops cost 10–50 ms each, so a full-history import must not touch the tree.
 * Every method is best-effort: tree failures never block DB writes (the DB is the source of truth).
 */
@Singleton
class EpisodeCacheWriter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val folderStore: PodcastsFolderStore,
) {

    /** Resolves <picked>/Podcasts, creating it if needed; null when no folder is granted yet. */
    private suspend fun podcastsRoot(): DocumentFile? {
        val uri = folderStore.treeUri.first() ?: return null
        val base = DocumentFile.fromTreeUri(context, Uri.parse(uri)) ?: return null
        return base.findFile("Podcasts")?.takeIf { it.isDirectory }
            ?: base.createDirectory("Podcasts")
    }

    /**
     * Show dir named by sanitized title; on collision with a DIFFERENT show (marker file
     * `.orator-id` holds the owning podcast id) the name gets an id suffix.
     */
    private suspend fun showDir(podcast: PodcastEntity, create: Boolean): DocumentFile? {
        val root = podcastsRoot() ?: return null
        val plain = CacheNames.sanitize(podcast.title)
        for (name in listOf(plain, CacheNames.withIdSuffix(plain, podcast.id))) {
            val existing = root.findFile(name)
            if (existing == null) {
                if (!create) return null
                val dir = root.createDirectory(name) ?: return null
                writeText(dir, ".orator-id", podcast.id)
                return dir
            }
            if (ownerId(existing) == podcast.id) return existing
        }
        return null
    }

    private fun ownerId(dir: DocumentFile): String? =
        dir.findFile(".orator-id")?.let { f ->
            runCatching {
                context.contentResolver.openInputStream(f.uri)?.use {
                    it.readBytes().decodeToString().trim()
                }
            }.getOrNull()
        }

    suspend fun writeShow(podcast: PodcastEntity) = bestEffort {
        val dir = showDir(podcast, create = true) ?: return@bestEffort
        writeText(dir, "show.json", CacheJson.showJson(podcast))
    }

    suspend fun writeCover(podcast: PodcastEntity, bytes: ByteArray) = bestEffort {
        val dir = showDir(podcast, create = true) ?: return@bestEffort
        writeBytes(dir, "cover.jpg", bytes)
    }

    suspend fun writeEpisode(podcast: PodcastEntity, episode: EpisodeEntity) = bestEffort {
        episodeDir(podcast, episode, create = true)?.let { dir ->
            writeText(dir, "episode.json", CacheJson.episodeJson(episode))
            episode.showNotesHtml?.let { writeText(dir, "shownotes.html", it) }
        }
    }

    /** Used by the downloader to place audio files. */
    suspend fun episodeDir(
        podcast: PodcastEntity,
        episode: EpisodeEntity,
        create: Boolean,
    ): DocumentFile? {
        val show = showDir(podcast, create) ?: return null
        val episodes = show.findFile("episodes")?.takeIf { it.isDirectory }
            ?: if (create) show.createDirectory("episodes") else null
        val episodesDir = episodes ?: return null
        val name = CacheNames.episodeDirName(episode.pubDateUtc, episode.title)
        for (candidate in listOf(name, CacheNames.withIdSuffix(name, episode.id))) {
            val existing = episodesDir.findFile(candidate)
            if (existing == null) {
                if (!create) return null
                val dir = episodesDir.createDirectory(candidate) ?: return null
                writeText(dir, ".orator-id", episode.id)
                return dir
            }
            if (ownerId(existing) == episode.id) return existing
        }
        return null
    }

    private suspend fun writeText(dir: DocumentFile, name: String, content: String) =
        writeBytes(dir, name, content.toByteArray())

    private suspend fun writeBytes(dir: DocumentFile, name: String, bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            val mime = if (name.endsWith(".jpg")) "image/jpeg" else "text/plain"
            val file = dir.findFile(name) ?: dir.createFile(mime, name) ?: return@withContext
            context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(bytes) }
        }
    }

    private suspend fun bestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            // Tree is a mirror; a failed write must never fail the refresh.
        }
    }
}
```

NOTE: SAF renames `name` to `name.txt` etc. when the mime doesn't match an extension; using
`text/plain` for `.json`/`.html` can produce `show.json.txt` on some providers. If the device
checklist shows mangled names, switch `mime` per extension: `.json` → `application/json`,
`.html` → `text/html`, and keep the requested display name. Do NOT spend time on this before
device verification.

- [ ] **Step 3: Compile check** — Run: `./gradlew :feature:podcasts:assembleDebug` — BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add feature/podcasts/src
git commit -m "feat: SAF folder store and best-effort cache tree writer"
```

### Task 10: PodcastRepository

**Files:**
- Create: `feature/podcasts/src/main/java/com/orator/feature/podcasts/data/PodcastRepository.kt`
- Test: `feature/podcasts/src/test/java/com/orator/feature/podcasts/data/PodcastRepositoryTest.kt`

- [ ] **Step 1: Failing tests** (Robolectric + in-memory Room + fake fetcher; `runBlocking` — NOT `runTest`)

```kotlin
package com.orator.feature.podcasts.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.orator.core.database.OratorDatabase
import com.orator.core.network.FeedFetcher
import com.orator.core.network.FetchResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val FEED_A = "https://example.com/a.xml"
private const val FEED_B = "https://example.com/b.xml"

private fun rss(title: String, vararg items: Pair<String, String>) = buildString {
    append("""<?xml version="1.0"?><rss version="2.0"><channel><title>$title</title>""")
    for ((guid, itemTitle) in items) {
        append(
            """<item><title>$itemTitle</title><guid>$guid</guid>""" +
                """<enclosure url="https://example.com/$guid.mp3" type="audio/mpeg"/></item>""",
        )
    }
    append("</channel></rss>")
}

@RunWith(RobolectricTestRunner::class)
class PodcastRepositoryTest {

    private lateinit var db: OratorDatabase
    private lateinit var repository: PodcastRepository
    private val responses = mutableMapOf<String, FetchResult>()

    /** FeedFetcher is a concrete class; fake it by overriding fetch. */
    private val fetcher = object : FeedFetcher(OkHttpClient()) {
        override suspend fun fetch(url: String, etag: String?, lastModified: String?): FetchResult =
            responses[url] ?: FetchResult.Failure("no stub for $url")
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, OratorDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = PodcastRepository(
            fetcher = fetcher,
            podcastDao = db.podcastDao(),
            episodeDao = db.episodeDao(),
            cacheWriter = EpisodeCacheWriter(context, PodcastsFolderStore(context)),
            client = OkHttpClient(),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `subscribe stores podcast and episodes`() = runBlocking {
        responses[FEED_A] = FetchResult.Success(rss("Show A", "g1" to "One", "g2" to "Two"), "\"v1\"", null)

        val id = repository.subscribe(FEED_A).getOrThrow()

        val podcast = db.podcastDao().getById(id)!!
        assertEquals("Show A", podcast.title)
        assertEquals("\"v1\"", podcast.etag)
        assertEquals(2, db.episodeDao().latestForPodcast(id, 10).size)
    }

    @Test
    fun `subscribe twice is idempotent`() = runBlocking {
        responses[FEED_A] = FetchResult.Success(rss("Show A", "g1" to "One"), null, null)
        val id1 = repository.subscribe(FEED_A).getOrThrow()
        val id2 = repository.subscribe(FEED_A).getOrThrow()
        assertEquals(id1, id2)
        assertEquals(1, db.podcastDao().getAll().size)
    }

    @Test
    fun `subscribe to broken feed fails without writing rows`() = runBlocking {
        responses[FEED_A] = FetchResult.Success("<html>not rss</html>", null, null)
        assertTrue(repository.subscribe(FEED_A).isFailure)
        assertEquals(0, db.podcastDao().getAll().size)
    }

    @Test
    fun `refresh with 304 touches nothing`() = runBlocking {
        responses[FEED_A] = FetchResult.Success(rss("Show A", "g1" to "One"), "\"v1\"", null)
        val id = repository.subscribe(FEED_A).getOrThrow()
        responses[FEED_A] = FetchResult.NotModified

        val summary = repository.refreshAll()

        assertEquals(1, summary.refreshed)
        assertEquals(0, summary.failed)
        assertEquals("One", db.episodeDao().latestForPodcast(id, 10).single().title)
    }

    @Test
    fun `refresh preserves position and audioPath but updates metadata`() = runBlocking {
        responses[FEED_A] = FetchResult.Success(rss("Show A", "g1" to "One"), null, null)
        val id = repository.subscribe(FEED_A).getOrThrow()
        val episodeId = db.episodeDao().latestForPodcast(id, 10).single().id
        db.episodeDao().updateProgress(episodeId, 5_000, 99)
        db.episodeDao().updateAudioPath(episodeId, "content://dl/audio.mp3")
        responses[FEED_A] = FetchResult.Success(
            rss("Show A", "g1" to "One (remastered)", "g2" to "Two"), null, null,
        )

        repository.refreshAll()

        val episodes = db.episodeDao().latestForPodcast(id, 10)
        assertEquals(2, episodes.size)
        val updated = db.episodeDao().getById(episodeId)!!
        assertEquals("One (remastered)", updated.title)
        assertEquals(5_000L, updated.positionMs)
        assertEquals("content://dl/audio.mp3", updated.audioPath)
    }

    @Test
    fun `one failing feed does not block the others`() = runBlocking {
        responses[FEED_A] = FetchResult.Success(rss("Show A", "g1" to "One"), null, null)
        responses[FEED_B] = FetchResult.Success(rss("Show B", "g1" to "Uno"), null, null)
        repository.subscribe(FEED_A).getOrThrow()
        val idB = repository.subscribe(FEED_B).getOrThrow()
        responses[FEED_A] = FetchResult.Failure("boom")
        responses[FEED_B] = FetchResult.Success(rss("Show B", "g1" to "Uno", "g2" to "Dos"), null, null)

        val summary = repository.refreshAll()

        assertEquals(1, summary.refreshed)
        assertEquals(1, summary.failed)
        assertEquals(2, db.episodeDao().latestForPodcast(idB, 10).size)
    }

    @Test
    fun `importOpml subscribes all feeds and isolates failures`() = runBlocking {
        responses[FEED_A] = FetchResult.Success(rss("Show A", "g1" to "One"), null, null)
        responses[FEED_B] = FetchResult.Failure("unreachable")
        val opml = """<opml version="2.0"><body>""" +
            """<outline text="A" xmlUrl="$FEED_A"/><outline text="B" xmlUrl="$FEED_B"/>""" +
            """</body></opml>"""

        val summary = repository.importOpml(opml)

        assertEquals(1, summary.refreshed)
        assertEquals(1, summary.failed)
        assertEquals(1, db.podcastDao().getAll().size)
    }

    @Test
    fun `same guid across two shows stays two episodes`() = runBlocking {
        responses[FEED_A] = FetchResult.Success(rss("Show A", "g1" to "One"), null, null)
        responses[FEED_B] = FetchResult.Success(rss("Show B", "g1" to "Uno"), null, null)
        val idA = repository.subscribe(FEED_A).getOrThrow()
        val idB = repository.subscribe(FEED_B).getOrThrow()
        assertEquals(1, db.episodeDao().latestForPodcast(idA, 10).size)
        assertEquals(1, db.episodeDao().latestForPodcast(idB, 10).size)
    }
}
```

NOTE: faking `FeedFetcher` by subclassing requires it to be `open` with an `open fun fetch`.
Make that change in `core/network` as part of this task (document why: "open for test fakes —
no interface ceremony for a single-method class").

- [ ] **Step 2: Run to verify failure.**

- [ ] **Step 3: Implement `PodcastRepository.kt`**

```kotlin
package com.orator.feature.podcasts.data

import com.orator.core.database.EpisodeDao
import com.orator.core.database.EpisodeEntity
import com.orator.core.database.PodcastDao
import com.orator.core.database.PodcastEntity
import com.orator.core.network.FeedFetcher
import com.orator.core.network.FetchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/** Written to the tree per show: latest N episodes (plus anything downloaded). Spec amendment. */
private const val TREE_EPISODE_LIMIT = 20

@Singleton
class PodcastRepository @Inject constructor(
    private val fetcher: FeedFetcher,
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
    private val cacheWriter: EpisodeCacheWriter,
    private val client: OkHttpClient,
) {

    data class RefreshSummary(val refreshed: Int, val failed: Int)

    val podcasts: Flow<List<PodcastEntity>> = podcastDao.observeAll()

    /** Non-null while a long operation runs; placeholder screens render it as a status line. */
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    suspend fun subscribe(feedUrl: String): Result<String> = withContext(Dispatchers.IO) {
        val id = PodcastIds.podcastId(feedUrl)
        podcastDao.getById(id)?.let { return@withContext Result.success(id) }

        val fetched = fetcher.fetch(feedUrl)
        val success = fetched as? FetchResult.Success
            ?: return@withContext Result.failure(
                IllegalStateException("fetch failed: ${(fetched as? FetchResult.Failure)?.message}"),
            )
        val parsed = RssParser.parse(success.body)
            ?: return@withContext Result.failure(IllegalStateException("not a parsable RSS feed"))

        val podcast = PodcastEntity(
            id = id,
            feedUrl = feedUrl,
            title = parsed.title,
            author = parsed.author,
            description = parsed.description,
            artworkUrl = parsed.artworkUrl,
            subscribedAtUtc = System.currentTimeMillis(),
            lastRefreshUtc = System.currentTimeMillis(),
            etag = success.etag,
            lastModified = success.lastModified,
        )
        podcastDao.insertIgnore(podcast)
        upsertEpisodes(id, parsed)
        writeTree(podcast)
        Result.success(id)
    }

    suspend fun importOpml(opmlXml: String): RefreshSummary = withContext(Dispatchers.IO) {
        val feeds = OpmlParser.parse(opmlXml)
        var ok = 0
        var failed = 0
        feeds.forEachIndexed { index, feed ->
            _busy.value = "Importing ${index + 1}/${feeds.size}: ${feed.title}"
            if (subscribe(feed.xmlUrl).isSuccess) ok++ else failed++
        }
        _busy.value = null
        RefreshSummary(ok, failed)
    }

    suspend fun refreshAll(): RefreshSummary = withContext(Dispatchers.IO) {
        val all = podcastDao.getAll()
        var ok = 0
        var failed = 0
        all.forEachIndexed { index, podcast ->
            _busy.value = "Refreshing ${index + 1}/${all.size}: ${podcast.title}"
            if (refresh(podcast)) ok++ else failed++
        }
        _busy.value = null
        RefreshSummary(ok, failed)
    }

    private suspend fun refresh(podcast: PodcastEntity): Boolean {
        return when (val result = fetcher.fetch(podcast.feedUrl, podcast.etag, podcast.lastModified)) {
            is FetchResult.NotModified -> {
                podcastDao.touchRefresh(podcast.id, System.currentTimeMillis())
                true
            }
            is FetchResult.Success -> {
                val parsed = RssParser.parse(result.body) ?: return false
                podcastDao.updateFeedMeta(
                    id = podcast.id,
                    title = parsed.title,
                    author = parsed.author,
                    description = parsed.description,
                    artworkUrl = parsed.artworkUrl,
                    refreshedAtUtc = System.currentTimeMillis(),
                    etag = result.etag,
                    lastModified = result.lastModified,
                )
                upsertEpisodes(podcast.id, parsed)
                writeTree(podcastDao.getById(podcast.id) ?: podcast)
                true
            }
            is FetchResult.Failure -> false
        }
    }

    /** Insert-then-update keeps positions/downloads intact (EpisodeDao contract). */
    private suspend fun upsertEpisodes(podcastId: String, parsed: ParsedFeed) {
        val entities = parsed.items.map { item ->
            EpisodeEntity(
                id = PodcastIds.episodeId(podcastId, item.guid ?: item.enclosureUrl),
                podcastId = podcastId,
                title = item.title,
                pubDateUtc = item.pubDateUtc,
                durationMs = item.durationMs,
                enclosureUrl = item.enclosureUrl,
                showNotesHtml = item.showNotesHtml,
            )
        }
        episodeDao.insertIgnore(entities)
        entities.forEach { e ->
            episodeDao.updateMetadata(
                id = e.id,
                title = e.title,
                pubDateUtc = e.pubDateUtc,
                enclosureUrl = e.enclosureUrl,
                showNotesHtml = e.showNotesHtml,
                durationMs = e.durationMs,
            )
        }
    }

    /** Best-effort mirror: show.json + cover + latest N episode dirs. Never throws. */
    private suspend fun writeTree(podcast: PodcastEntity) {
        cacheWriter.writeShow(podcast)
        podcast.artworkUrl?.let { url ->
            fetchBytes(url)?.let { cacheWriter.writeCover(podcast, it) }
        }
        episodeDao.latestForPodcast(podcast.id, TREE_EPISODE_LIMIT).forEach { episode ->
            cacheWriter.writeEpisode(podcast, episode)
        }
    }

    private fun fetchBytes(url: String): ByteArray? = try {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.isSuccessful) response.body?.bytes() else null
        }
    } catch (_: Exception) {
        null
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :feature:podcasts:testDebugUnitTest --tests "com.orator.feature.podcasts.data.PodcastRepositoryTest"`
Expected: 8 tests PASS

- [ ] **Step 5: Run the whole module + core:network + core:database to catch regressions**

Run: `./gradlew :feature:podcasts:testDebugUnitTest :core:network:testDebugUnitTest :core:database:testDebugUnitTest`
Expected: all PASS

- [ ] **Step 6: Commit**

```bash
git add feature/podcasts/src core/network/src
git commit -m "feat: podcast repository — subscribe, OPML import, refresh with isolation"
```

---

## Chunk 4: playback glue + downloads

### Task 11: EpisodeQueueBuilder

**Files:**
- Create: `feature/podcasts/src/main/java/com/orator/feature/podcasts/data/EpisodeQueueBuilder.kt`
- Test: `feature/podcasts/src/test/java/com/orator/feature/podcasts/data/EpisodeQueueBuilderTest.kt`

- [ ] **Step 1: Failing tests** (plain JUnit; PlayRequest is a plain data class)

```kotlin
package com.orator.feature.podcasts.data

import com.orator.core.database.EpisodeEntity
import com.orator.core.database.PodcastEntity
import com.orator.core.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeQueueBuilderTest {

    private fun podcast(intro: Long = 0, outro: Long = 0, speed: Float? = null) = PodcastEntity(
        id = "p1", feedUrl = "https://x/f.xml", title = "Show", author = null,
        description = null, artworkUrl = null, subscribedAtUtc = 0,
        clipIntroMs = intro, clipOutroMs = outro, speedOverride = speed,
    )

    private fun episode(durationMs: Long = 0, audioPath: String? = null) = EpisodeEntity(
        id = "e1", podcastId = "p1", title = "Ep", pubDateUtc = 0,
        durationMs = durationMs, enclosureUrl = "https://x/e.mp3", audioPath = audioPath,
    )

    @Test
    fun `streams from enclosure when not downloaded`() {
        val request = EpisodeQueueBuilder.build(podcast(), episode(), 0)
        assertEquals("https://x/e.mp3", request.items.single().uri)
        assertEquals(MediaType.PODCAST, request.mediaType)
    }

    @Test
    fun `plays local file when downloaded`() {
        val request = EpisodeQueueBuilder.build(podcast(), episode(audioPath = "content://dl/a.mp3"), 0)
        assertEquals("content://dl/a.mp3", request.items.single().uri)
    }

    @Test
    fun `applies intro and outro clips`() {
        val request = EpisodeQueueBuilder.build(
            podcast(intro = 30_000, outro = 60_000), episode(durationMs = 600_000), 0,
        )
        val item = request.items.single()
        assertEquals(30_000L, item.clipStartMs)
        assertEquals(540_000L, item.clipEndMs)
    }

    @Test
    fun `no outro clip when duration unknown`() {
        val request = EpisodeQueueBuilder.build(
            podcast(intro = 30_000, outro = 60_000), episode(durationMs = 0), 0,
        )
        val item = request.items.single()
        assertEquals(30_000L, item.clipStartMs)
        assertNull(item.clipEndMs)
    }

    @Test
    fun `degenerate outro larger than duration leaves a playable sliver`() {
        val request = EpisodeQueueBuilder.build(
            podcast(intro = 30_000, outro = 600_000), episode(durationMs = 100_000), 0,
        )
        val item = request.items.single()
        // clipEnd is clamped above clipStart so Media3 never gets an empty/inverted window
        assertEquals(31_000L, item.clipEndMs)
    }

    @Test
    fun `carries start position speed override and mediaId`() {
        val request = EpisodeQueueBuilder.build(podcast(speed = 1.5f), episode(), 42_000)
        assertEquals(42_000L, request.startPositionMs)
        assertEquals(1.5f, request.speedOverride)
        assertEquals("podcast/e1", request.items.single().mediaId)
    }
}
```

- [ ] **Step 2: Run to verify failure.**

- [ ] **Step 3: Implement**

```kotlin
package com.orator.feature.podcasts.data

import com.orator.core.database.EpisodeEntity
import com.orator.core.database.PodcastEntity
import com.orator.core.model.MediaType
import com.orator.core.playback.PlayRequest
import com.orator.core.playback.PlayableItem

/**
 * Episode → single-item PlayRequest. Clip windows come from the show's intro/outro settings;
 * positions everywhere downstream are clip-relative (Phase 3 invariant). No outro clip when
 * the duration is unknown — the position listener backfills it after first play.
 */
object EpisodeQueueBuilder {

    fun build(podcast: PodcastEntity, episode: EpisodeEntity, startAtMs: Long): PlayRequest {
        val clipEnd = if (episode.durationMs > 0 && podcast.clipOutroMs > 0) {
            (episode.durationMs - podcast.clipOutroMs).coerceAtLeast(podcast.clipIntroMs + 1_000)
        } else {
            null
        }
        return PlayRequest(
            items = listOf(
                PlayableItem(
                    mediaId = PodcastMediaId.encode(episode.id),
                    uri = episode.audioPath ?: episode.enclosureUrl,
                    title = episode.title,
                    artist = podcast.title,
                    clipStartMs = podcast.clipIntroMs,
                    clipEndMs = clipEnd,
                ),
            ),
            startPositionMs = startAtMs,
            mediaType = MediaType.PODCAST,
            speedOverride = podcast.speedOverride,
        )
    }
}
```

- [ ] **Step 4: Run tests** — 6 PASS.

- [ ] **Step 5: Commit**

```bash
git add feature/podcasts/src
git commit -m "feat: episode queue builder with per-show clip windows"
```

### Task 12: PodcastPositionListener (+ duration backfill)

**Files:**
- Create: `feature/podcasts/src/main/java/com/orator/feature/podcasts/data/PodcastPositionListener.kt`
- Test: `feature/podcasts/src/test/java/com/orator/feature/podcasts/data/PodcastPositionListenerTest.kt`
- Modify: `PodcastsFeatureModule.kt` (bind `@IntoSet`)

- [ ] **Step 1: Failing tests** (Robolectric + in-memory Room + `runBlocking`, mirroring `AudiobookPositionListenerTest`)

Test cases:
- `persists clip-relative position and lastPlayedAt` — insert podcast+episode, call `onPositionChanged("podcast/<id>", 5000, 0)`, assert positionMs == 5000 and lastPlayedAtMs > 0.
- `ignores non-podcast media ids` — call with `"audiobook/x/0"`, assert row unchanged.
- `backfills duration with intro offset when unknown` — podcast clipIntroMs = 30_000, episode durationMs = 0; call with durationMs = 570_000; assert episode.durationMs == 600_000.
- `never overwrites a known duration` — episode durationMs = 600_000; call with durationMs = 1; assert still 600_000.
- `no backfill when player duration unknown` — call with durationMs = 0 (Media3 reports C.TIME_UNSET → service passes 0); assert durationMs stays 0.

- [ ] **Step 2: Run to verify failure.**

- [ ] **Step 3: Implement**

```kotlin
package com.orator.feature.podcasts.data

import com.orator.core.database.EpisodeDao
import com.orator.core.database.PodcastDao
import com.orator.core.playback.PlaybackPositionListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Persists clip-relative resume positions for episodes. Also backfills the original-timeline
 * duration after first play: the player reports the CLIPPED duration, and backfill only fires
 * when durationMs == 0 — in that case no outro clip was applied, so original = player + intro
 * (spec "Duration backfill rule"). The never-overwrite guard lives in EpisodeDao SQL.
 */
class PodcastPositionListener @Inject constructor(
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
) : PlaybackPositionListener {

    override suspend fun onPositionChanged(mediaId: String, positionMs: Long, durationMs: Long) {
        val episodeId = PodcastMediaId.parse(mediaId) ?: return
        withContext(Dispatchers.IO) {
            val episode = episodeDao.getById(episodeId) ?: return@withContext
            episodeDao.updateProgress(episodeId, positionMs, System.currentTimeMillis())
            if (episode.durationMs == 0L && durationMs > 0) {
                val intro = podcastDao.getById(episode.podcastId)?.clipIntroMs ?: 0
                episodeDao.backfillDuration(episodeId, durationMs + intro)
            }
        }
    }
}
```

Bind it in `PodcastsFeatureModule`:

```kotlin
    @Binds
    @IntoSet
    fun bindPositionListener(listener: PodcastPositionListener): PlaybackPositionListener
```

(plus imports `com.orator.core.playback.PlaybackPositionListener`, `PodcastPositionListener`)

- [ ] **Step 4: Run tests** — 5 PASS.

- [ ] **Step 5: Commit**

```bash
git add feature/podcasts/src
git commit -m "feat: podcast position listener with original-timeline duration backfill"
```

### Task 13: EpisodeSpeedOverrideListener

**Files:**
- Create: `feature/podcasts/src/main/java/com/orator/feature/podcasts/data/EpisodeSpeedOverrideListener.kt`
- Test: `feature/podcasts/src/test/java/com/orator/feature/podcasts/data/EpisodeSpeedOverrideListenerTest.kt`
- Modify: `PodcastsFeatureModule.kt`

- [ ] **Step 1: Failing tests** (Robolectric + in-memory Room, mirror `BookSpeedOverrideListenerTest`): sets the override on the episode's SHOW; clears with null; ignores non-podcast ids; ignores unknown episode ids.

- [ ] **Step 2: Run to verify failure.**

- [ ] **Step 3: Implement**

```kotlin
package com.orator.feature.podcasts.data

import com.orator.core.database.EpisodeDao
import com.orator.core.database.PodcastDao
import com.orator.core.playback.SpeedOverrideListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Speed overrides are per-SHOW (spec decision): setting one while playing any episode sticks for the whole podcast. */
class EpisodeSpeedOverrideListener @Inject constructor(
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
) : SpeedOverrideListener {

    override suspend fun onSpeedOverrideChanged(mediaId: String, speed: Float?) {
        val episodeId = PodcastMediaId.parse(mediaId) ?: return
        withContext(Dispatchers.IO) {
            val episode = episodeDao.getById(episodeId) ?: return@withContext
            podcastDao.updateSpeedOverride(episode.podcastId, speed)
        }
    }
}
```

Bind in `PodcastsFeatureModule`:

```kotlin
    @Binds
    @IntoSet
    fun bindSpeedOverrideListener(listener: EpisodeSpeedOverrideListener): SpeedOverrideListener
```

- [ ] **Step 4: Run tests** — 4 PASS.

- [ ] **Step 5: Commit**

```bash
git add feature/podcasts/src
git commit -m "feat: per-show speed override listener for podcasts"
```

### Task 14: EpisodeDownloader

**Files:**
- Create: `feature/podcasts/src/main/java/com/orator/feature/podcasts/data/EpisodeDownloader.kt`
- Test: `feature/podcasts/src/test/java/com/orator/feature/podcasts/data/AudioExtTest.kt` (the pure part)

The streaming-to-SAF path is device-verified (Chunk 6); the testable pure logic is extension
mapping.

- [ ] **Step 1: Failing test**

```kotlin
package com.orator.feature.podcasts.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioExtTest {
    @Test
    fun `maps mime types and falls back to url extension then mp3`() {
        assertEquals("mp3", EpisodeDownloader.audioExt("audio/mpeg", "https://x/e?id=1"))
        assertEquals("m4a", EpisodeDownloader.audioExt("audio/mp4", "https://x/e"))
        assertEquals("m4a", EpisodeDownloader.audioExt("audio/x-m4a", "https://x/e"))
        assertEquals("ogg", EpisodeDownloader.audioExt("audio/ogg", "https://x/e"))
        assertEquals("m4a", EpisodeDownloader.audioExt(null, "https://x/ep.m4a?tok=2"))
        assertEquals("mp3", EpisodeDownloader.audioExt(null, "https://x/ep"))
    }
}
```

NOTE: the enclosure mime type isn't stored on `EpisodeEntity` — the downloader uses the
response `Content-Type` header at download time, with the URL extension as fallback. The test
exercises the same function.

- [ ] **Step 2: Run to verify failure.**

- [ ] **Step 3: Implement**

```kotlin
package com.orator.feature.podcasts.data

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.orator.core.database.EpisodeDao
import com.orator.core.database.PodcastDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Explicit per-episode downloads, one at a time (Mutex). Streams to "audio.partial" then renames,
 * so an interrupted download never masquerades as a finished file; a stale partial from a killed
 * app is deleted at the next attempt. Progress is -1 while indeterminate (no Content-Length).
 */
@Singleton
class EpisodeDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
    private val cacheWriter: EpisodeCacheWriter,
) {

    private val mutex = Mutex()
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()

    @Volatile private var cancelled: String? = null

    fun cancel(episodeId: String) {
        cancelled = episodeId
    }

    suspend fun download(episodeId: String): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.IO) {
            cancelled = null
            val episode = episodeDao.getById(episodeId)
                ?: return@withContext Result.failure(IllegalArgumentException("unknown episode"))
            val podcast = podcastDao.getById(episode.podcastId)
                ?: return@withContext Result.failure(IllegalArgumentException("unknown podcast"))
            val dir = cacheWriter.episodeDir(podcast, episode, create = true)
                ?: return@withContext Result.failure(IllegalStateException("no cache folder granted"))

            // also mirror metadata for downloaded episodes regardless of the latest-N window
            cacheWriter.writeEpisode(podcast, episode)

            dir.findFile("audio.partial")?.delete()
            val partial = dir.createFile("application/octet-stream", "audio.partial")
                ?: return@withContext Result.failure(IllegalStateException("cannot create file"))

            try {
                client.newCall(Request.Builder().url(episode.enclosureUrl).build())
                    .execute().use { response ->
                        if (!response.isSuccessful) {
                            partial.delete()
                            return@withContext Result.failure(IllegalStateException("HTTP ${response.code}"))
                        }
                        val body = response.body
                            ?: return@withContext Result.failure(IllegalStateException("empty body"))
                        val total = body.contentLength()
                        val ext = audioExt(response.header("Content-Type"), episode.enclosureUrl)
                        var copied = 0L
                        context.contentResolver.openOutputStream(partial.uri, "wt")!!.use { out ->
                            body.byteStream().use { input ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    if (cancelled == episodeId) {
                                        partial.delete()
                                        return@withContext Result.failure(InterruptedException("cancelled"))
                                    }
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    out.write(buffer, 0, read)
                                    copied += read
                                    setProgress(episodeId, if (total > 0) copied.toFloat() / total else -1f)
                                }
                            }
                        }
                        if (!partial.renameTo("audio.$ext")) {
                            partial.delete()
                            return@withContext Result.failure(IllegalStateException("rename failed"))
                        }
                        val finalFile = dir.findFile("audio.$ext")
                            ?: return@withContext Result.failure(IllegalStateException("file vanished"))
                        episodeDao.updateAudioPath(episodeId, finalFile.uri.toString())
                        Result.success(Unit)
                    }
            } catch (e: Exception) {
                partial.delete()
                Result.failure(e)
            } finally {
                setProgress(episodeId, null)
            }
        }
    }

    suspend fun deleteDownload(episodeId: String) = withContext(Dispatchers.IO) {
        val episode = episodeDao.getById(episodeId) ?: return@withContext
        episode.audioPath?.let { path ->
            runCatching {
                DocumentFile.fromSingleUri(context, android.net.Uri.parse(path))?.delete()
            }
        }
        episodeDao.updateAudioPath(episodeId, null)
    }

    private fun setProgress(episodeId: String, value: Float?) {
        _progress.value = if (value == null) {
            _progress.value - episodeId
        } else {
            _progress.value + (episodeId to value)
        }
    }

    companion object {
        fun audioExt(contentType: String?, url: String): String {
            when {
                contentType == null -> Unit
                contentType.startsWith("audio/mpeg") -> return "mp3"
                contentType.startsWith("audio/mp4") || contentType.contains("m4a") -> return "m4a"
                contentType.startsWith("audio/ogg") -> return "ogg"
            }
            val path = url.substringBefore('?').substringAfterLast('/')
            val ext = path.substringAfterLast('.', missingDelimiterValue = "")
            return if (ext in setOf("mp3", "m4a", "m4b", "ogg", "opus", "aac", "wav")) ext else "mp3"
        }
    }
}
```

- [ ] **Step 4: Run tests** — AudioExtTest PASS; module compiles.

- [ ] **Step 5: Commit**

```bash
git add feature/podcasts/src
git commit -m "feat: sequential episode downloader with partial-file safety"
```

---

## Chunk 5: show notes + screens + navigation

### Task 15: ShowNotes (timestamp extraction)

**Files:**
- Create: `feature/podcasts/src/main/java/com/orator/feature/podcasts/data/ShowNotes.kt`
- Test: `feature/podcasts/src/test/java/com/orator/feature/podcasts/data/ShowNotesTest.kt`

- [ ] **Step 1: Failing tests** (Robolectric — `HtmlCompat` needs Android; `@Config(sdk = [34])`)

```kotlin
package com.orator.feature.podcasts.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShowNotesTest {

    @Test
    fun `strips html and finds mm-ss timestamps`() {
        val rendered = ShowNotes.render("<p>Intro at <b>1:23</b> and outro.</p>")
        assertTrue(rendered.text.contains("Intro at 1:23"))
        val link = rendered.links.single()
        assertEquals(83_000L, link.positionMs)
        assertEquals("1:23", rendered.text.substring(link.startIndex, link.endIndex))
    }

    @Test
    fun `finds hh-mm-ss timestamps`() {
        val rendered = ShowNotes.render("Deep dive at 1:02:03.")
        assertEquals(((1 * 60 + 2) * 60 + 3) * 1000L, rendered.links.single().positionMs)
    }

    @Test
    fun `ignores dates and invalid times`() {
        val rendered = ShowNotes.render("Published 2026-06-10, version 1.2.3, at 99:99.")
        assertTrue(rendered.links.isEmpty())
    }

    @Test
    fun `multiple timestamps keep document order`() {
        val rendered = ShowNotes.render("First 0:30 then 12:34 then 1:00:00")
        assertEquals(listOf(30_000L, 754_000L, 3_600_000L), rendered.links.map { it.positionMs })
    }

    @Test
    fun `plain text without html survives`() {
        val rendered = ShowNotes.render("no markup at 2:00 here")
        assertEquals(120_000L, rendered.links.single().positionMs)
    }
}
```

- [ ] **Step 2: Run to verify failure.**

- [ ] **Step 3: Implement**

```kotlin
package com.orator.feature.podcasts.data

import androidx.core.text.HtmlCompat

/**
 * Show notes for the placeholder UI: feed HTML → plain text + tappable timestamp spans.
 * Timestamps refer to the ORIGINAL (unclipped) timeline; callers subtract clipIntroMs.
 */
object ShowNotes {

    data class TimestampLink(val startIndex: Int, val endIndex: Int, val positionMs: Long)
    data class Rendered(val text: String, val links: List<TimestampLink>)

    // hh:mm:ss or m:ss / mm:ss; minutes and seconds must be valid base-60 fields.
    private val TIMESTAMP = Regex("""(?<![\d:.\-])(?:(\d{1,2}):)?([0-5]?\d):([0-5]\d)(?![\d:.\-])""")

    fun render(html: String): Rendered {
        val text = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT)
            .toString().trim()
        val links = TIMESTAMP.findAll(text).map { match ->
            val hours = match.groupValues[1].toLongOrNull() ?: 0L
            val minutes = match.groupValues[2].toLong()
            val seconds = match.groupValues[3].toLong()
            TimestampLink(
                startIndex = match.range.first,
                endIndex = match.range.last + 1,
                positionMs = ((hours * 60 + minutes) * 60 + seconds) * 1000,
            )
        }.toList()
        return Rendered(text, links)
    }
}
```

NOTE: the regex guards `(?<![\d:.\-])`/`(?![\d:.\-])` reject `2026-06-10`, `1.2.3`, and
`99:99` (the 60-based field classes do the latter). If `12:34 PM` style wall-clock times in
notes produce false links, accept it — placeholder UI, seeks are clamped, no harm.

- [ ] **Step 4: Run tests** — 5 PASS.

- [ ] **Step 5: Commit**

```bash
git add feature/podcasts/src
git commit -m "feat: show-notes renderer with tappable timestamp extraction"
```

### Task 16: ViewModels

**Files:**
- Create: `feature/podcasts/src/main/java/com/orator/feature/podcasts/PodcastListViewModel.kt`, `PodcastDetailViewModel.kt`, `EpisodeDetailViewModel.kt`

ViewModels are thin glue over already-tested units (repository, queue builder, ShowNotes,
downloader, prefs); they get no dedicated unit tests — same call as P2/P3 placeholder VMs.

- [ ] **Step 1: `PodcastListViewModel.kt`**

```kotlin
package com.orator.feature.podcasts

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orator.core.database.PodcastEntity
import com.orator.core.playback.PlaybackConnection
import com.orator.core.playback.PlaybackUiState
import com.orator.feature.podcasts.data.PodcastRepository
import com.orator.feature.podcasts.data.PodcastsFolderStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PodcastListViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: PodcastRepository,
    private val folderStore: PodcastsFolderStore,
    playbackConnection: PlaybackConnection,
) : ViewModel() {

    val podcasts: StateFlow<List<PodcastEntity>> = repository.podcasts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val hasFolder: StateFlow<Boolean> = folderStore.treeUri.map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val busy: StateFlow<String?> = repository.busy

    val playback: StateFlow<PlaybackUiState> = playbackConnection.state

    /** One-shot result line ("Imported 42, 1 failed"); cleared on the next action. */
    private val _lastResult = MutableStateFlow<String?>(null)
    val lastResult: StateFlow<String?> = _lastResult.asStateFlow()

    fun onFolderPicked(treeUri: String) {
        viewModelScope.launch { folderStore.setTreeUri(treeUri) }
    }

    fun onAddFeed(url: String) {
        if (url.isBlank()) return
        viewModelScope.launch {
            _lastResult.value = null
            _lastResult.value = repository.subscribe(url.trim()).fold(
                onSuccess = { "Subscribed" },
                onFailure = { "Failed: ${it.message}" },
            )
        }
    }

    fun onImportOpml(uri: Uri) {
        viewModelScope.launch {
            _lastResult.value = null
            val xml = runCatching {
                context.contentResolver.openInputStream(uri)?.use {
                    it.readBytes().decodeToString()
                }
            }.getOrNull()
            if (xml == null) {
                _lastResult.value = "Could not read OPML"
                return@launch
            }
            val summary = repository.importOpml(xml)
            _lastResult.value = "Imported ${summary.refreshed}, ${summary.failed} failed"
        }
    }

    fun onRefreshAll() {
        viewModelScope.launch {
            _lastResult.value = null
            val summary = repository.refreshAll()
            _lastResult.value = "Refreshed ${summary.refreshed}, ${summary.failed} failed"
        }
    }
}
```

- [ ] **Step 2: `PodcastDetailViewModel.kt`**

```kotlin
package com.orator.feature.podcasts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orator.core.database.EpisodeDao
import com.orator.core.database.EpisodeEntity
import com.orator.core.database.PodcastDao
import com.orator.core.database.PodcastEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PodcastDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val podcastDao: PodcastDao,
    episodeDao: EpisodeDao,
) : ViewModel() {

    private val podcastId: String = checkNotNull(savedStateHandle["podcastId"])

    val podcast: StateFlow<PodcastEntity?> = podcastDao.observeById(podcastId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val episodes: StateFlow<List<EpisodeEntity>> = episodeDao.observeForPodcast(podcastId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Steps are whole seconds in the UI; stored as ms. Applies on the NEXT play (placeholder UI). */
    fun onClipChange(introMs: Long, outroMs: Long) {
        viewModelScope.launch {
            podcastDao.updateClips(podcastId, introMs.coerceAtLeast(0), outroMs.coerceAtLeast(0))
        }
    }

    fun onSpeedOverride(speed: Float?) {
        viewModelScope.launch {
            podcastDao.updateSpeedOverride(
                podcastId,
                speed?.let { (it.coerceIn(0.5f, 3.0f) * 100).toInt() / 100f },
            )
        }
    }
}
```

- [ ] **Step 3: `EpisodeDetailViewModel.kt`**

```kotlin
package com.orator.feature.podcasts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orator.core.database.EpisodeDao
import com.orator.core.database.EpisodeEntity
import com.orator.core.database.PodcastDao
import com.orator.core.database.PodcastEntity
import com.orator.core.model.MediaType
import com.orator.core.playback.PlaybackConnection
import com.orator.core.playback.PlaybackUiState
import com.orator.core.playback.PlayerPreferences
import com.orator.core.playback.SmartRewind
import com.orator.feature.podcasts.data.EpisodeDownloader
import com.orator.feature.podcasts.data.EpisodeQueueBuilder
import com.orator.feature.podcasts.data.PodcastMediaId
import com.orator.feature.podcasts.data.ShowNotes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EpisodeDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val episodeDao: EpisodeDao,
    private val podcastDao: PodcastDao,
    private val playbackConnection: PlaybackConnection,
    private val playerPreferences: PlayerPreferences,
    private val downloader: EpisodeDownloader,
) : ViewModel() {

    private val episodeId: String = checkNotNull(savedStateHandle["episodeId"])

    val episode: StateFlow<EpisodeEntity?> = episodeDao.observeById(episodeId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val notes: StateFlow<ShowNotes.Rendered?> = episodeDao.observeById(episodeId)
        .map { e -> e?.showNotesHtml?.let(ShowNotes::render) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val playback: StateFlow<PlaybackUiState> = playbackConnection.state

    val downloadProgress: StateFlow<Float?> = downloader.progress
        .map { it[episodeId] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun isThisEpisode(state: PlaybackUiState): Boolean =
        state.mediaId?.let(PodcastMediaId::parse) == episodeId

    fun onPlayResume() {
        viewModelScope.launch {
            val e = episodeDao.getById(episodeId) ?: return@launch
            // Cold-start smart rewind, podcast flavor — same tiers as BookDetailViewModel;
            // warm/cold can't stack because the service resets on queue load (P3 invariant).
            val prefs = playerPreferences.flow.first()
            val rewind = if (prefs.smartRewind[MediaType.PODCAST] == true && e.lastPlayedAtMs > 0) {
                SmartRewind.rewindMs(System.currentTimeMillis() - e.lastPlayedAtMs)
            } else {
                0
            }
            playFrom((e.positionMs - rewind).coerceAtLeast(0))
        }
    }

    fun onPlayPause() = playbackConnection.playPause()

    /** [rawPositionMs] is original-timeline (show-note links); stored positions are clip-relative. */
    fun onTimestampTap(rawPositionMs: Long) {
        viewModelScope.launch {
            val podcast = podcastFor() ?: return@launch
            val clipRelative = (rawPositionMs - podcast.clipIntroMs).coerceAtLeast(0)
            if (isThisEpisode(playback.value)) {
                playbackConnection.seekWithinCurrent(clipRelative)
            } else {
                playFrom(clipRelative)
            }
        }
    }

    fun onDownload() {
        viewModelScope.launch { downloader.download(episodeId) }
    }

    fun onCancelDownload() = downloader.cancel(episodeId)

    fun onDeleteDownload() {
        viewModelScope.launch { downloader.deleteDownload(episodeId) }
    }

    private suspend fun podcastFor(): PodcastEntity? =
        episodeDao.getById(episodeId)?.let { podcastDao.getById(it.podcastId) }

    private suspend fun playFrom(clipRelativeMs: Long) {
        val e = episodeDao.getById(episodeId) ?: return
        val podcast = podcastDao.getById(e.podcastId) ?: return
        playbackConnection.play(EpisodeQueueBuilder.build(podcast, e, clipRelativeMs))
    }
}
```

- [ ] **Step 4: Compile** — `./gradlew :feature:podcasts:assembleDebug` — BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add feature/podcasts/src
git commit -m "feat: podcast list/detail/episode view models"
```

### Task 17: Screens + navigation wiring

**Files:**
- Create: `PodcastListScreen.kt`, `PodcastDetailScreen.kt`, `EpisodeDetailScreen.kt` (in `feature/podcasts/src/main/java/com/orator/feature/podcasts/`)
- Modify: `PodcastsFeatureEntry.kt` (replace stub)
- Modify: `feature/audiobooks/src/main/java/com/orator/feature/audiobooks/AudiobookListScreen.kt` (+Podcasts button)
- Modify: `feature/audiobooks/src/main/java/com/orator/feature/audiobooks/AudiobooksFeatureEntry.kt` (wire the callback)

All screens follow the placeholder style: `Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally)`, menus centered (user preference), `collectAsStateWithLifecycle`.

- [ ] **Step 1: `PodcastListScreen.kt`**

```kotlin
package com.orator.feature.podcasts

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orator.core.database.PodcastEntity

@Composable
fun PodcastListScreen(
    onPodcastClick: (String) -> Unit,
    onOpenPlayer: () -> Unit,
    viewModel: PodcastListViewModel = hiltViewModel(),
) {
    val podcasts by viewModel.podcasts.collectAsStateWithLifecycle()
    val hasFolder by viewModel.hasFolder.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val lastResult by viewModel.lastResult.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var feedUrl by remember { mutableStateOf("") }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            viewModel.onFolderPicked(uri.toString())
        }
    }
    val pickOpml = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.onImportOpml(uri)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Podcasts")
        Row(horizontalArrangement = Arrangement.Center) {
            Button(onClick = { pickFolder.launch(null) }) {
                Text(if (hasFolder) "Change folder" else "Choose podcast folder")
            }
            OutlinedButton(onClick = { pickOpml.launch(arrayOf("*/*")) }) { Text("Import OPML") }
            OutlinedButton(onClick = viewModel::onRefreshAll) { Text("Refresh all") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = feedUrl,
                onValueChange = { feedUrl = it },
                label = { Text("Feed URL") },
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { viewModel.onAddFeed(feedUrl); feedUrl = "" }) { Text("Add") }
        }
        busy?.let { Text(it) }
        lastResult?.let { Text(it) }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(podcasts, key = PodcastEntity::id) { podcast ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPodcastClick(podcast.id) }
                        .padding(vertical = 12.dp),
                ) {
                    Text(podcast.title)
                    Text(podcast.author ?: "Unknown author")
                }
            }
        }
        if (playback.title.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenPlayer)
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = (if (playback.isPlaying) "▶ " else "⏸ ") + playback.title,
                    maxLines = 1,
                )
            }
        }
    }
}
```

- [ ] **Step 2: `PodcastDetailScreen.kt`**

```kotlin
package com.orator.feature.podcasts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orator.core.database.EpisodeEntity

@Composable
fun PodcastDetailScreen(
    onEpisodeClick: (String) -> Unit,
    viewModel: PodcastDetailViewModel = hiltViewModel(),
) {
    val podcast by viewModel.podcast.collectAsStateWithLifecycle()
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val p = podcast ?: return

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(p.title)
        Text(p.author ?: "")

        // Per-show settings: clip steppers (±15 s) and speed override (±0.1, Clear)
        SettingRow("Skip intro: ${p.clipIntroMs / 1000}s",
            onMinus = { viewModel.onClipChange(p.clipIntroMs - 15_000, p.clipOutroMs) },
            onPlus = { viewModel.onClipChange(p.clipIntroMs + 15_000, p.clipOutroMs) })
        SettingRow("Skip outro: ${p.clipOutroMs / 1000}s",
            onMinus = { viewModel.onClipChange(p.clipIntroMs, p.clipOutroMs - 15_000) },
            onPlus = { viewModel.onClipChange(p.clipIntroMs, p.clipOutroMs + 15_000) })
        SettingRow(
            "Speed: ${p.speedOverride?.let { "%.2f×".format(it) } ?: "default"}",
            onMinus = { viewModel.onSpeedOverride((p.speedOverride ?: 1.0f) - 0.1f) },
            onPlus = { viewModel.onSpeedOverride((p.speedOverride ?: 1.0f) + 0.1f) },
            extra = { OutlinedButton(onClick = { viewModel.onSpeedOverride(null) }) { Text("Clear") } },
        )

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(episodes, key = EpisodeEntity::id) { episode ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEpisodeClick(episode.id) }
                        .padding(vertical = 12.dp),
                ) {
                    Text(episode.title)
                    Text(
                        listOfNotNull(
                            if (episode.audioPath != null) "downloaded" else null,
                            if (episode.positionMs > 0) "in progress" else null,
                        ).joinToString(" · ").ifEmpty { " " },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingRow(
    label: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onMinus) { Text("−") }
        OutlinedButton(onClick = onPlus) { Text("+") }
        extra()
    }
}
```

- [ ] **Step 3: `EpisodeDetailScreen.kt`**

```kotlin
package com.orator.feature.podcasts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun EpisodeDetailScreen(viewModel: EpisodeDetailViewModel = hiltViewModel()) {
    val episode by viewModel.episode.collectAsStateWithLifecycle()
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val e = episode ?: return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(e.title, style = MaterialTheme.typography.titleMedium)
        Text(if (e.audioPath != null) "Downloaded" else "Streams from feed")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val active = viewModel.isThisEpisode(playback)
            Button(onClick = {
                if (active) viewModel.onPlayPause() else viewModel.onPlayResume()
            }) {
                Text(if (active && playback.isPlaying) "Pause" else "Play")
            }
            when {
                downloadProgress != null -> OutlinedButton(onClick = viewModel::onCancelDownload) {
                    val pct = downloadProgress?.takeIf { it >= 0 }?.let { " ${(it * 100).toInt()}%" } ?: ""
                    Text("Cancel$pct")
                }
                e.audioPath != null -> OutlinedButton(onClick = viewModel::onDeleteDownload) {
                    Text("Delete download")
                }
                else -> OutlinedButton(onClick = viewModel::onDownload) { Text("Download") }
            }
        }

        notes?.let { rendered ->
            val annotated = buildAnnotatedString {
                append(rendered.text)
                rendered.links.forEachIndexed { index, link ->
                    addStyle(
                        SpanStyle(
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline,
                        ),
                        link.startIndex, link.endIndex,
                    )
                    addStringAnnotation("timestamp", "$index", link.startIndex, link.endIndex)
                }
            }
            ClickableText(
                text = annotated,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
            ) { offset ->
                annotated.getStringAnnotations("timestamp", offset, offset).firstOrNull()
                    ?.let { viewModel.onTimestampTap(rendered.links[it.item.toInt()].positionMs) }
            }
        }
    }
}
```

(`ClickableText` is deprecated in newer Compose but present and fine in BOM 2024.12.01 —
placeholder UI; replace during the UI phase.)

- [ ] **Step 4: Replace the stub in `PodcastsFeatureEntry.kt`**

```kotlin
package com.orator.feature.podcasts

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.orator.core.navigation.CommonRoutes
import com.orator.core.navigation.FeatureEntry
import javax.inject.Inject

class PodcastsFeatureEntry @Inject constructor() : FeatureEntry {

    override val route: String = PodcastsRoute

    override fun register(navGraphBuilder: NavGraphBuilder, navController: NavController) {
        navGraphBuilder.composable(PodcastsRoute) {
            PodcastListScreen(
                onPodcastClick = { id -> navController.navigate(podcastDetailRoute(id)) },
                onOpenPlayer = { navController.navigate(CommonRoutes.Player) },
            )
        }
        navGraphBuilder.composable(PodcastDetailRoutePattern) {
            PodcastDetailScreen(
                onEpisodeClick = { id -> navController.navigate(episodeDetailRoute(id)) },
            )
        }
        navGraphBuilder.composable(EpisodeDetailRoutePattern) {
            EpisodeDetailScreen()
        }
    }
}
```

- [ ] **Step 5: Add the Podcasts button to the start screen**

Read `feature/audiobooks/src/main/java/com/orator/feature/audiobooks/AudiobookListScreen.kt`,
add an `onOpenPodcasts: () -> Unit` parameter, and extend the centered top menu row:

```kotlin
        Row(horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = onOpenPodcasts) { Text("Podcasts") }
            TextButton(onClick = onOpenHistory) { Text("History") }
            TextButton(onClick = onOpenSettings) { Text("Settings") }
        }
```

In `AudiobooksFeatureEntry.kt` pass `onOpenPodcasts = { navController.navigate(CommonRoutes.Podcasts) }`.
The audiobooks feature references only `core:navigation` — no feature-to-feature dependency.

- [ ] **Step 6: Build + full test suite**

Run: `./gradlew assembleDebug test`
Expected: BUILD SUCCESSFUL, all module tests PASS.

- [ ] **Step 7: Commit**

```bash
git add feature/podcasts feature/audiobooks
git commit -m "feat: podcast screens, navigation, and Podcasts entry on start screen"
```

---

## Chunk 6: verification + close-out

### Task 18: Device verification + docs

- [ ] **Step 1: Full test suite + install**

```bash
./gradlew test          # all modules
./gradlew installDebug  # wireless adb; adb lives at ~/Android/Sdk/platform-tools/adb
```

Report build times to the user (standing instruction since the RAM upgrade).

- [ ] **Step 2: Manual device checklist** (user drives the Pixel 7a; wait for their results)

Before starting: `~/Android/Sdk/platform-tools/adb push local/podcasts.opml /sdcard/Download/podcasts.opml`
(private file: it stays on the user's device; never in git)

1. Open Podcasts from the start screen; pick/create a base folder (e.g. `Orator`).
2. Paste one feed URL → show appears with episodes, newest first.
3. Import the OPML from Downloads → status line counts up; final "Imported N, M failed" with N ≥ 40.
4. `adb shell ls "/sdcard/Orator/Podcasts/"` shows readable show dirs; one contains `show.json`, `cover.jpg`, `episodes/` with date-prefixed dirs (≤20).
5. Open an episode → Play (streams); notification + player screen work; speed/trim/boost/sleep timer all behave as in P3.
6. Pause, wait >30 s, Play again → smart rewind steps back.
7. Download an episode → progress %, then "Downloaded"; `audio.mp3` (or `.m4a`) in the episode dir. Airplane mode → still plays.
8. On a show: set Skip intro 30 s / Skip outro 30 s → replay an episode with known duration → starts 30 s in; slider range shrinks by 60 s total.
9. Tap a timestamp in show notes → seeks there (minus the intro clip).
10. Refresh all → completes in well under a minute (304s), "Refreshed N, 0 failed"; no duplicate episodes; the in-progress episode kept its position.
11. Set a per-show speed override → applies on play; other shows unaffected.

- [ ] **Step 3: Tick plan checkboxes, record deviations** in this file under an
"Execution notes" section (same convention as the P2/P3 plans).

- [ ] **Step 4: Update `docs/architecture.md`** — split roadmap row 4 into 4a (this work, ✅ with
date once verified) and 4b (discovery + transcripts, next); update the Status line.

- [ ] **Step 5: Commit + push + PR**

```bash
git add -A docs
git commit -m "docs: tick executed P4a plan, update roadmap"
git push -u origin phase-4a-podcasts
gh pr create --title "Phase 4a: podcasts — subscribe, cache, play" --body "..."
```

---

## Execution notes (deviations from the written plan)

(filled in during execution)
