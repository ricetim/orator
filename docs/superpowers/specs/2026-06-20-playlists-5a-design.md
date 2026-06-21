# Phase 5a — Playlists — Design

**Date:** 2026-06-20
**Status:** Approved (design); spec for implementation planning
**Type:** New feature module (`feature:playlists`) + small additive seams in `core:database`,
`core:model`, `core:playback`. First slice of roadmap Phase 5.

---

## Problem / intent

Orator can play one episode or one book at a time; the Queue tab is read-only and shows only
the files of the *current* entity. The user wants **multiple, user-nameable playlists that mix
podcast episodes and audiobooks**, can be reordered, and play straight through.

This is roadmap Phase 5. Phase 5 splits into:

- **5a (this spec):** playlist data + management UI + mixed-entity playback.
- **5b (separate, later):** WorkManager periodic feed refresh + auto-insert rules. Auto-insert
  is useless without periodic refresh, so they ship together later.

5a delivers standalone value and matches the project's "piece by piece, addable/removable"
principle.

## Product decisions (user, 2026-06-20)

1. **Queue lifecycle — drain on completion.** A playlist is a consumption queue, *not* a
   curated collection. When an item finishes playing it is removed from the playlist. Items do
   not carry a "played" marker; the list empties as you listen.
2. **Book granularity — whole book = one item.** Adding an audiobook adds a single playlist
   item that plays the whole book (all chapters/files), resuming where you left off, and drops
   off only when the entire book ends. (Not one-item-per-chapter.)
3. **Current = top, always.** Tapping any item promotes it to the top and plays it; natural
   completion pops the top and the next item bubbles up and plays. No stored "current item"
   pointer is needed. Swipe removes; drag reorders.
4. **Playback integration — orchestrate from above.** `PlayRequest` stays single-entity and
   untouched. A thin controller loads the **top** item as a normal play; on completion it pops
   the top row and loads the next. All playlist logic lives in `feature:playlists`. No changes
   to the shape `feature:audiobooks` / `feature:podcasts` depend on. Cost: a sub-second reload
   between items — irrelevant for speech; gaplessness has no value here.
5. **Reorder affordance — drag.** The detail screen uses long-press drag-to-reorder (custom,
   no new dependency), alongside tap-to-play-from-top and swipe-to-remove.

## Goals

- Create, name, rename, and delete multiple playlists.
- Add a podcast **episode** or an **audiobook** to a playlist via a **new per-item "Add to
  playlist" action** in each feature (neither feature has an overflow menu today — see §6),
  with no dependency from those features on `feature:playlists`.
- A playlist detail screen lists items (mixed types) with artwork/title/subtitle/duration;
  supports tap-to-play-from-top, swipe-to-remove, and drag-to-reorder.
- "Play from top" plays the whole playlist: each item plays to its end (a book resumes and
  plays all its chapters), then is removed and the next item plays automatically.
- Resume positions are the entities' existing positions — no duplicate progress state.

## Non-goals (→ Phase 5b or later)

- WorkManager periodic feed refresh.
- Auto-insert rules (`autoInsertRule` on a playlist, `autoInsertPlaylistId` on a podcast,
  new episodes → top/bottom).
- Per-chapter playlist items; "played" history of drained items; cross-device sync.
- Gapless/seamless transitions between playlist items.
- A "played" / re-listen affordance (drain model deliberately discards finished items).

---

## Design

### The unifying insight: a playlist item is a `MediaRef`, not a copy

A playlist item stores only a **pointer** to an entity — `MediaRef(type, id)` — never a copy of
its data. Two consequences remove most of the would-be complexity:

- **No duplicate progress state.** Resume position already lives on the entity
  (`EpisodeEntity.positionMs`, the book's resume position). The playlist row carries no
  position/completed columns; it exists only while the item is pending and is deleted on
  completion.
- **Dangling refs are recoverable.** If the underlying entity is deleted (podcast
  unsubscribed, book removed), the playlist row is simply pruned at hydration time.

### Module boundaries (modularity is a hard constraint)

```
feature:playlists  ──depends on──▶  core:model, core:database, core:playback, core:designsystem,
                                    core:navigation
       ▲
       │ (NO dependency the other way)
feature:audiobooks ─┐
feature:podcasts   ─┴─ contribute PlayRequestFactory + PlaylistItemResolver via Hilt @IntoSet;
                       gain a new "Add to playlist" action that navigates by a CommonRoutes string
app  ── collects PlaylistsFeatureEntry via the existing Set<FeatureEntry> multibinding;
        adds a Playlists top-level tab (a CommonRoutes string — still names no feature module)
```

`feature:playlists` never references `feature:audiobooks` or `feature:podcasts`. The other
features never reference `feature:playlists`. They meet only at `core` seams:
`MediaRef` (core:model), `PlaylistItemResolver` (core:model, Hilt set — display fields),
`PlayRequestFactory` (core:playback, Hilt set — playback), and `CommonRoutes` strings
(core:navigation). New media types in future need **zero** playlist changes — they just
contribute one resolver + one factory.

### 1. Data model — `core:database` (schema v5 → v6)

DB stays `fallbackToDestructiveMigration(dropAllTables=true)`, `exportSchema=false` (pre-release,
per existing OratorDatabase KDoc — committed schema + migrations are Phase 9).

```kotlin
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAtMs: Long,
)

@Entity(
    tableName = "playlist_items",
    foreignKeys = [ForeignKey(
        entity = PlaylistEntity::class,
        parentColumns = ["id"], childColumns = ["playlistId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index("playlistId"),
        Index(value = ["playlistId", "mediaType", "mediaId"], unique = true), // dedupe adds
    ],
)
data class PlaylistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val mediaType: MediaType,   // PODCAST = episode, AUDIOBOOK = whole book
    val mediaId: String,        // episode.id or book.id — both are already String PKs
    val position: Long,         // order within the playlist; top = smallest value
)
```

- No `@TypeConverter` needed: Room persists enums natively as their constant name, exactly as
  `BookEntity.sourceKind: SourceKind` already does (there is no converter in the module today).
  `PlaylistItemEntity.mediaType: MediaType` just works.
- `PlaylistDao`: observe playlists (with item counts), observe a playlist's items ordered by
  `position`, insert/rename/delete playlist, insert item (ignore-on-conflict for dedupe),
  delete item, batch-update positions (transaction), delete top item.
- New entities + DAO registered on `OratorDatabase`; `version = 6`.

### 2. `MediaRef` + `PlaylistItemResolver` — `core:model`

```kotlin
enum class MediaType { PODCAST, AUDIOBOOK }   // unchanged

data class MediaRef(val type: MediaType, val id: String)

/** Display fields for one playlist row. Plain data — no Android, no playback. */
data class PlaylistItemContent(
    val title: String, val subtitle: String,
    val artworkUri: String?, val durationMs: Long,
)

/** Resolves a MediaRef to its display fields. Contributed per media type via Hilt @IntoSet
 *  (mirrors PlaybackEventListener). null = the underlying entity is gone (prune the row). */
interface PlaylistItemResolver {
    val mediaType: MediaType
    suspend fun resolve(ref: MediaRef): PlaylistItemContent?
}
```

`MediaRef` is a plain pointer. `PlaylistItemResolver` is the **display seam**: it keeps
per-type knowledge (an episode's title + show name, a book's title + author + cover) inside the
owning feature, so `feature:playlists` never reads the other features' entities or derives their
display logic. Kept separate from the playback seam below (display vs. playback are different
concerns; `core:model` must not depend on `core:playback`).

### 3. `PlayRequestFactory` — `core:playback` (the playback seam)

```kotlin
/** Builds a single-entity PlayRequest for one MediaRef, reading the entity + its resume
 *  position. Contributed per media type via Hilt @IntoSet, mirroring PlaybackEventListener. */
interface PlayRequestFactory {
    val mediaType: MediaType
    suspend fun create(ref: MediaRef): PlayRequest?   // null = ref no longer resolvable
}
```

- `feature:audiobooks` provides `AudiobookPlayRequestFactory` + `AudiobookPlaylistItemResolver`
  (mediaType = AUDIOBOOK). `BookEntity.id` is already a `String` PK, so `ref.id` is the book id
  directly (no parsing). The factory loads the book + chapters (`BookDao.getById`,
  `ChapterDao`), reads the book's resume position (`BookEntity.positionMs`), delegates to existing
  `QueueBuilder`; the resolver returns title/author/cover/`durationMs`. A `ref.id` with no matching
  book → `null` (consistent with "unresolvable").
- `feature:podcasts` provides `EpisodePlayRequestFactory` + `EpisodePlaylistItemResolver`
  (mediaType = PODCAST). The factory loads the episode + its podcast, reads `episode.positionMs`,
  delegates to existing `EpisodeQueueBuilder`; the resolver returns episode title / show name /
  artwork / duration.
- `feature:playlists` consumes `Set<PlayRequestFactory>` and `Set<PlaylistItemResolver>`, each
  indexed by `mediaType`. It builds no `PlayRequest` and resolves no entity itself, and never
  imports the other features.

### 4. Playback orchestration — `PlaylistPlaybackController` (Singleton, `feature:playlists`)

Owns "a playlist is currently playing" and advances it. Reuses the existing `PlaybackConnection`.

State (survives process death via a tiny dedicated DataStore — small, included):
- `activePlaylistId: Long?` — which playlist is draining (null = none).

Operations:
- `playFromTop(playlistId)` — set active; build the **top** item's `PlayRequest` via the factory
  set; `connection.play(req)`. If the playlist is empty, no-op.
- `playItem(playlistId, itemId)` — move that item to position 0 (DB), then `playFromTop`.
- **Advance on completion.** Observe `connection.state`, tracking the previous `isEnded` value.
  On a **rising edge of `isEnded`** (false→true) while a playlist is active: delete the top row,
  then play the new top; if none remain, clear `activePlaylistId` and stop.
  - **Re-arm invariant:** the next `connection.play(...)` calls `setMediaItems` + `prepare()`,
    which moves the player out of `STATE_ENDED`, so `isEnded` falls back to false and the edge
    detector re-arms for the *next* item's completion. The controller must therefore key off the
    transition, not the level. (Test: two consecutive completions each advance.)
- **Self-deactivation.** If the currently-playing `mediaId` no longer corresponds to the active
  playlist's top item (the user played a standalone book/episode), the controller stands down
  (`activePlaylistId = null`). No cross-feature "cancel playlist" wiring required.
  - **Matching rule (concrete).** `PlaybackUiState.mediaId` is an *encoded* id, not the raw
    `MediaRef.id`, so equivalence is computed per type via the existing codecs — a small pure,
    testable helper (`MediaRefMatch.matches(ref, encodedMediaId)`):
    - PODCAST: `PodcastMediaId.parse(mediaId) == ref.id`
    - AUDIOBOOK: `AudiobookMediaId.parse(mediaId)?.bookId == ref.id` — **ignore `fileIndex`**, so
      a multi-file book stays "matched" across its internal file→file transitions (otherwise the
      controller would wrongly stand down mid-book).
    - A **null/blank** `mediaId` (the brief window between `play()` and the first state emission,
      when no queue has loaded yet) is treated as "no match" → the controller does **not** stand
      down. It only deactivates on a *non-blank* mediaId that resolves to a different ref.

> **Completion signal — critical subtlety.** In `PlaybackService`, an internal file→file jump
> inside a multi-file book is a Media3 `AUTO` transition that reports `onItemEnded(completed=true)`
> *per file*. The **whole logical item finishing** is the single `STATE_ENDED` event. The
> controller therefore advances the playlist on **end-of-queue (`isEnded`)**, NOT on per-item
> `PlaybackEventListener.onItemEnded` — otherwise a book would pop off after its first file.

Additive support needed in `core:playback`:
- `PlaybackUiState` gains `isEnded: Boolean` (true when player state is `STATE_ENDED`), derived
  in `PlaybackConnection.updateState()` from `Player.STATE_ENDED`. Existing features ignore it.

**Relationship to existing history recording.** `PlaybackService` already fires
`onItemEnded(completed=true)` to `PlaybackEventListener`s (the history recorder) on `STATE_ENDED`.
The controller advances off the **UI-side** `connection.state.isEnded` instead — a different
object observing the same end-of-queue moment. There is no shared mutable state between them, and
they touch disjoint tables (history rows / episode-position vs. `playlist_items`), with no foreign
key linking them — so deleting the drained playlist row cannot race or corrupt the finished item's
history/position write. The finished item is still recorded in history exactly as it is today.

### 5. Repository + ordering — `feature:playlists`

- `PlaylistRepository` wraps `PlaylistDao` and the injected `Set<PlaylistItemResolver>` (§2) to
  hydrate `PlaylistItemEntity` rows into UI rows. It does **not** read books/episodes directly —
  resolution is delegated to the per-type resolver, keeping `feature:playlists` free of other
  features' schemas.

  ```kotlin
  data class PlaylistItemUi(
      val itemId: Long, val ref: MediaRef,
      val content: PlaylistItemContent,   // title / subtitle / artworkUri / durationMs (§2)
  )
  ```

  For each `PlaylistItemEntity`, the repository picks the resolver matching `mediaType` and calls
  `resolve(ref)`; a `null` result (entity deleted — unsubscribed podcast, removed book) → the row
  is **pruned**: dropped from the emitted list and its DB row deleted, so the playlist
  self-heals.
- `PlaylistOrdering` — a **pure object** (no Android, fully unit-testable): given current ordered
  items and an operation (append, remove, move-to-top, move(from,to)), returns the new
  `(itemId → position)` assignment. DAO persists in a transaction.

### 6. UI — `feature:playlists` (Onyx design system) + entry points

- **PlaylistsScreen** — new top-level destination `CommonRoutes.Playlists`. Lists playlists
  (name + item count); "＋ New playlist" opens a name dialog. Tap → detail.
- **PlaylistDetailScreen** — header (name, **Play from top**, overflow: rename / delete);
  rows show artwork, title, subtitle (show name / author), duration (hidden when `durationMs == 0`
  — a streamed episode whose length isn't known yet, never rendered as "0:00"). Interactions:
  **tap = play from top** (promotes tapped item to top then plays), **swipe = remove**,
  **long-press drag = reorder**. Empty state when drained.
- **AddToPlaylistSheet** — destination `CommonRoutes.AddToPlaylist` taking `{mediaType}/{mediaId}`
  args. Lists playlists to add into (with create-new), inserts via the DAO (ignore-on-conflict),
  and pops. **New per-item affordance in each feature (neither has an overflow menu today):**
  - Podcasts: episode rows currently expose a left-swipe "Delete ✕" only. Add an **overflow
    (⋮) icon** to the row that navigates to `CommonRoutes.AddToPlaylist` for that episode.
  - Audiobooks: book tiles are tap-to-open today. Add the same **⋮ overflow** affordance to the
    tile (or its detail header) navigating to `AddToPlaylist` for that book.
  - Both navigate via a `CommonRoutes.addToPlaylist(type, id)` string builder — the features gain
    the action with **no** module dependency on `feature:playlists`. (If the ⋮ proves awkward in
    a list row during implementation, fall back to a long-press context action — same navigation,
    no data/model change.)
- `PlaylistsFeatureEntry : FeatureEntry` registers all three destinations (collected by the
  app's existing `Set<FeatureEntry>` multibinding).
- **`app` change (explicit):** add a **Playlists** entry as a **4th bottom tab** in
  `OratorShell`'s hardcoded `TABS` list (it's a primary, user-managed surface — better
  discoverability than the drawer). This references the `CommonRoutes.Playlists` string only, so
  `app` still names no feature module — consistent with the existing Podcasts/Audiobooks tabs.
- New `CommonRoutes` constants: `Playlists`, `PlaylistDetail` (with `{playlistId}`),
  `AddToPlaylist` (with `{mediaType}/{mediaId}`), plus small `route(...)` builder helpers.

### Data flow — "Play from top" end to end

1. User taps **Play from top** on playlist P (items: episode E, book B, episode E2).
2. Controller: `activePlaylistId = P`; reads top item (E) → `MediaRef(PODCAST, E.id)` →
   `EpisodePlayRequestFactory.create` → `PlayRequest` → `connection.play`.
3. E plays to its end → `isEnded` rising edge → controller deletes E's row → new top is B →
   `AudiobookPlayRequestFactory.create` (resumes B at its saved position) → `connection.play`.
4. B plays all chapters/files (internal AUTO transitions ignored by the controller) → `isEnded`
   → delete B's row → play E2.
5. E2 ends → `isEnded` → delete row → playlist empty → `activePlaylistId = null`, stop.

If during step 3 the user instead taps a standalone book in the library: `connection.play` runs
for that book; the controller sees the playing `mediaId` no longer matches P's top → stands down.

---

## Testing strategy (TDD, pure-JVM wherever possible)

- **`PlaylistOrdering`** (pure): append, remove, move-to-top, move(from,to); positions stay
  dense and ordered; idempotent move-to-top of the current top.
- **`PlaylistRepository`** hydration: mixed episode+book rows resolve (via fake resolvers) to UI
  rows in order; a resolver returning `null` prunes that row (dropped + deleted); duplicate add
  is ignored (unique index).
- **`MediaRefMatch`** (pure): PODCAST id round-trips through `PodcastMediaId`; AUDIOBOOK matches
  on `bookId` while **ignoring `fileIndex`** (a `audiobook/<id>/3` mediaId still matches
  `MediaRef(AUDIOBOOK, <id>)`); type mismatch and malformed ids → no match.
- **`PlaylistPlaybackController`** against a small `PlaylistPlayback` seam (fake connection +
  fake factories): play-from-top builds top item; `isEnded` rising edge → pop top + play next;
  **two consecutive completions each advance** (re-arm invariant); empty → stop + clear active;
  `playItem` promotes then plays; self-deactivation when playing mediaId diverges; no advance
  while inactive; a multi-file book's internal file→file transitions (mediaId keeps the same
  `bookId`, different `fileIndex`; no `isEnded`) do **not** advance the playlist.
- **Factories + resolvers** (thin): each factory maps a `MediaRef` to the same `PlayRequest` its
  existing builder produces; each resolver maps a `MediaRef` to expected display fields;
  unresolvable / malformed ref → `null`.
- **DAO**: dedupe via unique index; ordered query; cascade delete of items with playlist;
  batch position update. Tested with **Robolectric + `Room.inMemoryDatabaseBuilder`**, matching
  the existing `EpisodeDaoTest` / `PodcastDaoTest` / `HistoryDaoTest` convention in `core:database`
  (`@RunWith(RobolectricTestRunner)`, `@Config(sdk=[34])`, `runBlocking`).
- Per-chunk gate (project standard): `./gradlew test lint assembleDebug`; report build times.

## Risks / mitigations

- **Advancing on the wrong signal** (per-file vs. end-of-queue) — addressed by keying advance
  off `isEnded` end-of-queue only; explicit multi-file test.
- **Reload gap between items** — accepted; sub-second, speech content, no UX cost.
- **Drag-to-reorder without a new dependency** — custom reorder; if it proves fiddly during
  implementation, fall back to move-up/down buttons (does not change the data model or tests).
- **Process death mid-playlist** — `activePlaylistId` persisted to DataStore; on reconnect the
  controller re-derives the top from the DB.
- **Modularity regressions** — enforced by the dependency direction above; `feature:playlists`
  must not import the other features, and vice versa.
- **Position drift** — `position` (Long, top = smallest) is reassigned densely by `PlaylistOrdering`
  and written only through the DAO's single transactional batch-update, so concurrent edits can't
  produce duplicate or sparse positions.

## Out-of-scope follow-up (Phase 5b)

WorkManager periodic feed refresh; auto-insert rules; both ship together because auto-insert
needs periodic refresh to have anything to insert.
