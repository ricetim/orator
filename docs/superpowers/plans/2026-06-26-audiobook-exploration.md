# Audiobook Exploration Restyle Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restyle audiobook browsing — a single grid with switchable sort/grouping (Recently added default / Title / Author / Series via a top-bar dropdown), a multi-category local search screen (Books / Series / Authors), a filtered cover grid for series/author results, and a passive offline badge on downloaded tiles.

**Architecture:** All exploration logic is pure Kotlin in `feature:audiobooks` (`BookExplore`) operating on `BookEntity` from the existing `repository.observeBooks()` Flow — no new SQL in `core:database`. The only data-layer change is capturing the real ABS `addedAt` and `seriesName` at catalog sync in `feature:audiobookshelf`. Two hand-rolled `OnyxIcons` vectors avoid the material-icons-extended dependency.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Hilt, Room, DataStore, kotlinx.serialization, JUnit (plain, no Robolectric needed for the pure logic).

**Spec:** `docs/superpowers/specs/2026-06-26-audiobook-exploration-design.md`

**Branch:** `audiobook-exploration` (already created off `main`, post PR #12).

---

## Conventions for every chunk

- **Gate command** (run at the end of each chunk, must be green before moving on):
  ```bash
  ANDROID_HOME=~/Android/Sdk ./gradlew test lint assembleDebug
  ```
- **Run one test class** while iterating:
  ```bash
  ANDROID_HOME=~/Android/Sdk ./gradlew :feature:audiobooks:testDebugUnitTest --tests "com.orator.feature.audiobooks.BookExploreTest"
  ```
- **TDD:** write the failing test first, watch it fail, implement minimally, watch it pass, commit.
- **Commits:** stage explicit paths only — **never** `git add -A`/`git add .` (the repo has untracked private files). End every commit message with:
  ```
  Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
  ```
- **Tests are plain JUnit** in `src/test/java/...`. The pure-logic objects (`BookExplore`, mapper, reconciler) need no Android runtime. ViewModels/Compose screens are **not** unit-tested here (they only wire tested logic + nav); they're covered by the `lint`/`assembleDebug` gate, mirroring how `AudiobookListViewModel` currently has no test.

## Decisions locked in during planning (deviations from the spec, with rationale)

1. **`parseSeries` returns `Pair<String, Double?>`** (not `Int?` as the spec sketched): ABS sequences can be decimals (`"2.5"`), so the sub-sort key is parsed with `toDoubleOrNull()`. Same behavior for whole numbers; just doesn't crash/misorder on decimals.
2. **Title/author/name sorting uses the existing `NaturalOrder` comparator** (`feature/audiobooks/.../data/NaturalOrder.kt`) — case-insensitive *and* numeric-aware (`"Book 2" < "Book 10"`), a strict superset of the spec's "case-insensitive asc".
3. **`addedAtUtc` uses a `0L` "server-omitted" sentinel** so the reconciler can tell a real server `addedAt` from a fallback. `AbsBookMapper` emits `item.addedAt ?: 0L`; `AbsCatalogReconciler` resolves the sentinel (prefer server, else previous, else injected `now`). This is what lets the spec's "prefer server, keep previous only when omitted" behavior be expressed and tested.
4. **Routes use a distinct top segment** (`audiobook-search`, `audiobook-filter/...`) rather than `audiobooks/search` etc. The existing detail route is `audiobooks/{bookId}` (a single-segment wildcard); `audiobooks/search` would be ambiguous with it. The podcast code documents this exact pitfall (`PodcastSearchRoute = "podcast-search"`). Still defined locally in `AudiobooksRoutes.kt`, still `Uri.encode`-ing the value like `audiobookDetailRoute`.

---

## Chunk 1: ABS data capture (addedAt + seriesName)

Capture the real ABS `addedAt` (so "Recently added" is meaningful) and the series name at catalog-sync time (so SERIES grouping/search work before a book is opened). Pure changes in `feature:audiobookshelf`, fully unit-tested.

**Files:**
- Modify: `feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/data/AbsDtos.kt`
- Modify: `feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/data/AbsBookMapper.kt`
- Modify: `feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/data/AbsCatalogReconciler.kt`
- Test: `feature/audiobookshelf/src/test/java/com/orator/feature/audiobookshelf/data/AbsBookMapperTest.kt` (extend)
- Test: `feature/audiobookshelf/src/test/java/com/orator/feature/audiobookshelf/data/AbsCatalogReconcilerTest.kt` (extend + fix one existing test)

### Task 1.1: DTO fields

- [ ] **Step 1: Add `addedAt` to `AbsLibraryItem` and `seriesName` to `AbsMetadata`.**

In `AbsDtos.kt`, change:
```kotlin
@Serializable data class AbsLibraryItem(
    val id: String,
    val media: AbsMedia = AbsMedia(),
    val addedAt: Long? = null,               // epoch-ms; item-level, present in minified payload
)
```
and add `seriesName` to `AbsMetadata`:
```kotlin
@Serializable data class AbsMetadata(
    val title: String = "",
    @SerialName("authorName") val authorName: String? = null,
    val description: String? = null,
    val series: List<AbsSeries> = emptyList(),
    @SerialName("seriesName") val seriesName: String? = null,  // minified flat string, e.g. "Foundation #2"
)
```
`AbsJson` already has `ignoreUnknownKeys = true`, so absent fields degrade to the defaults.

- [ ] **Step 2: Compile-check.**
  Run: `ANDROID_HOME=~/Android/Sdk ./gradlew :feature:audiobookshelf:compileDebugKotlin`
  Expected: BUILD SUCCESSFUL (no behavior change yet).

### Task 1.2: `AbsBookMapper` writes addedAt + series

- [ ] **Step 1: Write failing tests.** Append to `AbsBookMapperTest.kt`:
```kotlin
    @Test fun `maps server addedAt into addedAtUtc`() {
        val item = AbsLibraryItem(id = "li1", addedAt = 1_700_000_000_000)
        assertEquals(1_700_000_000_000, AbsBookMapper.toBook(item, "s", "https://x").addedAtUtc)
    }

    @Test fun `absent addedAt becomes the 0L sentinel`() {
        val item = AbsLibraryItem(id = "li1")
        assertEquals(0L, AbsBookMapper.toBook(item, "s", "https://x").addedAtUtc)
    }

    @Test fun `series taken from minified seriesName`() {
        val item = AbsLibraryItem(
            id = "li1",
            media = AbsMedia(metadata = AbsMetadata(seriesName = "Foundation #2")),
        )
        assertEquals("Foundation #2", AbsBookMapper.toBook(item, "s", "https://x").series)
    }

    @Test fun `first series kept when book is in several`() {
        val item = AbsLibraryItem(
            id = "li1",
            media = AbsMedia(metadata = AbsMetadata(seriesName = "Foundation #2, Empire #1")),
        )
        assertEquals("Foundation #2", AbsBookMapper.toBook(item, "s", "https://x").series)
    }

    @Test fun `blank seriesName yields null series`() {
        val item = AbsLibraryItem(id = "li1", media = AbsMedia(metadata = AbsMetadata(seriesName = "  ")))
        assertEquals(null, AbsBookMapper.toBook(item, "s", "https://x").series)
    }
```
(`assertEquals`/`Test` are already imported in this file.)

- [ ] **Step 2: Run to verify failure.**
  Run: `ANDROID_HOME=~/Android/Sdk ./gradlew :feature:audiobookshelf:testDebugUnitTest --tests "com.orator.feature.audiobookshelf.data.AbsBookMapperTest"`
  Expected: FAIL — `addedAtUtc` is `System.currentTimeMillis()`, `series` is unset (null but for the wrong reason; the seriesName tests fail because the field isn't read).

- [ ] **Step 3: Implement.** In `AbsBookMapper.kt`, inside `toBook`, before the `return`. **Note:** `val md = item.media.metadata` and `val multi = item.media.numAudioFiles > 1` already exist at the top of `toBook` — do **not** re-declare them; only add the `series` line:
```kotlin
        val series = md.seriesName?.substringBefore(",")?.trim()?.takeIf { it.isNotBlank() }
```
and change the entity construction:
```kotlin
            addedAtUtc = item.addedAt ?: 0L,        // 0 = server omitted; reconciler resolves it
            ...
            series = series,
```
(Keep all other fields. `series` is a `BookEntity` constructor arg; add it.)

- [ ] **Step 4: Run to verify pass.**
  Run: `ANDROID_HOME=~/Android/Sdk ./gradlew :feature:audiobookshelf:testDebugUnitTest --tests "com.orator.feature.audiobookshelf.data.AbsBookMapperTest"`
  Expected: PASS (all, including the two pre-existing tests).

- [ ] **Step 5: Commit.**
```bash
git add feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/data/AbsDtos.kt \
        feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/data/AbsBookMapper.kt \
        feature/audiobookshelf/src/test/java/com/orator/feature/audiobookshelf/data/AbsBookMapperTest.kt
git commit -m "feat(abs): map server addedAt and series at catalog sync"
```

### Task 1.3: `AbsCatalogReconciler` resolves the addedAt sentinel

- [ ] **Step 1: Fix the existing test + add new ones.** In `AbsCatalogReconcilerTest.kt`:

Replace the existing `re-sync preserves the original addedAtUtc` test with:
```kotlin
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
```
(The `abs(...)` helper already has an `added: Long = 0` param.)

- [ ] **Step 2: Run to verify failure.**
  Run: `ANDROID_HOME=~/Android/Sdk ./gradlew :feature:audiobookshelf:testDebugUnitTest --tests "com.orator.feature.audiobookshelf.data.AbsCatalogReconcilerTest"`
  Expected: FAIL — `prefers the server addedAt` returns 111 (old behavior); `now` param doesn't exist yet (won't compile).

- [ ] **Step 3: Implement.** In `AbsCatalogReconciler.kt`, change the signature and the two addedAt sites:
```kotlin
    fun reconcile(
        existing: List<BookEntity>,
        incoming: List<BookEntity>,
        now: Long = System.currentTimeMillis(),
    ): ReconcileResult {
        val old = existing.associateBy { it.id }
        val upserts = incoming.map { fresh ->
            val serverAdded = fresh.addedAtUtc.takeIf { it > 0L }
            val prev = old[fresh.id] ?: return@map fresh.copy(addedAtUtc = serverAdded ?: now)
            fresh.copy(
                positionMs = prev.positionMs,
                lastPlayedAtMs = prev.lastPlayedAtMs,
                speedOverride = prev.speedOverride,
                downloadState = prev.downloadState,
                addedAtUtc = serverAdded ?: prev.addedAtUtc,   // prefer server; else keep first-seen
                sourceUri = if (prev.downloadState == DownloadState.DOWNLOADED) prev.sourceUri else fresh.sourceUri,
            )
        }
        val incomingIds = incoming.map { it.id }.toSet()
        val deletes = existing.map { it.id }.filter { it !in incomingIds }
        return ReconcileResult(upserts, deletes)
    }
```

- [ ] **Step 4: Run to verify pass.**
  Run: `ANDROID_HOME=~/Android/Sdk ./gradlew :feature:audiobookshelf:testDebugUnitTest --tests "com.orator.feature.audiobookshelf.data.AbsCatalogReconcilerTest"`
  Expected: PASS. (The existing `new items…`, `downloaded books…` tests still pass; `AbsRepository.sync` calls `reconcile(existing, incoming)` with the default `now`, so it's unaffected.)

- [ ] **Step 5: Gate + commit.**
```bash
ANDROID_HOME=~/Android/Sdk ./gradlew test lint assembleDebug
git add feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/data/AbsCatalogReconciler.kt \
        feature/audiobookshelf/src/test/java/com/orator/feature/audiobookshelf/data/AbsCatalogReconcilerTest.kt
git commit -m "feat(abs): reconciler prefers server addedAt, keeps prev when omitted"
```

---

## Chunk 2: Exploration core (`BookExplore`, pure)

The testable heart of the feature: sort, group, search, filter — all pure functions over `List<BookEntity>`. No Android, no UI.

**Files:**
- Create: `feature/audiobooks/src/main/java/com/orator/feature/audiobooks/BookSortMode.kt`
- Create: `feature/audiobooks/src/main/java/com/orator/feature/audiobooks/BookExplore.kt`
- Test: `feature/audiobooks/src/test/java/com/orator/feature/audiobooks/BookExploreTest.kt`

### Task 2.1: `BookSortMode` enum

- [ ] **Step 1: Create the enum.** `BookSortMode.kt`:
```kotlin
package com.orator.feature.audiobooks

/** How the library grid is ordered/grouped. Persisted by AudiobooksPrefs; default RECENT. */
enum class BookSortMode { RECENT, TITLE, AUTHOR, SERIES }
```
- [ ] **Step 2: Compile-check.**
  Run: `ANDROID_HOME=~/Android/Sdk ./gradlew :feature:audiobooks:compileDebugKotlin`
  Expected: BUILD SUCCESSFUL.

### Task 2.2: `BookExplore.parseSeries` + sort

- [ ] **Step 1: Write the failing test.** Create `BookExploreTest.kt`:
```kotlin
package com.orator.feature.audiobooks

import com.orator.core.database.BookEntity
import com.orator.core.database.SourceKind
import com.orator.core.model.BookOrigin
import org.junit.Assert.assertEquals
import org.junit.Test

class BookExploreTest {
    private fun book(
        id: String, title: String, author: String? = null, series: String? = null,
        added: Long = 0,
    ) = BookEntity(
        id = id, title = title, author = author, coverPath = null, sourceUri = "",
        sourceKind = SourceKind.SINGLE_FILE, durationMs = 0, addedAtUtc = added,
        origin = BookOrigin.ABS, series = series,
    )

    @Test fun `parseSeries splits name and numeric sequence`() {
        assertEquals("Foundation" to 2.0, BookExplore.parseSeries("Foundation #2"))
    }

    @Test fun `parseSeries handles decimal sequence`() {
        assertEquals("Foundation" to 2.5, BookExplore.parseSeries("Foundation #2.5"))
    }

    @Test fun `parseSeries with no marker has null sequence`() {
        assertEquals("Standalone Name" to null, BookExplore.parseSeries("Standalone Name"))
    }

    @Test fun `sort RECENT orders by addedAt descending`() {
        val out = BookExplore.sort(
            listOf(book("a", "A", added = 100), book("b", "B", added = 300), book("c", "C", added = 200)),
            BookSortMode.RECENT,
        )
        assertEquals(listOf("b", "c", "a"), out.map { it.id })
    }

    @Test fun `sort TITLE is case-insensitive natural order`() {
        val out = BookExplore.sort(
            listOf(book("x", "Book 10"), book("y", "book 2"), book("z", "Apple")),
            BookSortMode.TITLE,
        )
        assertEquals(listOf("z", "y", "x"), out.map { it.id })   // Apple, book 2, Book 10
    }
}
```
- [ ] **Step 2: Run to verify failure.**
  Run: `ANDROID_HOME=~/Android/Sdk ./gradlew :feature:audiobooks:testDebugUnitTest --tests "com.orator.feature.audiobooks.BookExploreTest"`
  Expected: FAIL — `BookExplore` unresolved.

- [ ] **Step 3: Implement `parseSeries` + `sort`.** Create `BookExplore.kt`:
```kotlin
package com.orator.feature.audiobooks

import com.orator.core.database.BookEntity
import com.orator.feature.audiobooks.data.NaturalOrder

/** A labelled run of books in AUTHOR/SERIES grouping. */
data class Section(val header: String, val books: List<BookEntity>)

/** A series/author match with how many books carry it. */
data class NamedHit(val name: String, val count: Int)

/** Multi-category local search output; any list may be empty. */
data class SearchResults(
    val books: List<BookEntity>,
    val series: List<NamedHit>,
    val authors: List<NamedHit>,
)

/** Pure, Android-free library exploration over BookEntity. Single source of sort/group/search. */
object BookExplore {
    private const val UNKNOWN_AUTHOR = "Unknown author"
    private const val STANDALONE = "Standalone"

    /** "Foundation #2" -> ("Foundation", 2.0); "Name" -> ("Name", null). */
    fun parseSeries(stored: String): Pair<String, Double?> {
        val marker = stored.lastIndexOf(" #")
        if (marker < 0) return stored.trim() to null
        val name = stored.substring(0, marker).trim()
        val seq = stored.substring(marker + 2).trim().toDoubleOrNull()
        return name to seq
    }

    fun sort(books: List<BookEntity>, mode: BookSortMode): List<BookEntity> = when (mode) {
        BookSortMode.RECENT -> books.sortedByDescending { it.addedAtUtc }
        else -> books.sortedWith(compareBy(NaturalOrder) { it.title })
    }
}
```
- [ ] **Step 4: Run to verify pass.**
  Run: `ANDROID_HOME=~/Android/Sdk ./gradlew :feature:audiobooks:testDebugUnitTest --tests "com.orator.feature.audiobooks.BookExploreTest"`
  Expected: PASS.

- [ ] **Step 5: Commit.**
```bash
git add feature/audiobooks/src/main/java/com/orator/feature/audiobooks/BookSortMode.kt \
        feature/audiobooks/src/main/java/com/orator/feature/audiobooks/BookExplore.kt \
        feature/audiobooks/src/test/java/com/orator/feature/audiobooks/BookExploreTest.kt
git commit -m "feat(audiobooks): BookExplore parseSeries + sort"
```

### Task 2.3: `BookExplore.group`

- [ ] **Step 1: Write the failing tests.** Append to `BookExploreTest.kt`:
```kotlin
    @Test fun `group AUTHOR sections are alphabetical with Unknown last`() {
        val sections = BookExplore.group(
            listOf(
                book("a", "A", author = "Zadie"),
                book("b", "B", author = null),
                book("c", "C", author = "Adichie"),
            ),
            BookSortMode.AUTHOR,
        )
        assertEquals(listOf("Adichie", "Zadie", "Unknown author"), sections.map { it.header })
    }

    @Test fun `group SERIES sub-sorts by sequence, Standalone last`() {
        val sections = BookExplore.group(
            listOf(
                book("a", "Second", series = "Foundation #2"),
                book("b", "Loner", series = null),
                book("c", "First", series = "Foundation #1"),
            ),
            BookSortMode.SERIES,
        )
        assertEquals(listOf("Foundation", "Standalone"), sections.map { it.header })
        assertEquals(listOf("c", "a"), sections.first().books.map { it.id })  // #1 before #2
    }
```
- [ ] **Step 2: Run to verify failure.** Expected: FAIL — `group` unresolved.
- [ ] **Step 3: Implement.** Add to `BookExplore`:
```kotlin
    fun group(books: List<BookEntity>, mode: BookSortMode): List<Section> = when (mode) {
        BookSortMode.AUTHOR -> {
            val (known, unknown) = books.partition { !it.author.isNullOrBlank() }
            val sections = known.groupBy { it.author!!.trim() }
                .toSortedMap(NaturalOrder)
                .map { (name, group) -> Section(name, group.sortedWith(compareBy(NaturalOrder) { it.title })) }
            if (unknown.isEmpty()) sections
            else sections + Section(UNKNOWN_AUTHOR, unknown.sortedWith(compareBy(NaturalOrder) { it.title }))
        }
        BookSortMode.SERIES -> {
            val (inSeries, standalone) = books.partition { !it.series.isNullOrBlank() }
            val byName = inSeries.groupBy { parseSeries(it.series!!).first }
            val sections = byName.toSortedMap(NaturalOrder).map { (name, group) ->
                Section(name, group.sortedWith(
                    compareBy(nullsLast()) { parseSeries(it.series!!).second },
                ))
            }
            if (standalone.isEmpty()) sections
            else sections + Section(STANDALONE, standalone.sortedWith(compareBy(NaturalOrder) { it.title }))
        }
        else -> listOf(Section("", sort(books, mode)))
    }
```
(`NaturalOrder` was already imported in Task 2.2; no new import needed.) `toSortedMap(NaturalOrder)` requires the map key be `String` (it is).

- [ ] **Step 4: Run to verify pass.** Expected: PASS.
- [ ] **Step 5: Commit.**
```bash
git add feature/audiobooks/src/main/java/com/orator/feature/audiobooks/BookExplore.kt \
        feature/audiobooks/src/test/java/com/orator/feature/audiobooks/BookExploreTest.kt
git commit -m "feat(audiobooks): BookExplore author/series grouping"
```

### Task 2.4: `BookExplore.search` + filters

- [ ] **Step 1: Write the failing tests.** Append:
```kotlin
    @Test fun `search matches title series and author case-insensitively`() {
        val books = listOf(
            book("a", "Redwall", author = "Brian Jacques", series = "Redwall #1"),
            book("b", "Mossflower", author = "Brian Jacques", series = "Redwall #2"),
            book("c", "Dune", author = "Herbert"),
        )
        val r = BookExplore.search(books, "red")
        assertEquals(listOf("a"), r.books.map { it.id })                 // title contains
        assertEquals(listOf(NamedHit("Redwall", 2)), r.series)           // distinct + count
        assertEquals(emptyList<NamedHit>(), r.authors)                   // no author matches
    }

    @Test fun `blank search term yields all-empty results`() {
        val r = BookExplore.search(listOf(book("a", "A")), "   ")
        assertEquals(0, r.books.size + r.series.size + r.authors.size)
    }

    @Test fun `filterSeries returns that series ordered by sequence`() {
        val books = listOf(
            book("a", "Two", series = "Redwall #2"),
            book("b", "One", series = "Redwall #1"),
            book("c", "Other", series = "Dune #1"),
        )
        assertEquals(listOf("b", "a"), BookExplore.filterSeries(books, "Redwall").map { it.id })
    }

    @Test fun `filterAuthor returns that author ordered by title`() {
        val books = listOf(
            book("a", "Beta", author = "Jacques"),
            book("b", "Alpha", author = "Jacques"),
            book("c", "X", author = "Herbert"),
        )
        assertEquals(listOf("b", "a"), BookExplore.filterAuthor(books, "Jacques").map { it.id })
    }
```
- [ ] **Step 2: Run to verify failure.** Expected: FAIL — `search`/`filterSeries`/`filterAuthor` unresolved.
- [ ] **Step 3: Implement.** Add to `BookExplore`:
```kotlin
    fun search(books: List<BookEntity>, term: String): SearchResults {
        val q = term.trim().lowercase()
        if (q.isEmpty()) return SearchResults(emptyList(), emptyList(), emptyList())

        val titleHits = books.filter { it.title.lowercase().contains(q) }
            .sortedWith(compareBy(NaturalOrder) { it.title })

        val seriesHits = books.mapNotNull { it.series?.let { s -> parseSeries(s).first } }
            .filter { it.lowercase().contains(q) }
            .groupingBy { it }.eachCount()
            .map { (name, count) -> NamedHit(name, count) }
            .sortedWith(compareBy(NaturalOrder) { it.name })

        val authorHits = books.mapNotNull { it.author?.takeIf { a -> a.isNotBlank() } }
            .filter { it.lowercase().contains(q) }
            .groupingBy { it.trim() }.eachCount()
            .map { (name, count) -> NamedHit(name, count) }
            .sortedWith(compareBy(NaturalOrder) { it.name })

        return SearchResults(titleHits, seriesHits, authorHits)
    }

    fun filterSeries(books: List<BookEntity>, name: String): List<BookEntity> =
        books.filter { it.series != null && parseSeries(it.series!!).first == name }
            .sortedWith(compareBy(nullsLast()) { parseSeries(it.series!!).second })

    fun filterAuthor(books: List<BookEntity>, name: String): List<BookEntity> =
        books.filter { it.author?.trim() == name }
            .sortedWith(compareBy(NaturalOrder) { it.title })
```
- [ ] **Step 4: Run to verify pass.** Expected: PASS.
- [ ] **Step 5: Gate + commit.**
```bash
ANDROID_HOME=~/Android/Sdk ./gradlew test lint assembleDebug
git add feature/audiobooks/src/main/java/com/orator/feature/audiobooks/BookExplore.kt \
        feature/audiobooks/src/test/java/com/orator/feature/audiobooks/BookExploreTest.kt
git commit -m "feat(audiobooks): BookExplore search + series/author filters"
```

---

## Chunk 3: Offline badge (`OnyxIcons.Downloaded` + `CoverTile`)

A small passive corner badge on downloaded tiles. Foundational for the grids in Chunks 4–5. Additive — existing `CoverTile` callers are unaffected (default `false`).

**Files:**
- Modify: `core/designsystem/src/main/java/com/orator/core/designsystem/icons/OnyxIcons.kt`
- Modify: `core/designsystem/src/main/java/com/orator/core/designsystem/components/CoverTile.kt`

### Task 3.1: Hand-rolled `OnyxIcons.Downloaded`

- [ ] **Step 1: Add the vector.** In `OnyxIcons.kt`, inside the `object OnyxIcons {`, add (material "download done": a check above a baseline):
```kotlin
    /** Material "download_done": check mark over a baseline. Hand-drawn; tweak on device. */
    val Downloaded: ImageVector by lazy {
        materialIcon(name = "Onyx.Downloaded") {
            materialPath {
                moveTo(18.0f, 19.0f); horizontalLineTo(6.0f); verticalLineTo(21.0f)
                horizontalLineTo(18.0f); close()
                moveTo(10.0f, 15.17f); lineTo(5.83f, 11.0f); lineTo(4.41f, 12.41f)
                lineTo(10.0f, 18.0f); lineTo(20.0f, 8.0f); lineTo(18.59f, 6.59f); close()
            }
        }
    }
```
- [ ] **Step 2: Compile-check.**
  Run: `ANDROID_HOME=~/Android/Sdk ./gradlew :core:designsystem:compileDebugKotlin`
  Expected: BUILD SUCCESSFUL.
- [ ] **Step 3: Commit.**
```bash
git add core/designsystem/src/main/java/com/orator/core/designsystem/icons/OnyxIcons.kt
git commit -m "feat(designsystem): hand-rolled OnyxIcons.Downloaded"
```

### Task 3.2: `CoverTile` badge

- [ ] **Step 1: Add the param + overlay.** In `CoverTile.kt`:

Add the parameter (after `onLongClick`):
```kotlin
    onLongClick: (() -> Unit)? = null,
    downloaded: Boolean = false,
```
Inside the outer `Box`, after the progress strip block, add the badge:
```kotlin
        if (downloaded) {
            Box(
                Modifier.align(Alignment.TopStart).padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape).padding(2.dp),
            ) {
                Icon(
                    OnyxIcons.Downloaded,
                    contentDescription = "Downloaded",
                    tint = Color.White,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
```
Add imports:
```kotlin
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import com.orator.core.designsystem.icons.OnyxIcons
```
- [ ] **Step 2: Gate + commit.** (No unit test — Compose visual; covered by the build gate and on-device verification.)
```bash
ANDROID_HOME=~/Android/Sdk ./gradlew test lint assembleDebug
git add core/designsystem/src/main/java/com/orator/core/designsystem/components/CoverTile.kt
git commit -m "feat(designsystem): CoverTile downloaded badge (top-start)"
```

---

## Chunk 4: Library screen sort/grouping

Persist the sort mode, render the `⇅` dropdown + `🔍` action, and switch the grid between flat and sectioned. Wire the offline badge into the grid tiles.

**Files:**
- Modify: `feature/audiobooks/src/main/java/com/orator/feature/audiobooks/data/AudiobooksPrefs.kt`
- Modify: `core/designsystem/src/main/java/com/orator/core/designsystem/icons/OnyxIcons.kt` (add `Sort`)
- Modify: `feature/audiobooks/src/main/java/com/orator/feature/audiobooks/AudiobookListViewModel.kt`
- Modify: `feature/audiobooks/src/main/java/com/orator/feature/audiobooks/AudiobookListScreen.kt`
- Modify: `feature/audiobooks/src/main/java/com/orator/feature/audiobooks/AudiobooksFeatureEntry.kt`

### Task 4.1: Persist sort mode in `AudiobooksPrefs`

- [ ] **Step 1: Add sortMode to the prefs.** In `AudiobooksPrefs.kt`:

Add the key near `KEY_TREE_URI`:
```kotlin
private val KEY_SORT_MODE = stringPreferencesKey("book_sort_mode")
```
Add to the class body:
```kotlin
    val sortMode: Flow<BookSortMode> = context.audiobooksDataStore.data.map { prefs ->
        prefs[KEY_SORT_MODE]
            ?.let { runCatching { BookSortMode.valueOf(it) }.getOrNull() }
            ?: BookSortMode.RECENT
    }

    suspend fun setSortMode(mode: BookSortMode) {
        context.audiobooksDataStore.edit { it[KEY_SORT_MODE] = mode.name }
    }
```
Add import: `import com.orator.feature.audiobooks.BookSortMode`.

- [ ] **Step 2: Compile-check + commit.**
  Run: `ANDROID_HOME=~/Android/Sdk ./gradlew :feature:audiobooks:compileDebugKotlin` → SUCCESSFUL.
```bash
git add feature/audiobooks/src/main/java/com/orator/feature/audiobooks/data/AudiobooksPrefs.kt
git commit -m "feat(audiobooks): persist sort mode in AudiobooksPrefs"
```

### Task 4.2: Hand-rolled `OnyxIcons.Sort`

- [ ] **Step 1: Add the vector.** In `OnyxIcons.kt`, inside the object (material "sort"/"swap_vert" feel — three descending lines):
```kotlin
    /** Sort: three lines of decreasing width (material "sort"). Hand-drawn. */
    val Sort: ImageVector by lazy {
        materialIcon(name = "Onyx.Sort") {
            materialPath {
                moveTo(3.0f, 18.0f); horizontalLineTo(9.0f); verticalLineTo(16.0f)
                horizontalLineTo(3.0f); close()
                moveTo(3.0f, 6.0f); verticalLineTo(8.0f); horizontalLineTo(21.0f)
                verticalLineTo(6.0f); close()
                moveTo(3.0f, 13.0f); horizontalLineTo(15.0f); verticalLineTo(11.0f)
                horizontalLineTo(3.0f); close()
            }
        }
    }
```
- [ ] **Step 2: Compile-check + commit.**
  Run: `ANDROID_HOME=~/Android/Sdk ./gradlew :core:designsystem:compileDebugKotlin` → SUCCESSFUL.
```bash
git add core/designsystem/src/main/java/com/orator/core/designsystem/icons/OnyxIcons.kt
git commit -m "feat(designsystem): hand-rolled OnyxIcons.Sort"
```

### Task 4.3: ViewModel exposes sort mode + library view

- [ ] **Step 1: Add a `LibraryView` type + sort state to the VM.** In `AudiobookListViewModel.kt`:

Add a sealed view type at file scope (below the imports, above the class) or in `BookExplore.kt` — put it here for locality:
```kotlin
/** What the grid renders: a flat list (RECENT/TITLE) or labelled sections (AUTHOR/SERIES). */
sealed interface LibraryView {
    data class Flat(val books: List<BookEntity>) : LibraryView
    data class Sectioned(val sections: List<Section>) : LibraryView
}
```
In the class, inject nothing new (repository already there). Add:
```kotlin
    val sortMode: StateFlow<BookSortMode> = repository.sortMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookSortMode.RECENT)

    val view: StateFlow<LibraryView> =
        combine(repository.observeBooks(), repository.sortMode) { books, mode ->
            when (mode) {
                BookSortMode.RECENT, BookSortMode.TITLE -> LibraryView.Flat(BookExplore.sort(books, mode))
                BookSortMode.AUTHOR, BookSortMode.SERIES -> LibraryView.Sectioned(BookExplore.group(books, mode))
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryView.Flat(emptyList()))

    fun onSortSelected(mode: BookSortMode) {
        viewModelScope.launch { repository.setSortMode(mode) }
    }
```
Expose `repository.sortMode` and `repository.setSortMode` by delegating in `AudiobookRepository`:
```kotlin
    val sortMode: Flow<BookSortMode> = prefs.sortMode
    suspend fun setSortMode(mode: BookSortMode) = prefs.setSortMode(mode)
```
(Add `import com.orator.feature.audiobooks.BookSortMode` to the repository.)

Add imports to the VM: `import kotlinx.coroutines.flow.combine`. (`books`/`hasFolder` stay as they are — the screen keeps using `books` for the empty-state check, or switch to `view`; see Task 4.4.)

- [ ] **Step 2: Compile-check + commit.**
  Run: `ANDROID_HOME=~/Android/Sdk ./gradlew :feature:audiobooks:compileDebugKotlin` → SUCCESSFUL.
```bash
git add feature/audiobooks/src/main/java/com/orator/feature/audiobooks/AudiobookListViewModel.kt \
        feature/audiobooks/src/main/java/com/orator/feature/audiobooks/data/AudiobookRepository.kt
git commit -m "feat(audiobooks): list VM exposes sortMode + flat/sectioned view"
```

### Task 4.4: Screen — dropdown, sectioned grid, badge, search action

- [ ] **Step 1: Update the screen.** In `AudiobookListScreen.kt`:

Change the signature to accept an `onOpenSearch` callback:
```kotlin
fun AudiobookListScreen(
    onOpenBook: (bookId: String) -> Unit,
    onAddToPlaylist: (bookId: String) -> Unit,
    onOpenSearch: () -> Unit,
    viewModel: AudiobookListViewModel = hiltViewModel(),
) {
```
Collect the new state alongside the existing ones:
```kotlin
    val view by viewModel.view.collectAsStateWithLifecycle()
    val sortMode by viewModel.sortMode.collectAsStateWithLifecycle()
    val books by viewModel.books.collectAsStateWithLifecycle()   // keep for empty-state check
```
Give the top bar a trailing actions row that is shown **only when there are books**:
```kotlin
        OnyxTopBar(
            title = "Audiobooks",
            leadingIcon = Icons.Filled.Menu,
            onLeadingClick = shell.openDrawer,
            trailing = {
                if (books.isNotEmpty()) {
                    Row {
                        SortMenu(current = sortMode, onSelect = viewModel::onSortSelected)
                        IconButton(onClick = onOpenSearch) {
                            Icon(Icons.Filled.Search, "Search", tint = OnyxTokens.TextDim)
                        }
                    }
                }
            },
        )
```
Replace the `else -> LazyVerticalGrid { items(books) { ... } }` body with a render that switches on `view`. Extract the tile into a helper so flat + sectioned share it:
```kotlin
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = OnyxTokens.OverlayBottomPadding),
            ) {
                when (val v = view) {
                    is LibraryView.Flat -> items(v.books, key = { it.id }) { book ->
                        BookGridTile(book, onOpenBook, onAddToPlaylist)
                    }
                    is LibraryView.Sectioned -> v.sections.forEach { section ->
                        item(span = { GridItemSpan(maxLineSpan) }, key = "h:${section.header}") {
                            SectionHeader(section.header)
                        }
                        items(section.books, key = { it.id }) { book ->
                            BookGridTile(book, onOpenBook, onAddToPlaylist)
                        }
                    }
                }
            }
```
Add the helper composables at file scope:
```kotlin
@Composable
private fun BookGridTile(
    book: BookEntity,
    onOpenBook: (String) -> Unit,
    onAddToPlaylist: (String) -> Unit,
) {
    CoverTile(
        artworkModel = if (book.origin == BookOrigin.ABS) book.coverPath
        else book.coverPath?.let(::File),
        title = book.title,
        subLine = if (book.positionMs > 0)
            TimeFormats.timeLeft((book.durationMs - book.positionMs).coerceAtLeast(0)) else null,
        progress = if (book.durationMs > 0) book.positionMs.toFloat() / book.durationMs else null,
        onClick = { onOpenBook(book.id) },
        onLongClick = { onAddToPlaylist(book.id) },
        downloaded = book.downloadState == DownloadState.DOWNLOADED,
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        color = OnyxTokens.Text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth()
            .background(OnyxTokens.Background)
            .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun SortMenu(current: BookSortMode, onSelect: (BookSortMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(OnyxIcons.Sort, "Sort", tint = OnyxTokens.TextDim)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortChoice("Recently added", BookSortMode.RECENT, current) { onSelect(it); open = false }
            SortChoice("Title", BookSortMode.TITLE, current) { onSelect(it); open = false }
            SortChoice("Author", BookSortMode.AUTHOR, current) { onSelect(it); open = false }
            SortChoice("Series", BookSortMode.SERIES, current) { onSelect(it); open = false }
        }
    }
}

@Composable
private fun SortChoice(
    label: String, mode: BookSortMode, current: BookSortMode, onPick: (BookSortMode) -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = { onPick(mode) },
        leadingIcon = { if (mode == current) Icon(Icons.Filled.Check, null, tint = OnyxTokens.Accent) },
    )
}
```
Add imports:
```kotlin
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.font.FontWeight
import com.orator.core.database.BookEntity
import com.orator.core.designsystem.icons.OnyxIcons
import com.orator.core.model.DownloadState
```
- [ ] **Step 2: Update the feature entry call site.** In `AudiobooksFeatureEntry.kt`, the `AudiobookListScreen(...)` call adds:
```kotlin
                onOpenSearch = { navController.navigate(AudiobookSearchRoute) },
```
(`AudiobookSearchRoute` is added in Chunk 5; to keep this chunk compiling on its own, temporarily pass `onOpenSearch = {}` here and switch to the real route in Chunk 5 Task 5.4. Note this in the commit.)

- [ ] **Step 3: Gate + commit.**
```bash
ANDROID_HOME=~/Android/Sdk ./gradlew test lint assembleDebug
git add feature/audiobooks/src/main/java/com/orator/feature/audiobooks/AudiobookListScreen.kt \
        feature/audiobooks/src/main/java/com/orator/feature/audiobooks/AudiobooksFeatureEntry.kt
git commit -m "feat(audiobooks): sort dropdown, sectioned grid, offline badge on tiles"
```

---

## Chunk 5: Search screen + filtered grid + navigation

The `🔍` destination (grouped Books/Series/Authors) and the series/author filtered grid. Routes live in the local `AudiobooksRoutes.kt`.

**Files:**
- Modify: `feature/audiobooks/src/main/java/com/orator/feature/audiobooks/AudiobooksRoutes.kt`
- Create: `feature/audiobooks/src/main/java/com/orator/feature/audiobooks/AudiobookSearchViewModel.kt`
- Create: `feature/audiobooks/src/main/java/com/orator/feature/audiobooks/AudiobookSearchScreen.kt`
- Create: `feature/audiobooks/src/main/java/com/orator/feature/audiobooks/AudiobookFilterViewModel.kt`
- Create: `feature/audiobooks/src/main/java/com/orator/feature/audiobooks/AudiobookFilterScreen.kt`
- Modify: `feature/audiobooks/src/main/java/com/orator/feature/audiobooks/AudiobooksFeatureEntry.kt`

### Task 5.1: Routes

- [ ] **Step 1: Add the route constants + builder.** In `AudiobooksRoutes.kt`:
```kotlin
// Distinct top segment: "audiobooks/search" would collide with the audiobooks/{bookId} detail
// wildcard (same pitfall the podcast code documents). Value is Uri.encode'd like the detail route.
internal const val AudiobookSearchRoute = "audiobook-search"
internal const val AudiobookFilterRoutePattern = "audiobook-filter/{type}/{value}"
internal fun audiobookFilterRoute(type: String, value: String) =
    "audiobook-filter/$type/" + Uri.encode(value)
```
- [ ] **Step 2: Compile-check + commit.**
  Run: `ANDROID_HOME=~/Android/Sdk ./gradlew :feature:audiobooks:compileDebugKotlin` → SUCCESSFUL.
```bash
git add feature/audiobooks/src/main/java/com/orator/feature/audiobooks/AudiobooksRoutes.kt
git commit -m "feat(audiobooks): search + filter route definitions"
```

### Task 5.2: Search ViewModel + screen

- [ ] **Step 1: Create the ViewModel.** `AudiobookSearchViewModel.kt`:
```kotlin
package com.orator.feature.audiobooks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orator.feature.audiobooks.data.AudiobookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AudiobookSearchViewModel @Inject constructor(
    repository: AudiobookRepository,
) : ViewModel() {
    private val term = MutableStateFlow("")

    val results: StateFlow<SearchResults> =
        combine(repository.observeBooks(), term) { books, t -> BookExplore.search(books, t) }
            .stateIn(
                viewModelScope, SharingStarted.WhileSubscribed(5_000),
                SearchResults(emptyList(), emptyList(), emptyList()),
            )

    val query: StateFlow<String> = term.asStateFlow()

    fun onQueryChange(value: String) { term.value = value }
}
```
- [ ] **Step 2: Create the screen.** `AudiobookSearchScreen.kt`:
```kotlin
package com.orator.feature.audiobooks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orator.core.database.BookEntity
import com.orator.core.designsystem.components.EpisodeRow
import com.orator.core.designsystem.components.OnyxTopBar
import com.orator.core.designsystem.components.RowArt
import com.orator.core.designsystem.theme.OnyxTokens
import com.orator.core.model.BookOrigin
import java.io.File

@Composable
fun AudiobookSearchScreen(
    onOpenBook: (String) -> Unit,
    onOpenSeries: (String) -> Unit,
    onOpenAuthor: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: AudiobookSearchViewModel = hiltViewModel(),
) {
    val results by viewModel.results.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(OnyxTokens.Background)) {
        OnyxTopBar(
            title = "Search",
            leadingIcon = Icons.AutoMirrored.Filled.ArrowBack,
            onLeadingClick = onBack,
        )
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::onQueryChange,
            placeholder = { Text("Search your library", color = OnyxTokens.TextFaint) },
            singleLine = true,
            shape = RoundedCornerShape(11.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = OnyxTokens.Accent,
                unfocusedBorderColor = OnyxTokens.ChipBorder,
                focusedTextColor = OnyxTokens.Text,
                unfocusedTextColor = OnyxTokens.Text,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        )

        val empty = results.books.isEmpty() && results.series.isEmpty() && results.authors.isEmpty()
        if (query.isNotBlank() && empty) {
            Text("No matches", color = OnyxTokens.TextFaint, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }

        LazyColumn(contentPadding = PaddingValues(bottom = OnyxTokens.OverlayBottomPadding)) {
            if (results.books.isNotEmpty()) {
                item { SearchHeader("Books") }
                items(results.books, key = { "b:${it.id}" }) { book ->
                    EpisodeRow(
                        title = book.title,
                        subLine = book.author.orEmpty(),
                        onClick = { onOpenBook(book.id) },
                        leading = { RowArt(artworkModel = artwork(book), title = book.title) },
                    )
                }
            }
            if (results.series.isNotEmpty()) {
                item { SearchHeader("Series") }
                items(results.series, key = { "s:${it.name}" }) { hit ->
                    EpisodeRow(
                        title = hit.name,
                        subLine = "${hit.count} books",
                        onClick = { onOpenSeries(hit.name) },
                    )
                }
            }
            if (results.authors.isNotEmpty()) {
                item { SearchHeader("Authors") }
                items(results.authors, key = { "a:${it.name}" }) { hit ->
                    EpisodeRow(
                        title = hit.name,
                        subLine = "${hit.count} books",
                        onClick = { onOpenAuthor(hit.name) },
                    )
                }
            }
        }
    }
}

private fun artwork(book: BookEntity): Any? =
    if (book.origin == BookOrigin.ABS) book.coverPath else book.coverPath?.let(::File)

@Composable
private fun SearchHeader(text: String) {
    Text(
        text = text,
        color = OnyxTokens.TextDim,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp),
    )
}
```
**Note for the implementer:** verify `EpisodeRow`'s exact parameter names and the `RowArt` signature in `core/designsystem/.../components/` (the podcast `SearchScreen.kt` uses `EpisodeRow(title, subLine, onClick, leading, trailing)` and `RowArt(artworkModel, title)`). Adjust the call sites if the signatures differ. Use `import androidx.compose.foundation.lazy.items`.

- [ ] **Step 3: Compile-check + commit.**
  Run: `ANDROID_HOME=~/Android/Sdk ./gradlew :feature:audiobooks:compileDebugKotlin` → SUCCESSFUL (add the missing `items` import if the compiler flags it).
```bash
git add feature/audiobooks/src/main/java/com/orator/feature/audiobooks/AudiobookSearchViewModel.kt \
        feature/audiobooks/src/main/java/com/orator/feature/audiobooks/AudiobookSearchScreen.kt
git commit -m "feat(audiobooks): grouped local search screen"
```

### Task 5.3: Filtered grid ViewModel + screen

- [ ] **Step 1: Create the ViewModel.** `AudiobookFilterViewModel.kt`:
```kotlin
package com.orator.feature.audiobooks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orator.feature.audiobooks.data.AudiobookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AudiobookFilterViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repository: AudiobookRepository,
) : ViewModel() {
    // Nav decodes the encoded {value} segment before it lands here.
    private val type: String = checkNotNull(savedStateHandle["type"])
    val value: String = checkNotNull(savedStateHandle["value"])

    val books: StateFlow<List<com.orator.core.database.BookEntity>> =
        repository.observeBooks().map { all ->
            if (type == "series") BookExplore.filterSeries(all, value)
            else BookExplore.filterAuthor(all, value)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
```
- [ ] **Step 2: Create the screen.** `AudiobookFilterScreen.kt` — a titled 3-col grid reusing `BookGridTile` semantics:
```kotlin
package com.orator.feature.audiobooks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orator.core.database.BookEntity
import com.orator.core.designsystem.components.CoverTile
import com.orator.core.designsystem.components.OnyxTopBar
import com.orator.core.designsystem.text.TimeFormats
import com.orator.core.designsystem.theme.OnyxTokens
import com.orator.core.model.BookOrigin
import com.orator.core.model.DownloadState
import java.io.File

@Composable
fun AudiobookFilterScreen(
    onOpenBook: (String) -> Unit,
    onAddToPlaylist: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: AudiobookFilterViewModel = hiltViewModel(),
) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(OnyxTokens.Background)) {
        OnyxTopBar(
            title = viewModel.value,
            leadingIcon = Icons.AutoMirrored.Filled.ArrowBack,
            onLeadingClick = onBack,
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = OnyxTokens.OverlayBottomPadding),
        ) {
            items(books, key = { it.id }) { book ->
                CoverTile(
                    artworkModel = if (book.origin == BookOrigin.ABS) book.coverPath
                    else book.coverPath?.let(::File),
                    title = book.title,
                    subLine = if (book.positionMs > 0)
                        TimeFormats.timeLeft((book.durationMs - book.positionMs).coerceAtLeast(0)) else null,
                    progress = if (book.durationMs > 0) book.positionMs.toFloat() / book.durationMs else null,
                    onClick = { onOpenBook(book.id) },
                    onLongClick = { onAddToPlaylist(book.id) },
                    downloaded = book.downloadState == DownloadState.DOWNLOADED,
                )
            }
        }
    }
}
```
- [ ] **Step 3: Compile-check + commit.**
  Run: `ANDROID_HOME=~/Android/Sdk ./gradlew :feature:audiobooks:compileDebugKotlin` → SUCCESSFUL.
```bash
git add feature/audiobooks/src/main/java/com/orator/feature/audiobooks/AudiobookFilterViewModel.kt \
        feature/audiobooks/src/main/java/com/orator/feature/audiobooks/AudiobookFilterScreen.kt
git commit -m "feat(audiobooks): series/author filtered cover grid"
```

### Task 5.4: Register routes + wire navigation

- [ ] **Step 1: Register the composables + real search nav.** In `AudiobooksFeatureEntry.kt`:

Change the list call's `onOpenSearch` to the real route:
```kotlin
                onOpenSearch = { navController.navigate(AudiobookSearchRoute) },
```
Add inside `register(...)`, after the detail `composable`:
```kotlin
        navGraphBuilder.composable(AudiobookSearchRoute) {
            AudiobookSearchScreen(
                onOpenBook = { navController.navigate(audiobookDetailRoute(it)) },
                onOpenSeries = { navController.navigate(audiobookFilterRoute("series", it)) },
                onOpenAuthor = { navController.navigate(audiobookFilterRoute("author", it)) },
                onBack = { navController.popBackStack() },
            )
        }
        navGraphBuilder.composable(AudiobookFilterRoutePattern) {
            AudiobookFilterScreen(
                onOpenBook = { navController.navigate(audiobookDetailRoute(it)) },
                onAddToPlaylist = { bookId ->
                    navController.navigate(CommonRoutes.addToPlaylist(MediaType.AUDIOBOOK.name, bookId))
                },
                onBack = { navController.popBackStack() },
            )
        }
```
(`CommonRoutes` and `MediaType` are already imported in this file.)

- [ ] **Step 2: Gate + commit.**
```bash
ANDROID_HOME=~/Android/Sdk ./gradlew test lint assembleDebug
git add feature/audiobooks/src/main/java/com/orator/feature/audiobooks/AudiobooksFeatureEntry.kt
git commit -m "feat(audiobooks): register search + filter routes, wire search action"
```

---

## Final verification (after all chunks)

- [ ] **Full gate green:**
  ```bash
  ANDROID_HOME=~/Android/Sdk ./gradlew test lint assembleDebug
  ```
- [ ] **On-device smoke test** (wireless adb to the Pixel 7a; see CLAUDE.md / memory for adb quirks). Confirm:
  - Library defaults to **Recently added**; the `⇅` menu switches Title / Author / Series, and the choice **survives an app restart** (DataStore).
  - Author & Series modes show sticky-feeling section headers; Series sub-orders by sequence; "Standalone" / "Unknown author" buckets land last.
  - **Recently added** reflects ABS order (newest first) after a fresh sync — verify a couple of known-recent titles sort to the top.
  - `🔍` opens search; "redwall" (or a known series) yields grouped **Books / Series / Authors**; a Book row → detail, a Series/Author row → the filtered grid; the grid's title is the series/author name.
  - Downloaded books show the corner badge in every grid.
- [ ] **Tune the two hand-rolled icons on device** if the `Sort`/`Downloaded` glyphs look off (they're hand-plotted `materialPath`s — adjust coordinates, re-build).

## Execution handoff

After the final gate is green and the device smoke test passes, complete the branch with **superpowers:finishing-a-development-branch** (verify tests → present the four options → execute). Typical path here: push and open a PR against `main`, mirroring PRs #11/#12.
