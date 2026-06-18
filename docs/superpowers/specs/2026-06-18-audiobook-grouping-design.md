# Audiobook Multi-File Grouping — Design

**Date:** 2026-06-18
**Status:** Approved (design); spec for implementation planning
**Type:** P2 bugfix / scanner + book-model change (pre-existing defect, surfaced during the Onyx UI device checklist)

---

## Problem

`AudiobookScanner` treats **every `.m4b` file as its own book, at any depth**. A directory
containing several `.m4b` files therefore shows up as several separate "books." Real examples
in the user's library (`/storage/emulated/0/Audiobooks/`):

- `Holy Bible - ESV read by David Heath/` — ~1189 numbered `.m4b` files → ~1189 fake books.
- `Ada Palmer/Inventing the Renaissance/` — 6 parts `(1)`–`(6)`.m4b of one work → 6 fake books.

The `.mp3` path already does the right thing ("a directory whose direct children are `.mp3`
files is one book"); only `.m4b` is inconsistent.

**Desired rule (user, 2026-06-18):** *a directory of audio files is one book.* The user's
library uses one-folder-per-book (`Author/Title/parts…`), so grouping by directory is safe.

**Chapter granularity (user decision, 2026-06-18):** for a multi-part `.m4b` book, **flatten
each file's internal `chpl` chapters**, concatenated across files in playback order (not just
"one chapter per file"). When a part has no internal chapters, it contributes a single
file-level chapter — so this degenerates to "file = chapter" for mechanically-split books.

## Goals

- A directory containing multiple audio files (`.m4b` and/or `.mp3`) is imported as **one
  book**, files natural-sorted, with all chapters flattened in order.
- A directory containing exactly **one** audio file stays a single-file book with its full
  internal `chpl` chapters (the common case — must not regress).
- Existing single-`.m4b` and multi-`.mp3` playback (both device-verified) keep working
  identically.

## Non-goals

- CD1/CD2-style books where the parts live in **sub**directories with no audio at the parent
  level still scan as separate books (pre-existing v1 limitation — out of scope).
- No new metadata UI, no per-file artwork, no reordering UI.
- audiobookshelf (Phase 6) is unaffected.

---

## Design

### The unifying insight: chapters tile the timeline

Chapters are **contiguous** — together they cover the whole book end to end, in order. That
single fact removes the need for any new position math:

- A chapter's **global start** = sum of all *preceding* chapters' `durationMs`.
- A **file's duration** = sum of the `durationMs` of the chapters whose `fileUri` is that file
  (chapters within a file partition that file exactly).
- The ordered, de-duplicated `fileUri` list (in chapter order) **is** the playback queue;
  `PositionMapper` (already built, takes `fileDurationsMs`) maps global ⇄ (fileIndex, offset).

`ChapterEntity` already carries everything needed: `fileUri`, `startMs` (offset **within its
file**), `durationMs`, `chapterIndex`. No schema column changes.

### `SourceKind`: two kinds instead of three

| Today | Becomes | Meaning |
|-------|---------|---------|
| `M4B` | `SINGLE_FILE` | exactly one file; chapters = internal `chpl` offsets (or whole-file) |
| `MP3_DIR` | `MULTI_FILE` | N files; chapters = per-file `chpl` flattened, contiguous |

`MP3_DIR` is the no-`chpl` special case of `MULTI_FILE` (one chapter per file), so it folds in
with **identical output**. A multi-`.m4b` book is `MULTI_FILE` with possibly several chapters
per file.

> Enum rename → **destructive DB migration** (bump version, `fallbackToDestructiveMigration`),
> consistent with the project's established pattern (DB has been bumped destructively before;
> a rescan rebuilds the library). No user data of value is lost — positions/bookmarks for a
> mis-split library are not worth preserving, and the rescan re-imports everything correctly.

### Components & changes

1. **`AudiobookScanner`** (`feature/audiobooks/.../data/AudiobookScanner.kt`)
   - At each directory: collect **direct** audio children (`.m4b` + `.mp3`), natural-sorted.
   - If ≥1 audio file → emit one `ScannedBook`: `SingleFile` if exactly one file, else
     `MultiFile(files=[…])`. Title = directory name for multi-file; file-stem for a lone file.
   - **Always recurse into subdirectories** (so `Author/Book/parts` and nested libraries are
     found, and a stray file beside subfolders doesn't hide them).
   - `ScannedBook` sealed type collapses to `SingleFile(title, uri)` and
     `MultiFile(title, rootUri, files: List<ScannedFile>)`.

2. **`AudiobookImporter`** (`feature/audiobooks/.../data/AudiobookImporter.kt`)
   - `SingleFile`: unchanged behavior (extract metadata; `M4bChapterSource.chaptersOf` →
     `chpl` chapters, else one whole-file chapter).
   - `MultiFile`: for each file **in order**, run `M4bChapterSource.chaptersOf` (mp3 / no-`chpl`
     → one chapter for the whole file). Build **contiguous** `ChapterEntity` rows: a global
     `chapterIndex` increments across files; each chapter's `fileUri` = its file, `startMs` =
     `chpl` offset **within that file** (0 for a file's first/only chapter), `durationMs` =
     next-`chpl`-in-same-file − this-`chpl` (last chapter in a file = fileDuration − startMs,
     using the per-file extracted `durationMs`). Title = `chpl` title, else file stem.
   - Book `durationMs` = sum of all chapter `durationMs`. Cover/author from the first file.

3. **`QueueBuilder`** (`feature/audiobooks/.../data/QueueBuilder.kt`)
   - `SINGLE_FILE`: unchanged (one item; chapters are in-file seek targets;
     `chapterBoundariesMs` = `startMs` list).
   - `MULTI_FILE`: derive ordered files by grouping chapters by `fileUri` (preserving order);
     `fileDurations` = per-file summed chapter durations. `items` = one `PlayableItem` per file;
     `start` = `PositionMapper.toFilePosition(fileDurations, startAtMs)`. Subsumes the old
     `MP3_DIR` path (which is this with one chapter per file).

4. **`PlayerChapters`** (`feature/player/.../PlayerChapters.kt`) — pure math, fully TDD'd.
   - `SINGLE_FILE`: unchanged.
   - `MULTI_FILE`: derive `fileUri` groups + per-file durations from `chapters`. `current`
     converts (`currentIndex`=fileIndex, `positionMs`=offset-in-file) → global, then finds the
     contiguous chapter (last whose global start ≤ global). `tap(chapterIndex)` →
     `SeekTarget(fileIndexOf(chapter), chapter.startMs)`. `ticks`/`itemFraction` use global
     starts. `itemSeek(fraction)` → global → `PositionMapper.toFilePosition(fileDurations, …)`.
   - `next`/`previous` already build on `current`/`tap`, so they generalize for free.

5. **Sleep-timer "end of chapter"** — verify in planning: for `MULTI_FILE`, chapter boundaries
   can fall **inside** a queue item (file), not only at item transitions. The end-of-chapter
   sleep must use the **current chapter's** global end, not the item end. Check how
   `PlayRequest.chapterBoundariesMs` / the sleep logic consume boundaries and feed per-item or
   global chapter boundaries accordingly. (Flagged as a planning task, not a design unknown.)

### Data flow (multi-file)

```
directory (≥2 audio files)
  → AudiobookScanner: MultiFile(title=dirName, files=[f1,f2,…] natural-sorted)
  → AudiobookImporter: for each file, chpl → contiguous ChapterEntity rows
       (chapterIndex global; fileUri=file; startMs=in-file offset; durationMs)
  → BookEntity(sourceKind=MULTI_FILE, durationMs=Σ chapter durations)
  → QueueBuilder: files = group chapters by fileUri; items = one per file;
       fileDurations = Σ per file; start via PositionMapper
  → PlayerChapters: global ⇄ (fileIndex, offset) from grouped durations
```

## Edge cases

- **Single `.m4b` with `chpl`** → `SINGLE_FILE`, chapters preserved (unchanged path).
- **Directory of `.mp3` (no chpl)** → `MULTI_FILE`, one chapter per file (== old `MP3_DIR`).
- **Mixed `.m4b` + `.mp3` in one directory** → `MULTI_FILE`, all files natural-sorted together.
- **Directory with one `.mp3`** → `SINGLE_FILE` (one file, one chapter). Behavior identical.
- **`.m4b` part with no `chpl`** → contributes one file-level chapter (file stem as title).
- **Nested: `Author/` (no direct audio) → `Book/` (audio)** → recurse; `Book` is the book.
- **Natural sort**: `(1),(2),…,(10)` order via existing `NaturalOrder` comparator.

## Testing strategy (TDD)

- **`AudiobookScannerTest`**: 1 m4b → SingleFile; N m4b → MultiFile(ordered); mp3 dir →
  MultiFile; mixed m4b+mp3 → MultiFile(natural-sorted); nested author/book; stray file beside
  subfolders still finds subfolder books; empty dir → nothing.
- **`AudiobookImporter`**: multi-file contiguous chapter rows (indices, fileUri, startMs,
  durationMs), book duration = Σ; mp3-no-chpl yields one-chapter-per-file (regression guard).
- **`PlayerChaptersTest`**: MULTI_FILE `current`/`next`/`previous`/`tap`/`ticks`/`itemFraction`/
  `itemSeek` with a fixture of 2 files × 2 chapters each (cross-file boundaries); plus the
  existing SINGLE_FILE / one-chapter-per-file cases unchanged (regression).
- **`QueueBuilder`**: MULTI_FILE items = files, correct `fileDurations`, start mapping;
  SINGLE_FILE unchanged.
- Per-chunk gate: `./gradlew test lint assembleDebug`.
- **Device (Pixel 7a):** ESV Bible folder = **one** book with per-file chapters; Ada Palmer
  "Inventing the Renaissance" = one book with chapters spanning the 6 parts; play across a file
  boundary; chapter-tap to a chapter in a later file seeks correctly; resume; a normal
  single-`.m4b` book still shows its `chpl` chapters.

## Decisions made

- **Flatten internal chapters** across multi-file books (not file-as-chapter). *(user)*
- **Two `SourceKind`s** (`SINGLE_FILE`/`MULTI_FILE`), folding `MP3_DIR` into the general
  multi-file path. **Destructive DB migration**; rescan rebuilds.
- Group-by-`fileUri` to derive files + durations — **no new DB columns/tables**.

## Open questions for planning

- Exact sleep-timer "end of chapter" wiring for in-file chapter boundaries (see §5).
- Whether `AudiobookMediaId` indexing stays per-file (`encode(bookId, fileIndex)`) — expected
  yes, matching today's `MP3_DIR`.
