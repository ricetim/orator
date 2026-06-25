# Audiobook Detail Screen + Play-UX Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tapping an audiobook cover in the library grid opens a book-info detail screen (synopsis, series, author, duration, progress) with Stream/Download/Play actions; opening the screen resolves the book, which fixes the Phase 6a streaming defect.

**Architecture:** A new `AudiobookDetailScreen` + `AudiobookDetailViewModel` in `feature:audiobooks`, reached by a new `audiobooks/{bookId}` route. Opening it runs the origin-matched `BookDetailResolver.ensureDetails` (which also fills two new `BookEntity` columns, `description`/`series`, captured from the ABS expanded item). Play flows through a testable `AudiobookPlayPreparer` (resolve → build queue with smart-rewind), so playback can never start on an unresolved book. The library tile stops auto-playing; the download badge and "not started" label are removed; two player tweaks (pager order, dual-bar order) ride along.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Room (v8→v9 destructive), Media3, kotlinx.serialization, Robolectric + in-memory Room for tests.

**Spec:** `docs/superpowers/specs/2026-06-24-audiobook-detail-screen-design.md`

**Branch:** `audiobook-detail-screen` (stacked on `phase-6a-audiobookshelf` / PR #11; rebase onto `main` after #11 merges).

**Gate (run at the end of every chunk):** `ANDROID_HOME=~/Android/Sdk ./gradlew test lint assembleDebug` — expected `BUILD SUCCESSFUL`. JVM-module unit tests use `runBlocking` (not `runTest`); Room-backed tests use Robolectric `@Config(sdk = [34])` + in-memory Room.

---

## Chunk 1: Data layer — description/series columns, ABS mapping, resolver

**File structure for this chunk:**
- Modify: `core/database/src/main/java/com/orator/core/database/BookEntity.kt` — add `description`/`series` columns.
- Modify: `core/database/src/main/java/com/orator/core/database/OratorDatabase.kt:17` — `version = 9`.
- Modify: `feature/audiobookshelf/.../data/AbsDtos.kt` — add `description` + `series[]` to `AbsMetadata`; new `AbsSeries`.
- Modify: `feature/audiobookshelf/.../data/AbsItemDetailMapper.kt` — `AbsBookDetail` gains `description`/`series`; `map()` extracts them.
- Modify: `feature/audiobookshelf/.../data/AbsBookDetailResolver.kt:29` — persist `description`/`series`.
- Test: `feature/audiobookshelf/src/test/.../data/AbsItemDetailMapperTest.kt` (new), `AbsBookDetailResolverTest.kt` (extend), `core/database/src/test/.../BookDaoOriginTest.kt` (extend) — confirm exact path of the last with `find core/database -name BookDaoOriginTest.kt`.

### Task 1.1: Add `description`/`series` columns to `BookEntity` + DB v9

**Files:**
- Modify: `core/database/src/main/java/com/orator/core/database/BookEntity.kt`
- Modify: `core/database/src/main/java/com/orator/core/database/OratorDatabase.kt:17`
- Test: `core/database/src/test/java/com/orator/core/database/BookDaoOriginTest.kt` (extend; verify the file exists first)

- [ ] **Step 1: Write the failing test** — a DAO round-trip proving the new columns persist. Append to `BookDaoOriginTest` (it already builds an in-memory DB + inserts `BookEntity`s; reuse its setup):

```kotlin
@Test fun `description and series round-trip`() = runBlocking {
    dao.upsert(listOf(
        book("abs:9", BookOrigin.ABS).copy(description = "A focused life.", series = "Foundation #2"),
    ))
    val row = dao.getById("abs:9")!!
    assertEquals("A focused life.", row.description)
    assertEquals("Foundation #2", row.series)
}
```
`BookDaoOriginTest` already has a `book(id, origin)` builder (it lacks `description`/`series`, hence the `.copy(...)`). Match the file's actual DAO field name (it may not be `dao`) and reuse its existing imports.

- [ ] **Step 2: Run it — expect FAIL (compile error: no `description`/`series`).**
Run: `ANDROID_HOME=~/Android/Sdk ./gradlew :core:database:testDebugUnitTest --tests "com.orator.core.database.BookDaoOriginTest"`
Expected: compilation failure / unresolved reference.

- [ ] **Step 3: Add the columns.** In `BookEntity.kt`, after the `downloadState` field:

```kotlin
    /** ABS book description/synopsis, filled on first resolve; null for LOCAL or when absent. */
    val description: String? = null,
    /** ABS series display string, e.g. "Foundation #2"; null when the book isn't in a series. */
    val series: String? = null,
```

- [ ] **Step 4: Bump the DB version.** In `OratorDatabase.kt` change `version = 8` to `version = 9`. (Destructive migration is already configured in `DatabaseModule.kt` via `.fallbackToDestructiveMigration(dropAllTables = true)`; no migration code needed. `exportSchema = false`, so no schema files.)

- [ ] **Step 5: Run the test — expect PASS.**
Run: `ANDROID_HOME=~/Android/Sdk ./gradlew :core:database:testDebugUnitTest --tests "com.orator.core.database.BookDaoOriginTest"`
Expected: PASS.

- [ ] **Step 6: Commit.**
```bash
git add core/database/src/main/java/com/orator/core/database/BookEntity.kt \
        core/database/src/main/java/com/orator/core/database/OratorDatabase.kt \
        core/database/src/test/java/com/orator/core/database/BookDaoOriginTest.kt
git commit -m "feat(database): BookEntity description/series columns; DB v9"
```

### Task 1.2: ABS DTO — parse `description` + `series[]`

**Files:**
- Modify: `feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/data/AbsDtos.kt`
- Test: covered by the mapper test in Task 1.3 (DTO has no behavior of its own).

- [ ] **Step 1: Add the series DTO + metadata fields.** In `AbsDtos.kt`, extend `AbsMetadata` and add `AbsSeries`:

```kotlin
@Serializable data class AbsMetadata(
    val title: String = "",
    @SerialName("authorName") val authorName: String? = null,
    val description: String? = null,
    val series: List<AbsSeries> = emptyList(),
)

@Serializable data class AbsSeries(
    val name: String = "",
    val sequence: String? = null,            // ABS sends sequence as a string, e.g. "2" or "2.5"
)
```
*Confirm against a live expanded-item response during implementation:* `curl` (or reuse the connected device) `GET /api/items/{id}?expanded=1` and verify `media.metadata.description` and `media.metadata.series` (array of `{name, sequence}`) are the real shapes. `ignoreUnknownKeys = true` (in `AbsJson`) means a wrong guess silently yields null/empty rather than crashing — so verify, don't assume.

- [ ] **Step 2: Build to confirm it compiles** (no behavior yet).
Run: `ANDROID_HOME=~/Android/Sdk ./gradlew :feature:audiobookshelf:compileDebugKotlin`
Expected: success.

- [ ] **Step 3: Commit.**
```bash
git add feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/data/AbsDtos.kt
git commit -m "feat(audiobookshelf): parse ABS description + series metadata"
```

### Task 1.3: Mapper extracts description + series string

**Files:**
- Modify: `feature/audiobookshelf/.../data/AbsItemDetailMapper.kt`
- Test: `feature/audiobookshelf/src/test/java/com/orator/feature/audiobookshelf/data/AbsItemDetailMapperTest.kt` (new)

- [ ] **Step 1: Write the failing test.**

```kotlin
package com.orator.feature.audiobookshelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AbsItemDetailMapperTest {
    private fun item(md: AbsMetadata) = AbsLibraryItem(
        "li1",
        AbsMedia(metadata = md, audioFiles = listOf(AbsAudioFile("100", 1, 60.0)),
            chapters = listOf(AbsChapter(start = 0.0, end = 60.0, title = "Ch1"))),
    )

    @Test fun `series name and sequence join as name hash seq`() {
        val d = AbsItemDetailMapper.map(
            item(AbsMetadata(description = "blurb", series = listOf(AbsSeries("Foundation", "2")))),
            "https://abs.example.com",
        )
        assertEquals("blurb", d.description)
        assertEquals("Foundation #2", d.series)
    }

    @Test fun `series without sequence is just the name`() {
        val d = AbsItemDetailMapper.map(
            item(AbsMetadata(series = listOf(AbsSeries("Foundation", null)))), "https://x",
        )
        assertEquals("Foundation", d.series)
    }

    @Test fun `missing description and series map to null`() {
        val d = AbsItemDetailMapper.map(item(AbsMetadata()), "https://x")
        assertNull(d.description)
        assertNull(d.series)
    }
}
```

- [ ] **Step 2: Run it — expect FAIL** (`AbsBookDetail` has no `description`/`series`).
Run: `ANDROID_HOME=~/Android/Sdk ./gradlew :feature:audiobookshelf:testDebugUnitTest --tests "com.orator.feature.audiobookshelf.data.AbsItemDetailMapperTest"`
Expected: compile failure.

- [ ] **Step 3: Implement.** In `AbsItemDetailMapper.kt`, extend the data class and `map()`:

```kotlin
data class AbsBookDetail(
    val sourceKind: SourceKind,
    val sourceUri: String,
    val chapters: List<ChapterEntity>,
    val description: String? = null,
    val series: String? = null,
)
```
At the top of `map()`, derive the two values and pass them into BOTH `AbsBookDetail(...)` return sites:

```kotlin
val description = item.media.metadata.description?.takeIf { it.isNotBlank() }
val series = item.media.metadata.series.firstOrNull()?.let { s ->
    val seq = s.sequence?.takeIf { it.isNotBlank() }
    if (seq != null) "${s.name} #$seq" else s.name.takeIf { it.isNotBlank() }
}
// ... SINGLE_FILE branch:
AbsBookDetail(SourceKind.SINGLE_FILE, uri, chapters, description, series)
// ... MULTI_FILE branch:
AbsBookDetail(SourceKind.MULTI_FILE, url(files.first().ino), chapters, description, series)
```

- [ ] **Step 4: Run the test — expect PASS.**
Run: `ANDROID_HOME=~/Android/Sdk ./gradlew :feature:audiobookshelf:testDebugUnitTest --tests "com.orator.feature.audiobookshelf.data.AbsItemDetailMapperTest"`
Expected: PASS.

- [ ] **Step 5: Commit.**
```bash
git add feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/data/AbsItemDetailMapper.kt \
        feature/audiobookshelf/src/test/java/com/orator/feature/audiobookshelf/data/AbsItemDetailMapperTest.kt
git commit -m "feat(audiobookshelf): map ABS description + series into AbsBookDetail"
```

### Task 1.4: Resolver persists description + series

**Files:**
- Modify: `feature/audiobookshelf/.../data/AbsBookDetailResolver.kt:29`
- Test: `feature/audiobookshelf/src/test/.../data/AbsBookDetailResolverTest.kt` (extend)

- [ ] **Step 1: Write the failing test.** Add to `AbsBookDetailResolverTest`:

```kotlin
@Test fun `ensureDetails persists description and series`() = runBlocking {
    val books = FakeBookDao().apply { upsert(listOf(absBook("abs:li1", sourceUri = ""))) }
    val r = AbsBookDetailResolver(
        detail = { _, _ ->
            AbsItemDetailMapper.map(
                AbsLibraryItem("li1", AbsMedia(
                    metadata = AbsMetadata(description = "blurb", series = listOf(AbsSeries("Foundation", "2"))),
                    audioFiles = listOf(AbsAudioFile("100", 1, 60.0)),
                    chapters = listOf(AbsChapter(start = 0.0, end = 60.0, title = "Ch1")),
                )),
                "https://abs.example.com",
            )
        },
        store = connectedStore(), bookDao = books, chapterDao = FakeChapterDao(),
    )
    r.ensureDetails("abs:li1")
    val row = books.getById("abs:li1")!!
    assertEquals("blurb", row.description)
    assertEquals("Foundation #2", row.series)
}
```

- [ ] **Step 2: Run it — expect FAIL** (resolver doesn't copy the fields yet → assertion fails / null).
Run: `ANDROID_HOME=~/Android/Sdk ./gradlew :feature:audiobookshelf:testDebugUnitTest --tests "com.orator.feature.audiobookshelf.data.AbsBookDetailResolverTest"`
Expected: FAIL.

- [ ] **Step 3: Implement.** In `AbsBookDetailResolver.ensureDetails`, change the final upsert to copy the new fields:

```kotlin
bookDao.upsert(listOf(book.copy(
    sourceKind = d.sourceKind, sourceUri = d.sourceUri,
    description = d.description, series = d.series,
)))
```

- [ ] **Step 4: Run the test — expect PASS** (existing resolver tests still pass).
Run: `ANDROID_HOME=~/Android/Sdk ./gradlew :feature:audiobookshelf:testDebugUnitTest --tests "com.orator.feature.audiobookshelf.data.AbsBookDetailResolverTest"`
Expected: PASS.

- [ ] **Step 5: Commit.**
```bash
git add feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/data/AbsBookDetailResolver.kt \
        feature/audiobookshelf/src/test/java/com/orator/feature/audiobookshelf/data/AbsBookDetailResolverTest.kt
git commit -m "feat(audiobookshelf): resolver persists description + series"
```

### Task 1.5: Chunk gate

- [ ] Run `ANDROID_HOME=~/Android/Sdk ./gradlew test lint assembleDebug` → expect `BUILD SUCCESSFUL`. Fix any fallout before moving on.

---

## Chunk 2: Play preparer + action-state mapping (the logic, fully testable)

**File structure for this chunk:**
- Create: `feature/audiobooks/.../data/AudiobookPlayPreparer.kt` — resolve + build cold-start `PlayRequest`.
- Create: `feature/audiobooks/.../AudiobookActions.kt` — pure action-state function + types.
- Test: `feature/audiobooks/src/test/.../data/AudiobookPlayPreparerTest.kt` (new, Robolectric + Room — the P0 regression), `feature/audiobooks/src/test/.../AudiobookActionsTest.kt` (new, pure).

### Task 2.1: Pure action-state mapping

**Files:**
- Create: `feature/audiobooks/src/main/java/com/orator/feature/audiobooks/AudiobookActions.kt`
- Test: `feature/audiobooks/src/test/java/com/orator/feature/audiobooks/AudiobookActionsTest.kt`

- [ ] **Step 1: Write the failing test.**

```kotlin
package com.orator.feature.audiobooks

import com.orator.core.model.BookOrigin
import com.orator.core.model.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Test

class AudiobookActionsTest {
    @Test fun `local book plays, no download affordance`() =
        assertEquals(BookActions(BookAction.PLAY_RESUME, null),
            bookActions(BookOrigin.LOCAL, DownloadState.NONE))

    @Test fun `abs not downloaded offers stream + download`() =
        assertEquals(BookActions(BookAction.STREAM, BookAction.DOWNLOAD),
            bookActions(BookOrigin.ABS, DownloadState.NONE))

    @Test fun `abs downloading offers stream + cancel`() =
        assertEquals(BookActions(BookAction.STREAM, BookAction.CANCEL_DOWNLOAD),
            bookActions(BookOrigin.ABS, DownloadState.DOWNLOADING))

    @Test fun `abs downloaded offers play + remove`() =
        assertEquals(BookActions(BookAction.PLAY_RESUME, BookAction.REMOVE_DOWNLOAD),
            bookActions(BookOrigin.ABS, DownloadState.DOWNLOADED))
}
```

- [ ] **Step 2: Run it — expect FAIL** (unresolved references).
Run: `ANDROID_HOME=~/Android/Sdk ./gradlew :feature:audiobooks:testDebugUnitTest --tests "com.orator.feature.audiobooks.AudiobookActionsTest"`
Expected: compile failure.

- [ ] **Step 3: Implement.**

```kotlin
package com.orator.feature.audiobooks

import com.orator.core.model.BookOrigin
import com.orator.core.model.DownloadState

enum class BookAction { PLAY_RESUME, STREAM, DOWNLOAD, CANCEL_DOWNLOAD, REMOVE_DOWNLOAD }

/** Primary + optional secondary action button for a book's detail screen. */
data class BookActions(val primary: BookAction, val secondary: BookAction?)

/**
 * Which action buttons a book shows. PLAY_RESUME's "Play" vs "Resume" label is a UI concern
 * (positionMs > 0), not encoded here. LOCAL books have no download affordance.
 */
fun bookActions(origin: BookOrigin, downloadState: DownloadState): BookActions = when {
    origin == BookOrigin.LOCAL -> BookActions(BookAction.PLAY_RESUME, null)
    downloadState == DownloadState.DOWNLOADED -> BookActions(BookAction.PLAY_RESUME, BookAction.REMOVE_DOWNLOAD)
    downloadState == DownloadState.DOWNLOADING -> BookActions(BookAction.STREAM, BookAction.CANCEL_DOWNLOAD)
    else -> BookActions(BookAction.STREAM, BookAction.DOWNLOAD)   // ABS · NONE
}
```

- [ ] **Step 4: Run the test — expect PASS.**
Run: `ANDROID_HOME=~/Android/Sdk ./gradlew :feature:audiobooks:testDebugUnitTest --tests "com.orator.feature.audiobooks.AudiobookActionsTest"`
Expected: PASS.

- [ ] **Step 5: Commit.**
```bash
git add feature/audiobooks/src/main/java/com/orator/feature/audiobooks/AudiobookActions.kt \
        feature/audiobooks/src/test/java/com/orator/feature/audiobooks/AudiobookActionsTest.kt
git commit -m "feat(audiobooks): pure book action-state mapping"
```

### Task 2.2: `AudiobookPlayPreparer` — resolve then build (the P0 regression test)

**Files:**
- Create: `feature/audiobooks/src/main/java/com/orator/feature/audiobooks/data/AudiobookPlayPreparer.kt`
- Test: `feature/audiobooks/src/test/java/com/orator/feature/audiobooks/data/AudiobookPlayPreparerTest.kt`

Rationale: `PlaybackConnection` is a `final` class needing a `MediaController`, so it can't be faked in a unit test. We extract the resolve-then-build logic (the thing that was broken) into a plain class and assert it returns a NON-EMPTY `PlayRequest` after resolving — mirroring how `AudiobookPlayRequestFactory` is tested.

- [ ] **Step 1: Write the failing test** (real in-memory Room + a fake resolver that fills sourceUri/chapters, mimicking the real ABS resolve). Follow `AudiobookPlayRequestResolverTest` for setup.

```kotlin
package com.orator.feature.audiobooks.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.orator.core.database.BookEntity
import com.orator.core.database.ChapterEntity
import com.orator.core.database.OratorDatabase
import com.orator.core.database.SourceKind
import com.orator.core.model.BookDetailResolver
import com.orator.core.model.BookOrigin
import com.orator.core.playback.PlayerPrefs
import kotlinx.coroutines.runBlocking
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
class AudiobookPlayPreparerTest {
    private lateinit var db: OratorDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), OratorDatabase::class.java,
        ).allowMainThreadQueries().build()
    }
    @After fun tearDown() = db.close()

    // Fake resolver: an un-resolved ABS book (sourceUri "") gets a real file URI + a chapter,
    // exactly as AbsBookDetailResolver would.
    private fun resolver() = object : BookDetailResolver {
        override fun handles(origin: BookOrigin) = origin == BookOrigin.ABS
        override suspend fun ensureDetails(bookId: String) {
            val b = db.bookDao().getById(bookId) ?: return
            if (b.sourceUri.isNotBlank()) return
            db.chapterDao().replaceForBook(bookId, listOf(
                ChapterEntity(bookId, 0, "Ch1", "https://abs/stream", 0, 60_000),
            ))
            db.bookDao().upsert(listOf(b.copy(sourceUri = "https://abs/stream")))
        }
    }

    private fun preparer() = AudiobookPlayPreparer(
        repository = error("use the real repo or a thin wrapper"),  // see Step 3 note
        detailResolvers = setOf(resolver()),
    )

    @Test fun `prepare resolves an un-resolved ABS book and builds a non-empty queue`() = runBlocking {
        db.bookDao().upsert(listOf(BookEntity(
            id = "abs:1", title = "B", author = null, coverPath = null,
            sourceUri = "", sourceKind = SourceKind.SINGLE_FILE, durationMs = 60_000,
            addedAtUtc = 0, origin = BookOrigin.ABS, absItemId = "1",
        )))
        val req = preparer().prepare("abs:1", PlayerPrefs())
        // resolved:
        assertTrue(db.bookDao().getById("abs:1")!!.sourceUri.isNotBlank())
        // non-empty queue with a real uri (the P0 was an empty uri):
        assertTrue(req!!.items.isNotEmpty())
        assertEquals("https://abs/stream", req.items.first().uri)
    }
}
```

> **Note for Step 3:** `AudiobookPlayPreparer` depends on `AudiobookRepository`, which needs `Context` + DAOs + importer/prefs — heavy for a unit test. Make the preparer depend on the **DAOs directly** (`BookDao` + `ChapterDao`), not the repository, so the test can pass `db.bookDao()`/`db.chapterDao()`. Update the test's `preparer()` to `AudiobookPlayPreparer(db.bookDao(), db.chapterDao(), setOf(resolver()))`. (The repository already delegates to these DAOs, so no behavior changes.)

- [ ] **Step 2: Run it — expect FAIL** (class doesn't exist).
Run: `ANDROID_HOME=~/Android/Sdk ./gradlew :feature:audiobooks:testDebugUnitTest --tests "com.orator.feature.audiobooks.data.AudiobookPlayPreparerTest"`
Expected: compile failure.

- [ ] **Step 3: Implement** `AudiobookPlayPreparer` (DAO-based, takes a `PlayerPrefs` snapshot so it stays unit-testable without DataStore):

```kotlin
package com.orator.feature.audiobooks.data

import com.orator.core.database.BookDao
import com.orator.core.database.ChapterDao
import com.orator.core.model.BookDetailResolver
import com.orator.core.model.MediaType
import com.orator.core.playback.PlayRequest
import com.orator.core.playback.PlayerPrefs
import com.orator.core.playback.SmartRewind
import javax.inject.Inject

/**
 * Resolves a book's playable detail (origin-matched resolver) then builds a cold-start PlayRequest
 * with smart-rewind. The resolve step is what makes streaming an un-downloaded ABS book work —
 * every play path must go through here (or AudiobookPlayRequestFactory for playlists).
 */
class AudiobookPlayPreparer @Inject constructor(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val detailResolvers: Set<@JvmSuppressWildcards BookDetailResolver>,
) {
    suspend fun prepare(bookId: String, prefs: PlayerPrefs): PlayRequest? {
        val initial = bookDao.getById(bookId) ?: return null
        detailResolvers.firstOrNull { it.handles(initial.origin) }?.ensureDetails(bookId)
        val book = bookDao.getById(bookId) ?: return null
        val rewind = if (prefs.smartRewind[MediaType.AUDIOBOOK] == true && book.lastPlayedAtMs > 0) {
            SmartRewind.rewindMs(System.currentTimeMillis() - book.lastPlayedAtMs)
        } else {
            0
        }
        return QueueBuilder.build(book, chapterDao.getForBook(bookId), (book.positionMs - rewind).coerceAtLeast(0))
    }
}
```
*Verify imports against the codebase:* `PlayRequest`, `PlayerPrefs`, `SmartRewind` live in `core.playback`; `BookDao.getById`/`ChapterDao.getForBook` already exist (used by `AudiobookPlayRequestFactory`).

- [ ] **Step 4: Run the test — expect PASS.**
Run: `ANDROID_HOME=~/Android/Sdk ./gradlew :feature:audiobooks:testDebugUnitTest --tests "com.orator.feature.audiobooks.data.AudiobookPlayPreparerTest"`
Expected: PASS.

- [ ] **Step 5: Commit.**
```bash
git add feature/audiobooks/src/main/java/com/orator/feature/audiobooks/data/AudiobookPlayPreparer.kt \
        feature/audiobooks/src/test/java/com/orator/feature/audiobooks/data/AudiobookPlayPreparerTest.kt
git commit -m "feat(audiobooks): AudiobookPlayPreparer (resolve-then-build) + P0 regression test"
```

### Task 2.3: Chunk gate

- [ ] Run `ANDROID_HOME=~/Android/Sdk ./gradlew test lint assembleDebug` → expect `BUILD SUCCESSFUL`.

---

## Chunk 3: Detail ViewModel + screen + nav wiring + library-grid changes

**File structure for this chunk:**
- Create: `feature/audiobooks/.../AudiobookDetailViewModel.kt`, `AudiobookDetailScreen.kt`.
- Modify: `feature/audiobooks/.../AudiobooksRoutes.kt` (detail route), `AudiobooksFeatureEntry.kt` (register + pass `onOpenBook`), `AudiobookListScreen.kt` (tap → open; remove badge + "not started"), `AudiobookListViewModel.kt` (drop `onPlayBook`/download handlers).

This chunk's UI has no unit tests (Compose UI + a VM that wires already-tested pieces); it's gated by `assembleDebug` + the chunk gate, then device-verified after the plan.

### Task 3.1: `AudiobookDetailViewModel`

**Files:**
- Create: `feature/audiobooks/src/main/java/com/orator/feature/audiobooks/AudiobookDetailViewModel.kt`

- [ ] **Step 1: Implement.** (No new unit test — its play path is covered by `AudiobookPlayPreparerTest`, action mapping by `AudiobookActionsTest`; the rest is wiring.)

```kotlin
package com.orator.feature.audiobooks

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orator.core.database.BookEntity
import com.orator.core.database.ChapterEntity
import com.orator.core.model.BookDetailResolver
import com.orator.core.model.BookDownloadController
import com.orator.core.playback.PlaybackConnection
import com.orator.core.playback.PlayerPreferences
import com.orator.core.playback.ids.AudiobookMediaId
import com.orator.feature.audiobooks.data.AudiobookPlayPreparer
import com.orator.feature.audiobooks.data.AudiobookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AudiobookDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: AudiobookRepository,
    private val preparer: AudiobookPlayPreparer,
    private val detailResolvers: Set<@JvmSuppressWildcards BookDetailResolver>,
    private val downloadControllers: Set<@JvmSuppressWildcards BookDownloadController>,
    private val playbackConnection: PlaybackConnection,
    private val playerPreferences: PlayerPreferences,
) : ViewModel() {

    // Nav decodes path args already; the route is built with Uri.encode (see AudiobooksFeatureEntry),
    // so read it raw here.
    private val bookId: String = checkNotNull(savedStateHandle["bookId"])

    val book: StateFlow<BookEntity?> = repository.observeBook(bookId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val chapters: StateFlow<List<ChapterEntity>> = repository.observeChapters(bookId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _resolving = MutableStateFlow(true)
    val resolving: StateFlow<Boolean> = _resolving.asStateFlow()

    init {
        viewModelScope.launch {
            val b = repository.observeBook(bookId).first()
            if (b != null) detailResolvers.firstOrNull { it.handles(b.origin) }?.ensureDetails(bookId)
            _resolving.value = false
        }
    }

    /** Start (or resume) playback, then open the player. */
    fun onPlay(onOpenPlayer: () -> Unit) {
        viewModelScope.launch {
            val s = playbackConnection.state.value
            if (s.mediaId?.let { AudiobookMediaId.parse(it)?.bookId } == bookId) {
                if (!s.isPlaying) playbackConnection.playPause()
                onOpenPlayer(); return@launch
            }
            val req = preparer.prepare(bookId, playerPreferences.flow.first()) ?: return@launch
            playbackConnection.play(req)
            onOpenPlayer()
        }
    }

    fun onDownload() { book.value?.let { b -> controllerFor(b)?.enqueue(b.id) } }
    fun onCancelDownload() { book.value?.let { b -> controllerFor(b)?.cancel(b.id) } }
    fun onRemoveDownload() {
        val b = book.value ?: return
        val c = controllerFor(b) ?: return
        viewModelScope.launch { c.remove(b.id) }
    }

    private fun controllerFor(b: BookEntity) = downloadControllers.firstOrNull { it.handles(b.origin) }
}
```
*Verify against the codebase:* `BookDownloadController` exposes `handles`/`enqueue`/`cancel`/`remove` (the ABS provider wires `enqueueFn`/`cancelFn`/`removeFn`); `PlaybackConnection` exposes `state`, `playPause()`, `play(PlayRequest)`; `repository.observeChapters` exists.

*Nav-decode caution:* reading `savedStateHandle["bookId"]` raw relies on Navigation-Compose's default `StringType` percent-decoding the path arg (the route is built with `Uri.encode` in Task 3.3). No existing in-repo route exercises this, so **device-verify the decoded id is `abs:<uuid>`, not `abs%3A...`**. If it ever arrives still-encoded, the one-line fix is `private val bookId: String = Uri.decode(checkNotNull(savedStateHandle["bookId"]))`.

- [ ] **Step 2: Build to confirm it compiles.**
Run: `ANDROID_HOME=~/Android/Sdk ./gradlew :feature:audiobooks:compileDebugKotlin`
Expected: success.

- [ ] **Step 3: Commit.**
```bash
git add feature/audiobooks/src/main/java/com/orator/feature/audiobooks/AudiobookDetailViewModel.kt
git commit -m "feat(audiobooks): AudiobookDetailViewModel (resolve on open, play/download actions)"
```

### Task 3.2: `AudiobookDetailScreen`

**Files:**
- Create: `feature/audiobooks/src/main/java/com/orator/feature/audiobooks/AudiobookDetailScreen.kt`

- [ ] **Step 1: Implement** a minimal, scrollable screen following `PodcastDetailScreen`'s structure (`OnyxTopBar` + back, `OnyxTokens`, `ArtworkImage`/`CoverTile`, `TimeFormats`). Use `BookAction`/`bookActions` (Task 2.1) to choose buttons; origin-aware artwork (ABS → URL string, local → `File`). Render order: cover → title → author → `Series · <series>` (when non-null) → duration → progress stats → action buttons → synopsis (`book.description`, when non-null). Show a small spinner near synopsis/series while `resolving` is true and they're null.

```kotlin
@Composable
fun AudiobookDetailScreen(
    onOpenPlayer: () -> Unit,
    onBack: () -> Unit,
    viewModel: AudiobookDetailViewModel = hiltViewModel(),
) {
    val book by viewModel.book.collectAsStateWithLifecycle()
    val resolving by viewModel.resolving.collectAsStateWithLifecycle()
    val b = book
    Column(Modifier.fillMaxSize().background(OnyxTokens.Background)) {
        OnyxTopBar(title = b?.title.orEmpty(), leadingIcon = Icons.AutoMirrored.Filled.ArrowBack, onLeadingClick = onBack)
        if (b == null) return@Column
        val actions = bookActions(b.origin, b.downloadState)
        LazyColumn(contentPadding = PaddingValues(16.dp)) {
            item { /* cover (origin-aware), title, author */ }
            item { /* series line if b.series != null; duration; progress stats */ }
            item { ActionRow(actions, b, onPlay = { viewModel.onPlay(onOpenPlayer) },
                onDownload = viewModel::onDownload, onCancel = viewModel::onCancelDownload,
                onRemove = viewModel::onRemoveDownload) }
            item { /* synopsis: b.description, or a small spinner while resolving && b.description == null */ }
        }
    }
}
```
Fill in the `item { }` bodies with `Text`/`ArtworkImage`/`Button`s in the Onyx style (reuse `TimeFormats.timeLeft`/`clock`, the `Pill`/`Button` patterns from `PodcastDetailScreen`/`AudiobookListScreen`). Progress stats: when `b.positionMs > 0` show `"<pct>% · <timeLeft> left"` (+ last-played date via `b.lastPlayedAtMs` when > 0), else `"Not started"`. Primary button label: `if (b.positionMs > 0) "Resume" else "Play"` for PLAY_RESUME, `"Stream"` for STREAM.

- [ ] **Step 2: Build to confirm it compiles.**
Run: `ANDROID_HOME=~/Android/Sdk ./gradlew :feature:audiobooks:compileDebugKotlin`
Expected: success.

- [ ] **Step 3: Commit.**
```bash
git add feature/audiobooks/src/main/java/com/orator/feature/audiobooks/AudiobookDetailScreen.kt
git commit -m "feat(audiobooks): AudiobookDetailScreen (minimal info screen + actions)"
```

### Task 3.3: Route + nav wiring + flip the library tile

**Files:**
- Modify: `feature/audiobooks/.../AudiobooksRoutes.kt`, `AudiobooksFeatureEntry.kt`, `AudiobookListScreen.kt`, `AudiobookListViewModel.kt`

- [ ] **Step 1: Add the detail route helpers** to `AudiobooksRoutes.kt` (mirror `PodcastsRoutes`):

```kotlin
internal const val AudiobookDetailRoutePattern = "audiobooks/{bookId}"
internal fun audiobookDetailRoute(bookId: String) = "audiobooks/" + Uri.encode(bookId)
```
(Add `import android.net.Uri`.)

- [ ] **Step 2: Register the detail composable + pass `onOpenBook`** in `AudiobooksFeatureEntry.register`:

```kotlin
navGraphBuilder.composable(AudiobooksRoute) {
    AudiobookListScreen(
        onOpenBook = { bookId -> navController.navigate(audiobookDetailRoute(bookId)) },
        onAddToPlaylist = { bookId ->
            navController.navigate(CommonRoutes.addToPlaylist(MediaType.AUDIOBOOK.name, bookId))
        },
    )
}
navGraphBuilder.composable(AudiobookDetailRoutePattern) {
    AudiobookDetailScreen(
        onOpenPlayer = { navController.navigate(CommonRoutes.Player) },
        onBack = { navController.popBackStack() },
    )
}
```
(The `AudiobookListScreen` no longer needs `onOpenPlayer`.)

- [ ] **Step 3: Update `AudiobookListScreen`** — replace `onOpenPlayer`/play-on-tap with `onOpenBook`, and remove the badge + "not started":
  - Signature: `fun AudiobookListScreen(onOpenBook: (String) -> Unit, onAddToPlaylist: (String) -> Unit, viewModel: ... )`.
  - Tile `onClick = { onOpenBook(book.id) }` (drop the `viewModel.onPlayBook(...)` call and the `onOpenPlayer` param).
  - Delete the `if (book.origin == BookOrigin.ABS) { DownloadBadge(...) }` block and the `DownloadBadge` composable.
  - In `CoverTile(...)`, drop the `subLine = when { book.positionMs <= 0 -> "not started" ... }` "not started" arm — keep only the time-left line (pass `null`/empty when not started). Keep `progress`.
  - Remove now-unused imports (`DownloadState`, `CircleShape`, `Color`, the badge bits) as the compiler flags them.

- [ ] **Step 4: Slim `AudiobookListViewModel`** — delete `onPlayBook` and the `onDownload`/`onRemoveDownload` handlers and their now-unused deps (`playbackConnection`, `playerPreferences`, `downloadControllers`, and imports for `PlaybackConnection`/`PlayerPreferences`/`SmartRewind`/`AudiobookMediaId`/`QueueBuilder`/`MediaType`/`BookDownloadController`). The VM keeps `books`, `hasFolder`, `onFolderPicked`, `onRescan`.

- [ ] **Step 5: Build the whole app** (this is the cross-cutting wiring change).
Run: `ANDROID_HOME=~/Android/Sdk ./gradlew :app:assembleDebug`
Expected: success. (No in-repo test constructs `AudiobookListViewModel` — `AudiobookPlaylistContributionsTest` builds the factory/resolver, not the VM — so the slimming is test-safe; this build is the safety check.)

- [ ] **Step 6: Commit.**
```bash
git add feature/audiobooks/src/main/java/com/orator/feature/audiobooks/AudiobooksRoutes.kt \
        feature/audiobooks/src/main/java/com/orator/feature/audiobooks/AudiobooksFeatureEntry.kt \
        feature/audiobooks/src/main/java/com/orator/feature/audiobooks/AudiobookListScreen.kt \
        feature/audiobooks/src/main/java/com/orator/feature/audiobooks/AudiobookListViewModel.kt
git commit -m "feat(audiobooks): tap cover -> detail screen; drop badge, not-started label, play-on-tap"
```

### Task 3.4: Chunk gate

- [ ] Run `ANDROID_HOME=~/Android/Sdk ./gradlew test lint assembleDebug` → expect `BUILD SUCCESSFUL`. Fix any test fallout from the VM slimming.

---

## Chunk 4: Player tweaks — pager order + dual-bar order

**File structure for this chunk:**
- Modify: `feature/player/.../PlayerScreen.kt` (pager order for books).
- Modify: `core/designsystem/.../components/DualProgressBars.kt` (render order).

These are UI-only; gated by `assembleDebug`, device-verified after the plan.

### Task 4.1: Pager order Cover → Chapters → Bookmarks

**Files:**
- Modify: `feature/player/src/main/java/com/orator/feature/player/PlayerScreen.kt` (the `is NowPlayingContent.Book -> when (page)` block, ~lines 194-215)

- [ ] **Step 1: Swap the page bodies, handling the no-chapters case.** `pageCount` is `if (content.chapters.isEmpty()) 2 else 3`, so page index 1 must be Bookmarks when there are no chapters (otherwise an empty Chapters page appears):

```kotlin
is NowPlayingContent.Book -> when (page) {
    0 -> CoverPage(
        content.book.coverPath?.let { if (content.book.origin == BookOrigin.ABS) it else File(it) },
        content.book.title,
    )
    1 -> if (content.chapters.isEmpty()) {
        BookmarksPage(
            bookmarks = content.bookmarks, chapters = content.chapters,
            sourceKind = content.book.sourceKind,
            currentGlobalMs = viewModel.currentGlobalMs(content),
            onBookmarkTap = viewModel::onBookmarkTap, onDeleteBookmark = viewModel::onDeleteBookmark,
            onAddBookmark = viewModel::onAddBookmark,
        )
    } else {
        ChaptersPage(
            chapters = content.chapters, sourceKind = content.book.sourceKind,
            currentChapterIndex = chapterUi?.index,
            onChapterTap = { i -> viewModel.onSeekTarget(PlayerChapters.tap(content.chapters, content.book.sourceKind, i)) },
        )
    }
    else -> BookmarksPage(   // page 2 only exists when chapters are present
        bookmarks = content.bookmarks, chapters = content.chapters,
        sourceKind = content.book.sourceKind,
        currentGlobalMs = viewModel.currentGlobalMs(content),
        onBookmarkTap = viewModel::onBookmarkTap, onDeleteBookmark = viewModel::onDeleteBookmark,
        onAddBookmark = viewModel::onAddBookmark,
    )
}
```
(The `BookmarksPage`/`ChaptersPage` argument lists must match the current call sites verbatim — copy them from the existing code rather than retyping.) Consider extracting `bookmarksPage`/`chaptersPage` local `@Composable` lambdas to avoid the duplication, if it reads cleaner.

- [ ] **Step 2: Build.**
Run: `ANDROID_HOME=~/Android/Sdk ./gradlew :feature:player:compileDebugKotlin`
Expected: success.

- [ ] **Step 3: Commit.**
```bash
git add feature/player/src/main/java/com/orator/feature/player/PlayerScreen.kt
git commit -m "feat(player): pager order cover -> chapters -> bookmarks for books"
```

### Task 4.2: Dual progress bars — book on top, chapter on bottom

**Files:**
- Modify: `core/designsystem/src/main/java/com/orator/core/designsystem/components/DualProgressBars.kt`

- [ ] **Step 1: Reorder the render blocks** so the whole-item (book) bar renders first (top) and the chapter bar second (bottom). This is a component-internal reorder — do NOT swap the call-site `chapter`/`item` arguments (that would also swap styling). Keep each bar's identity: book = `Accent` + ticks; chapter = `AccentBright`. Move the thumb to the book bar (the always-present one):

```kotlin
Column(modifier = modifier.fillMaxWidth().padding(horizontal = 26.dp)) {
    BarLabels(item)
    SeekBar(
        fraction = item.fraction, ticks = item.ticks,
        fillColor = OnyxTokens.Accent, showThumb = true, onSeek = onItemSeek,
    )
    if (chapter != null) {
        BarLabels(chapter)
        SeekBar(
            fraction = chapter.fraction, ticks = chapter.ticks,
            fillColor = OnyxTokens.AccentBright, showThumb = true, onSeek = onChapterSeek,
        )
    }
}
```
Update the KDoc on `DualProgressBars` to describe the new order ("whole-item bar with ticks above an optional chapter bar"). The `PlayerScreen` call site does not change (still passes `chapter`/`item`); only this component's internal order does. The chapter ticks remain on the `item` (book) bar via `item.ticks`, as before.

*Thumb note:* today only one thumb shows (chapter bar always; item bar only when `chapter == null`). The snippet above sets `showThumb = true` on both, giving two thumbs. Device-verify this reads well; if it looks busy, keep the thumb on the book bar (`item`) and set the chapter bar's `showThumb = false`.

- [ ] **Step 2: Build.**
Run: `ANDROID_HOME=~/Android/Sdk ./gradlew :core:designsystem:compileDebugKotlin`
Expected: success.

- [ ] **Step 3: Commit.**
```bash
git add core/designsystem/src/main/java/com/orator/core/designsystem/components/DualProgressBars.kt
git commit -m "feat(designsystem): dual progress bars book-on-top, chapter-on-bottom"
```

### Task 4.3: Final chunk gate + device verification

- [ ] Run `ANDROID_HOME=~/Android/Sdk ./gradlew test lint assembleDebug` → expect `BUILD SUCCESSFUL`.
- [ ] Install (`~/Android/Sdk/platform-tools/adb -t <id> install -r app/build/outputs/apk/debug/app-debug.apk`) and device-verify (note: the v9 destructive bump wipes the library — re-pick the SAF folder + re-sync ABS first):
  - Tap a local book → detail screen (cover/title/author/duration/progress, Play/Resume) → Play → player.
  - Tap a never-downloaded ABS book → detail screen shows synopsis + series after a beat → **Stream plays** (the P0 fix; confirm via logcat no `FileDataSource` ENOENT) → player cover shows.
  - Download from the detail screen → buttons flip to Play/Resume + Remove → offline play.
  - Player: swipe order is Cover → Chapters → Bookmarks; progress bars are book-on-top, chapter-on-bottom (chapter ticks still on the book bar).
  - Library grid: no download badge, no "not started" label; long-press still adds to a playlist; playlist taps still play directly.

---

## Done

After Chunk 4 verifies: **Use superpowers:finishing-a-development-branch** to verify tests, present options, and finish (likely a PR to `main` after PR #11 has merged; rebase this branch onto `main` first).
