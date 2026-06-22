# Phase 5b — Background Refresh + Auto-Insert — Design

**Date:** 2026-06-20
**Status:** Approved (design); spec for implementation planning
**Type:** Extends `feature:podcasts` + `feature:playlists` + `feature:settings`; introduces WorkManager.
Second slice of roadmap Phase 5 (5a = playlists, merged PR #9).

---

## Problem / intent

Phase 5a shipped playlists, but feeds only refresh when the user taps refresh, and nothing adds
new episodes to a playlist automatically. Phase 5b adds:

1. **Background feed refresh** — subscribed feeds refresh on a schedule (and on app open), so new
   episodes appear without opening the app and tapping refresh.
2. **Auto-insert rules** — a podcast can be configured so its newly-discovered episodes are
   automatically inserted into a chosen playlist, at the top or bottom.

These ship together: auto-insert is useless without periodic refresh to discover new episodes.

## Product decisions (user, 2026-06-20)

1. **Refresh cadence — configurable interval.** A Settings row picks the period:
   **Off / 15m / 1h / 3h / 6h / 12h / daily** (default 6h). "Off" disables background refresh
   (manual refresh + refresh-on-app-open still work). 15m is WorkManager's hard floor for
   periodic work; actual timing is subject to OS battery batching (Doze/standby) — it is a
   *minimum* cadence, not a precise alarm.
2. **Auto-insert backfill — future-only.** Enabling auto-insert for a podcast affects only
   episodes discovered *after* it is enabled. Existing episodes are untouched (still addable by
   hand). No risk of dumping a back-catalogue into a draining queue.
3. **Rule location — per podcast.** Each podcast carries both its target playlist and its own
   `NEW_TO_TOP` / `NEW_TO_BOTTOM` rule, so different shows feeding one playlist can use different
   rules (e.g. a daily-news show → top, a serialized show → bottom).

## Goals

- Subscribed feeds refresh periodically in the background per the chosen interval, and once on
  app open, honoring a network constraint.
- A podcast can be set to auto-insert its new episodes into a playlist at top or bottom; clearing
  it stops auto-insert.
- Auto-insert fires on **any** refresh that discovers new episodes — the manual refresh button
  *and* the background worker — never for already-seen episodes (future-only), never duplicating
  (the playlist's existing unique index).
- Modularity preserved: `feature:podcasts` and `feature:playlists` still never import each other.

## Non-goals (later phases)

- Download-on-Wi-Fi-only constraint, `CleanupWorker` (storage limits / auto-delete), `AbsSyncWorker`
  (audiobookshelf) — roadmap §9, later phases.
- Backfilling existing episodes; per-episode auto-insert filters (e.g. by keyword/duration).
- Notifications for new episodes.
- Auto-insert from audiobooks (books have no "new episode" feed concept).

---

## Design

### The cross-feature seam (modularity keystone)

Auto-insert is inherently cross-feature: the **trigger** is podcast-domain (a refresh discovered a
new episode) but the **action** is playlist-domain (insert a row at top/bottom). They are wired
through a tiny `core` interface — the same seam pattern Phase 5a used for `PlayRequestFactory` /
`PlaylistItemResolver`:

```
feature:podcasts ──emits new episode ids──▶ NewEpisodeListener (core seam) ◀──implements── feature:playlists
                                                                                   │
                                                  reads podcast.autoInsert* + inserts via core:database DAOs
```

`feature:podcasts` never names `feature:playlists`. `feature:playlists` reads the per-podcast
auto-insert config via the shared `core:database` DAOs (exactly as its 5a resolvers already read
`EpisodeDao` / `BookDao`) — it does not import `feature:podcasts`.

Alternatives considered: a neutral coordinator module depending on both (more plumbing, pushes
feature logic up); auto-insert logic inside the worker (couples the worker to playlists). The seam
is the least-coupled and is consistent with the existing codebase.

### 1. Data model (`core:database`, schema v6 → v7, destructive)

Destructive bump is still acceptable pre-release (`fallbackToDestructiveMigration(dropAllTables=true)`,
`exportSchema=false`; real migrations are the Phase 9 release gate).

`core:model` — new enum:
```kotlin
enum class AutoInsertRule { NEW_TO_TOP, NEW_TO_BOTTOM }
```

`PodcastEntity` (`core:database`) — two new nullable columns (null = auto-insert off):
```kotlin
val autoInsertPlaylistId: Long? = null,   // target playlist; null = off
val autoInsertRule: AutoInsertRule? = null, // how new episodes enter; null = off
```
(Room persists the enum natively, as `SourceKind`/`MediaType` already do — no converter.)

`PodcastDao` — add:
```kotlin
@Query("UPDATE podcasts SET autoInsertPlaylistId = :playlistId, autoInsertRule = :rule WHERE id = :id")
suspend fun updateAutoInsert(id: String, playlistId: Long?, rule: AutoInsertRule?)
```

`EpisodeDao.insertIgnore` — change the return type so callers learn which rows are new:
```kotlin
@Insert(onConflict = OnConflictStrategy.IGNORE)
suspend fun insertIgnore(episodes: List<EpisodeEntity>): List<Long>  // rowid per input; -1 = ignored (dup)
```

### 2. New-episode detection (`feature:podcasts`)

In `PodcastRepository.upsertEpisodes`, zip the input entities with the `insertIgnore` rowids and
keep those whose rowid is `!= -1L` — these are the genuinely-new episodes (Room returns rowids in
input order, `-1` for an ignored duplicate). `refresh()` returns/accumulates the new episode ids;
`refreshAll()` aggregates across feeds.

```kotlin
val rowIds = episodeDao.insertIgnore(entities)
val newIds = entities.zip(rowIds).filter { it.second != -1L }.map { it.first.id }
// metadata backfill (updateMetadata loop) is unchanged
return newIds
```

After all DB writes for a refresh pass complete — **including the `updateMetadata` backfill loop**,
so `PlaylistAutoInserter`'s `episodeDao.getById` sees fully-populated rows — notify the seam
**once** with the aggregated new ids (so the playlist side does its own per-episode podcast lookup).

Touch-points for the `insertIgnore: Unit → List<Long>` change: the sole production caller is
`upsertEpisodes`; five test files call it directly (`EpisodeDaoTest`,
`EpisodePlaylistContributionsTest`, `EpisodeSpeedOverrideListenerTest`, `TranscriptFetcherTest`,
`PodcastPositionListenerTest`) and all discard the return — source-compatible, no test changes
required.

### 3. Auto-insert seam + evaluator

`core:playback` (or `core:model` — a plain suspend interface, no Android):
```kotlin
/** Called after a feed refresh discovers brand-new episodes (manual refresh or background worker).
 *  Contributed per consumer via Hilt @IntoSet, mirroring PlaybackEventListener. */
interface NewEpisodeListener {
    suspend fun onNewEpisodes(episodeIds: List<String>)
}
```

`PodcastRepository` injects `Set<NewEpisodeListener>` and calls `onNewEpisodes(newIds)` after a
refresh pass (skip when empty).

`feature:playlists` provides `PlaylistAutoInserter : NewEpisodeListener`:
- For each new episode id: `episodeDao.getById(id)` → `podcastDao.getById(ep.podcastId)`.
- If `podcast.autoInsertPlaylistId != null` and the playlist still exists: build
  `MediaRef(PODCAST, episodeId)` and insert a `PlaylistItemEntity` per `autoInsertRule`:
  - `NEW_TO_BOTTOM`: position = `(maxPosition ?: 0) + 10` (append).
  - `NEW_TO_TOP`: position = `minPosition?.minus(10) ?: 10` (prepend; on an empty playlist use
    `+10` for symmetry with append). Negative positions are safe — `getTopItem`/`observeItems`
    `ORDER BY position ASC` so the smallest sorts first, and the next `PlaylistOrdering.reindex`
    normalizes to dense positive values. Add a `PlaylistDao.minPosition`.
- Dedupe is automatic (the playlist's unique `(playlistId, mediaType, mediaId)` index → insert
  ignored if already present).
- A dangling `autoInsertPlaylistId` (playlist deleted) → skip (treat as off). Optionally clear it.

This evaluator is pure-DAO logic, fully unit-testable with fake DAOs.

### 4. Background refresh — `FeedRefreshWorker` + `RefreshScheduler` (`feature:podcasts`)

New dependencies: `androidx.work:work-runtime-ktx` and `androidx.hilt:hilt-work` (+ KSP
`androidx.hilt:hilt-compiler`). The app already uses Hilt.

```kotlin
@HiltWorker
class FeedRefreshWorker @AssistedInject constructor(
    @Assisted ctx: Context, @Assisted params: WorkerParameters,
    private val repository: PodcastRepository,
) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        repository.refreshAll() // refreshAll already fires the NewEpisodeListener seam
        return Result.success()
    }
}
```

`RefreshScheduler` (Singleton) owns enqueue/cancel against `WorkManager`:
- `reconcile(intervalMinutes: Int)`: if 0 → `cancelUniqueWork(REFRESH_WORK)`; else enqueue a
  `PeriodicWorkRequest` (interval = `intervalMinutes`, `Constraints` = `NetworkType.CONNECTED`)
  with `ExistingPeriodicWorkPolicy.UPDATE` under a unique name so interval changes replace cleanly.
- Driven by `RefreshPreferences.intervalMinutes` (collect → reconcile on change). Wired at app
  start via the same eager-`FeatureEntry` mechanism 5a used for the playlist controller —
  `PodcastsFeatureEntry` already exists and is eagerly constructed (the app injects
  `Set<FeatureEntry>` in `MainActivity.onCreate`); have it kick `RefreshScheduler.start(scope)`.
- **Refresh-on-app-open:** the eager `RefreshScheduler.start` also triggers a **direct**
  `repository.refreshAll()` once per cold start (in a try/catch, network permitting) — simpler than
  enqueuing a second worker and it reuses the same seam path. Independent of the interval.

WorkManager + Hilt initialization (in `app`):
- A custom `Application` already exists — **`OratorApplication`** (`@HiltAndroidApp`, already
  implements Coil's `SingletonImageLoader.Factory` and injects `okHttpClient`). Add
  `Configuration.Provider` to it and inject `HiltWorkerFactory` (`@Inject lateinit var workerFactory`;
  `override val workManagerConfiguration = Configuration.Builder().setWorkerFactory(workerFactory).build()`)
  **without disturbing** the existing Coil/OkHttp setup.
- **Disable the default WorkManager initializer** in `app/src/main/AndroidManifest.xml`. The
  manifest currently has no `<provider>` block and no `xmlns:tools`; add both:
  ```xml
  <!-- on <manifest>: xmlns:tools="http://schemas.android.com/tools" -->
  <provider
      android:name="androidx.startup.InitializationProvider"
      android:authorities="${applicationId}.androidx-startup"
      android:exported="false"
      tools:node="merge">
      <meta-data
          android:name="androidx.work.WorkManagerInitializer"
          android:value="androidx.startup"
          tools:node="remove" />
  </provider>
  ```

### 5. Refresh-interval settings (`feature:settings`)

`RefreshPreferences` (DataStore, **in `feature:podcasts`** — `RefreshScheduler` is the sole
consumer, so co-locate; follow the `PlayerPreferences` qualifier + `preferencesDataStore` + typed
wrapper pattern): `intervalMinutes: Int` (0 = Off; 15/60/180/360/720/1440), default 360.

A `SettingsSection` row (the existing pluggable settings mechanism) cycles
**Off → 15m → 1h → 3h → 6h → 12h → daily**, persisting to `RefreshPreferences`; `RefreshScheduler`
reacts to the change.

### 6. Per-podcast auto-insert config UI (`feature:podcasts` show screen)

On the podcast show screen (where intro/outro clips + speed already live), add an **"Auto-add new
episodes"** control showing the current target ("Off" or the playlist name). Tapping opens a
dialog:
- A list of existing playlists (read via `PlaylistDao.observePlaylists()` — already returns
  `PlaylistSummary` with names; `core:database`, no feature import) + an "Off" choice.
- A **Top / Bottom** toggle (the `AutoInsertRule`).
- Confirm → `podcastDao.updateAutoInsert(id, playlistId, rule)`.

If there are no playlists yet, the dialog explains one must be created first (or offers to navigate
to the Playlists tab via a `CommonRoutes` string — optional).

### Data flow — background refresh → auto-insert

1. `FeedRefreshWorker` fires (interval elapsed, network available) → `repository.refreshAll()`.
2. Each feed: conditional GET → on `Success`, `upsertEpisodes` inserts; rowids identify the new
   episodes; their ids are accumulated.
3. After the pass, `refreshAll()` calls `Set<NewEpisodeListener>.onNewEpisodes(newIds)`.
4. `PlaylistAutoInserter` resolves each episode → its podcast → if auto-insert is configured,
   inserts a playlist row at top/bottom (dedupe by unique index).
5. The Playlists detail screen, already observing `observeItems`, re-renders with the new item(s).

---

## Testing strategy (TDD)

- **`EpisodeDao.insertIgnore` rowids** (Robolectric in-memory Room): new rows return positive
  rowids, duplicates return `-1`, mixed batch maps correctly to new-vs-existing.
- **`PlaylistAutoInserter`** (fake DAOs): `NEW_TO_TOP` prepends, `NEW_TO_BOTTOM` appends; no-op
  when the podcast has no auto-insert config; no-op when the target playlist is gone; duplicate
  episode not inserted twice; multiple new episodes across different podcasts route correctly.
- **`PodcastRepository`** new-episode aggregation: a refresh that inserts some-new/some-existing
  episodes notifies the seam with exactly the new ids; a no-new refresh does not notify.
- **`RefreshScheduler`** reconciliation: interval 0 cancels; a non-zero interval enqueues a unique
  periodic request with the right period + network constraint; changing the interval replaces it.
  (Use WorkManager's `androidx.work:work-testing` `WorkManagerTestInitHelper`.)
- **Settings row** cycles the presets and persists; **per-podcast dialog** writes the config.
- Per-chunk gate: `./gradlew test lint assembleDebug`; report build times.

## Risks / mitigations

- **WorkManager timing isn't exact** — documented as a *minimum* cadence; acceptable for feed
  refresh. No exact-alarm path (battery cost not justified).
- **`insertIgnore` return-type change** ripples to its current callers — they currently ignore the
  return (`Unit`); changing to `List<Long>` is source-compatible at call sites that don't read it.
  Verify `importOpml` / `subscribe` paths still compile and behave.
- **Auto-insert into a draining queue while it plays** — inserting at top while the top item is
  playing: the controller keys "current" off the top row; inserting a *new* top during playback
  does not interrupt the currently-loaded item (playback only advances on `isEnded`). New top is
  honored on the next advance. Confirm this matches intent (it does — drain model).
- **WorkManager init + Hilt** — the custom `HiltWorkerFactory` + manifest initializer-disable is a
  known-fiddly setup; isolate it in `app` and verify on device.
- **Modularity** — enforced by the seam; `feature:podcasts`/`feature:playlists` must not import
  each other.

## Out-of-scope follow-up
Wi-Fi-only refresh constraint, `CleanupWorker`, `AbsSyncWorker`, new-episode notifications.
