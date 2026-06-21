# Phase 5a — Playlists Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Multiple user-nameable playlists mixing podcast episodes and whole audiobooks, that
drain as they play (current = top), with drag-reorder / swipe-remove / tap-to-top, played by
orchestrating the existing single-entity playback core from above.

**Architecture:** A new `feature:playlists` module owns playlist data UI + a
`PlaylistPlaybackController` that loads the **top** item via per-type `PlayRequestFactory`
contributions and, on each end-of-queue (`isEnded`) event, pops the top row and plays the next.
Display fields come from per-type `PlaylistItemResolver` contributions. `feature:playlists` never
imports `feature:audiobooks`/`feature:podcasts`; they meet only at `core` seams (`MediaRef`,
`PlaylistItemResolver`, `PlayRequestFactory`, `CommonRoutes`). `PlayRequest` stays single-entity
and untouched.

**Tech Stack:** Kotlin 2.1.0, Jetpack Compose, Hilt (`@IntoSet` multibindings), Room (schema
v5→v6, destructive fallback, `exportSchema=false`), Media3 via `PlaybackConnection`, DataStore
Preferences, Robolectric for DAO tests, JUnit4 + kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-06-20-playlists-5a-design.md`

**Standing project rules (apply to every task):**
- Build only via `./gradlew` wrapper; **report build times**.
- Per-chunk gate: `./gradlew test lint assembleDebug` must pass before the chunk is done.
- Commit with explicit paths only — **never `git add -A`/`git add .`** (untracked private files
  exist). End commit messages with `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.
- Branch: `phase-5a-playlists` (already created).

---

## File Structure

**`core:model`** (leaf; everyone depends on it)
- Create `core/model/src/main/java/com/orator/core/model/MediaRef.kt` — `MediaRef`,
  `PlaylistItemContent`, `PlaylistItemResolver` (display seam).

**`core:database`** (depends on core:model)
- Create `PlaylistEntity.kt`, `PlaylistItemEntity.kt`, `PlaylistDao.kt`.
- Modify `OratorDatabase.kt` — register entities + DAO, bump `version = 6`.
- Test `PlaylistDaoTest.kt` (Robolectric in-memory).

**`core:playback`** (depends on core:model)
- Create `PlayRequestFactory.kt` (playback seam).
- Modify `PlaybackUiState.kt` (+`isEnded`), `PlaybackConnection.kt` (derive `isEnded`).

**`feature:playlists`** (NEW module; depends on core:{model,database,playback,designsystem,navigation})
- `build.gradle.kts`, `src/main/AndroidManifest.xml` (if required by AGP).
- `data/PlaylistOrdering.kt` (pure), `data/MediaRefMatch.kt` (pure),
  `data/ActivePlaylistStore.kt` (DataStore), `data/PlaylistRepository.kt`,
  `playback/PlaylistPlayback.kt` (+ `ConnectionPlaylistPlayback`),
  `playback/PlaylistPlaybackController.kt`.
- UI: `PlaylistsScreen.kt`+VM, `PlaylistDetailScreen.kt`+VM, `AddToPlaylistSheet.kt`+VM,
  `PlaylistRoutes.kt`, `PlaylistsFeatureEntry.kt`, `PlaylistsFeatureModule.kt`.
- Tests under `src/test/...`.

**`core:navigation`**
- Modify `CommonRoutes.kt` — `Playlists`, `PlaylistDetail` + `playlistDetail(id)`,
  `AddToPlaylist` + `addToPlaylist(type, id)`.

**`feature:audiobooks`** (contributes; no dependency on feature:playlists)
- Create `data/AudiobookPlayRequestFactory.kt`, `data/AudiobookPlaylistItemResolver.kt`.
- Modify `AudiobooksFeatureModule.kt` (bind both `@IntoSet`), book list/detail UI (＋ add ⋮).

**`feature:podcasts`** (contributes)
- Create `data/EpisodePlayRequestFactory.kt`, `data/EpisodePlaylistItemResolver.kt`.
- Modify `PodcastsFeatureModule.kt` (bind both `@IntoSet`), episode row UI (＋ add ⋮).

**`app`**
- Modify `app/build.gradle.kts` (+`feature:playlists` dependency), `OratorShell.kt` (4th tab).

---

## Chunk 1: Data & seams foundation (`core:model`, `core:database`, `core:playback`)

Outcome: schema v6 with a tested `PlaylistDao`, the two seam interfaces, and `isEnded` on the
playback state. No feature behavior yet, but everything below builds on these.

### Task 1.1: `MediaRef` + display seam (`core:model`)

**Files:**
- Create: `core/model/src/main/java/com/orator/core/model/MediaRef.kt`

- [ ] **Step 1: Write the file** (no test — plain data + interface, exercised by later tasks)

```kotlin
package com.orator.core.model

/** A type-tagged pointer to a playable entity. [id] is the entity's own String PK
 *  (episode.id or book.id — both are already Strings). */
data class MediaRef(val type: MediaType, val id: String)

/** Display fields for one playlist row. Plain data — no Android, no playback. */
data class PlaylistItemContent(
    val title: String,
    val subtitle: String,
    val artworkUri: String?,
    val durationMs: Long,
)

/**
 * Resolves a [MediaRef] to its display fields. Each feature contributes one per media type via
 * Hilt @IntoSet (mirrors PlaybackEventListener). Returns null when the underlying entity is gone
 * (e.g. podcast unsubscribed, book removed) — the playlist then prunes that row.
 */
interface PlaylistItemResolver {
    val mediaType: MediaType
    suspend fun resolve(ref: MediaRef): PlaylistItemContent?
}
```

- [ ] **Step 2: Compile** — Run `./gradlew :core:model:compileDebugKotlin`. Expected: BUILD
  SUCCESSFUL. Report the build time.

- [ ] **Step 3: Commit**

```bash
git add core/model/src/main/java/com/orator/core/model/MediaRef.kt
git commit -m "feat(model): MediaRef + PlaylistItemResolver display seam

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task 1.2: Playlist entities (`core:database`)

**Files:**
- Create: `core/database/src/main/java/com/orator/core/database/PlaylistEntity.kt`
- Create: `core/database/src/main/java/com/orator/core/database/PlaylistItemEntity.kt`

> Note: Room persists enums natively as their constant name (as `BookEntity.sourceKind:
> SourceKind` already does — there is no TypeConverter in this module). `mediaType: MediaType`
> needs none.

- [ ] **Step 1: Write `PlaylistEntity.kt`**

```kotlin
package com.orator.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A user-named playlist. Items live in [PlaylistItemEntity]; this row carries only identity. */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAtMs: Long,
)
```

- [ ] **Step 2: Write `PlaylistItemEntity.kt`**

```kotlin
package com.orator.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.orator.core.model.MediaType

/**
 * One pending entry in a playlist — a *pointer* (mediaType + mediaId) to an entity, never a copy.
 * Resume position lives on the target entity (Phase 5a "queue drains" model), so this row carries
 * no progress. [position] orders items; the top (currently-playing) item has the smallest value.
 * The unique index dedupes re-adds of the same entity to the same playlist.
 */
@Entity(
    tableName = "playlist_items",
    foreignKeys = [ForeignKey(
        entity = PlaylistEntity::class,
        parentColumns = ["id"],
        childColumns = ["playlistId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index("playlistId"),
        Index(value = ["playlistId", "mediaType", "mediaId"], unique = true),
    ],
)
data class PlaylistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val mediaType: MediaType,   // PODCAST = episode, AUDIOBOOK = whole book
    val mediaId: String,        // episode.id or book.id (both String PKs)
    val position: Long,
)
```

- [ ] **Step 3: Verify core:model is on core:database's classpath.** Run
  `./gradlew :core:database:dependencies --configuration debugCompileClasspath | grep core:model`.
  If absent, add `implementation(project(":core:model"))` to `core/database/build.gradle.kts`
  (it is needed for `MediaType`). Report build time of any change.

- [ ] **Step 4: Compile** — Run `./gradlew :core:database:compileDebugKotlin`. Expected: SUCCESS
  (DAO + DB registration come next; entities alone compile). Report build time.

- [ ] **Step 5: Commit**

```bash
git add core/database/src/main/java/com/orator/core/database/PlaylistEntity.kt \
        core/database/src/main/java/com/orator/core/database/PlaylistItemEntity.kt
git commit -m "feat(db): playlist + playlist_items entities

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task 1.3: `PlaylistDao` + register on DB (v6)

**Files:**
- Create: `core/database/src/main/java/com/orator/core/database/PlaylistDao.kt`
- Modify: `core/database/src/main/java/com/orator/core/database/OratorDatabase.kt`
- Test: `core/database/src/test/java/com/orator/core/database/PlaylistDaoTest.kt`

- [ ] **Step 1: Write the failing test** (Robolectric in-memory — mirror `EpisodeDaoTest`)

```kotlin
package com.orator.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.orator.core.model.MediaType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaylistDaoTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        OratorDatabase::class.java,
    ).allowMainThreadQueries().build()

    private val dao = db.playlistDao()

    @After fun tearDown() = db.close()

    private suspend fun newPlaylist(name: String) =
        dao.insertPlaylist(PlaylistEntity(name = name, createdAtMs = 0))

    private fun item(playlistId: Long, mediaId: String, pos: Long, type: MediaType = MediaType.PODCAST) =
        PlaylistItemEntity(playlistId = playlistId, mediaType = type, mediaId = mediaId, position = pos)

    @Test fun `items come back ordered by position`() = runBlocking {
        val p = newPlaylist("Mix")
        dao.insertItem(item(p, "b", pos = 20))
        dao.insertItem(item(p, "a", pos = 10))
        dao.insertItem(item(p, "c", pos = 30))

        assertEquals(listOf("a", "b", "c"), dao.observeItems(p).first().map { it.mediaId })
    }

    @Test fun `duplicate add of same ref is ignored`() = runBlocking {
        val p = newPlaylist("Mix")
        dao.insertItem(item(p, "a", pos = 10))
        dao.insertItem(item(p, "a", pos = 20)) // same (playlist, type, mediaId)

        assertEquals(1, dao.observeItems(p).first().size)
    }

    @Test fun `deleting a playlist cascades its items`() = runBlocking {
        val p = newPlaylist("Mix")
        dao.insertItem(item(p, "a", pos = 10))
        dao.deletePlaylist(p)

        assertEquals(0, dao.observeItems(p).first().size)
        assertNull(dao.getItem(/* itemId that no longer exists */ 1))
    }

    @Test fun `updatePositions rewrites order`() = runBlocking {
        val p = newPlaylist("Mix")
        val a = dao.insertItem(item(p, "a", pos = 10))
        val b = dao.insertItem(item(p, "b", pos = 20))

        dao.updatePositions(listOf(PlaylistItemPosition(b, 10), PlaylistItemPosition(a, 20)))

        assertEquals(listOf("b", "a"), dao.observeItems(p).first().map { it.mediaId })
    }

    @Test fun `deleteItem removes a single row`() = runBlocking {
        val p = newPlaylist("Mix")
        val a = dao.insertItem(item(p, "a", pos = 10))
        dao.insertItem(item(p, "b", pos = 20))

        dao.deleteItem(a)

        assertEquals(listOf("b"), dao.observeItems(p).first().map { it.mediaId })
    }
}
```

- [ ] **Step 2: Run the test, expect failure** — Run
  `./gradlew :core:database:testDebugUnitTest --tests "*PlaylistDaoTest"`. Expected: FAIL
  (`playlistDao()` / DAO symbols unresolved). Report build time.

- [ ] **Step 3: Write `PlaylistDao.kt`**

```kotlin
package com.orator.core.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Lightweight projection for a transactional reorder write. */
data class PlaylistItemPosition(val id: Long, val position: Long)

/** Playlist count summary for the list screen. */
data class PlaylistSummary(val id: Long, val name: String, val itemCount: Int)

@Dao
interface PlaylistDao {

    @Insert
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun renamePlaylist(id: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    @Query(
        """
        SELECT p.id AS id, p.name AS name,
               (SELECT COUNT(*) FROM playlist_items i WHERE i.playlistId = p.id) AS itemCount
        FROM playlists p ORDER BY p.createdAtMs DESC, p.id DESC
        """,
    )
    fun observePlaylists(): Flow<List<PlaylistSummary>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getPlaylist(id: Long): PlaylistEntity?

    /** OnConflict IGNORE realizes the unique (playlist, type, mediaId) dedupe. Returns -1 on skip. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItem(item: PlaylistItemEntity): Long

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY position ASC")
    fun observeItems(playlistId: Long): Flow<List<PlaylistItemEntity>>

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun getItems(playlistId: Long): List<PlaylistItemEntity>

    /** Top (currently-playing) item — smallest position. Null when the playlist is empty. */
    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY position ASC LIMIT 1")
    suspend fun getTopItem(playlistId: Long): PlaylistItemEntity?

    @Query("SELECT * FROM playlist_items WHERE id = :itemId")
    suspend fun getItem(itemId: Long): PlaylistItemEntity?

    @Query("DELETE FROM playlist_items WHERE id = :itemId")
    suspend fun deleteItem(itemId: Long)

    @Query("SELECT MAX(position) FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun maxPosition(playlistId: Long): Long?

    @Update(entity = PlaylistItemEntity::class)
    suspend fun updatePositions(positions: List<PlaylistItemPosition>)
}
```

- [ ] **Step 4: Register on the database** — edit `OratorDatabase.kt`:
  add `PlaylistEntity::class, PlaylistItemEntity::class` to `entities = [...]`, bump
  `version = 5` → `version = 6`, and add `abstract fun playlistDao(): PlaylistDao`.

- [ ] **Step 5: Run the test, expect pass** — Run
  `./gradlew :core:database:testDebugUnitTest --tests "*PlaylistDaoTest"`. Expected: PASS (5
  tests). Report build time.

- [ ] **Step 6: Commit**

```bash
git add core/database/src/main/java/com/orator/core/database/PlaylistDao.kt \
        core/database/src/main/java/com/orator/core/database/OratorDatabase.kt \
        core/database/src/test/java/com/orator/core/database/PlaylistDaoTest.kt
git commit -m "feat(db): PlaylistDao + register entities (schema v6)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task 1.4: Playback seam + `isEnded` (`core:playback`)

**Files:**
- Create: `core/playback/src/main/java/com/orator/core/playback/PlayRequestFactory.kt`
- Modify: `core/playback/src/main/java/com/orator/core/playback/PlaybackUiState.kt`
- Modify: `core/playback/src/main/java/com/orator/core/playback/PlaybackConnection.kt`

- [ ] **Step 1: Write `PlayRequestFactory.kt`**

```kotlin
package com.orator.core.playback

import com.orator.core.model.MediaRef
import com.orator.core.model.MediaType

/**
 * Builds a single-entity [PlayRequest] for one [MediaRef], reading the target entity and its
 * saved resume position. Each feature contributes one per media type via Hilt @IntoSet
 * (mirrors PlaybackEventListener). Returns null when the ref is no longer resolvable.
 */
interface PlayRequestFactory {
    val mediaType: MediaType
    suspend fun create(ref: MediaRef): PlayRequest?
}
```

- [ ] **Step 2: Add `isEnded` to `PlaybackUiState`** — add `val isEnded: Boolean = false,`
  (e.g. right after `isPlaying`). Default keeps every existing construction valid.

- [ ] **Step 3: Derive it in `PlaybackConnection.updateState()`** — in the `_state.value =
  PlaybackUiState(...)` block, add `isEnded = c.playbackState == Player.STATE_ENDED,`.
  (`Player` is already imported.)

- [ ] **Step 4: Compile** — Run `./gradlew :core:playback:compileDebugKotlin`. Expected:
  SUCCESS. Report build time.

- [ ] **Step 5: Commit**

```bash
git add core/playback/src/main/java/com/orator/core/playback/PlayRequestFactory.kt \
        core/playback/src/main/java/com/orator/core/playback/PlaybackUiState.kt \
        core/playback/src/main/java/com/orator/core/playback/PlaybackConnection.kt
git commit -m "feat(playback): PlayRequestFactory seam + isEnded state

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task 1.5: Chunk 1 gate

- [ ] **Step 1:** Run `./gradlew test lint assembleDebug`. Expected: BUILD SUCCESSFUL, all unit
  tests pass. Report build time. Fix any failure before continuing.

---

## Chunk 2: `feature:playlists` core logic (module, ordering, match, store, repo, controller)

Outcome: a new module with tested pure logic and a tested `PlaylistPlaybackController` driving an
abstract `PlaylistPlayback` seam. No UI and no cross-feature contributions yet (controller has an
empty factory/resolver set at runtime until Chunk 3, which is fine — it just can't resolve refs).

### Task 2.1: Scaffold the `feature:playlists` module

**Files:**
- Create: `feature/playlists/build.gradle.kts`
- Create: `feature/playlists/src/main/AndroidManifest.xml` (only if AGP requires one)
- Modify: `settings.gradle` (add `include(":feature:playlists")`)
- Modify: `app/build.gradle.kts` (add the module dependency)

- [ ] **Step 1: Read a sibling** — read `feature/podcasts/build.gradle.kts` for the exact plugin
  aliases and test deps. Our module needs no `buildConfig`/local.properties block.

- [ ] **Step 2: Write `feature/playlists/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.orator.feature.playlists"
    compileSdk = 35

    defaultConfig { minSdk = 26 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:navigation"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:playback"))
    implementation(project(":core:database"))

    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

- [ ] **Step 3: Register the module** — add `include(":feature:playlists")` to
  `settings.gradle.kts` (after `:feature:podcasts`).

- [ ] **Step 4: Wire it into the app** — in `app/build.gradle.kts`, alongside the other
  `implementation(project(":feature:..."))` lines, add `implementation(project(":feature:playlists"))`.
  (This is what pulls the module's Hilt multibindings — `FeatureEntry`, factories, resolvers —
  into the app component. `app` still references no feature *type*.)

- [ ] **Step 5: Add a manifest only if the build asks for one** — run
  `./gradlew :feature:playlists:compileDebugKotlin`. If AGP complains about a missing manifest /
  package, create `feature/playlists/src/main/AndroidManifest.xml` with:
  `<manifest xmlns:android="http://schemas.android.com/apk/res/android" />`.
  Re-run until SUCCESS. Report build time.

- [ ] **Step 6: Commit**

```bash
git add feature/playlists/build.gradle.kts settings.gradle.kts app/build.gradle.kts
# include the manifest in the add only if you created one:
# git add feature/playlists/src/main/AndroidManifest.xml
git commit -m "feat(playlists): scaffold feature:playlists module

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task 2.2: `PlaylistOrdering` (pure)

**Files:**
- Create: `feature/playlists/src/main/java/com/orator/feature/playlists/data/PlaylistOrdering.kt`
- Test: `feature/playlists/src/test/java/com/orator/feature/playlists/data/PlaylistOrderingTest.kt`

> Positions are produced densely (10, 20, 30, …) so there's always head-room and the order is
> obvious. The DAO's `updatePositions` is the only writer (spec "Position drift" mitigation).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.orator.feature.playlists.data

import com.orator.core.database.PlaylistItemPosition
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistOrderingTest {

    // current order is just the list of item ids, top-first
    @Test fun `append puts a new id at the end`() {
        assertEquals(
            listOf(PlaylistItemPosition(1, 10), PlaylistItemPosition(2, 20), PlaylistItemPosition(3, 30)),
            PlaylistOrdering.reindex(listOf(1L, 2L, 3L)),
        )
    }

    @Test fun `moveToTop promotes the chosen id, others keep relative order`() {
        assertEquals(listOf(3L, 1L, 2L), PlaylistOrdering.moveToTop(listOf(1L, 2L, 3L), id = 3L))
    }

    @Test fun `moveToTop of the current top is a no-op order`() {
        assertEquals(listOf(1L, 2L, 3L), PlaylistOrdering.moveToTop(listOf(1L, 2L, 3L), id = 1L))
    }

    @Test fun `move shifts an id from one index to another`() {
        assertEquals(listOf(2L, 3L, 1L), PlaylistOrdering.move(listOf(1L, 2L, 3L), from = 0, to = 2))
        assertEquals(listOf(3L, 1L, 2L), PlaylistOrdering.move(listOf(1L, 2L, 3L), from = 2, to = 0))
    }

    @Test fun `remove drops an id`() {
        assertEquals(listOf(1L, 3L), PlaylistOrdering.remove(listOf(1L, 2L, 3L), id = 2L))
    }

    @Test fun `reindex assigns dense 10-step positions`() {
        assertEquals(
            listOf(PlaylistItemPosition(5, 10), PlaylistItemPosition(9, 20)),
            PlaylistOrdering.reindex(listOf(5L, 9L)),
        )
    }
}
```

- [ ] **Step 2: Run, expect failure** — `./gradlew :feature:playlists:testDebugUnitTest --tests
  "*PlaylistOrderingTest"`. Expected: FAIL (unresolved `PlaylistOrdering`). Report build time.

- [ ] **Step 3: Implement**

```kotlin
package com.orator.feature.playlists.data

import com.orator.core.database.PlaylistItemPosition

/**
 * Pure reorder math over a top-first list of playlist item ids. The result is always turned into
 * dense (10, 20, 30, …) positions via [reindex], which the DAO writes in one transaction.
 */
object PlaylistOrdering {
    private const val STEP = 10L

    fun moveToTop(ids: List<Long>, id: Long): List<Long> =
        if (id !in ids) ids else listOf(id) + ids.filterNot { it == id }

    fun move(ids: List<Long>, from: Int, to: Int): List<Long> {
        if (from !in ids.indices || to !in ids.indices || from == to) return ids
        val mutable = ids.toMutableList()
        mutable.add(to, mutable.removeAt(from))
        return mutable
    }

    fun remove(ids: List<Long>, id: Long): List<Long> = ids.filterNot { it == id }

    fun reindex(ids: List<Long>): List<PlaylistItemPosition> =
        ids.mapIndexed { index, id -> PlaylistItemPosition(id, (index + 1) * STEP) }
}
```

- [ ] **Step 4: Run, expect pass.** Report build time.

- [ ] **Step 5: Commit**

```bash
git add feature/playlists/src/main/java/com/orator/feature/playlists/data/PlaylistOrdering.kt \
        feature/playlists/src/test/java/com/orator/feature/playlists/data/PlaylistOrderingTest.kt
git commit -m "feat(playlists): PlaylistOrdering pure reorder math

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task 2.3: `MediaRefMatch` (pure)

**Files:**
- Create: `feature/playlists/src/main/java/com/orator/feature/playlists/data/MediaRefMatch.kt`
- Test: `feature/playlists/src/test/java/com/orator/feature/playlists/data/MediaRefMatchTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.orator.feature.playlists.data

import com.orator.core.model.MediaRef
import com.orator.core.model.MediaType
import com.orator.core.playback.ids.AudiobookMediaId
import com.orator.core.playback.ids.PodcastMediaId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaRefMatchTest {

    @Test fun `podcast ref matches its encoded media id`() {
        val ref = MediaRef(MediaType.PODCAST, "ep-1")
        assertTrue(MediaRefMatch.matches(ref, PodcastMediaId.encode("ep-1")))
        assertFalse(MediaRefMatch.matches(ref, PodcastMediaId.encode("ep-2")))
    }

    @Test fun `audiobook ref matches any file index of the same book`() {
        val ref = MediaRef(MediaType.AUDIOBOOK, "book-1")
        assertTrue(MediaRefMatch.matches(ref, AudiobookMediaId.encode("book-1", 0)))
        assertTrue(MediaRefMatch.matches(ref, AudiobookMediaId.encode("book-1", 7))) // mid-book
        assertFalse(MediaRefMatch.matches(ref, AudiobookMediaId.encode("book-2", 0)))
    }

    @Test fun `type mismatch never matches`() {
        assertFalse(MediaRefMatch.matches(MediaRef(MediaType.PODCAST, "x"), AudiobookMediaId.encode("x", 0)))
        assertFalse(MediaRefMatch.matches(MediaRef(MediaType.AUDIOBOOK, "x"), PodcastMediaId.encode("x")))
    }

    @Test fun `null or blank media id is not a match`() {
        assertFalse(MediaRefMatch.matches(MediaRef(MediaType.PODCAST, "x"), null))
        assertFalse(MediaRefMatch.matches(MediaRef(MediaType.PODCAST, "x"), ""))
    }
}
```

- [ ] **Step 2: Run, expect failure.** Report build time.

- [ ] **Step 3: Implement**

```kotlin
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
```

- [ ] **Step 4: Run, expect pass.** Report build time.

- [ ] **Step 5: Commit**

```bash
git add feature/playlists/src/main/java/com/orator/feature/playlists/data/MediaRefMatch.kt \
        feature/playlists/src/test/java/com/orator/feature/playlists/data/MediaRefMatchTest.kt
git commit -m "feat(playlists): MediaRefMatch (ref ↔ encoded mediaId)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task 2.4: `ActivePlaylistStore` (DataStore)

**Files:**
- Create: `feature/playlists/src/main/java/com/orator/feature/playlists/data/ActivePlaylistStore.kt`

> Mirrors the `PlayerPreferences` DataStore idiom (`preferencesDataStore` extension + a
> `@Qualifier` + a Hilt `@Provides`). Persists which playlist is draining so auto-advance survives
> process death. No test (thin DataStore wrapper; exercised via the controller with a fake store).

- [ ] **Step 1: Write the file**

```kotlin
package com.orator.feature.playlists.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlaylistDataStore

private val Context.playlistDataStore by preferencesDataStore(name = "playlists")
private val KEY_ACTIVE = longPreferencesKey("active_playlist_id")

@Module
@InstallIn(SingletonComponent::class)
object PlaylistDataStoreModule {
    @Provides
    @Singleton
    @PlaylistDataStore
    fun provide(@ApplicationContext context: Context): DataStore<Preferences> =
        context.playlistDataStore
}

/** Which playlist is currently draining (null = none). Survives process death. */
@Singleton
class ActivePlaylistStore @Inject constructor(
    @PlaylistDataStore private val store: DataStore<Preferences>,
) {
    suspend fun activePlaylistId(): Long? =
        store.data.map { it[KEY_ACTIVE] }.first()?.takeIf { it >= 0 }

    suspend fun set(playlistId: Long) {
        store.edit { it[KEY_ACTIVE] = playlistId }
    }

    suspend fun clear() {
        store.edit { it.remove(KEY_ACTIVE) }
    }
}
```

- [ ] **Step 2: Compile** — `./gradlew :feature:playlists:compileDebugKotlin`. Report build time.

- [ ] **Step 3: Commit**

```bash
git add feature/playlists/src/main/java/com/orator/feature/playlists/data/ActivePlaylistStore.kt
git commit -m "feat(playlists): ActivePlaylistStore (DataStore-backed active id)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task 2.5: `PlaylistRepository` (hydration + prune + mutations)

**Files:**
- Create: `feature/playlists/src/main/java/com/orator/feature/playlists/data/PlaylistRepository.kt`
- Test: `feature/playlists/src/test/java/com/orator/feature/playlists/data/PlaylistRepositoryTest.kt`

> The repository depends on `PlaylistDao` + `Set<PlaylistItemResolver>`. It never touches book or
> episode tables — resolution is delegated per type. A `null` resolve prunes that row (drops it
> from the emitted list **and** deletes the DB row, so the playlist self-heals).

- [ ] **Step 1: Write the failing test** (fake DAO in-memory + fake resolvers)

```kotlin
package com.orator.feature.playlists.data

import com.orator.core.database.PlaylistItemEntity
import com.orator.core.model.MediaRef
import com.orator.core.model.MediaType
import com.orator.core.model.PlaylistItemContent
import com.orator.core.model.PlaylistItemResolver
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistRepositoryTest {

    private fun resolver(type: MediaType, known: Set<String>) = object : PlaylistItemResolver {
        override val mediaType = type
        override suspend fun resolve(ref: MediaRef): PlaylistItemContent? =
            if (ref.id in known) PlaylistItemContent("T:${ref.id}", "S", null, 1000) else null
    }

    @Test fun `hydrates mixed rows in order`() = runTest {
        val dao = FakePlaylistDao()
        val p = dao.insertPlaylist(playlist())
        dao.insertItem(item(p, MediaType.PODCAST, "ep", 10))
        dao.insertItem(item(p, MediaType.AUDIOBOOK, "bk", 20))
        val repo = PlaylistRepository(
            dao,
            setOf(resolver(MediaType.PODCAST, setOf("ep")), resolver(MediaType.AUDIOBOOK, setOf("bk"))),
        )

        val ui = repo.items(p)

        assertEquals(listOf("T:ep", "T:bk"), ui.map { it.content.title })
        assertEquals(listOf(MediaType.PODCAST, MediaType.AUDIOBOOK), ui.map { it.ref.type })
    }

    @Test fun `prunes a dangling ref (resolver returns null) and deletes its row`() = runTest {
        val dao = FakePlaylistDao()
        val p = dao.insertPlaylist(playlist())
        dao.insertItem(item(p, MediaType.PODCAST, "ep", 10))
        val gone = dao.insertItem(item(p, MediaType.PODCAST, "ghost", 20))
        val repo = PlaylistRepository(dao, setOf(resolver(MediaType.PODCAST, setOf("ep"))))

        val ui = repo.items(p)

        assertEquals(listOf("T:ep"), ui.map { it.content.title })
        assertEquals(null, dao.getItem(gone)) // row deleted
    }

    @Test fun `topRef returns the smallest-position ref, null when empty`() = runTest {
        val dao = FakePlaylistDao()
        val p = dao.insertPlaylist(playlist())
        val repo = PlaylistRepository(dao, emptySet())
        assertEquals(null, repo.topRef(p))
        dao.insertItem(item(p, MediaType.AUDIOBOOK, "bk", 30))
        dao.insertItem(item(p, MediaType.PODCAST, "ep", 10))
        assertEquals(MediaRef(MediaType.PODCAST, "ep"), repo.topRef(p))
    }
}
```

> Provide a small `FakePlaylistDao` test double implementing `PlaylistDao` over in-memory maps
> (only the methods the repository/controller use need real behavior: insert*, getItems,
> observeItems, getTopItem, getItem, deleteItem, maxPosition, updatePositions, deletePlaylist,
> renamePlaylist, observePlaylists, getPlaylist, insertPlaylist). Place it at
> `feature/playlists/src/test/java/com/orator/feature/playlists/data/FakePlaylistDao.kt`. Keep
> `observe*` flows backed by a `MutableStateFlow` you re-emit on every mutation.

- [ ] **Step 2: Run, expect failure.** Report build time.

- [ ] **Step 3: Implement `PlaylistRepository`**

```kotlin
package com.orator.feature.playlists.data

import com.orator.core.database.PlaylistDao
import com.orator.core.database.PlaylistEntity
import com.orator.core.database.PlaylistItemEntity
import com.orator.core.database.PlaylistSummary
import com.orator.core.model.MediaRef
import com.orator.core.model.MediaType
import com.orator.core.model.PlaylistItemContent
import com.orator.core.model.PlaylistItemResolver
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** A hydrated playlist row for the UI. */
data class PlaylistItemUi(val itemId: Long, val ref: MediaRef, val content: PlaylistItemContent)

@Singleton
class PlaylistRepository @Inject constructor(
    private val dao: PlaylistDao,
    resolvers: Set<@JvmSuppressWildcards PlaylistItemResolver>,
) {
    private val byType: Map<MediaType, PlaylistItemResolver> = resolvers.associateBy { it.mediaType }

    fun observePlaylists(): Flow<List<PlaylistSummary>> = dao.observePlaylists()
    fun observeItems(playlistId: Long): Flow<List<PlaylistItemEntity>> = dao.observeItems(playlistId)
    suspend fun getPlaylist(id: Long): PlaylistEntity? = dao.getPlaylist(id)

    suspend fun createPlaylist(name: String, nowMs: Long): Long =
        dao.insertPlaylist(PlaylistEntity(name = name.trim(), createdAtMs = nowMs))

    suspend fun renamePlaylist(id: Long, name: String) = dao.renamePlaylist(id, name.trim())
    suspend fun deletePlaylist(id: Long) = dao.deletePlaylist(id)

    /** Append a ref to the bottom. Dedupe is enforced by the DAO's unique index (insert ignored). */
    suspend fun addToBottom(playlistId: Long, ref: MediaRef) {
        val next = (dao.maxPosition(playlistId) ?: 0L) + 10L
        dao.insertItem(
            PlaylistItemEntity(playlistId = playlistId, mediaType = ref.type, mediaId = ref.id, position = next),
        )
    }

    /** Hydrate to UI rows; prune (drop + delete) rows whose entity no longer resolves. */
    suspend fun items(playlistId: Long): List<PlaylistItemUi> = buildList {
        for (row in dao.getItems(playlistId)) {
            val ref = MediaRef(row.mediaType, row.mediaId)
            val content = byType[row.mediaType]?.resolve(ref)
            if (content == null) dao.deleteItem(row.id) else add(PlaylistItemUi(row.id, ref, content))
        }
    }

    suspend fun topRef(playlistId: Long): MediaRef? =
        dao.getTopItem(playlistId)?.let { MediaRef(it.mediaType, it.mediaId) }

    suspend fun removeTop(playlistId: Long) {
        dao.getTopItem(playlistId)?.let { dao.deleteItem(it.id) }
    }

    suspend fun removeItem(itemId: Long) = dao.deleteItem(itemId)

    suspend fun moveToTop(playlistId: Long, itemId: Long) =
        persist(playlistId, PlaylistOrdering.moveToTop(currentIds(playlistId), itemId))

    suspend fun move(playlistId: Long, from: Int, to: Int) =
        persist(playlistId, PlaylistOrdering.move(currentIds(playlistId), from, to))

    private suspend fun currentIds(playlistId: Long): List<Long> =
        dao.getItems(playlistId).map { it.id }

    private suspend fun persist(playlistId: Long, orderedIds: List<Long>) =
        dao.updatePositions(PlaylistOrdering.reindex(orderedIds))
}
```

- [ ] **Step 4: Run, expect pass** (3 tests). Report build time.

- [ ] **Step 5: Commit**

```bash
git add feature/playlists/src/main/java/com/orator/feature/playlists/data/PlaylistRepository.kt \
        feature/playlists/src/test/java/com/orator/feature/playlists/data/PlaylistRepositoryTest.kt \
        feature/playlists/src/test/java/com/orator/feature/playlists/data/FakePlaylistDao.kt
git commit -m "feat(playlists): PlaylistRepository hydration + prune + mutations

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task 2.6: `PlaylistPlayback` seam + `ConnectionPlaylistPlayback`

**Files:**
- Create: `feature/playlists/src/main/java/com/orator/feature/playlists/playback/PlaylistPlayback.kt`

> The controller depends on this narrow seam (not `PlaybackConnection` directly) so it's testable
> with a fake. The real impl wraps the existing `PlaybackConnection`.

- [ ] **Step 1: Write the file**

```kotlin
package com.orator.feature.playlists.playback

import com.orator.core.playback.PlaybackConnection
import com.orator.core.playback.PlaybackUiState
import com.orator.core.playback.PlayRequest
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The slice of playback the playlist controller needs. */
interface PlaylistPlayback {
    val state: StateFlow<PlaybackUiState>
    fun play(request: PlayRequest)
}

@Singleton
class ConnectionPlaylistPlayback @Inject constructor(
    private val connection: PlaybackConnection,
) : PlaylistPlayback {
    override val state: StateFlow<PlaybackUiState> get() = connection.state
    override fun play(request: PlayRequest) = connection.play(request)
}

@Module
@InstallIn(SingletonComponent::class)
interface PlaylistPlaybackBindingModule {
    @Binds
    fun bindPlaylistPlayback(impl: ConnectionPlaylistPlayback): PlaylistPlayback
}
```

- [ ] **Step 2: Compile.** Report build time.

- [ ] **Step 3: Commit**

```bash
git add feature/playlists/src/main/java/com/orator/feature/playlists/playback/PlaylistPlayback.kt
git commit -m "feat(playlists): PlaylistPlayback seam over PlaybackConnection

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task 2.7: `PlaylistPlaybackController` (the orchestrator)

**Files:**
- Create: `feature/playlists/src/main/java/com/orator/feature/playlists/playback/PlaylistPlaybackController.kt`
- Test: `feature/playlists/src/test/java/com/orator/feature/playlists/playback/PlaylistPlaybackControllerTest.kt`

> Advance logic lives in a single suspend `onState(state)` so tests can drive it directly with
> crafted states (no Main dispatcher needed). `start(scope)` wires `playback.state` → `onState`
> for production; tests never call `start`.

- [ ] **Step 1: Write the failing test** (fake playback + fake factory + real repo over FakePlaylistDao)

```kotlin
package com.orator.feature.playlists.playback

import com.orator.core.model.MediaRef
import com.orator.core.model.MediaType
import com.orator.core.playback.PlayRequest
import com.orator.core.playback.PlayableItem
import com.orator.core.playback.PlayRequestFactory
import com.orator.core.playback.PlaybackUiState
import com.orator.core.playback.ids.AudiobookMediaId
import com.orator.core.playback.ids.PodcastMediaId
import com.orator.feature.playlists.data.ActivePlaylistStore
import com.orator.feature.playlists.data.FakePlaylistDao
import com.orator.feature.playlists.data.PlaylistRepository
import com.orator.feature.playlists.data.item
import com.orator.feature.playlists.data.playlist
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistPlaybackControllerTest {

    private class FakePlayback : PlaylistPlayback {
        override val state = MutableStateFlow(PlaybackUiState())
        val played = mutableListOf<PlayRequest>()
        override fun play(request: PlayRequest) { played += request }
    }

    // factory whose PlayRequest encodes the ref so we can assert which item loaded
    private fun factory(type: MediaType) = object : PlayRequestFactory {
        override val mediaType = type
        override suspend fun create(ref: MediaRef): PlayRequest {
            val mediaId = if (type == MediaType.PODCAST) PodcastMediaId.encode(ref.id)
                          else AudiobookMediaId.encode(ref.id, 0)
            return PlayRequest(
                items = listOf(PlayableItem(mediaId = mediaId, uri = "u", title = ref.id, artist = "")),
                mediaType = type,
            )
        }
    }

    private fun controller(dao: FakePlaylistDao, playback: FakePlayback, store: ActivePlaylistStore) =
        PlaylistPlaybackController(
            playback = playback,
            repo = PlaylistRepository(dao, emptySet()),
            factories = setOf(factory(MediaType.PODCAST), factory(MediaType.AUDIOBOOK)),
            active = store,
        )

    private fun ended() = PlaybackUiState(isEnded = true)
    private fun playing(mediaId: String) = PlaybackUiState(isEnded = false, mediaId = mediaId)

    @Test fun `playFromTop loads the top item and marks the playlist active`() = runTest {
        val dao = FakePlaylistDao(); val pb = FakePlayback(); val store = fakeStore()
        val p = dao.insertPlaylist(playlist())
        dao.insertItem(item(p, MediaType.PODCAST, "ep1", 10))
        val c = controller(dao, pb, store)

        c.playFromTop(p)

        assertEquals("ep1", pb.played.single().items.single().title)
        assertEquals(p, store.activePlaylistId())
    }

    @Test fun `isEnded rising edge pops the top and plays the next`() = runTest {
        val dao = FakePlaylistDao(); val pb = FakePlayback(); val store = fakeStore()
        val p = dao.insertPlaylist(playlist())
        dao.insertItem(item(p, MediaType.PODCAST, "ep1", 10))
        dao.insertItem(item(p, MediaType.AUDIOBOOK, "bk1", 20))
        val c = controller(dao, pb, store)
        c.playFromTop(p)              // plays ep1
        c.onState(playing(PodcastMediaId.encode("ep1")))

        c.onState(ended())            // ep1 finished

        assertEquals(listOf("ep1", "bk1"), pb.played.map { it.items.single().title })
        assertEquals(listOf("bk1"), dao.getItems(p).map { it.mediaId }) // ep1 row gone
    }

    @Test fun `two consecutive completions each advance (re-arm)`() = runTest {
        val dao = FakePlaylistDao(); val pb = FakePlayback(); val store = fakeStore()
        val p = dao.insertPlaylist(playlist())
        dao.insertItem(item(p, MediaType.PODCAST, "ep1", 10))
        dao.insertItem(item(p, MediaType.PODCAST, "ep2", 20))
        dao.insertItem(item(p, MediaType.PODCAST, "ep3", 30))
        val c = controller(dao, pb, store)
        c.playFromTop(p)

        c.onState(playing(PodcastMediaId.encode("ep1")))
        c.onState(ended())                                   // -> ep2
        c.onState(playing(PodcastMediaId.encode("ep2")))     // isEnded falls back to false (re-arm)
        c.onState(ended())                                   // -> ep3

        assertEquals(listOf("ep1", "ep2", "ep3"), pb.played.map { it.items.single().title })
    }

    @Test fun `empty after last completion stops and clears active`() = runTest {
        val dao = FakePlaylistDao(); val pb = FakePlayback(); val store = fakeStore()
        val p = dao.insertPlaylist(playlist())
        dao.insertItem(item(p, MediaType.PODCAST, "ep1", 10))
        val c = controller(dao, pb, store)
        c.playFromTop(p)
        c.onState(playing(PodcastMediaId.encode("ep1")))

        c.onState(ended())

        assertEquals(1, pb.played.size)            // nothing new played
        assertNull(store.activePlaylistId())       // deactivated
    }

    @Test fun `playItem promotes then plays`() = runTest {
        val dao = FakePlaylistDao(); val pb = FakePlayback(); val store = fakeStore()
        val p = dao.insertPlaylist(playlist())
        dao.insertItem(item(p, MediaType.PODCAST, "ep1", 10))
        val second = dao.insertItem(item(p, MediaType.PODCAST, "ep2", 20))
        val c = controller(dao, pb, store)

        c.playItem(p, second)

        assertEquals("ep2", pb.played.single().items.single().title)
        assertEquals(listOf("ep2", "ep1"), dao.getItems(p).map { it.mediaId }) // promoted
    }

    @Test fun `multi-file book internal transition does not advance`() = runTest {
        val dao = FakePlaylistDao(); val pb = FakePlayback(); val store = fakeStore()
        val p = dao.insertPlaylist(playlist())
        dao.insertItem(item(p, MediaType.AUDIOBOOK, "bk1", 10))
        dao.insertItem(item(p, MediaType.PODCAST, "ep1", 20))
        val c = controller(dao, pb, store)
        c.playFromTop(p)                                     // plays bk1

        // file 0 -> file 1 inside the book: mediaId changes file index but no isEnded
        c.onState(playing(AudiobookMediaId.encode("bk1", 0)))
        c.onState(playing(AudiobookMediaId.encode("bk1", 1)))

        assertEquals(listOf("bk1"), pb.played.map { it.items.single().title }) // no advance
        assertEquals(2, dao.getItems(p).size)                                  // nothing popped
    }

    @Test fun `playing something outside the playlist deactivates`() = runTest {
        val dao = FakePlaylistDao(); val pb = FakePlayback(); val store = fakeStore()
        val p = dao.insertPlaylist(playlist())
        dao.insertItem(item(p, MediaType.PODCAST, "ep1", 10))
        val c = controller(dao, pb, store)
        c.playFromTop(p)

        c.onState(playing(PodcastMediaId.encode("other-ep"))) // user played a standalone episode

        assertNull(store.activePlaylistId())
    }

    @Test fun `no advance when no playlist is active`() = runTest {
        val dao = FakePlaylistDao(); val pb = FakePlayback(); val store = fakeStore()
        val c = controller(dao, pb, store)

        c.onState(ended())

        assertEquals(0, pb.played.size)
    }
}
```

> Add test helpers if not already present: `playlist()` / `item(...)` factory funcs and a
> `fakeStore()` returning a **real `ActivePlaylistStore`** (it is a `final class`, so it can't be
> faked by subclassing) backed by an in-memory `DataStore<Preferences>` created with
> `PreferenceDataStoreFactory.create(scope = this /* TestScope */) { tmpFolder.newFile("active.preferences_pb") }`
> — use a JUnit `@get:Rule val tmpFolder = TemporaryFolder()`. Keep these helpers in the test
> source set next to `FakePlaylistDao`.

- [ ] **Step 2: Run, expect failure.** Report build time.

- [ ] **Step 3: Implement the controller**

```kotlin
package com.orator.feature.playlists.playback

import com.orator.core.model.MediaRef
import com.orator.core.playback.PlayRequestFactory
import com.orator.core.playback.PlaybackUiState
import com.orator.feature.playlists.data.ActivePlaylistStore
import com.orator.feature.playlists.data.MediaRefMatch
import com.orator.feature.playlists.data.PlaylistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives a draining playlist on top of the single-entity playback core. Loads the TOP item, and on
 * each end-of-queue (isEnded rising edge) pops the top row and plays the next. Stands down if the
 * user plays something outside the active playlist. All advance logic is in [onState] for testing;
 * [start] wires it to live playback in production.
 */
@Singleton
class PlaylistPlaybackController @Inject constructor(
    private val playback: PlaylistPlayback,
    private val repo: PlaylistRepository,
    private val factories: Set<@JvmSuppressWildcards PlayRequestFactory>,
    private val active: ActivePlaylistStore,
) {
    private val factoryByType = factories.associateBy { it.mediaType }
    private var wasEnded = false

    /** Production wiring: call once at app start (from PlaylistsFeatureEntry). */
    fun start(scope: CoroutineScope) {
        scope.launch { playback.state.collect { onState(it) } }
    }

    suspend fun playFromTop(playlistId: Long) {
        active.set(playlistId)
        wasEnded = false
        val ref = repo.topRef(playlistId) ?: run { active.clear(); return }
        playRef(ref)
    }

    suspend fun playItem(playlistId: Long, itemId: Long) {
        repo.moveToTop(playlistId, itemId)
        playFromTop(playlistId)
    }

    /** Reacts to one playback state. Idempotent per state; only the rising edge of isEnded advances. */
    suspend fun onState(state: PlaybackUiState) {
        val activeId = active.activePlaylistId() ?: run { wasEnded = state.isEnded; return }

        // Stand down if a non-blank, non-ended mediaId points outside this playlist's top.
        if (!state.isEnded && !state.mediaId.isNullOrBlank()) {
            val top = repo.topRef(activeId)
            if (top != null && !MediaRefMatch.matches(top, state.mediaId)) {
                active.clear()
                wasEnded = false
                return
            }
        }

        if (state.isEnded && !wasEnded) {
            repo.removeTop(activeId)
            val next = repo.topRef(activeId)
            if (next != null) playRef(next) else active.clear()
        }
        wasEnded = state.isEnded
    }

    private suspend fun playRef(ref: MediaRef) {
        val request = factoryByType[ref.type]?.create(ref) ?: return
        playback.play(request)
        wasEnded = false // new queue clears STATE_ENDED; keep our flag in sync so the edge re-arms
    }
}
```

- [ ] **Step 4: Run, expect pass** (all controller tests). Report build time.
- [ ] If a test fails because `onState` re-reads `active`/`top` in a way the fake store doesn't
  reflect synchronously, ensure `fakeStore()` updates are visible to the next suspend read (the
  hand-rolled fake's `set/clear/activePlaylistId` should be plain field reads/writes).

- [ ] **Step 5: Commit**

```bash
git add feature/playlists/src/main/java/com/orator/feature/playlists/playback/PlaylistPlaybackController.kt \
        feature/playlists/src/test/java/com/orator/feature/playlists/playback/PlaylistPlaybackControllerTest.kt
# include any new test helper files you created:
# git add feature/playlists/src/test/java/com/orator/feature/playlists/data/TestFixtures.kt
git commit -m "feat(playlists): PlaylistPlaybackController orchestration

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task 2.8: Chunk 2 gate

- [ ] Run `./gradlew test lint assembleDebug`. Expected: SUCCESS, all tests pass. Report build
  time. (assembleDebug now compiles the new module even though it has no UI/entry yet.)

---

## Chunk 3: Per-type contributions (`feature:audiobooks`, `feature:podcasts`)

Outcome: refs resolve and play end-to-end. After this chunk the controller can actually load and
advance real episodes and books (still no playlist UI — verified by unit tests).

### Task 3.1: Audiobook factory + resolver

**Files:**
- Create: `feature/audiobooks/.../data/AudiobookPlayRequestFactory.kt`
- Create: `feature/audiobooks/.../data/AudiobookPlaylistItemResolver.kt`
- Modify: `feature/audiobooks/.../AudiobooksFeatureModule.kt`
- Test: `feature/audiobooks/.../data/AudiobookPlayRequestFactoryTest.kt`

> Read `feature/audiobooks/.../data/QueueBuilder.kt` and the audiobook repository/DAO usage first
> to reuse the exact load path (`BookDao.getById`, chapters via `ChapterDao`). `BookEntity.id` is
> a String, so `ref.id` is the book id directly.

- [ ] **Step 1: Write the failing factory test** (fake book + chapter sources; assert it produces
  the same `PlayRequest` shape `QueueBuilder.build` does for that book at its saved position, and
  `null` for an unknown id). Model the fakes on existing audiobook tests in that module.

- [ ] **Step 2: Run, expect failure.** Report build time.

- [ ] **Step 3: Implement the factory**

```kotlin
package com.orator.feature.audiobooks.data

import com.orator.core.database.BookDao
import com.orator.core.database.ChapterDao
import com.orator.core.model.MediaRef
import com.orator.core.model.MediaType
import com.orator.core.playback.PlayRequest
import com.orator.core.playback.PlayRequestFactory
import javax.inject.Inject

/** Builds a whole-book PlayRequest for a playlist item, resuming at the book's saved position. */
class AudiobookPlayRequestFactory @Inject constructor(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
) : PlayRequestFactory {
    override val mediaType = MediaType.AUDIOBOOK

    override suspend fun create(ref: MediaRef): PlayRequest? {
        val book = bookDao.getById(ref.id) ?: return null
        val chapters = chapterDao.getForBook(ref.id) // confirm the actual method name
        return QueueBuilder.build(book, chapters, startAtMs = book.positionMs)
    }
}
```
> Verify `ChapterDao`'s "chapters for book" method name during implementation and adjust. If the
> audiobook module already has a repository that loads (book, chapters) together, prefer injecting
> that over the DAOs directly to match local conventions.

- [ ] **Step 4: Implement the resolver**

```kotlin
package com.orator.feature.audiobooks.data

import com.orator.core.database.BookDao
import com.orator.core.model.MediaRef
import com.orator.core.model.MediaType
import com.orator.core.model.PlaylistItemContent
import com.orator.core.model.PlaylistItemResolver
import javax.inject.Inject

class AudiobookPlaylistItemResolver @Inject constructor(
    private val bookDao: BookDao,
) : PlaylistItemResolver {
    override val mediaType = MediaType.AUDIOBOOK

    override suspend fun resolve(ref: MediaRef): PlaylistItemContent? {
        val book = bookDao.getById(ref.id) ?: return null
        return PlaylistItemContent(
            title = book.title,
            subtitle = book.author.orEmpty(),   // BookEntity.author is String?
            artworkUri = book.coverPath,        // BookEntity.coverPath: String?
            durationMs = book.durationMs,
        )
    }
}
```

- [ ] **Step 5: Bind both `@IntoSet`** in `AudiobooksFeatureModule.kt`:

```kotlin
@Binds @IntoSet
fun bindPlayRequestFactory(f: AudiobookPlayRequestFactory): PlayRequestFactory

@Binds @IntoSet
fun bindPlaylistItemResolver(r: AudiobookPlaylistItemResolver): PlaylistItemResolver
```
(add the imports for `PlayRequestFactory` and `PlaylistItemResolver`.)

- [ ] **Step 6: Run the factory test, expect pass.** Report build time.

- [ ] **Step 7: Commit**

```bash
git add feature/audiobooks/src/main/java/com/orator/feature/audiobooks/data/AudiobookPlayRequestFactory.kt \
        feature/audiobooks/src/main/java/com/orator/feature/audiobooks/data/AudiobookPlaylistItemResolver.kt \
        feature/audiobooks/src/main/java/com/orator/feature/audiobooks/AudiobooksFeatureModule.kt \
        feature/audiobooks/src/test/java/com/orator/feature/audiobooks/data/AudiobookPlayRequestFactoryTest.kt
git commit -m "feat(audiobooks): playlist factory + resolver contributions

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task 3.2: Episode factory + resolver

**Files:**
- Create: `feature/podcasts/.../data/EpisodePlayRequestFactory.kt`
- Create: `feature/podcasts/.../data/EpisodePlaylistItemResolver.kt`
- Modify: `feature/podcasts/.../PodcastsFeatureModule.kt`
- Test: `feature/podcasts/.../data/EpisodePlayRequestFactoryTest.kt`

> Mirror Task 3.1. Both the factory and the resolver inject **`EpisodeDao` + `PodcastDao`** —
> `EpisodeEntity` has no show-name or artwork field, so those come from the parent podcast.
> - Factory: `val ep = episodeDao.getById(ref.id) ?: return null; val pod =
>   podcastDao.getById(ep.podcastId) ?: return null; EpisodeQueueBuilder.build(pod, ep,
>   startAtMs = ep.positionMs)`.
> - Resolver: `PlaylistItemContent(title = ep.title, subtitle = pod.title, artworkUri =
>   pod.artworkUrl, durationMs = ep.durationMs)` (`PodcastEntity.artworkUrl: String?`,
>   `PodcastEntity.title`). Return `null` if either the episode or its podcast is missing.

- [ ] **Step 1:** Write the failing factory test (fake episode + podcast sources; assert same
  PlayRequest shape `EpisodeQueueBuilder.build` yields; `null` for unknown id).
- [ ] **Step 2:** Run, expect failure. Report build time.
- [ ] **Step 3:** Implement `EpisodePlayRequestFactory` (mediaType = PODCAST), delegating to
  `EpisodeQueueBuilder`.
- [ ] **Step 4:** Implement `EpisodePlaylistItemResolver` (mediaType = PODCAST).
- [ ] **Step 5:** Bind both `@IntoSet` in `PodcastsFeatureModule.kt`.
- [ ] **Step 6:** Run the factory test, expect pass. Report build time.
- [ ] **Step 7: Commit**

```bash
git add feature/podcasts/src/main/java/com/orator/feature/podcasts/data/EpisodePlayRequestFactory.kt \
        feature/podcasts/src/main/java/com/orator/feature/podcasts/data/EpisodePlaylistItemResolver.kt \
        feature/podcasts/src/main/java/com/orator/feature/podcasts/PodcastsFeatureModule.kt \
        feature/podcasts/src/test/java/com/orator/feature/podcasts/data/EpisodePlayRequestFactoryTest.kt
git commit -m "feat(podcasts): playlist factory + resolver contributions

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task 3.3: Chunk 3 gate

- [ ] Run `./gradlew test lint assembleDebug`. Expected: SUCCESS, all tests pass. Report build
  time. Hilt now aggregates two factories + two resolvers into the app component — a successful
  `assembleDebug` confirms the multibindings compile.

---

## Chunk 4: UI + entry points + app wiring

Outcome: a shippable feature — Playlists tab, list/detail/add screens, cross-feature "Add to
playlist", and eager controller startup.

### Task 4.1: Routes (`core:navigation`)

**Files:**
- Modify: `core/navigation/src/main/java/com/orator/core/navigation/CommonRoutes.kt`

- [ ] **Step 1:** Add constants + builders (follow the existing podcast-detail route pattern,
  which lives in the podcasts feature; here keep the shared strings + builders in `CommonRoutes`):

```kotlin
const val Playlists = "playlists"
const val PlaylistDetail = "playlist/{playlistId}"
fun playlistDetail(playlistId: Long) = "playlist/$playlistId"
const val AddToPlaylist = "add-to-playlist/{mediaType}/{mediaId}"
fun addToPlaylist(mediaType: String, mediaId: String) = "add-to-playlist/$mediaType/$mediaId"
```

- [ ] **Step 2: Compile** `./gradlew :core:navigation:compileDebugKotlin`. Report build time.
- [ ] **Step 3: Commit**

```bash
git add core/navigation/src/main/java/com/orator/core/navigation/CommonRoutes.kt
git commit -m "feat(navigation): playlist + add-to-playlist routes

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task 4.2: Playlists list screen + ViewModel

**Files:**
- Create: `feature/playlists/.../PlaylistsViewModel.kt`, `PlaylistsScreen.kt`

> Follow `PodcastListScreen`/`PodcastDetailScreen` for Hilt VM + `collectAsStateWithLifecycle` +
> Onyx components (`OnyxTopBar`, `SectionLabel`, list rows). VM exposes
> `repo.observePlaylists()` as state; actions: `create(name)`, navigate to detail.

- [ ] **Step 1:** Write `PlaylistsViewModel` (`@HiltViewModel`, injects `PlaylistRepository`):
  `val playlists = repo.observePlaylists().stateIn(...)`; `fun create(name: String)` →
  `viewModelScope.launch { repo.createPlaylist(name, System.currentTimeMillis()) }`.
- [ ] **Step 2:** Write `PlaylistsScreen(onOpenPlaylist: (Long) -> Unit)` — top bar, list of
  playlists (name + "N items"), a "＋ New playlist" affordance opening a name `AlertDialog`.
  Empty state when there are none.
- [ ] **Step 3: Compile** `./gradlew :feature:playlists:compileDebugKotlin`. Report build time.
- [ ] **Step 4: Commit** (explicit paths).

### Task 4.3: Playlist detail screen + ViewModel (tap / swipe / drag)

**Files:**
- Create: `feature/playlists/.../PlaylistDetailViewModel.kt`, `PlaylistDetailScreen.kt`

> VM injects `PlaylistRepository`, `PlaylistPlaybackController`, and `SavedStateHandle`
> (`playlistId` arg). It exposes hydrated rows. Because hydration is a suspend prune-and-return
> (`repo.items`), re-hydrate whenever `repo.observeItems(id)` emits:
> `repo.observeItems(id).mapLatest { repo.items(id) }` → `stateIn`.

- [ ] **Step 1:** Write `PlaylistDetailViewModel`:
  - `val items: StateFlow<List<PlaylistItemUi>>`
  - `fun playFromTop()` → `launch { controller.playFromTop(id) }`
  - `fun playItem(itemId)` → `launch { controller.playItem(id, itemId) }`
  - `fun remove(itemId)` → `launch { repo.removeItem(itemId) }`
  - `fun move(from, to)` → `launch { repo.move(id, from, to) }`
  - `fun rename(name)` / `fun delete()`.
- [ ] **Step 2:** Write `PlaylistDetailScreen`:
  - Header: playlist name, **Play from top** button, overflow (rename / delete).
  - Rows via `EpisodeRow`/a list row showing `content.title`, `content.subtitle`, and duration
    formatted with `TimeFormats` (package `com.orator.core.designsystem.text`) **only when
    `content.durationMs > 0`** (never "0:00").
  - **tap** row → `playItem(itemId)`; **swipe** → `remove(itemId)` using
    `SwipeActionRow(enabled = true, actionLabel = "Remove ✕", onSwipeLeft = { remove(itemId) }) { /* row */ }`;
    **long-press drag** to reorder → `move(from, to)` on drop.
  - For drag: implement with `Modifier.pointerInput` + `detectDragGesturesAfterLongPress`
    tracking a dragged index and target index over a `LazyColumn`; commit with `move(from,to)` on
    release. **If this proves fiddly, fall back to ▲/▼ move buttons on each row calling
    `move(index, index-1)` / `move(index, index+1)`** — same VM call, no data/model change (spec
    allows this fallback).
  - Empty state when drained.
- [ ] **Step 3: Compile.** Report build time.
- [ ] **Step 4: Commit** (explicit paths).

### Task 4.4: Add-to-playlist sheet + ViewModel

**Files:**
- Create: `feature/playlists/.../AddToPlaylistViewModel.kt`, `AddToPlaylistSheet.kt`

> Destination args: `{mediaType}` (a `MediaType.name`) and `{mediaId}`. VM parses them into a
> `MediaRef`, observes playlists, and offers create-new.

- [ ] **Step 1:** Write `AddToPlaylistViewModel` (`SavedStateHandle` → `MediaRef`):
  - `val playlists = repo.observePlaylists()...`
  - `fun add(playlistId: Long)` → `launch { repo.addToBottom(playlistId, ref); onDone() }`
  - `fun createAndAdd(name: String)` → `launch { val id = repo.createPlaylist(...); repo.addToBottom(id, ref) }`
  - Guard malformed args (`MediaType.valueOf` in try/catch) → empty/no-op.
- [ ] **Step 2:** Write `AddToPlaylistSheet(onDone)` — a simple list of playlists to tap +
  "＋ New playlist". (A full-screen destination is fine; "sheet" is cosmetic.) Pop on done.
- [ ] **Step 3: Compile.** Report build time.
- [ ] **Step 4: Commit** (explicit paths).

### Task 4.5: FeatureEntry + module bindings + eager controller start

**Files:**
- Create: `feature/playlists/.../PlaylistsFeatureEntry.kt`, `PlaylistsFeatureModule.kt`

- [ ] **Step 1:** Write `PlaylistsFeatureEntry` — registers the three destinations and **forces
  eager controller startup** via constructor injection:

```kotlin
class PlaylistsFeatureEntry @Inject constructor(
    controller: PlaylistPlaybackController,
) : FeatureEntry {

    init {
        // Hilt builds this entry eagerly (app injects Set<FeatureEntry> at startup), so the
        // controller begins observing playback immediately — auto-advance survives process death
        // without app ever referencing this module's types.
        controller.start(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    }

    override val route: String = CommonRoutes.Playlists

    override fun register(navGraphBuilder: NavGraphBuilder, navController: NavController) {
        navGraphBuilder.composable(CommonRoutes.Playlists) {
            PlaylistsScreen(onOpenPlaylist = { id -> navController.navigate(CommonRoutes.playlistDetail(id)) })
        }
        navGraphBuilder.composable(
            CommonRoutes.PlaylistDetail,
            arguments = listOf(navArgument("playlistId") { type = NavType.LongType }),
        ) {
            PlaylistDetailScreen(
                onOpenPlayer = { navController.navigate(CommonRoutes.Player) },
                onBack = { navController.popBackStack() },
            )
        }
        navGraphBuilder.composable(
            CommonRoutes.AddToPlaylist,
            arguments = listOf(
                navArgument("mediaType") { type = NavType.StringType },
                navArgument("mediaId") { type = NavType.StringType },
            ),
        ) {
            AddToPlaylistSheet(onDone = { navController.popBackStack() })
        }
    }
}
```

- [ ] **Step 2:** Write `PlaylistsFeatureModule` — `@Binds @IntoSet bindFeatureEntry(...):
  FeatureEntry`. (The `PlaylistPlayback` binding module from Task 2.6 and the DataStore module
  from 2.4 are already `@InstallIn(SingletonComponent)`.)
- [ ] **Step 3: Compile** `./gradlew :feature:playlists:compileDebugKotlin`. Report build time.
- [ ] **Step 4: Commit** (explicit paths).

### Task 4.6: App tab

**Files:**
- Modify: `core/designsystem/src/main/java/com/orator/core/designsystem/icons/OnyxIcons.kt` (add `Playlists` vector)
- Modify: `app/src/main/java/com/orator/app/OratorShell.kt`

- [ ] **Step 1:** Read `OratorShell.kt` around `private val TABS = listOf(...)` to learn the tab
  data shape (route + label + icon).
- [ ] **Step 2:** Add a **Playlists** tab entry referencing `CommonRoutes.Playlists`, placed after
  Audiobooks. The existing tabs use custom `OnyxIcons` vectors (`OnyxIcons.Mic`/`.Book`/`.Queue`)
  — there is no Material icons dependency. Add a new `Playlists` `ImageVector` to
  `core/designsystem/.../icons/OnyxIcons.kt` (follow the lazy-`ImageVector.Builder` pattern of the
  existing glyphs; a simple list/stack glyph) and use `OnyxIcons.Playlists` for the tab. This is
  the single `app`-module change; it names a `CommonRoutes` string, not the feature module.
- [ ] **Step 3: Build** `./gradlew assembleDebug`. Expected: SUCCESS. Report build time.
- [ ] **Step 4: Commit** (explicit path).

### Task 4.7: Cross-feature "Add to playlist" affordance

**Files:**
- Modify: podcasts episode row usage (`feature/podcasts/.../PodcastDetailScreen.kt` and/or
  `EpisodeRow` call sites) — add a ⋮ button in the `trailing` slot navigating to
  `CommonRoutes.addToPlaylist(MediaType.PODCAST.name, episode.id)`.
- Modify: audiobooks list/detail (`feature/audiobooks/.../AudiobookListScreen.kt` or detail) —
  add the same ⋮ affordance navigating with `MediaType.AUDIOBOOK.name` + `book.id`.

> `EpisodeRow` already exposes a free `trailing: @Composable () -> Unit` slot — drop an
> `IconButton` there. There is no Material icons dependency; use an `OnyxIcons` glyph (add a small
> `OnyxIcons.AddToPlaylist`/`OnyxIcons.More` vector if none fits) or a plain `Text("＋")`. For
> audiobook `CoverTile` (a grid tile), prefer adding the affordance on the audiobook **detail**
> screen header, or a long-press menu on the tile, to avoid cluttering the grid. Pass an
> `onAddToPlaylist: (id) -> Unit` lambda down from each FeatureEntry's screen registration so
> navigation stays in the feature's entry (consistent with how those features already navigate).

- [ ] **Step 1:** Podcasts — thread an `onAddToPlaylist: (episodeId: String) -> Unit` from
  `PodcastsFeatureEntry`'s `PodcastDetailScreen` registration (it already has `navController`),
  navigating to `CommonRoutes.addToPlaylist(MediaType.PODCAST.name, id)`. Add the ⋮ `IconButton`
  in the episode row's `trailing`.
- [ ] **Step 2:** Audiobooks — same pattern with `MediaType.AUDIOBOOK.name` + book id, on the
  detail header (or long-press). Thread the lambda from `AudiobooksFeatureEntry`.
- [ ] **Step 3: Build** `./gradlew assembleDebug`. Report build time.
- [ ] **Step 4: Commit** (explicit paths for both features).

### Task 4.8: Chunk 4 gate + full verification

- [ ] **Step 1:** Run `./gradlew test lint assembleDebug`. Expected: SUCCESS, all tests pass.
  Report build time.
- [ ] **Step 2: Device smoke test** (Pixel 7a). Drive the app's OWN UI via adb taps where needed;
  **ask the user for any SAF folder-picker step** (do not drive DocumentsUI). Verify:
  1. Create a playlist, name it.
  2. Add a podcast episode (⋮ → playlist) and an audiobook (⋮ → same playlist).
  3. Open detail: both rows show with artwork/title/subtitle; durations sane.
  4. Drag to reorder; swipe to remove one; tap a lower item → it jumps to top and plays.
  5. **Play from top**: short episode → completes → next item (book) auto-plays and **resumes at
     its saved position**; book's internal chapter/file advance does **not** pop the playlist.
  6. Play a standalone book from the library mid-playlist → controller stands down (playlist stops
     auto-advancing); confirm no row was wrongly removed.
  7. Drain a playlist fully → playback stops, playlist empties, tab shows empty state.
- [ ] **Step 3:** Note any issues; fix and re-gate. Report final build time.

---

## Completion

After Chunk 4 passes its gate and the device smoke test is clean:

- Announce: "I'm using the finishing-a-development-branch skill to complete this work."
- Use **superpowers:finishing-a-development-branch**: verify `./gradlew test` passes, then present
  the four options (merge locally / push + PR / keep / discard) and execute the user's choice.
- On merge/PR: update `docs/architecture.md` §15 roadmap (Phase 5a done; Next: Phase 5b —
  WorkManager refresh + auto-insert) and the `akouo-phase-status` memory. Commit those doc updates
  on explicit paths.

## Deferred to Phase 5b (do NOT build here)

WorkManager periodic feed refresh; auto-insert rules (`autoInsertRule` on a playlist,
`autoInsertPlaylistId` on a podcast, new episodes → top/bottom). They ship together because
auto-insert needs periodic refresh to have anything to insert.
