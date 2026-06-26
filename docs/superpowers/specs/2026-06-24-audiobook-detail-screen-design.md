# Audiobook Detail Screen + Play-UX — Design

**Date:** 2026-06-24
**Status:** Approved (brainstorming)
**Branch:** `audiobook-detail-screen` (stacked on `phase-6a-audiobookshelf` / PR #11; rebase onto `main` after #11 merges)

## Goal

Tapping an audiobook cover in the **Audiobooks library grid** opens a **book-info detail
screen** instead of starting playback. From there the user reads the book's info (synopsis,
series, author, duration, progress) and chooses to **Stream** or **Download** (ABS) or
**Play/Resume** (local / downloaded). This screen becomes the single home for starting
audiobook playback, which fixes the Phase 6a streaming defect.

## Background / why

Phase 6a shipped ABS connect + catalog mirror + download, but **streaming a never-downloaded
ABS book is broken**: `AudiobookListViewModel.onPlayBook` builds the queue directly via
`QueueBuilder`, bypassing `AudiobookPlayRequestFactory` — the only **play-path** caller of
`BookDetailResolver.ensureDetails` (the ABS downloader also resolves, but only when
downloading). So an un-resolved ABS book (sourceUri `""`) plays an empty URI → ExoPlayer
`FileDataSource` ENOENT. On device, 540/541 mirrored books were never resolved.

The detail screen fixes this structurally: **opening it resolves the book** (the same
`ensureDetails` call), and every play path then flows through a resolve step, so an empty-URI
play becomes impossible. The resolve also fetches the synopsis/series, so one network trip
serves both the info display and the stream fix.

## Scope decisions (locked)

- **Layout:** minimal — no chapter list on the detail screen (chapters stay in the player).
- **Content:** cover, title, author, **series** (new data), duration, **synopsis** (new data,
  from ABS), and progress stats.
- **Applies to both origins:** tapping any library cover (local or ABS) opens the detail
  screen. ABS-only rows (synopsis, series, download) simply don't render for local books.
- **Playlists unchanged:** inside a playlist/queue a tap still plays directly. The detail
  screen is a *library-grid* behavior only.
- **Data strategy: Approach A** — store `description` + `series` on the book, lazy-filled when
  the detail screen first resolves the book. Offline-friendly; instant on re-open; reusable for
  a future "browse by series." Costs a destructive DB bump (one-time local library wipe).

## Design

### 1. Data layer — `core:database` + `feature:audiobookshelf`

- `BookEntity` gains two nullable columns: `description: String? = null`, `series: String? = null`
  (a display string, e.g. `"Foundation #2"`). `OratorDatabase` version → **9** (destructive
  migration, consistent with prior phases; installing wipes the local library once → user
  re-picks the SAF folder and the ABS catalog re-syncs).
- `AbsBookDetail` and the expanded-item DTO gain `description: String?` and `series: String?`.
  `AbsItemDetailMapper` reads `media.metadata.description` and the `media.metadata.series[]`
  array (first entry → `"<name> #<sequence>"`, name-only when no sequence, `null` when absent).
  DTO uses the existing `ignoreUnknownKeys` / `coerceInputValues` JSON config.
- `AbsBookDetailResolver.ensureDetails` (today fills `sourceKind` + `sourceUri` + chapters on
  first touch) now **also writes `description` + `series`**. Same "resolve once" guard
  (`sourceUri.isBlank()`); post-wipe every book is fresh, so the first detail-open (or the
  download path, which also calls this) fills all of it — downloaded books carry the blurb
  offline.

### 2. Detail screen — `feature:audiobooks`

New `AudiobookDetailScreen` + `AudiobookDetailViewModel`. No new cross-feature coupling: ABS
behavior is reached through the existing `Set<BookDetailResolver>` / `Set<BookDownloadController>`
seams that `feature:audiobooks` already injects.

- **Route:** `audiobooks/{bookId}` added to `AudiobooksRoutes`, registered in
  `AudiobooksFeatureEntry` (mirrors `PodcastsFeatureEntry`'s detail wiring). ABS ids are
  `abs:<uuid>` (contain a colon) → `Uri.encode` the id when navigating, read it decoded from
  the back-stack args.
- **ViewModel** (origin from the book; injects `BookDao`/repository, the two seam sets,
  `PlaybackConnection`, player preferences):
  - On init, launch `resolve()`: pick the origin-matched `BookDetailResolver` and call
    `ensureDetails(bookId)` (resolves stream URL + chapters, fills synopsis/series).
  - Exposes a state flow: metadata we already have renders immediately; synopsis/series show a
    small spinner until resolve completes. Already-resolved / downloaded books make no network
    call. Observes the book so download-state and progress update live.
  - **Actions:**
    - `onPlay(onOpenPlayer)`: re-read the fresh book, build the queue via `QueueBuilder` with
      the **cold-start smart-rewind** logic relocated from today's `onPlayBook` (resume-if-loaded
      shortcut preserved), `PlaybackConnection.play(...)`, then `onOpenPlayer()`.
    - `onDownload()` / `onRemoveDownload()` via the `BookDownloadController` seam (moved from the
      list VM).
- **Layout** (minimal, scrollable): back top-bar → centered cover (origin-aware: ABS URL string
  vs local `File`) → title → author → `Series · <series>` (when present) → duration → progress
  stats (`"63% · 2h 51m left · last played Jun 22"`, or `"Not started"`) → actions → synopsis
  text.
- **Action buttons by state:**
  - LOCAL → `[▶ Play/Resume]`
  - ABS · NONE → `[▶ Stream] [⬇ Download]`
  - ABS · DOWNLOADING → `[▶ Stream] [… Cancel]`
  - ABS · DOWNLOADED → `[▶ Play/Resume] [✓ Remove download]`
  - Play and Stream are the same underlying action; the label only signals local vs network.
    Saved resume position is always honored.
  - State source: `DownloadState` (`NONE`/`DOWNLOADING`/`DOWNLOADED` — `DOWNLOADING` is a real
    enum value the worker sets and the old grid badge already rendered) drives which row shows;
    Cancel is `BookDownloadController.cancel` (the seam already exposes enqueue/cancel/remove).
    Resolver/controller are selected origin-matched (`firstOrNull { it.handles(book.origin) }`),
    so local books no-op cleanly.

### 3. Library grid — `AudiobookListScreen` / `AudiobookListViewModel`

- Tile tap → **navigate to the detail route** (new `onOpenBook(bookId)` callback) instead of
  `onPlayBook`.
- **Remove** the corner `DownloadBadge` (download now lives on the detail screen) and the
  **"not started"** subline.
- Long-press → add-to-playlist stays.
- `onPlayBook` and the download/remove handlers move out of the list VM into the detail VM; the
  broken empty-URI play path is deleted outright.

### 4. Folded-in player tweaks — `feature:player`

- Pager order for books → **Cover → Chapters → Bookmarks** (swap pages 1↔2, update the dots).
- `DualProgressBars` → **book bar on top, chapter bar on bottom**. NOT a trivial call-site
  argument swap: the styling is parameter-bound — the *top* slot is hardcoded to the bright
  accent + thumb, the *bottom* slot carries the chapter ticks (`item.ticks`). To put the book
  bar on top without it inheriting the chapter styling (and without dropping the chapter ticks),
  adjust the component's internal layout/styling, not just the call-site `BarSpec` order.

## Testing

- **ABS mapper:** extracts `description` + `series` from an expanded-item JSON, including the
  `series[]` array → `"name #seq"`, name-only, and missing/empty cases.
- **Resolver:** `ensureDetails` writes `description` + `series` onto the book.
- **`AudiobookDetailViewModel`** (Robolectric + in-memory Room, like
  `AudiobookPlayRequestResolverTest`): opening a never-resolved ABS book triggers `ensureDetails`
  (sourceUri + synopsis/series filled), and **`onPlay` builds a non-empty queue** — the
  regression test for the streaming P0, now on the real entry point. Plus a pure action-state
  mapping test (LOCAL vs ABS × downloadState).
- **DB v9:** destructive bump → no migration test (matches prior phases).
- **Player tweaks:** UI-only → manual/device check.

## Out of scope (future cycles)

- Two-way ABS progress sync (Phase 6b).
- Full "browse by author / series / title" with grouping/sort UI (own cycle; this cycle only
  *stores* `series` as a byproduct).
- Chapter list / local m4b description extraction on the detail screen.
- Cover fast-scroll prefetch + the OkHttp/Coil cover polish (separate follow-up).

## Risks / notes

- Destructive v9 migration wipes the device library once (known pattern; user re-picks the SAF
  folder + re-syncs ABS).
- Branch is stacked on the unmerged 6a; rebase onto `main` after PR #11 merges (repo uses merge
  commits, so duplicated 6a commits drop cleanly on rebase).
- Exact ABS `media.metadata` JSON shape for `description`/`series` to be confirmed against a live
  expanded-item response during implementation.
