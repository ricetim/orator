# Phase 5b — Background Refresh + Auto-Insert Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Subscribed feeds refresh in the background on a configurable interval (+ on app open), and a podcast can auto-insert its newly-discovered episodes into a chosen playlist at top or bottom.

**Architecture:** A `FeedRefreshWorker` (WorkManager, `@HiltWorker`) calls the existing `PodcastRepository.refreshAll()`, which now detects new episodes (from `insertIgnore` rowids) and fires a `NewEpisodeListener` core seam. `feature:playlists` implements that seam (`PlaylistAutoInserter`) and inserts playlist rows per each episode's podcast `autoInsert*` config — read via shared `core:database` DAOs, so `feature:podcasts` and `feature:playlists` still never import each other. A `RefreshScheduler` reconciles a `RefreshPreferences` interval into WorkManager.

**Tech Stack:** Kotlin 2.1.0, WorkManager (`androidx.work:work-runtime-ktx`) + `androidx.hilt:hilt-work` (NEW deps), Hilt, Room (schema v6→v7, destructive), DataStore, Jetpack Compose, Robolectric + `androidx.work:work-testing` for tests.

**Spec:** `docs/superpowers/specs/2026-06-20-playlists-5b-design.md`

**Standing rules:** `./gradlew` only (report build times); per-chunk gate `./gradlew test lint assembleDebug`; commit explicit paths only (**never `git add -A`** — untracked private files); commit trailer `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`; branch `phase-5b-autoinsert` (already created).

---

## File Structure

**`core:model`** — `AutoInsertRule.kt` (new enum).
**`core:database`** — modify `PodcastEntity` (+2 cols), `PodcastDao` (+`updateAutoInsert`), `EpisodeDao` (`insertIgnore` → `List<Long>`), `PlaylistDao` (+`minPosition`), `OratorDatabase` (v7); test `PlaylistDaoTest`/`EpisodeDaoTest`/`PodcastDaoTest`.
**`core:playback`** — `NewEpisodeListener.kt` (new seam interface).
**`feature:podcasts`** — modify `PodcastRepository` (new-episode detection + fire seam); new `data/RefreshPreferences.kt`, `data/RefreshScheduler.kt`, `work/FeedRefreshWorker.kt`; modify `PodcastsFeatureModule`/`PodcastsFeatureEntry`; new settings row + per-podcast config UI in the show screen; `build.gradle.kts` (+deps).
**`feature:playlists`** — new `data/PlaylistAutoInserter.kt`; modify `PlaylistsFeatureModule` (bind `@IntoSet`); `PlaylistRepository` (+`addAtTop`/`addAtBottom` by ref+rule, reusing DAO).
**`app`** — modify `OratorApplication` (`Configuration.Provider` + `HiltWorkerFactory`), `AndroidManifest.xml` (disable default WM initializer), `build.gradle.kts` (+deps).
**`gradle/libs.versions.toml`** — add `work`, `androidxHilt` versions + library entries.

---

## Chunk 1: Data + seam foundation

Outcome: schema v7 with the auto-insert columns, `insertIgnore` returning rowids, `minPosition`, and the `NewEpisodeListener` interface. All DAO-tested. No behavior yet.

### Task 1.1: `AutoInsertRule` enum (`core:model`)

**Files:** Create `core/model/src/main/java/com/orator/core/model/AutoInsertRule.kt`

- [ ] **Step 1: Write it**
```kotlin
package com.orator.core.model

/** Where a podcast's newly-discovered episodes enter its auto-insert target playlist. */
enum class AutoInsertRule { NEW_TO_TOP, NEW_TO_BOTTOM }
```
- [ ] **Step 2:** `./gradlew :core:model:compileKotlin` (pure JVM module — `compileKotlin`, not `compileDebugKotlin`). Report build time.
- [ ] **Step 3: Commit**
```bash
git add core/model/src/main/java/com/orator/core/model/AutoInsertRule.kt
git commit -m "feat(model): AutoInsertRule enum

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task 1.2: `PodcastEntity` columns + `PodcastDao.updateAutoInsert` + DB v7

**Files:** Modify `PodcastEntity.kt`, `PodcastDao.kt`, `OratorDatabase.kt`; test `core/database/src/test/.../PodcastDaoTest.kt` (create if absent — mirror `EpisodeDaoTest`).

- [ ] **Step 1: Write the failing test** (Robolectric in-memory; if `PodcastDaoTest` exists, add this case)
```kotlin
@Test fun `updateAutoInsert sets and clears the target + rule`() = runBlocking {
    dao.insertIgnore(podcast("p1")) // existing helper or inline a PodcastEntity
    dao.updateAutoInsert("p1", playlistId = 5L, rule = AutoInsertRule.NEW_TO_TOP)
    dao.getById("p1")!!.let {
        assertEquals(5L, it.autoInsertPlaylistId)
        assertEquals(AutoInsertRule.NEW_TO_TOP, it.autoInsertRule)
    }
    dao.updateAutoInsert("p1", playlistId = null, rule = null)
    assertNull(dao.getById("p1")!!.autoInsertPlaylistId)
}
```
- [ ] **Step 2:** Run it, expect FAIL (unresolved columns/method). Report build time.
- [ ] **Step 3: Add the columns** to `PodcastEntity` (after `speedOverride`):
```kotlin
val autoInsertPlaylistId: Long? = null,
val autoInsertRule: com.orator.core.model.AutoInsertRule? = null,
```
- [ ] **Step 4: Add the DAO method** to `PodcastDao`:
```kotlin
@Query("UPDATE podcasts SET autoInsertPlaylistId = :playlistId, autoInsertRule = :rule WHERE id = :id")
suspend fun updateAutoInsert(id: String, playlistId: Long?, rule: AutoInsertRule?)
```
(import `com.orator.core.model.AutoInsertRule`.)
- [ ] **Step 5: Bump DB** — `OratorDatabase` `version = 6` → `7`.
- [ ] **Step 6:** Run the test, expect PASS. Report build time.
- [ ] **Step 7: Commit** (paths: PodcastEntity.kt, PodcastDao.kt, OratorDatabase.kt, PodcastDaoTest.kt)
`feat(db): podcast auto-insert columns + updateAutoInsert (schema v7)`

### Task 1.3: `EpisodeDao.insertIgnore` returns rowids

**Files:** Modify `EpisodeDao.kt`; test `EpisodeDaoTest.kt`.

- [ ] **Step 1: Write the failing test**
```kotlin
@Test fun `insertIgnore returns rowids for new rows and -1 for duplicates`() = runBlocking {
    val first = dao.insertIgnore(listOf(episode("e1"), episode("e2")))
    assertTrue(first.all { it != -1L })            // both new
    val second = dao.insertIgnore(listOf(episode("e1"), episode("e3")))
    assertEquals(-1L, second[0])                   // e1 duplicate -> -1
    assertTrue(second[1] != -1L)                   // e3 new
}
```
- [ ] **Step 2:** Run, expect FAIL (return type is `Unit`). Report build time.
- [ ] **Step 3: Change the signature**:
```kotlin
@Insert(onConflict = OnConflictStrategy.IGNORE)
suspend fun insertIgnore(episodes: List<EpisodeEntity>): List<Long>
```
- [ ] **Step 4:** Run, expect PASS. (Existing callers discard the return — source-compatible; the
  metadata-backfill loop in `upsertEpisodes` is unaffected.) Report build time.
- [ ] **Step 5: Commit** (paths: EpisodeDao.kt, EpisodeDaoTest.kt)
`feat(db): insertIgnore returns rowids (-1 = duplicate)`

### Task 1.4: `PlaylistDao.minPosition`

**Files:** Modify `PlaylistDao.kt`, `feature/playlists/src/test/.../data/FakePlaylistDao.kt`; test `PlaylistDaoTest.kt`.

> ⚠️ `FakePlaylistDao` (feature:playlists test double) implements the **full** `PlaylistDao`
> interface. Adding `minPosition` to the interface breaks its compilation — and the Chunk 1 gate
> (`./gradlew test`) compiles every module's tests — unless you add the override below in the
> SAME task.

- [ ] **Step 1: Write the failing test**
```kotlin
@Test fun `minPosition returns the smallest position, null when empty`() = runBlocking {
    val p = newPlaylist("Mix")
    assertNull(dao.minPosition(p))
    dao.insertItem(item(p, "a", pos = 30))
    dao.insertItem(item(p, "b", pos = 10))
    assertEquals(10L, dao.minPosition(p))
}
```
- [ ] **Step 2:** Run, expect FAIL. Report build time.
- [ ] **Step 3: Add to `PlaylistDao`**:
```kotlin
@Query("SELECT MIN(position) FROM playlist_items WHERE playlistId = :playlistId")
suspend fun minPosition(playlistId: Long): Long?
```
- [ ] **Step 4: Add the `FakePlaylistDao` override** (mirrors its existing `maxPosition`):
```kotlin
override suspend fun minPosition(playlistId: Long): Long? = itemsFor(playlistId).minOfOrNull { it.position }
```
- [ ] **Step 5:** Run the test, expect PASS. Then `./gradlew :feature:playlists:compileDebugUnitTestKotlin`
  to confirm the fake still satisfies the interface. Report build time.
- [ ] **Step 6: Commit** (paths: PlaylistDao.kt, FakePlaylistDao.kt, PlaylistDaoTest.kt)
`feat(db): PlaylistDao.minPosition (for NEW_TO_TOP auto-insert)`

### Task 1.5: `NewEpisodeListener` seam (`core:playback`)

**Files:** Create `core/playback/src/main/java/com/orator/core/playback/NewEpisodeListener.kt`

- [ ] **Step 1: Write it**
```kotlin
package com.orator.core.playback

/**
 * Called after a feed refresh discovers brand-new episodes (manual refresh OR the background
 * worker). Contributed per consumer via Hilt @IntoSet, mirroring PlaybackEventListener.
 * The implementation decides what to do (Phase 5b: auto-insert into a playlist).
 */
interface NewEpisodeListener {
    suspend fun onNewEpisodes(episodeIds: List<String>)
}
```
- [ ] **Step 2:** `./gradlew :core:playback:compileDebugKotlin`. Report build time.
- [ ] **Step 3: Commit** `feat(playback): NewEpisodeListener seam`

### Task 1.6: Chunk 1 gate
- [ ] `./gradlew test lint assembleDebug` — green. Report build time. Fix before continuing.

---

## Chunk 2: New-episode detection + auto-insert evaluator

Outcome: refresh fires the seam with new episode ids, and `feature:playlists` inserts them per config. End-to-end auto-insert works on manual refresh (worker comes in Chunk 3). Unit-verified.

### Task 2.1: `PodcastRepository` detects new episodes + fires the seam

**Files:** Modify `feature/podcasts/.../data/PodcastRepository.kt`; test `PodcastRepositoryTest.kt` (create if absent — Robolectric in-memory Room + a fake `NewEpisodeListener`).

- [ ] **Step 1: Write the failing test** — a refresh pass that inserts some-new/some-existing
  episodes notifies the listener with exactly the new ids; a no-new pass does not notify. (Drive
  `upsertEpisodes` via a small public seam or test `refresh()` with a fake `FeedFetcher`. Prefer
  testing the new-id computation directly if `refresh`/`refreshAll` are awkward to fake — extract
  the "zip rowids → new ids" into a tiny pure helper `NewEpisodeIds.from(entities, rowids)` and
  unit-test that, plus a repository test that the listener is invoked.)
```kotlin
// pure helper test
@Test fun `new ids are those with non -1 rowid, in order`() {
    val es = listOf(ep("a"), ep("b"), ep("c"))
    assertEquals(listOf("a","c"), NewEpisodeIds.from(es, listOf(5L, -1L, 7L)))
}
```
- [ ] **Step 2:** Run, expect FAIL. Report build time.
- [ ] **Step 3: Implement.**
  - Add `NewEpisodeIds` pure helper (in `feature:podcasts/data`):
    ```kotlin
    object NewEpisodeIds {
        fun from(entities: List<EpisodeEntity>, rowIds: List<Long>): List<String> =
            entities.zip(rowIds).filter { it.second != -1L }.map { it.first.id }
    }
    ```
  - `upsertEpisodes`: capture `val rowIds = episodeDao.insertIgnore(entities)`, run the existing
    `updateMetadata` loop, then `return NewEpisodeIds.from(entities, rowIds)`.
  - `refresh(podcast): Boolean` → change to accumulate new ids (return them, or collect into a
    passed-in `MutableList`); `refreshAll()` aggregates across feeds.
  - Inject `private val newEpisodeListeners: Set<@JvmSuppressWildcards NewEpisodeListener>`.
  - **After** the whole refresh pass (after the `updateMetadata` backfill, so rows are complete),
    if the aggregated new-id list is non-empty: `newEpisodeListeners.forEach { it.onNewEpisodes(newIds) }`.
- [ ] **Step 4:** Run, expect PASS. Report build time.
- [ ] **Step 5: Commit** (paths: PodcastRepository.kt, NewEpisodeIds.kt, test files)
`feat(podcasts): detect new episodes on refresh + fire NewEpisodeListener`

### Task 2.2: `PlaylistAutoInserter` (`feature:playlists`)

**Files:** Create `feature/playlists/.../data/PlaylistAutoInserter.kt`; modify `PlaylistRepository.kt` (add `addAtTop`/`addAtBottom`); modify `PlaylistsFeatureModule.kt` (bind `@IntoSet`); test `PlaylistAutoInserterTest.kt`.

- [ ] **Step 1: Add the top-insert helper** to `PlaylistRepository` (`addToBottom` already exists —
  reuse it directly for `NEW_TO_BOTTOM`; only `addAtTop` is new):
```kotlin
// in PlaylistRepository (alongside the existing addToBottom)
suspend fun addAtTop(playlistId: Long, ref: MediaRef) {
    val pos = (dao.minPosition(playlistId)?.minus(10)) ?: 10L
    dao.insertItem(PlaylistItemEntity(playlistId = playlistId, mediaType = ref.type, mediaId = ref.id, position = pos))
}
```
- [ ] **Step 2: Write the failing test** (fake `PlaylistDao` + fake `EpisodeDao`/`PodcastDao`, or
  Robolectric in-memory Room). Cases:
  - `NEW_TO_TOP` → episode lands at top (smallest position).
  - `NEW_TO_BOTTOM` → episode appended.
  - podcast with no `autoInsertPlaylistId` → no insert.
  - target playlist deleted → no insert (no crash).
  - duplicate episode (already in playlist) → not inserted twice (unique index).
  - two new episodes from two podcasts with different targets → each routed correctly.
- [ ] **Step 3:** Run, expect FAIL. Report build time.
- [ ] **Step 4: Implement `PlaylistAutoInserter`**:
```kotlin
class PlaylistAutoInserter @Inject constructor(
    private val episodeDao: EpisodeDao,
    private val podcastDao: PodcastDao,
    private val repository: PlaylistRepository,
    private val playlistDao: PlaylistDao,
) : NewEpisodeListener {
    override suspend fun onNewEpisodes(episodeIds: List<String>) {
        for (id in episodeIds) {
            val ep = episodeDao.getById(id) ?: continue
            val podcast = podcastDao.getById(ep.podcastId) ?: continue
            val target = podcast.autoInsertPlaylistId ?: continue
            val rule = podcast.autoInsertRule ?: continue
            if (playlistDao.getPlaylist(target) == null) continue // dangling target -> skip
            val ref = MediaRef(MediaType.PODCAST, id)
            when (rule) {
                AutoInsertRule.NEW_TO_TOP -> repository.addAtTop(target, ref)
                AutoInsertRule.NEW_TO_BOTTOM -> repository.addToBottom(target, ref)
            }
        }
    }
}
```
- [ ] **Step 5: Bind `@IntoSet`** in `PlaylistsFeatureModule`:
```kotlin
@Binds @IntoSet
fun bindNewEpisodeListener(impl: PlaylistAutoInserter): NewEpisodeListener
```
- [ ] **Step 6:** Run, expect PASS. Report build time.
- [ ] **Step 7: Commit** (paths: PlaylistAutoInserter.kt, PlaylistRepository.kt, PlaylistsFeatureModule.kt, test)
`feat(playlists): PlaylistAutoInserter (auto-insert seam impl)`

### Task 2.3: Chunk 2 gate
- [ ] `./gradlew test lint assembleDebug` — green. Hilt now aggregates a `NewEpisodeListener` into
  the set `PodcastRepository` injects (empty set is legal; here it's 1). Report build time.

---

## Chunk 3: WorkManager refresh + scheduler + settings

Outcome: feeds refresh periodically in the background per a Settings interval, and once on app open. New deps + WorkManager+Hilt init.

### Task 3.1: Add WorkManager + Hilt-Work dependencies

**Files:** `gradle/libs.versions.toml`, `feature/podcasts/build.gradle.kts`, `app/build.gradle.kts`.

- [ ] **Step 1: Catalog** — under `[versions]` add `work = "2.9.1"` and `androidxHilt = "1.2.0"`;
  under `[libraries]`:
```toml
androidx-work-runtime = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "work" }
androidx-work-testing = { group = "androidx.work", name = "work-testing", version.ref = "work" }
androidx-hilt-work = { group = "androidx.hilt", name = "hilt-work", version.ref = "androidxHilt" }
androidx-hilt-compiler = { group = "androidx.hilt", name = "hilt-compiler", version.ref = "androidxHilt" }
```
- [ ] **Step 2: `feature:podcasts` deps** — add `implementation(libs.androidx.work.runtime)`,
  `implementation(libs.androidx.hilt.work)`, `ksp(libs.androidx.hilt.compiler)`,
  `testImplementation(libs.androidx.work.testing)`.
- [ ] **Step 3: `app` deps** — add `implementation(libs.androidx.work.runtime)`,
  `implementation(libs.androidx.hilt.work)` (for `HiltWorkerFactory`).
- [ ] **Step 4:** `./gradlew :feature:podcasts:dependencies --configuration debugRuntimeClasspath | grep work` to confirm resolution; if `2.9.1` fails to resolve, try `2.10.0`. Report build time.
- [ ] **Step 5: Commit** (paths: the three build files) `build: add WorkManager + hilt-work deps`

### Task 3.2: WorkManager + Hilt init in `app`

**Files:** Modify `app/.../OratorApplication.kt`, `app/src/main/AndroidManifest.xml`.

- [ ] **Step 1: `OratorApplication`** — add `Configuration.Provider`, preserving the existing Coil
  `SingletonImageLoader.Factory` + `okHttpClient`:
```kotlin
@HiltAndroidApp
class OratorApplication : Application(), SingletonImageLoader.Factory, Configuration.Provider {
    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun newImageLoader(context: PlatformContext): ImageLoader = /* unchanged */
}
```
(imports: `androidx.work.Configuration`, `androidx.hilt.work.HiltWorkerFactory`.)
- [ ] **Step 2: Manifest** — add `xmlns:tools="http://schemas.android.com/tools"` to `<manifest>`,
  and inside `<application>` add the provider that removes the default WM initializer:
```xml
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
- [ ] **Step 3:** `./gradlew :app:assembleDebug`. Report build time. (Compile-only confirmation;
  runtime verified in 4.x device test.)
- [ ] **Step 4: Commit** (paths: OratorApplication.kt, AndroidManifest.xml)
`feat(app): on-demand WorkManager init via HiltWorkerFactory`

### Task 3.3: `RefreshPreferences` (DataStore, `feature:podcasts`)

**Files:** Create `feature/podcasts/.../data/RefreshPreferences.kt`.

- [ ] **Step 1: Write it** — mirror `PlayerPreferences` (qualifier + `preferencesDataStore` +
  typed wrapper). Key `intervalMinutes` (Int, default 360). Expose `val intervalMinutes: Flow<Int>`
  and `suspend fun setIntervalMinutes(value: Int)`. Provide the `DataStore<Preferences>` via a Hilt
  `@Provides` with a `@RefreshDataStore` qualifier and `preferencesDataStore(name = "refresh")`.
- [ ] **Step 2:** `./gradlew :feature:podcasts:compileDebugKotlin`. Report build time.
- [ ] **Step 3: Commit** `feat(podcasts): RefreshPreferences (interval)`

### Task 3.4: `FeedRefreshWorker` + `RefreshScheduler`

**Files:** Create `feature/podcasts/.../work/FeedRefreshWorker.kt`, `feature/podcasts/.../data/RefreshScheduler.kt`; test `RefreshSchedulerTest.kt`.

- [ ] **Step 1: Worker**:
```kotlin
@HiltWorker
class FeedRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: PodcastRepository,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runCatching { repository.refreshAll() }
        .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
}
```
- [ ] **Step 2: Write the failing scheduler test** (use `WorkManagerTestInitHelper`):
  - `reconcile(0)` → no unique periodic work enqueued (cancelled).
  - `reconcile(360)` → one unique periodic work present with `NetworkType.CONNECTED`.
  - `reconcile(60)` after `reconcile(360)` → still exactly one (replaced).
  (Inspect via `WorkManager.getWorkInfosForUniqueWork(REFRESH_WORK).get()`.)
- [ ] **Step 3:** Run, expect FAIL. Report build time.
- [ ] **Step 4: Implement `RefreshScheduler`** (Singleton, injects `@ApplicationContext` +
  `RefreshPreferences` + `PodcastRepository`):
```kotlin
companion object { const val REFRESH_WORK = "feed-refresh" }

fun reconcile(intervalMinutes: Int) {
    val wm = WorkManager.getInstance(context)
    if (intervalMinutes <= 0) { wm.cancelUniqueWork(REFRESH_WORK); return }
    val req = PeriodicWorkRequestBuilder<FeedRefreshWorker>(intervalMinutes.toLong(), TimeUnit.MINUTES)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()
    wm.enqueueUniquePeriodicWork(REFRESH_WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
}

/** Eager wiring: collect the interval pref + one app-open refresh. */
fun start(scope: CoroutineScope) {
    scope.launch { preferences.intervalMinutes.collect { reconcile(it) } }
    scope.launch { runCatching { repository.refreshAll() } } // app-open refresh (best-effort)
}
```
- [ ] **Step 5:** Run, expect PASS. Report build time.
- [ ] **Step 6: Eager start** — in `PodcastsFeatureEntry` (already eagerly constructed), inject
  `RefreshScheduler` and call `start(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))`
  from `init` (mirrors `PlaylistsFeatureEntry`). Confirm `PodcastsFeatureEntry`'s constructor can
  take it (add the param; it's `@Inject`).
- [ ] **Step 7: Commit** (paths: FeedRefreshWorker.kt, RefreshScheduler.kt, PodcastsFeatureEntry.kt, test)
`feat(podcasts): FeedRefreshWorker + RefreshScheduler (periodic + app-open)`

### Task 3.5: Refresh-interval Settings row

**Files:** Modify `feature/podcasts/.../PodcastsSettingsSection.kt` (+ its ViewModel) OR a new
`RefreshSettingsSection`. Prefer extending `PodcastsSettingsSection` (one "Podcasts" block).

- [ ] **Step 1:** Add a `SettingsRow` "Background refresh" showing the current label
  (Off/15m/1h/3h/6h/12h/daily). Tapping cycles to the next preset; persist via
  `RefreshPreferences.setIntervalMinutes`. The VM exposes `intervalMinutes` as state + an
  `onCyclePreset()` that maps current→next in the ordered preset list `[0,15,60,180,360,720,1440]`.
  Add a pure `RefreshPresets` object (list + `label(minutes)` + `next(minutes)`) and unit-test it.
- [ ] **Step 2: Test** `RefreshPresets` (label mapping; next wraps daily→Off). Run red→green.
- [ ] **Step 3:** `./gradlew :feature:podcasts:compileDebugKotlin` + the preset test. Report build time.
- [ ] **Step 4: Commit** (paths: settings section + VM + RefreshPresets + test)
`feat(podcasts): background-refresh interval setting`

### Task 3.6: Chunk 3 gate
- [ ] `./gradlew test lint assembleDebug` — green. Report build time.

---

## Chunk 4: Per-podcast auto-insert config UI + device verification

Outcome: configure a podcast's auto-insert from its show screen; full feature device-verified.

### Task 4.1: Auto-insert config on the show screen

**Files:** Modify `feature/podcasts/.../PodcastDetailScreen.kt` + `PodcastDetailViewModel.kt`.

- [ ] **Step 1: VM** — expose `autoInsert: StateFlow<AutoInsertConfig>` (target playlist id+name or
  null, + rule) derived from `podcastDao.observeById` (already observed) joined with playlist names
  from `playlistDao.observePlaylists()`; and `val playlists: StateFlow<List<PlaylistSummary>>`.
  Add `fun setAutoInsert(playlistId: Long?, rule: AutoInsertRule?)` →
  `viewModelScope.launch { podcastDao.updateAutoInsert(podcastId, playlistId, rule) }`. (Inject
  `playlistDao` — `core:database`, no feature import.)
- [ ] **Step 2: Screen** — add an **"Auto-add new episodes"** row in the show header/effects area
  showing the current target ("Off" or the playlist name). Tapping opens a dialog: radio list of
  playlists (+ "Off") and a Top/Bottom toggle; Confirm → `viewModel.setAutoInsert(...)`. If
  `playlists` is empty, show "Create a playlist first" (optionally a button navigating to
  `CommonRoutes.Playlists`).
- [ ] **Step 3:** `./gradlew :feature:podcasts:compileDebugKotlin`. Report build time.
- [ ] **Step 4: Commit** (paths: PodcastDetailScreen.kt, PodcastDetailViewModel.kt)
`feat(podcasts): per-podcast auto-insert config on the show screen`

### Task 4.2: Chunk 4 gate + device smoke test

- [ ] **Step 1:** `./gradlew test lint assembleDebug` — green. Report build time.
- [ ] **Step 2: Build + install** the debug APK on the Pixel 7a (live adb serial in `/tmp/adb-live`).
  **Ask the user for any SAF folder / subscription setup** (don't drive DocumentsUI). Note: schema
  v7 is destructive — the library/subscriptions are wiped on install; user restores as in 5a.
- [ ] **Step 3: Verify on device** (drive the app's own UI; `screencap` may be flaky — use
  `uiautomator dump` for state):
  1. Settings → Background refresh row cycles Off→15m→…→daily and persists.
  2. Subscribe to a podcast; on a show screen set **Auto-add new episodes → <playlist> → Top**.
  3. Trigger a refresh (manual refresh button is the reliable trigger) **after** simulating a new
     episode — easiest path: set auto-insert, then unsubscribe+resubscribe or pick a feed that has
     a fresh drop; OR verify the seam by confirming a *manual* refresh that discovers a new episode
     auto-inserts it into the playlist (check the playlist gains the episode at the chosen end).
  4. Confirm **NEW_TO_TOP** vs **NEW_TO_BOTTOM** land at the right end.
  5. Set the interval to 15m and confirm a periodic work is scheduled
     (`adb shell dumpsys jobscheduler | grep -i orator` shows a WorkManager job) — full periodic
     firing needn't be waited out; scheduling presence + the manual-refresh auto-insert prove the
     path.
  6. Clear auto-insert (→ Off) → subsequent new episodes are not inserted.
- [ ] **Step 4:** Fix any issues, re-gate. Report final build time.

---

## Completion

After Chunk 4 passes its gate + device smoke:
- Announce: "I'm using the finishing-a-development-branch skill to complete this work."
- Use **superpowers:finishing-a-development-branch**: verify `./gradlew test`, present the four
  options, execute the choice.
- On merge/PR: update `docs/architecture.md` §15 (Phase 5b done; **next: Phase 6 — audiobookshelf**)
  and the `akouo-phase-status` memory. Commit on explicit paths.

## Deferred (do NOT build here)
Wi-Fi-only refresh constraint, `CleanupWorker`, `AbsSyncWorker`, new-episode notifications.
