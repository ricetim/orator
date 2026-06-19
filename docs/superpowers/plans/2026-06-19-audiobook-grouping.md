# Audiobook Multi-File Grouping — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Import a directory of audio files as **one** audiobook, flattening each file's internal `chpl` chapters across files in order — fixing the scanner that splits multi-part `.m4b` books into many fake "books."

**Architecture:** Rename `SourceKind` to `SINGLE_FILE`/`MULTI_FILE`. Introduce a pure `ChapterTimeline` helper (`core:database`) that turns a book's contiguous `ChapterEntity` list into per-file durations, file indices, and global positions — the single source of "chapters tile the timeline" math. Scanner groups a directory's audio files; importer builds contiguous chapter rows; `QueueBuilder`/`PlayerChapters`/UI/listeners delegate to `ChapterTimeline` + the existing `PositionMapper`.

**Tech Stack:** Kotlin, Room (`core:database`), Media3 (`core:playback`), Jetpack Compose (`feature:player`), JUnit (JVM unit tests; Room-backed tests use `runBlocking`).

**Spec:** `docs/superpowers/specs/2026-06-18-audiobook-grouping-design.md` (reviewer-approved).

**Branch:** `fix-audiobook-grouping` (already created off `main`).

**Per-chunk gate:** `./gradlew test lint assembleDebug` must be green before moving on.

---

## File Structure

| File | Responsibility | Change |
|------|----------------|--------|
| `core/database/.../BookEntity.kt` | `SourceKind` enum | rename values |
| `core/database/.../OratorDatabase.kt` | `@Database(version=…)` | bump 4 → 5 |
| `core/database/.../ChapterTimeline.kt` | **NEW** pure chapter→file→global math | create |
| `feature/audiobooks/.../data/AudiobookScanner.kt` | walk tree → `ScannedBook` | group dir audio files |
| `feature/audiobooks/.../data/AudiobookImporter.kt` | `ScannedBook` → DB rows | SingleFile + MultiFile (flatten) |
| `feature/audiobooks/.../data/QueueBuilder.kt` | book → `PlayRequest` | MULTI_FILE via `ChapterTimeline` |
| `feature/audiobooks/.../data/AudiobookPositionListener.kt` | persist resume pos | MULTI_FILE via file durations |
| `feature/player/.../PlayerChapters.kt` | chapter math | MULTI_FILE branch |
| `feature/player/.../pages/ChaptersPage.kt` | chapter list UI | MULTI_FILE sort + start times |
| `feature/player/.../pages/BookmarksPage.kt` | `chapterNumberFor` | MULTI_FILE chapter (not file) index |
| `feature/player/.../PlayerViewModel.kt` | `onBookmarkTap`, `currentGlobalMs` | MULTI_FILE via file durations |
| sleep-timer consumer (TBD in Chunk 4) | end-of-chapter sleep | in-file boundaries |

---

## Chunk 1: Vocabulary — rename `SourceKind` + DB v5

Pure refactor, **no behavior change**. `SINGLE_FILE` = old `M4B` logic, `MULTI_FILE` = old `MP3_DIR` logic. Leaves build + all existing tests green.

### Task 1.1: Rename the enum and bump the DB version

**Files:**
- Modify: `core/database/src/main/java/com/orator/core/database/BookEntity.kt`
- Modify: `core/database/src/main/java/com/orator/core/database/OratorDatabase.kt:16`

- [ ] **Step 1: Rename enum values.** In `BookEntity.kt`, `enum class SourceKind`: `M4B` → `SINGLE_FILE`, `MP3_DIR` → `MULTI_FILE`.
- [ ] **Step 2: Bump DB version.** `OratorDatabase.kt:16` `version = 4` → `version = 5`. (No migration code: `DatabaseModule.kt:21` already `fallbackToDestructiveMigration(dropAllTables = true)`, `exportSchema = false`.)
- [ ] **Step 3: Compile to surface every call site.** Run `./gradlew :core:database:compileDebugKotlin` then `./gradlew compileDebugKotlin` — note each unresolved `SourceKind.M4B`/`MP3_DIR`.

### Task 1.2: Update all `SourceKind` branch sites (mechanical rename)

**Files (the 7 production branch sites + tests):**
- `feature/audiobooks/.../data/AudiobookImporter.kt:60,80`
- `feature/audiobooks/.../data/QueueBuilder.kt:21,37`
- `feature/audiobooks/.../data/AudiobookPositionListener.kt:28,29`
- `feature/player/.../PlayerChapters.kt:36,43,78,80,93,94,108,109,124,125`
- `feature/player/.../PlayerViewModel.kt:149,150,168,169`
- `feature/player/.../pages/BookmarksPage.kt:95,98`
- `feature/player/.../pages/ChaptersPage.kt:31,52,53`
- Tests referencing `SourceKind.M4B`/`MP3_DIR` (e.g. `PlayerChaptersTest`, `QueueBuilder`/importer tests).

- [ ] **Step 1:** Replace `SourceKind.M4B` → `SourceKind.SINGLE_FILE` and `SourceKind.MP3_DIR` → `SourceKind.MULTI_FILE` at every site above (production + test). **Behavior unchanged** — just the names.
- [ ] **Step 2: Run tests.** `./gradlew test` — Expected: PASS (rename only).
- [ ] **Step 3: Full gate.** `./gradlew test lint assembleDebug` — Expected: green.
- [ ] **Step 4: Commit.** `git commit -am "refactor(audiobooks): rename SourceKind M4B/MP3_DIR -> SINGLE_FILE/MULTI_FILE; DB v5"`

---

## Chunk 2: `ChapterTimeline` + scanner grouping + importer

### Task 2.1: `ChapterTimeline` pure helper (TDD)

The single source of contiguous-chapter math. Files derived by grouping chapters on `fileUri` in `chapterIndex` order; a file's duration = sum of its chapters' durations; a chapter's global start = sum of all preceding chapters' durations.

**Files:**
- Create: `core/database/src/main/java/com/orator/core/database/ChapterTimeline.kt`
- Test: `core/database/src/test/java/com/orator/core/database/ChapterTimelineTest.kt`

- [ ] **Step 1: Failing test.**

```kotlin
package com.orator.core.database
import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterTimelineTest {
    // 2 files: A=[c0:0..1000, c1:1000..3000], B=[c2:0..1500]
    private fun ch(i: Int, file: String, start: Long, dur: Long) =
        ChapterEntity(bookId = "b", chapterIndex = i, title = "c$i", fileUri = file, startMs = start, durationMs = dur)
    private val chapters = listOf(
        ch(0, "A", 0, 1000), ch(1, "A", 1000, 2000), ch(2, "B", 0, 1500),
    )

    @Test fun fileDurations_sum_per_file_in_order() {
        assertEquals(listOf(3000L, 1500L), ChapterTimeline.fileDurations(chapters))
    }
    @Test fun fileIndexOf_chapter() {
        assertEquals(0, ChapterTimeline.fileIndexOf(chapters, 1)) // c1 in file A
        assertEquals(1, ChapterTimeline.fileIndexOf(chapters, 2)) // c2 in file B
    }
    @Test fun globalStartOf_chapter_is_sum_of_preceding_durations() {
        assertEquals(0L, ChapterTimeline.globalStartOf(chapters, 0))
        assertEquals(1000L, ChapterTimeline.globalStartOf(chapters, 1))
        assertEquals(3000L, ChapterTimeline.globalStartOf(chapters, 2))
    }
    @Test fun chapterAtGlobal_finds_containing_chapter() {
        assertEquals(0, ChapterTimeline.chapterAtGlobal(chapters, 500))
        assertEquals(1, ChapterTimeline.chapterAtGlobal(chapters, 2999))
        assertEquals(2, ChapterTimeline.chapterAtGlobal(chapters, 3000))
        assertEquals(2, ChapterTimeline.chapterAtGlobal(chapters, 99999)) // clamp last
    }
    @Test fun empty_is_safe() {
        assertEquals(emptyList<Long>(), ChapterTimeline.fileDurations(emptyList()))
        assertEquals(0, ChapterTimeline.chapterAtGlobal(emptyList(), 100))
    }
}
```

- [ ] **Step 2: Run red.** `./gradlew :core:database:testDebugUnitTest --tests "com.orator.core.database.ChapterTimelineTest"` — FAIL (unresolved `ChapterTimeline`).
- [ ] **Step 3: Implement.**

```kotlin
package com.orator.core.database

/**
 * Pure math over a book's contiguous chapters. Chapters are assumed ordered by chapterIndex
 * and to tile the book end-to-end (each file's first chapter starts at 0; see the importer).
 * Files are the distinct fileUris in order; a file's duration is the sum of its chapters'.
 */
object ChapterTimeline {

    /** Ordered distinct files. */
    fun files(chapters: List<ChapterEntity>): List<String> =
        chapters.map { it.fileUri }.distinct()

    /** Per-file durations (sum of each file's chapter durations), file order. */
    fun fileDurations(chapters: List<ChapterEntity>): List<Long> =
        files(chapters).map { uri -> chapters.filter { it.fileUri == uri }.sumOf { it.durationMs } }

    /** File index (queue item index) of the chapter at [chapterIndex] (list position). */
    fun fileIndexOf(chapters: List<ChapterEntity>, chapterIndex: Int): Int {
        val uri = chapters[chapterIndex].fileUri
        return files(chapters).indexOf(uri)
    }

    /** Global start = sum of preceding chapter durations. */
    fun globalStartOf(chapters: List<ChapterEntity>, chapterIndex: Int): Long =
        chapters.take(chapterIndex).sumOf { it.durationMs }

    /** Index (list position) of the chapter whose [start, start+dur) contains globalMs; clamps. */
    fun chapterAtGlobal(chapters: List<ChapterEntity>, globalMs: Long): Int {
        if (chapters.isEmpty()) return 0
        var acc = 0L
        chapters.forEachIndexed { i, c ->
            acc += c.durationMs
            if (globalMs < acc) return i
        }
        return chapters.lastIndex
    }
}
```

- [ ] **Step 4: Run green.** same test command — PASS.
- [ ] **Step 5: Commit.** `git commit -am "feat(database): ChapterTimeline contiguous-chapter math"`

### Task 2.2: Scanner groups a directory's audio files (TDD)

**Files:**
- Modify: `feature/audiobooks/.../data/AudiobookScanner.kt`
- Test: `feature/audiobooks/.../data/AudiobookScannerTest.kt` (extend; create if absent)

- [ ] **Step 1: Failing tests.** Use a fake `DocumentNode`. Cases: directory with one `.m4b` → `SingleFile`; directory with three `.m4b` → `MultiFile` natural-sorted; directory of `.mp3` → `MultiFile`; mixed `.m4b`+`.mp3` → `MultiFile` natural-sorted together; `Author/` (no audio) → recurse → `Book/` is the book; a directory with a file **and** a subdirectory yields both the file's book and the subdir's book (recurse always); empty dir → no books.

```kotlin
private class Node(
    override val name: String, override val uri: String,
    override val isDirectory: Boolean, private val kids: List<DocumentNode> = emptyList(),
) : DocumentNode { override fun children() = kids }

private fun file(n: String) = Node(n, "uri/$n", false)
private fun dir(n: String, vararg kids: DocumentNode) = Node(n, "uri/$n", true, kids.toList())

@Test fun single_m4b_is_single_file_book() {
    val books = AudiobookScanner.scan(dir("Book", file("a.m4b")))
    assertEquals(1, books.size)
    assertTrue(books[0] is ScannedBook.SingleFile)
}
@Test fun many_m4b_in_one_dir_is_one_multi_file_book_sorted() {
    val books = AudiobookScanner.scan(dir("Book", file("part (10).m4b"), file("part (2).m4b"), file("part (1).m4b")))
    assertEquals(1, books.size)
    val mf = books[0] as ScannedBook.MultiFile
    assertEquals(listOf("part (1).m4b", "part (2).m4b", "part (10).m4b"), mf.files.map { it.name })
}
@Test fun mixed_audio_grouped_together() {
    val books = AudiobookScanner.scan(dir("Book", file("a.m4b"), file("b.mp3")))
    assertEquals(1, (books.single() as ScannedBook.MultiFile).let { 1 })
    assertEquals(2, (books.single() as ScannedBook.MultiFile).files.size)
}
@Test fun nested_author_then_book() {
    val books = AudiobookScanner.scan(dir("Author", dir("Book", file("a.m4b"), file("b.m4b"))))
    assertEquals(1, books.size); assertTrue(books[0] is ScannedBook.MultiFile)
}
```

- [ ] **Step 2: Run red.** `./gradlew :feature:audiobooks:testDebugUnitTest --tests "*AudiobookScannerTest"` — FAIL.
- [ ] **Step 3: Implement.** Replace `ScannedBook` and scanner:

```kotlin
sealed interface ScannedBook {
    val title: String
    val rootUri: String
    data class SingleFile(override val title: String, override val rootUri: String) : ScannedBook
    data class MultiFile(
        override val title: String,
        override val rootUri: String,
        val files: List<ScannedFile>,
    ) : ScannedBook
}
data class ScannedFile(val name: String, val uri: String)

object AudiobookScanner {
    private val AUDIO = listOf(".m4b", ".mp3")
    private fun isAudio(n: String) = AUDIO.any { n.endsWith(it, ignoreCase = true) }

    fun scan(root: DocumentNode): List<ScannedBook> =
        mutableListOf<ScannedBook>().also { scanDir(root, it) }

    private fun scanDir(dir: DocumentNode, sink: MutableList<ScannedBook>) {
        val children = dir.children()
        val audio = children.filter { !it.isDirectory && isAudio(it.name) }
            .sortedWith(compareBy(NaturalOrder) { it.name })
        when {
            audio.size == 1 -> sink.add(
                ScannedBook.SingleFile(title = audio[0].name.substringBeforeLast('.'), rootUri = audio[0].uri),
            )
            audio.size >= 2 -> sink.add(
                ScannedBook.MultiFile(
                    title = dir.name,
                    rootUri = dir.uri,
                    files = audio.map { ScannedFile(it.name, it.uri) },
                ),
            )
        }
        // Always recurse so nested books are found and a stray file never hides subfolders.
        children.filter { it.isDirectory }.forEach { scanDir(it, sink) }
    }
}
```

- [ ] **Step 4: Run green.** same command — PASS.
- [ ] **Step 5: Commit.** `git commit -am "feat(audiobooks): scanner groups a directory's audio files into one book"`

### Task 2.3: Importer builds contiguous chapter rows (TDD the assembly)

Extract a **pure** chapter-assembly function so it's testable without SAF/extractor, then wire the importer to it.

**Files:**
- Modify: `feature/audiobooks/.../data/AudiobookImporter.kt`
- Create: `feature/audiobooks/.../data/ChapterAssembler.kt` (pure)
- Test: `feature/audiobooks/.../data/ChapterAssemblerTest.kt`

- [ ] **Step 1: Failing test** for the pure assembler. Input: per-file `(fileUri, durationMs, marks: List<Mp4ChapterParser.Chapter>)`; output: contiguous `ChapterEntity` list (global `chapterIndex`; each file's first chapter anchored to `startMs=0`; last-in-file duration = `fileDuration - startMs`).

```kotlin
@Test fun flattens_chpl_across_files_contiguously() {
    val input = listOf(
        ChapterAssembler.FileChapters("A", 3000, listOf(Mp4ChapterParser.Chapter("Intro", 0), Mp4ChapterParser.Chapter("Ch1", 1000))),
        ChapterAssembler.FileChapters("B", 1500, emptyList()), // no chpl -> one whole-file chapter
    )
    val out = ChapterAssembler.assemble("book", input, fileStem = { it })
    assertEquals(listOf(0,1,2), out.map { it.chapterIndex })
    assertEquals(listOf("A","A","B"), out.map { it.fileUri })
    assertEquals(listOf(0L,1000L,0L), out.map { it.startMs })
    assertEquals(listOf(1000L,2000L,1500L), out.map { it.durationMs })
    assertEquals(listOf("Intro","Ch1","B"), out.map { it.title })
}
@Test fun anchors_first_chapter_to_zero_when_chpl_starts_late() {
    val input = listOf(ChapterAssembler.FileChapters("A", 2000, listOf(Mp4ChapterParser.Chapter("Late", 500))))
    val out = ChapterAssembler.assemble("book", input) { it }
    assertEquals(0L, out[0].startMs)          // anchored
    assertEquals(2000L, out[0].durationMs)    // tiles the whole file
}
```

- [ ] **Step 2: Run red.** `./gradlew :feature:audiobooks:testDebugUnitTest --tests "*ChapterAssemblerTest"` — FAIL.
- [ ] **Step 3: Implement** `ChapterAssembler`:

```kotlin
object ChapterAssembler {
    data class FileChapters(val fileUri: String, val durationMs: Long, val marks: List<Mp4ChapterParser.Chapter>)

    /** Contiguous chapters across files; each file's first chapter anchored to 0. */
    fun assemble(bookId: String, files: List<FileChapters>, fileStem: (String) -> String): List<ChapterEntity> {
        val out = mutableListOf<ChapterEntity>()
        var index = 0
        for (f in files) {
            val marks = f.marks.ifEmpty { listOf(Mp4ChapterParser.Chapter(fileStem(f.fileUri), 0)) }
                .sortedBy { it.startMs }
                .toMutableList()
            // anchor first chapter of this file to 0
            if (marks.first().startMs != 0L) marks[0] = marks[0].copy(startMs = 0)
            marks.forEachIndexed { i, m ->
                val end = marks.getOrNull(i + 1)?.startMs ?: f.durationMs
                out.add(
                    ChapterEntity(
                        bookId = bookId, chapterIndex = index++, title = m.title,
                        fileUri = f.fileUri, startMs = m.startMs,
                        durationMs = (end - m.startMs).coerceAtLeast(0),
                    ),
                )
            }
        }
        return out
    }
}
```

- [ ] **Step 4: Run green.** same command — PASS.
- [ ] **Step 5: Wire the importer.** In `AudiobookImporter.kt`: `importNew` switches on `SingleFile`/`MultiFile`.
  - `SingleFile`: keep today's behavior (extract metadata; `chapterSource.chaptersOf` with whole-file fallback; `SourceKind.SINGLE_FILE`). Works for a lone `.mp3` too (`chaptersOf` empty → one chapter).
  - `MultiFile`: for each `file` in order, `extractor.extract(uri)` (durationMs) + `chapterSource.chaptersOf(uri)`; build `ChapterAssembler.FileChapters`; call `ChapterAssembler.assemble`. Title = `book.title` (dir name); cover/author from the **first** file's metadata; book `durationMs` = Σ chapter durations; `SourceKind.MULTI_FILE`.
- [ ] **Step 6: Gate.** `./gradlew test lint assembleDebug` — green.
- [ ] **Step 7: Commit.** `git commit -am "feat(audiobooks): import multi-file books with flattened per-file chapters"`

---

## Chunk 3: Playback + UI generalization

### Task 3.1: `QueueBuilder` MULTI_FILE via `ChapterTimeline` (TDD)

**Files:** Modify `QueueBuilder.kt`; extend its test.

- [ ] **Step 1: Failing test** — MULTI_FILE book, 2 files (file A has 2 chapters, file B has 1): `items.size == 2`; `items[i].uri` == each file's uri; `items[i].mediaId` encodes **fileIndex** (0,1); `startAtMs` in file B maps to `startIndex=1` with the right offset (use `PositionMapper`). Keep a SINGLE_FILE assertion unchanged.
- [ ] **Step 2: Run red.**
- [ ] **Step 3: Implement** MULTI_FILE branch:

```kotlin
SourceKind.MULTI_FILE -> {
    val files = ChapterTimeline.files(chapters)
    val fileDurations = ChapterTimeline.fileDurations(chapters)
    val start = PositionMapper.toFilePosition(fileDurations, startAtMs)
    PlayRequest(
        items = files.mapIndexed { fileIndex, uri ->
            PlayableItem(
                mediaId = AudiobookMediaId.encode(book.id, fileIndex),
                uri = uri,
                title = book.title,
                artist = book.author.orEmpty(),
            )
        },
        startIndex = start.fileIndex,
        startPositionMs = start.offsetMs,
        mediaType = MediaType.AUDIOBOOK,
        speedOverride = book.speedOverride,
    )
}
```
(SINGLE_FILE branch unchanged from today's `M4B`.)

- [ ] **Step 4: Run green.** **Step 5: Commit.** `git commit -am "feat(audiobooks): QueueBuilder multi-file path via ChapterTimeline"`

### Task 3.2: `PlayerChapters` MULTI_FILE branch (TDD)

`currentIndex` = file (queue item) index; `positionMs` = offset in that file. Convert to global via `ChapterTimeline.globalStartOf(file) + positionMs` using file durations, then operate on the global chapter list.

**Files:** Modify `PlayerChapters.kt`; update `PlayerChaptersTest.kt`.

- [ ] **Step 1: Failing tests.** Fixture: file A `[c0 0..1000, c1 1000..3000]`, file B `[c2 0..1500]`.
  - `current(MULTI_FILE, currentIndex=0, positionMs=1500)` → `ChapterUi.index == 1`, `count == 3`, `positionInChapterMs == 500`, `chapterDurationMs == 2000`.
  - `current(MULTI_FILE, currentIndex=1, positionMs=200)` → index `2` (file B).
  - `tap(MULTI_FILE, chapterIndex=1)` → `SeekTarget(index=0, positionMs=1000)` (file A, in-file offset 1000).
  - `tap(MULTI_FILE, chapterIndex=2)` → `SeekTarget(index=1, positionMs=0)` (file B).
  - `ticks` → global starts `{1000, 3000}` / total `4500`.
  - `itemSeek(MULTI_FILE, 3200f/4500f)` → `SeekTarget(1, ~200)`.
  - **Rewrite** the old "mp3 current chapter is the queue index" test: under one-chapter-per-file it still holds (file index == chapter index), so re-express it as a MULTI_FILE fixture with one chapter per file and assert `ChapterUi.index == currentIndex`.
- [ ] **Step 2: Run red.** `./gradlew :feature:player:testDebugUnitTest --tests "*PlayerChaptersTest"` — FAIL.
- [ ] **Step 3: Implement** a `MULTI_FILE` branch in `current`/`tap`/`ticks`/`itemFraction`/`itemSeek` delegating to `ChapterTimeline` + `PositionMapper`. Helper inside the object:

```kotlin
// global position for a MULTI_FILE (fileIndex, in-file offset)
private fun globalOf(chapters: List<ChapterEntity>, fileIndex: Int, positionMs: Long): Long =
    PositionMapper.toGlobal(ChapterTimeline.fileDurations(chapters), fileIndex, positionMs)
```
- `current`: `val g = globalOf(...)`; `val i = ChapterTimeline.chapterAtGlobal(chapters, g)`; `ChapterUi(i, chapters.size, chapters[i].title, g - ChapterTimeline.globalStartOf(chapters, i), chapters[i].durationMs)`.
- `tap(chapterIndex)`: `SeekTarget(ChapterTimeline.fileIndexOf(chapters, chapterIndex), chapters[chapterIndex].startMs)`.
- `ticks`: `chapters.indices.map { ChapterTimeline.globalStartOf(chapters, it) }.filter { it > 0 }.map { it / total }`.
- `itemFraction`: `globalOf(...) / total`.
- `itemSeek(fraction)`: `val g = fraction*total`; `val p = PositionMapper.toFilePosition(ChapterTimeline.fileDurations(chapters), g)`; `SeekTarget(p.fileIndex, p.offsetMs)`.
- `next`/`previous` already build on `current`/`tap` — no change.
- [ ] **Step 4: Run green.** **Step 5: Commit.** `git commit -am "feat(player): PlayerChapters multi-file branch (global chapter index)"`

### Task 3.3: Fix `chapterNumberFor`, listeners, `PlayerViewModel`, `ChaptersPage`

**Files:** `BookmarksPage.kt`, `AudiobookPositionListener.kt`, `PlayerViewModel.kt`, `ChaptersPage.kt`.

- [ ] **Step 1: Failing test** for `chapterNumberFor` (extract it to a testable pure fn or test via a small wrapper). MULTI_FILE fixture file A `[c0,c1]`, file B `[c2]`; a global position inside `c1` → returns `2` (chapter number), **not** `1` (file number).
- [ ] **Step 2: Run red.**
- [ ] **Step 3: Implement.**
  - `BookmarksPage.chapterNumberFor` MULTI_FILE: `ChapterTimeline.chapterAtGlobal(chapters, globalMs) + 1`. (SINGLE_FILE unchanged.)
  - `AudiobookPositionListener` MULTI_FILE: `PositionMapper.toGlobal(ChapterTimeline.fileDurations(chapters), parsed.fileIndex, positionMs)` (same call, but durations are now per-file via `ChapterTimeline`, since chapters may exceed files). SINGLE_FILE → `positionMs`.
  - `PlayerViewModel.onBookmarkTap` MULTI_FILE: map the bookmark's global position to `(fileIndex, offset)` via `PositionMapper.toFilePosition(ChapterTimeline.fileDurations(chapters), bookmark.positionMs)` then `seekTo(fileIndex, offset)`. SINGLE_FILE → `seekTo(0, positionMs)`.
  - `PlayerViewModel.currentGlobalMs` MULTI_FILE: `PositionMapper.toGlobal(ChapterTimeline.fileDurations(chapters), currentIndex, positionMs)`.
  - `ChaptersPage`: sort for both kinds by global order (for MULTI_FILE keep DB order; for SINGLE_FILE keep `sortedBy startMs`); time column = chapter **start position**: SINGLE_FILE `TimeFormats.clock(chapter.startMs)`, MULTI_FILE `TimeFormats.clock(ChapterTimeline.globalStartOf(chapters, i))`. Pass enough context (the full chapter list is already passed).
  - Verify `currentChapterIndex` fed to `ChaptersPage` (`PlayerScreen.kt:82`) is the **global chapter index** (i.e. `ChapterUi.index`), not the file index.
- [ ] **Step 4: Gate.** `./gradlew test lint assembleDebug` — green.
- [ ] **Step 5: Commit.** `git commit -am "fix(player): multi-file chapter number, bookmark jump, chapters list, position persistence"`

---

## Chunk 4: Sleep-timer "end of chapter" for in-file boundaries

- [ ] **Step 1: Investigate.** Find the end-of-chapter sleep consumer (grep `chapterBoundariesMs`, "end of chapter", sleep timer in `core:playback` + `feature:player`). Determine whether boundaries are per-item or global, and how they're computed for the current model.
- [ ] **Step 2: Decide + write failing test** for the chosen unit (e.g. "next chapter boundary after global position" for a MULTI_FILE book where the boundary is **inside** the current file/item).
- [ ] **Step 3: Implement** so end-of-chapter uses the current chapter's global end (`ChapterTimeline.globalStartOf(chapters, currentChapter+1)`), mapped to a within-item target if the sleep mechanism stops at item-relative positions.
- [ ] **Step 4: Gate + commit.** `git commit -am "fix(player): end-of-chapter sleep respects in-file chapter boundaries"`

---

## Chunk 5: Device verification (Pixel 7a)

- [ ] Re-point/keep the audiobook folder at `/storage/emulated/0/Audiobooks` and rescan.
- [ ] **ESV Bible folder = one book** with per-file chapters (not ~1189 books).
- [ ] **Ada Palmer "Inventing the Renaissance" = one book** with chapters spanning the 6 `.m4b` parts.
- [ ] Play **across a file boundary** (last seconds of part N → part N+1) — continuous, position correct.
- [ ] **Chapter-tap to a chapter in a later file** (non-zero in-file offset) seeks correctly.
- [ ] Chapters list is **in order with start positions**; current chapter highlighted on the right row.
- [ ] **Bookmark inside a later chapter** reports the right **chapter** number; jump returns there.
- [ ] **Resume** after process death lands at the right global position (smart rewind ok).
- [ ] **Sleep "end of chapter"** stops at the current chapter's end (mid-file).
- [ ] **Regression:** a normal single-`.m4b` book still shows its `chpl` chapters; a multi-`.mp3` book still plays as before.

---

## After all chunks

Announce and use **superpowers:finishing-a-development-branch** — verify tests, then present merge/PR options (project merges via PR, merge commit, branch deleted).
