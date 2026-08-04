# Audiobook Exploration Restyle — Design

**Date:** 2026-06-26
**Status:** Approved (design)
**Branch:** `audiobook-exploration` (off `main`, post PR #12)

## Goal

Restyle how the user browses and explores their audiobook library. Today the
library is a single flat 3-column cover grid hardcoded to `ORDER BY title`, with
a bare top bar (`☰ Audiobooks`) and no actions. This cycle adds:

1. **Switchable sort/grouping** — Recently added (default) / Title / Author / Series.
2. **A search screen** — multi-category results (Books / Series / Authors).
3. **A passive offline badge** on downloaded grid tiles.

Folds in backlog items: orator-6a-followups item D (browse by author/series/title,
sort by recently-added), orator-detail-screen-device-notes note 7 (audiobook
search) and note 4 (offline indicator on tiles).

## Hard constraints (from CLAUDE.md)

- **Minimal & lightweight** — no feature bloat; quick and responsive.
- **Modular** — independently addable/removable; don't over-couple to other features.

Both are honoured by keeping *all* exploration logic in `feature:audiobooks` and
leaving the shared `core:database` module untouched (see Architecture).

## Decisions locked during brainstorming

- **Information architecture: single grid with switchable grouping/ordering** (not a
  drill-down index). Default order is **Recently added**.
- **Sort control: a top-bar `⇅` dropdown** (Material3 `DropdownMenu`) of the four
  modes, current mode check-marked. Keeps the grid full-height (chips rejected).
- **"Recently added" means added to the ABS library**, i.e. the ABS server's real
  `addedAt` — not the device's first-sync time.
- **Search is multi-category**: one query fans out into labelled `Books` / `Series` /
  `Authors` sections, each shown only if it has hits. Book hit → detail screen;
  series/author hit → a filtered cover grid.
- **Sorting/grouping/search run in-memory** in the feature ViewModel over the existing
  `BookDao.observeAll()` Flow. No new SQL in `core:database`.
- **Series remains stored as the display string `"Name #seq"`**; a single
  `parseSeries()` helper is the only code that knows the format (no schema change).
- **Series mode keeps a "Standalone" bucket** at the end so books without a series
  stay visible.

## Architecture

```
feature:audiobooks  (all new exploration code lives here)
  BookSortMode                 enum: RECENT, TITLE, AUTHOR, SERIES
  BookExplore                  pure object: sort / group / search / filter  ← unit-tested
  AudiobookUiPreferences       DataStore-backed sticky sort mode
  AudiobookListScreen          modified: ⇅ dropdown + 🔍 icon + sectioned grid
  AudiobookListViewModel       modified: exposes sortMode + grouped/sorted books
  AudiobookSearchScreen / VM   new: grouped local search
  AudiobookFilterScreen / VM   new: filtered cover grid (series=X | author=Y)

  AudiobooksRoutes             + AudiobookSearchRoute, + AudiobookFilterRoute (local)

feature:audiobookshelf  (only data change)
  AbsLibraryItem               + addedAt: Long? = null
  AbsMetadata                  + seriesName: String? = null  (minified payload field)
  AbsBookMapper                addedAtUtc = item.addedAt ?: now; series from seriesName
  AbsCatalogReconciler         prefer server addedAt (fall back to prev only if null)

core:designsystem
  OnyxIcons                    + Sort, + Downloaded  (hand-rolled, no extended-icons dep)
  CoverTile                    + downloaded: Boolean = false  (top-start badge)
```

All exploration types operate on **`BookEntity`** (the type `repository.observeBooks()`
emits — there is no separate domain `Book` model in this layer).

**Why in-memory:** ~540 books sort/group/filter sub-millisecond in Kotlin; one data
source (`observeBooks()`) feeds the grid, all four sort modes, and search alike; and the
shared `core:database` module gains no feature-specific query surface, so the whole
feature stays cleanly removable.

**Icon constraint:** `core:designsystem` deliberately avoids `material-icons-extended`
(`OnyxIcons.kt` hand-rolls vectors). `Search` and `Menu` are in `material-icons-core`
(usable directly), but the **sort** glyph and the **downloaded** badge are extended —
so this cycle adds two hand-rolled `OnyxIcons` entries (`Sort`, `Downloaded`) rather
than pulling in the extended dependency.

## Component design

### 1. Data layer

**ABS `addedAt` capture** (`feature:audiobookshelf`):
- `AbsLibraryItem` gains `val addedAt: Long? = null` (ABS sends epoch-ms at the item
  level, present in the minified payload; `AbsJson` has `ignoreUnknownKeys = true` so a
  wrong guess degrades to null).
- `AbsBookMapper.toBook`: `addedAtUtc = item.addedAt ?: System.currentTimeMillis()`.
- `AbsCatalogReconciler`: currently force-keeps `prev.addedAtUtc` ("device-owned").
  Change so ABS recency reflects the server: take the freshly-mapped (server) value,
  falling back to the previous value **only when the server omits `addedAt`**.
- **No DB migration** — `addedAtUtc` already exists as `INTEGER NOT NULL` in the right
  units. LOCAL books keep using scan/discovery time (the only signal available), so
  the two pools co-sort on one timeline.

**Series populated at catalog sync** (`feature:audiobookshelf`):
- Today `series` is filled **only lazily** by `AbsBookDetailResolver.ensureDetails`
  (on first open/play), reading the expanded `media.metadata.series[]` array. The
  catalog-sync `AbsBookMapper` never sets it — so a freshly-synced library has `series`
  null on every never-opened ABS book, which would dump almost everything into SERIES
  mode's "Standalone" bucket and yield no series-search hits until each book is opened.
- Fix: the minified items payload (`…/items?minified=1`, the sync path) includes a
  flattened `media.metadata.seriesName` string already in `"Name #seq"` shape. Add
  `val seriesName: String? = null` to `AbsMetadata` and have `AbsBookMapper.toBook` set
  `series = seriesName?.substringBefore(",")?.trim()?.takeIf { it.isNotBlank() }` (first
  series when a book belongs to several). Both writers then produce the same stored
  `"Name #seq"` format: catalog sync from `seriesName`, the lazy resolver from
  `series[]`. AUTHOR mode already works at sync (the mapper sets `authorName`).
- **Implementation check:** confirm the live ABS minified item exposes `metadata.seriesName`
  in this shape (the detail-screen cycle set the same precedent of verifying ABS JSON
  against a live item before relying on a field).

**Sort state**:
- `enum class BookSortMode { RECENT, TITLE, AUTHOR, SERIES }`.
- `AudiobookUiPreferences` — a small DataStore wrapper persisting the selected mode
  (same pattern as the existing player preferences). Default `RECENT`.

### 2. `BookExplore` (pure, Android-free → plain JUnit)

```
object BookExplore {                               // operates on BookEntity throughout
    fun sort(books: List<BookEntity>, mode): List<BookEntity>
    fun group(books: List<BookEntity>, mode): List<Section>     // Section(header, books)
    fun search(books: List<BookEntity>, term): SearchResults    // (books, series, authors)
    fun filterSeries(books: List<BookEntity>, name): List<BookEntity>
    fun filterAuthor(books: List<BookEntity>, name): List<BookEntity>
    fun parseSeries(stored: String): Pair<String, Int?>   // "Foundation #2" -> ("Foundation", 2)
}
```

- `sort` — RECENT = `addedAtUtc` desc; TITLE = title asc (case-insensitive). AUTHOR /
  SERIES fall back to TITLE here (grouping is via `group`).
- `group` —
  - **AUTHOR**: group by `author`; sections alphabetical by author; books within a
    section sorted by title; an **"Unknown author"** bucket (null/blank author) last.
  - **SERIES**: group by parsed series **name**; sections alphabetical by name; books
    within a section sorted by parsed sequence (numeric; null sequence last); a
    **"Standalone"** bucket (no series) last so nothing disappears.
- `search` — lowercase *contains* match. `books` = title matches. `series` = distinct
  parsed series names that match (each with a book count). `authors` = distinct authors
  that match (each with a count). Empty term → all-empty results.
- `filterSeries(name)` — books whose parsed series name == name, sorted by sequence.
- `filterAuthor(name)` — books whose author == name, sorted by title.
- `parseSeries` — split on the last `" #"`; sequence parsed to Int when numeric, else
  null. The single source of truth for the stored format.

### 3. Library screen (`AudiobookListScreen` / `AudiobookListViewModel`)

- VM exposes `sortMode: StateFlow<BookSortMode>` (seeded from `AudiobookUiPreferences`)
  and a derived view of the library: either a flat `List<BookEntity>` (RECENT/TITLE) or a
  `List<Section>` (AUTHOR/SERIES). `onSortSelected(mode)` updates state and persists.
- Top bar `trailing` slot → a `Row { SortMenu(); IconButton(Icons.Filled.Search) }`.
  - `SortMenu` = an `IconButton(OnyxIcons.Sort)` that toggles a `DropdownMenu` of four
    `DropdownMenuItem`s; the active mode shows a leading check.
  - `Search` and `Menu` are core icons; `OnyxIcons.Sort` is the new hand-rolled glyph.
- The sort + search actions render **only when the library is non-empty** (hidden in the
  no-folder and empty-library states, where they'd be inert).
- Body:
  - **RECENT / TITLE** → existing flat `LazyVerticalGrid` of `CoverTile`.
  - **AUTHOR / SERIES** → same grid, but each section emits a full-width header via
    `item(span = { GridItemSpan(maxLineSpan) })` followed by `items(section.books)`.
- Tile-tap → detail, long-press → add-to-playlist: unchanged.

### 4. Offline badge (`CoverTile`)

- New optional param `downloaded: Boolean = false`.
- When true, draw a small `OnyxIcons.Downloaded` (new hand-rolled vector — the existing
  `OnyxIcons` set avoids the extended-icons dependency) in a dark rounded chip at the
  **top-start** corner (clear of the bottom caption scrim and progress strip). Passive,
  non-interactive.
- The list/filter VMs pass `downloaded = book.downloadState == DownloadState.DOWNLOADED`;
  the badge then appears wherever `CoverTile` is used.

### 5. Search screen (`AudiobookSearchScreen` / `AudiobookSearchViewModel`)

- Structure mirrors the podcast `SearchScreen`: `OnyxTopBar(back, "Search")` +
  `OutlinedTextField` (single-line, search IME action). But the VM filters the **local**
  library in-memory via `BookExplore.search` — instant, offline, no provider/error states.
- VM holds the library list (`repository.observeBooks()`) and recomputes `SearchResults`
  as the term changes. UI renders labelled sections (each only if non-empty):
  - **Books** → `RowArt` cover + title + author → `onOpenBook(id)` → detail route.
  - **Series** → `"<name> · N books"` → `onOpenSeries(name)` → filtered grid.
  - **Authors** → `"<author> · N books"` → `onOpenAuthor(name)` → filtered grid.
- Empty term → empty list; non-empty term with no hits → a "No matches" line.

### 6. Filtered grid (`AudiobookFilterScreen` / `AudiobookFilterViewModel`)

- One screen, parameterized by `type` (`series` | `author`) + `value` (URL-encoded).
- `OnyxTopBar(back, title = value)`; body is a 3-column `CoverTile` grid of the filtered,
  sorted books (`BookExplore.filterSeries` / `filterAuthor`).
- Tile-tap → detail, long-press → add-to-playlist: same as the main grid.

### 7. Navigation & module wiring

- Routes go in the **local** `feature/audiobooks/.../AudiobooksRoutes.kt` (alongside the
  existing `AudiobookDetailRoutePattern` / `audiobookDetailRoute`), not in
  `core:navigation` `CommonRoutes` — these routes are navigated to only *within*
  `feature:audiobooks`, and `CommonRoutes` is reserved for cross-feature destinations.
  Add `AudiobookSearchRoute = "audiobooks/search"` and a filter pattern
  `audiobooks/filter/{type}/{value}` with an `audiobookFilterRoute(type, value)` builder
  that `Uri.encode`s the value — mirroring how `audiobookDetailRoute` already encodes the
  `abs:<uuid>` id.
- `AudiobooksFeatureEntry`: register the search and filter composables; pass nav
  callbacks (`onOpenBook`, `onOpenSeries`, `onOpenAuthor`, `onBack`). The list screen's
  🔍 navigates to `AudiobookSearch`.

## Data flow

```
Room books ──observeBooks()──> repository ──> VM(s)   (BookEntity throughout)
                                   │
   list VM:    sortMode + BookExplore.sort/group ──> flat list | sections ──> grid
   search VM:  term + BookExplore.search ──> Books/Series/Authors sections
   filter VM:  type+value + BookExplore.filterSeries/Author ──> grid
```

Navigation: list `🔍` → search; search Book row → detail; search Series/Author row →
filtered grid; filtered grid tile → detail.

## Error handling / edge cases

- **Blank/null author** → "Unknown author" bucket (AUTHOR mode) / excluded from author
  search hits.
- **No series** → "Standalone" bucket (SERIES mode) / excluded from series search hits.
  With series now populated at catalog sync (§1), this bucket holds genuinely
  standalone books, not merely un-opened ones.
- **Series string without `#seq`** → `parseSeries` returns `(name, null)`; sequence-null
  books sort last within their series section.
- **Empty library / no folder** → existing empty states unchanged; the sort + search
  actions are **hidden** (not shown inert) until the library has books.
- **ABS item missing `addedAt`** → mapper falls back to `now`; reconciler keeps the
  previous timestamp (only in this omitted-field case) to avoid churn across syncs.
- **ABS item missing `seriesName`** → `series` stays null at sync; the lazy resolver
  still fills it from the expanded `series[]` on first open.
- **Search term is whitespace** → treated as empty (no results).

## Testing

Pure-function unit tests (no Robolectric) over `BookExplore` carry the weight:
- `sort`: RECENT orders by `addedAtUtc` desc; TITLE case-insensitive asc.
- `group` AUTHOR: alphabetical sections, "Unknown author" bucket last.
- `group` SERIES: parses `"Name #seq"`, numeric sub-sort, "Standalone" bucket last.
- `search("redwall")`: non-empty Books + Series sections, empty Authors; case-insensitive
  contains; series/authors de-duplicated with correct counts.
- `filterSeries`: ordered by sequence; `filterAuthor`: ordered by title.
- `parseSeries`: `"Foundation #2"` → `("Foundation", 2)`; no-`#` → `(name, null)`.

Data-layer tests:
- `AbsBookMapper` maps `item.addedAt` into `addedAtUtc`; falls back to non-zero when null.
- `AbsBookMapper` populates `series` from `metadata.seriesName` (first of several;
  null when absent).
- `AbsCatalogReconciler` prefers the server `addedAt` over the previous value, but keeps
  the previous value when the server omits it.
- DTO parses `addedAt` + `seriesName` (and tolerates their absence via `ignoreUnknownKeys`).

Gate each chunk with `ANDROID_HOME=~/Android/Sdk ./gradlew test lint assembleDebug`.

## Out of scope

The other device-test polish notes are a **separate** branch, not this cycle: rescan
button animation (1), HTML rendering in ABS descriptions (2), download-button progress
animation (3), Queue shows book not chapters (5), partially-played books auto-join queue
(6), mini-player book-cover icon (8). This cycle is exploration/browse only — notes 4
(offline indicator) and 7 (search) plus the sort/browse work.
