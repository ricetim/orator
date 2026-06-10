# Phase 3: Player Experience Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** All Phase 3 playback behaviors working end to end — speed (global/per-type/per-item), silence trimming, volume boost, sleep timer (duration + chapter boundary), play history, smart rewind on resume, intro/outro clip mechanism — plus placeholder Now-Playing, Settings, and History screens.

**Architecture:** Approach A from the approved spec (`docs/superpowers/specs/2026-06-10-player-experience-design.md`): playback policy lives service-side in `core:playback` as small independent classes configured by DataStore prefs; clip windows ride Media3's `ClippingConfiguration` through `PlayRequest`; history and per-item-speed persistence use the existing `@IntoSet` listener pattern so `core:playback` never learns about Room.

**Tech Stack:** Kotlin 2.1.0, Media3 1.5.1 (`SilenceSkippingAudioProcessor`, `ClippingConfiguration`), platform `LoudnessEnhancer`, Room 2.7.1, DataStore Preferences, Hilt, Robolectric JVM tests.

---

## Execution notes (deviations from the written plan)

Executed in-session 2026-06-10; all 11 device-checklist steps passed on the Pixel 7a.

- **Red-verification steps skipped** (write test + implementation together, verify green) —
  same recorded deviation as Phases 1–2.
- **`updatePosition` had two extra callers** the plan missed: `OratorDatabaseTest` and
  `AudiobookImporterTest` both needed updating to `updateProgress` (the plan claimed
  `AudiobookPositionListener` was the only caller).
- **Tasks 13+14 landed as one commit** — `PlayerFeatureEntry` references `HistoryScreen`,
  so the routes change couldn't compile alone.
- **`PlayerPreferences` setters changed to block bodies returning `Unit`** — the planned
  expression bodies leaked `Preferences` (the `edit` return type) into consumers, forcing a
  DataStore classpath dependency on every feature module.
- **History lazy-close** implemented as planned in the Chunk 4 note: `endedAtUtc == null`
  means "interrupted"; no invented end times.
- **`SpeedResolver` extra cases** covered via `PlayerPreferencesTest.toSpeedPreferences`
  (planned note), not new `SpeedResolverTest` methods.
- **Post-plan addition:** placeholder-screen menus centered per user preference given during
  device testing (`style:` commit; saved to memory for future screens).

---

## Orientation (read once before Chunk 1)

**Branch:** `phase-3-player-experience` (already created; the spec is its first commit).

**Environment facts that will bite you if forgotten:**

- Always run Gradle from `/home/tim/projects/akouo` via `./gradlew`. Background long builds:
  `./gradlew --console=plain <tasks> > /tmp/X.log 2>&1; echo "GRADLE_EXIT=$?"` and **check the
  log**, not the task exit code.
- Room-backed JVM tests must use `runBlocking`, **not** `runTest` (Room's transaction executor
  deadlocks under the virtual-time scheduler).
- Robolectric tests need `@RunWith(RobolectricTestRunner::class)` and
  `@Config(sdk = [34])` (project convention from `OratorDatabaseTest`).
- No emulator. Device testing = wireless adb to the Pixel 7a (`~/Android/Sdk/platform-tools/adb`).

**Single-process simplification (used throughout):** the app, the `PlaybackService`, and every
Hilt `@Singleton` live in **one process**. If the process dies, playback dies with it. Therefore
UI ↔ service coordination that Media3 would route through custom session commands can instead be
a shared `@Singleton` holding a `StateFlow` — the UI writes commands into it, the service
observes and enforces. We use this for the sleep timer and for queue metadata (chapter
boundaries). This is deliberate and must not be "fixed" into session-command plumbing.

**How MediaType reaches the service:** `PlayRequest.mediaType` is mapped into each
`MediaItem`'s `MediaMetadata.mediaType` (`MEDIA_TYPE_AUDIO_BOOK_CHAPTER` /
`MEDIA_TYPE_PODCAST_EPISODE`) so service-side policy (smart rewind per-type enable, history
rows) can recover it without parsing feature-owned mediaId strings.

**Positions are clip-relative** (spec decision): for clipped items Media3 reports positions
relative to the clip start; we store them as-is and restore them as-is. Never convert.

**Module dependency rules:** app → feature → core, never feature → feature. Cross-feature
navigation targets live as route constants in `core:navigation` (`CommonRoutes`).

**New/changed surface map:**

| Module | New | Modified |
|---|---|---|
| `core:playback` | `PlayerPreferences.kt`, `SmartRewind.kt`, `SleepTimer.kt`, `ActiveQueueInfo.kt`, `MediaItemFactory.kt`, `SilenceTrim.kt`, `LoudnessBooster.kt`, `SmartRewindController.kt`, `PlaybackEventListener.kt`, `SpeedOverrideListener.kt` | `PlayRequest.kt`, `PlaybackConnection.kt`, `PlaybackService.kt`, `PlaybackUiState.kt`, `PlaybackModule.kt`, `build.gradle.kts` (datastore dep); delete `res/raw/sample.mp3` + `playBundledSample()` |
| `core:database` | `HistoryEntity.kt`, `HistoryDao.kt` | `BookEntity.kt` (+`lastPlayedAtMs`, +`speedOverride`), `BookDao.kt`, `OratorDatabase.kt` (v2), `DatabaseModule.kt` |
| `core:navigation` | `CommonRoutes.kt` | — |
| `feature:player` | `HistoryRecorder.kt`, `HistoryScreen.kt`, `HistoryViewModel.kt`, `NowPlayingScreen.kt` content | `PlayerScreen.kt` (rewritten), `PlayerViewModel.kt`, `PlayerFeatureEntry.kt`, `PlayerFeatureModule.kt`, `build.gradle.kts` (+core:database) |
| `feature:settings` | whole module | `settings.gradle.kts`, `app/build.gradle.kts` |
| `feature:audiobooks` | `BookSpeedOverrideListener.kt` | `QueueBuilder.kt` (boundaries + override), `AudiobookPositionListener.kt` (lastPlayedAt), `BookDetailViewModel.kt` (cold rewind), `AudiobookListScreen.kt` + `AudiobookListViewModel.kt` + `AudiobooksFeatureEntry.kt` (nav glue) |

**Chunks:**

1. Pure logic + preferences (`SmartRewind`, `PlayerPreferences`, `SleepTimer` state, boundary math)
2. Play-request plumbing (`PlayRequest` fields, `MediaItemFactory`, `PlaybackConnection` speed/override/seek APIs, `ActiveQueueInfo`)
3. Service-side enforcement (silence trim, loudness boost, smart rewind, sleep timer, playback events)
4. Persistence (DB v2: history + book columns; history recorder; speed-override listener; cold-start rewind)
5. Screens (Now-Playing rewrite, `feature:settings`, history list, nav glue)
6. Full build + on-device verification

---

## Chunk 1: Pure logic + preferences

### Task 1: `SmartRewind` pure calculator

**Files:**
- Create: `core/playback/src/main/java/com/orator/core/playback/SmartRewind.kt`
- Test: `core/playback/src/test/java/com/orator/core/playback/SmartRewindTest.kt`

- [x] **Step 1: Write the failing test**

```kotlin
package com.orator.core.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartRewindTest {

    @Test
    fun `short pause rewinds nothing`() {
        assertEquals(0L, SmartRewind.rewindMs(pausedForMs = 0))
        assertEquals(0L, SmartRewind.rewindMs(pausedForMs = 29_999))
    }

    @Test
    fun `medium pause rewinds five seconds`() {
        assertEquals(5_000L, SmartRewind.rewindMs(pausedForMs = 30_000))
        assertEquals(5_000L, SmartRewind.rewindMs(pausedForMs = 5 * 60_000L - 1))
    }

    @Test
    fun `long pause rewinds fifteen seconds`() {
        assertEquals(15_000L, SmartRewind.rewindMs(pausedForMs = 5 * 60_000L))
        assertEquals(15_000L, SmartRewind.rewindMs(pausedForMs = 60 * 60_000L - 1))
    }

    @Test
    fun `very long pause rewinds thirty seconds`() {
        assertEquals(30_000L, SmartRewind.rewindMs(pausedForMs = 60 * 60_000L))
        assertEquals(30_000L, SmartRewind.rewindMs(pausedForMs = Long.MAX_VALUE))
    }

    @Test
    fun `negative pause duration rewinds nothing`() {
        // Clock skew (e.g. device time changed) must not produce a forward seek.
        assertEquals(0L, SmartRewind.rewindMs(pausedForMs = -5_000))
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:playback:testDebugUnitTest --tests "com.orator.core.playback.SmartRewindTest"`
Expected: FAIL — `Unresolved reference: SmartRewind` (compile error counts as red).

- [x] **Step 3: Write the implementation**

```kotlin
package com.orator.core.playback

/**
 * How far to seek back when resuming after a pause, so the listener re-anchors in the
 * narrative. Stepped tiers (user-confirmed, Smart AudioBook Player is the reference):
 * the longer you were away, the more context you need back.
 *
 * Pure function — no Android, no clock; callers pass the elapsed pause duration.
 */
object SmartRewind {

    fun rewindMs(pausedForMs: Long): Long = when {
        pausedForMs < 30_000 -> 0
        pausedForMs < 5 * 60_000 -> 5_000
        pausedForMs < 60 * 60_000 -> 15_000
        else -> 30_000
    }
}
```

Note `pausedForMs < 30_000` already returns 0 for negatives — no separate guard needed.

- [x] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:playback:testDebugUnitTest --tests "com.orator.core.playback.SmartRewindTest"`
Expected: 5 tests PASS.

- [x] **Step 5: Commit**

```bash
git add core/playback/src
git commit -m "feat: smart-rewind tier calculator"
```

### Task 2: Sleep-timer state model + boundary math

**Files:**
- Create: `core/playback/src/main/java/com/orator/core/playback/SleepTimer.kt`
- Test: `core/playback/src/test/java/com/orator/core/playback/SleepTimerTest.kt`

The `SleepTimer` is the shared singleton described in Orientation: the UI arms/cancels it, the
service enforces it. The *state* and the *boundary arithmetic* are pure and tested here; the
enforcement loop is service code (Chunk 3).

- [x] **Step 1: Write the failing test**

```kotlin
package com.orator.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SleepTimerTest {

    @Test
    fun `starts off`() {
        assertEquals(SleepTimerState.Off, SleepTimer().state.value)
    }

    @Test
    fun `arming a duration computes the deadline from the provided clock`() {
        val timer = SleepTimer()
        timer.armDuration(minutes = 30, nowMs = 1_000_000)
        assertEquals(SleepTimerState.Duration(endsAtMs = 1_000_000 + 30 * 60_000L), timer.state.value)
    }

    @Test
    fun `arming boundary mode and cancelling`() {
        val timer = SleepTimer()
        timer.armBoundary()
        assertEquals(SleepTimerState.EndOfBoundary, timer.state.value)
        timer.cancel()
        assertEquals(SleepTimerState.Off, timer.state.value)
    }

    @Test
    fun `next boundary is the first one strictly after the current position`() {
        val boundaries = listOf(0L, 240_000L, 600_000L)
        assertEquals(240_000L, SleepTimer.nextBoundary(boundaries, positionMs = 10_000))
        assertEquals(600_000L, SleepTimer.nextBoundary(boundaries, positionMs = 240_000))
        assertNull(SleepTimer.nextBoundary(boundaries, positionMs = 600_000))
        assertNull(SleepTimer.nextBoundary(emptyList(), positionMs = 0))
    }

    @Test
    fun `unsorted boundaries are tolerated`() {
        assertEquals(
            240_000L,
            SleepTimer.nextBoundary(listOf(600_000L, 0L, 240_000L), positionMs = 10_000),
        )
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:playback:testDebugUnitTest --tests "com.orator.core.playback.SleepTimerTest"`
Expected: FAIL — unresolved references.

- [x] **Step 3: Write the implementation**

```kotlin
package com.orator.core.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SleepTimerState {
    data object Off : SleepTimerState

    /** Pause when the wall clock reaches [endsAtMs]. */
    data class Duration(val endsAtMs: Long) : SleepTimerState

    /** Pause at the next chapter boundary (single-file books) or item transition. */
    data object EndOfBoundary : SleepTimerState
}

/**
 * Shared sleep-timer command holder. The UI arms/cancels; PlaybackService observes [state]
 * and does the pausing (Chunk 3). A plain singleton instead of Media3 custom session
 * commands because app and service share one process (see plan Orientation).
 */
@Singleton
class SleepTimer @Inject constructor() {

    private val _state = MutableStateFlow<SleepTimerState>(SleepTimerState.Off)
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    fun armDuration(minutes: Int, nowMs: Long = System.currentTimeMillis()) {
        _state.value = SleepTimerState.Duration(endsAtMs = nowMs + minutes * 60_000L)
    }

    fun armBoundary() {
        _state.value = SleepTimerState.EndOfBoundary
    }

    fun cancel() {
        _state.value = SleepTimerState.Off
    }

    companion object {
        /** First boundary strictly after [positionMs], or null (≙ fall back to item transition). */
        fun nextBoundary(boundariesMs: List<Long>, positionMs: Long): Long? =
            boundariesMs.sorted().firstOrNull { it > positionMs }
    }
}
```

- [x] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:playback:testDebugUnitTest --tests "com.orator.core.playback.SleepTimerTest"`
Expected: 5 tests PASS.

- [x] **Step 5: Commit**

```bash
git add core/playback/src
git commit -m "feat: sleep-timer state model with boundary math"
```

### Task 3: `PlayerPreferences` (DataStore)

**Files:**
- Modify: `core/playback/build.gradle.kts` (add DataStore + test deps)
- Create: `core/playback/src/main/java/com/orator/core/playback/PlayerPreferences.kt`
- Test: `core/playback/src/test/java/com/orator/core/playback/PlayerPreferencesTest.kt`

- [x] **Step 1: Add dependencies**

In `core/playback/build.gradle.kts` `dependencies { }`, after the Media3 lines, add:

```kotlin
    implementation(libs.androidx.datastore.preferences)
```

and in the test section (create the lines if absent — copy the test block shape from
`feature/audiobooks/build.gradle.kts`):

```kotlin
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
```

Also ensure the android block has Robolectric resource support (copy from feature:audiobooks
if missing):

```kotlin
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
```

- [x] **Step 2: Write the failing test**

`runBlocking`, not `runTest`. No Robolectric: `PlayerPreferences` takes its `DataStore`
via the constructor (see Step 4), so tests build a **fresh store per test method** in a
`TemporaryFolder`. (A `by preferencesDataStore` delegate read in the test would cache one
store statically per process; Robolectric does not reset statics between methods, so tests
would contaminate each other in JUnit's hash order.)

```kotlin
package com.orator.core.playback

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.orator.core.model.MediaType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PlayerPreferencesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val prefs by lazy {
        PlayerPreferences(
            PreferenceDataStoreFactory.create { File(tmp.root, "test.preferences_pb") },
        )
    }

    @Test
    fun `defaults are sane`() = runBlocking {
        val p = prefs.flow.first()
        assertEquals(1.0f, p.globalSpeed)
        assertEquals(emptyMap<MediaType, Float>(), p.perTypeSpeed)
        assertEquals(false, p.silenceTrim)
        assertEquals(0, p.boostMb)
        assertEquals(true, p.smartRewind.getValue(MediaType.AUDIOBOOK))
        assertEquals(true, p.smartRewind.getValue(MediaType.PODCAST))
        assertEquals(30, p.defaultSleepMinutes)
    }

    @Test
    fun `values round-trip`() = runBlocking {
        prefs.setGlobalSpeed(1.5f)
        prefs.setTypeSpeed(MediaType.AUDIOBOOK, 1.25f)
        prefs.setSilenceTrim(true)
        prefs.setBoostMb(600)
        prefs.setSmartRewind(MediaType.PODCAST, false)
        prefs.setDefaultSleepMinutes(45)

        val p = prefs.flow.first()
        assertEquals(1.5f, p.globalSpeed)
        assertEquals(1.25f, p.perTypeSpeed.getValue(MediaType.AUDIOBOOK))
        assertEquals(true, p.silenceTrim)
        assertEquals(600, p.boostMb)
        assertEquals(false, p.smartRewind.getValue(MediaType.PODCAST))
        assertEquals(true, p.smartRewind.getValue(MediaType.AUDIOBOOK))
        assertEquals(45, p.defaultSleepMinutes)
    }

    @Test
    fun `clearing a per-type speed falls back to global`() = runBlocking {
        prefs.setTypeSpeed(MediaType.AUDIOBOOK, 1.25f)
        prefs.setTypeSpeed(MediaType.AUDIOBOOK, null)
        val p = prefs.flow.first()
        assertEquals(null, p.perTypeSpeed[MediaType.AUDIOBOOK])
    }

    @Test
    fun `toSpeedPreferences feeds the existing resolver`() = runBlocking {
        prefs.setGlobalSpeed(2.0f)
        prefs.setTypeSpeed(MediaType.PODCAST, 1.1f)
        val sp = prefs.flow.first().toSpeedPreferences()
        assertEquals(1.1f, SpeedResolver.resolve(sp, MediaType.PODCAST, itemOverride = null))
        assertEquals(2.0f, SpeedResolver.resolve(sp, MediaType.AUDIOBOOK, itemOverride = null))
        assertEquals(0.8f, SpeedResolver.resolve(sp, MediaType.PODCAST, itemOverride = 0.8f))
    }
}
```

- [x] **Step 3: Run test to verify it fails**

Run: `./gradlew :core:playback:testDebugUnitTest --tests "com.orator.core.playback.PlayerPreferencesTest"`
Expected: FAIL — unresolved references.

- [x] **Step 4: Write the implementation**

```kotlin
package com.orator.core.playback

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.orator.core.model.MediaType
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/** Distinguishes the player-policy DataStore from any other DataStore<Preferences> binding. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlayerDataStore

private val Context.playerDataStore by preferencesDataStore(name = "player")

@Module
@InstallIn(SingletonComponent::class)
object PlayerDataStoreModule {
    @Provides
    @Singleton
    @PlayerDataStore
    fun providePlayerDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.playerDataStore
}

private val KEY_GLOBAL_SPEED = floatPreferencesKey("global_speed")
private val KEY_SILENCE_TRIM = booleanPreferencesKey("silence_trim")
private val KEY_BOOST_MB = intPreferencesKey("boost_mb")
private val KEY_SLEEP_MINUTES = intPreferencesKey("default_sleep_minutes")
private fun speedKey(type: MediaType) = floatPreferencesKey("speed_${type.name}")
private fun rewindKey(type: MediaType) = booleanPreferencesKey("rewind_${type.name}")

/** Snapshot of every player-policy preference. */
data class PlayerPrefs(
    val globalSpeed: Float = SpeedResolver.DEFAULT_SPEED,
    val perTypeSpeed: Map<MediaType, Float> = emptyMap(),
    val silenceTrim: Boolean = false,
    val boostMb: Int = 0,
    val smartRewind: Map<MediaType, Boolean> = MediaType.entries.associateWith { true },
    val defaultSleepMinutes: Int = 30,
) {
    fun toSpeedPreferences() = SpeedPreferences(global = globalSpeed, perType = perTypeSpeed)
}

/**
 * Typed DataStore wrapper for player-policy settings (pattern: AudiobooksPrefs, but the
 * store itself is constructor-injected so tests can use a fresh, isolated instance).
 */
@Singleton
class PlayerPreferences @Inject constructor(
    @PlayerDataStore private val dataStore: DataStore<Preferences>,
) {
    val flow: Flow<PlayerPrefs> = dataStore.data.map { p ->
        PlayerPrefs(
            globalSpeed = p[KEY_GLOBAL_SPEED] ?: SpeedResolver.DEFAULT_SPEED,
            perTypeSpeed = MediaType.entries
                .mapNotNull { t -> p[speedKey(t)]?.let { t to it } }
                .toMap(),
            silenceTrim = p[KEY_SILENCE_TRIM] ?: false,
            boostMb = p[KEY_BOOST_MB] ?: 0,
            smartRewind = MediaType.entries.associateWith { t -> p[rewindKey(t)] ?: true },
            defaultSleepMinutes = p[KEY_SLEEP_MINUTES] ?: 30,
        )
    }

    suspend fun setGlobalSpeed(speed: Float) =
        dataStore.edit { it[KEY_GLOBAL_SPEED] = speed }

    suspend fun setTypeSpeed(type: MediaType, speed: Float?) =
        dataStore.edit {
            if (speed == null) it.remove(speedKey(type)) else it[speedKey(type)] = speed
        }

    suspend fun setSilenceTrim(enabled: Boolean) =
        dataStore.edit { it[KEY_SILENCE_TRIM] = enabled }

    suspend fun setBoostMb(mb: Int) =
        dataStore.edit { it[KEY_BOOST_MB] = mb }

    suspend fun setSmartRewind(type: MediaType, enabled: Boolean) =
        dataStore.edit { it[rewindKey(type)] = enabled }

    suspend fun setDefaultSleepMinutes(minutes: Int) =
        dataStore.edit { it[KEY_SLEEP_MINUTES] = minutes }
}
```

Note `MediaType.entries` — `MediaType` is `enum class MediaType { AUDIOBOOK, PODCAST }` in
`core:model`; `core:playback` already depends on `core:model`.

- [x] **Step 5: Run test to verify it passes**

Run: `./gradlew :core:playback:testDebugUnitTest --tests "com.orator.core.playback.PlayerPreferencesTest"`
Expected: 4 tests PASS.

Spec-coverage note: the spec's "extended `SpeedResolver` cases" are covered by the
`toSpeedPreferences feeds the existing resolver` test above rather than by adding cases to
`SpeedResolverTest` — the resolver itself is unchanged. Record as a (benign) deviation in
execution notes.

- [x] **Step 6: Run the whole module's tests + commit**

Run: `./gradlew :core:playback:testDebugUnitTest`
Expected: all pass (existing `SpeedResolverTest` still green).

```bash
git add core/playback
git commit -m "feat: typed DataStore preferences for player policy"
```

---

## Chunk 2: Play-request plumbing

### Task 4: Extend `PlayRequest` and add `MediaItemFactory`

**Files:**
- Modify: `core/playback/src/main/java/com/orator/core/playback/PlayRequest.kt`
- Create: `core/playback/src/main/java/com/orator/core/playback/MediaItemFactory.kt`
- Test: `core/playback/src/test/java/com/orator/core/playback/MediaItemFactoryTest.kt`

- [x] **Step 1: Extend the request types** (additive, all defaulted — `QueueBuilder` keeps compiling)

Replace `PlayRequest.kt` contents with:

```kotlin
package com.orator.core.playback

import com.orator.core.model.MediaType

/**
 * One playable file/stream in a queue. [mediaId] must be globally unique and parseable by its
 * owning feature. [clipStartMs]/[clipEndMs] are the intro/outro auto-skip windows: Media3 clips
 * the item so playback (and all reported positions — which become clip-relative) covers only
 * [clipStartMs, clipEndMs). Null end = play to the end of the file.
 */
data class PlayableItem(
    val mediaId: String,
    val uri: String,
    val title: String,
    val artist: String = "",
    val clipStartMs: Long = 0,
    val clipEndMs: Long? = null,
)

/**
 * A complete "play this" command from a feature: the queue plus where to start in it.
 * [chapterBoundariesMs] are chapter start positions *within a single item* (the m4b case) so the
 * boundary sleep timer can pause at "end of chapter"; multi-file queues leave it empty and the
 * timer falls back to item transitions. [speedOverride] is the per-item speed (book/episode),
 * resolved against type/global defaults by SpeedResolver.
 */
data class PlayRequest(
    val items: List<PlayableItem>,
    val startIndex: Int = 0,
    val startPositionMs: Long = 0,
    val mediaType: MediaType,
    val chapterBoundariesMs: List<Long> = emptyList(),
    val speedOverride: Float? = null,
)
```

- [x] **Step 2: Write the failing `MediaItemFactory` test**

```kotlin
package com.orator.core.playback

import androidx.media3.common.MediaMetadata
import com.orator.core.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaItemFactoryTest {

    private val item = PlayableItem(
        mediaId = "audiobook/abc/0",
        uri = "content://x/file.m4b",
        title = "Book",
        artist = "Author",
    )

    @Test
    fun `maps identity, metadata and media type`() {
        val mi = MediaItemFactory.from(item, MediaType.AUDIOBOOK)
        assertEquals("audiobook/abc/0", mi.mediaId)
        assertEquals("Book", mi.mediaMetadata.title.toString())
        assertEquals("Author", mi.mediaMetadata.artist.toString())
        assertEquals(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER, mi.mediaMetadata.mediaType)

        val pod = MediaItemFactory.from(item, MediaType.PODCAST)
        assertEquals(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE, pod.mediaMetadata.mediaType)
    }

    @Test
    fun `no clip fields means no clipping configuration`() {
        val mi = MediaItemFactory.from(item, MediaType.AUDIOBOOK)
        assertEquals(0L, mi.clippingConfiguration.startPositionMs)
        assertEquals(androidx.media3.common.C.TIME_END_OF_SOURCE, mi.clippingConfiguration.endPositionMs)
    }

    @Test
    fun `clip fields map to ClippingConfiguration`() {
        val clipped = MediaItemFactory.from(
            item.copy(clipStartMs = 25_000, clipEndMs = 1_800_000),
            MediaType.PODCAST,
        )
        assertEquals(25_000L, clipped.clippingConfiguration.startPositionMs)
        assertEquals(1_800_000L, clipped.clippingConfiguration.endPositionMs)
    }

    @Test
    fun `media type round-trips back out of metadata`() {
        val mi = MediaItemFactory.from(item, MediaType.PODCAST)
        assertEquals(MediaType.PODCAST, MediaItemFactory.mediaTypeOf(mi.mediaMetadata))
        assertEquals(null, MediaItemFactory.mediaTypeOf(MediaMetadata.EMPTY))
    }
}
```

- [x] **Step 3: Run test to verify it fails**

Run: `./gradlew :core:playback:testDebugUnitTest --tests "com.orator.core.playback.MediaItemFactoryTest"`
Expected: FAIL — `Unresolved reference: MediaItemFactory`.

- [x] **Step 4: Write the implementation**

```kotlin
package com.orator.core.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.orator.core.model.MediaType

/**
 * Single place where a feature's PlayableItem becomes a Media3 MediaItem. Carries MediaType in
 * MediaMetadata.mediaType so service-side policy (smart rewind, history) can recover it without
 * parsing feature-owned mediaId strings. Clip windows become ClippingConfiguration: Media3 then
 * handles seeking/duration/transitions inside the clip natively, and every reported position is
 * clip-relative (stored as-is; see plan Orientation).
 */
object MediaItemFactory {

    fun from(item: PlayableItem, mediaType: MediaType): MediaItem {
        val builder = MediaItem.Builder()
            .setMediaId(item.mediaId)
            .setUri(item.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.title)
                    .setArtist(item.artist)
                    .setMediaType(
                        when (mediaType) {
                            MediaType.AUDIOBOOK -> MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER
                            MediaType.PODCAST -> MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE
                        },
                    )
                    .build(),
            )
        if (item.clipStartMs > 0 || item.clipEndMs != null) {
            val clip = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(item.clipStartMs.coerceAtLeast(0))
            item.clipEndMs?.let { clip.setEndPositionMs(it) }
            builder.setClippingConfiguration(clip.build())
        }
        return builder.build()
    }

    /** Inverse of the mediaType mapping above; null for items we didn't build. */
    fun mediaTypeOf(metadata: MediaMetadata): MediaType? = when (metadata.mediaType) {
        MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER -> MediaType.AUDIOBOOK
        MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE -> MediaType.PODCAST
        else -> null
    }
}
```

Error-handling note from the spec ("clip windows wider than the file → ignored"): Media3 itself
ignores an end position past the file end, and a start past the end yields an unplayable item
error surfaced like any source error — no extra code here, by design.

- [x] **Step 5: Run test to verify it passes**

Run: `./gradlew :core:playback:testDebugUnitTest --tests "com.orator.core.playback.MediaItemFactoryTest"`
Expected: 4 tests PASS.

- [x] **Step 6: Commit**

```bash
git add core/playback/src
git commit -m "feat: clip windows and media type carried into MediaItems"
```

### Task 5: `ActiveQueueInfo` + listener interfaces

**Files:**
- Create: `core/playback/src/main/java/com/orator/core/playback/ActiveQueueInfo.kt`
- Create: `core/playback/src/main/java/com/orator/core/playback/PlaybackEventListener.kt`
- Create: `core/playback/src/main/java/com/orator/core/playback/SpeedOverrideListener.kt`

No tests — pure declarations (state holder + two interfaces); behavior is tested where used.

- [x] **Step 1: Write the three files**

`ActiveQueueInfo.kt`:

```kotlin
package com.orator.core.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Queue-scoped metadata the service needs but Media3 items don't carry: the chapter boundary
 * positions for single-file books. Written by PlaybackConnection.play(), read by the boundary
 * sleep timer. Shared singleton instead of session-command plumbing (see plan Orientation).
 */
@Singleton
class ActiveQueueInfo @Inject constructor() {

    private val _chapterBoundariesMs = MutableStateFlow<List<Long>>(emptyList())
    val chapterBoundariesMs: StateFlow<List<Long>> = _chapterBoundariesMs.asStateFlow()

    fun onNewQueue(boundariesMs: List<Long>) {
        _chapterBoundariesMs.value = boundariesMs
    }
}
```

`PlaybackEventListener.kt`:

```kotlin
package com.orator.core.playback

import com.orator.core.model.MediaType

/**
 * Session events for features that record listening (play history). Contributed via Hilt
 * @IntoSet, mirroring PlaybackPositionListener: core:playback emits, features persist.
 */
interface PlaybackEventListener {
    /** A queue item started playing (initial play or queue transition). */
    suspend fun onItemStarted(mediaId: String, title: String, mediaType: MediaType?)

    /** The previously started item stopped: ran to its end ([completed]) or was switched away/paused-final. */
    suspend fun onItemEnded(mediaId: String, positionMs: Long, completed: Boolean)
}
```

`SpeedOverrideListener.kt`:

```kotlin
package com.orator.core.playback

/**
 * Notified when the user sets/clears a per-item speed override from the player UI, so the
 * owning feature can persist it (feature:player must not write feature-owned tables directly).
 */
interface SpeedOverrideListener {
    suspend fun onSpeedOverrideChanged(mediaId: String, speed: Float?)
}
```

- [x] **Step 2: Verify it compiles + commit**

Run: `./gradlew :core:playback:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

```bash
git add core/playback/src
git commit -m "feat: queue info holder and playback event/override listener seams"
```

### Task 6: `PlaybackConnection` — real speed resolution, override API, seek helpers

**Files:**
- Modify: `core/playback/src/main/java/com/orator/core/playback/PlaybackConnection.kt`
- Modify: `core/playback/src/main/java/com/orator/core/playback/PlaybackUiState.kt`

This class is UI-side glue around a `MediaController`; it has no JVM-testable seams worth
faking (the controller requires a running service). The policy it applies — `SpeedResolver`,
`MediaItemFactory` — is already unit-tested; this task is wiring, verified by compile + the
existing build, then on device in Chunk 6.

- [x] **Step 1: Add `speed` to the UI state**

In `PlaybackUiState.kt` add a field at the end of the data class:

```kotlin
    val speed: Float = 1.0f,
```

- [x] **Step 2: Rework `PlaybackConnection`**

Apply these changes (full new constructor + changed members shown):

```kotlin
@Singleton
class PlaybackConnection @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playerPreferences: PlayerPreferences,
    private val activeQueueInfo: ActiveQueueInfo,
    private val speedOverrideListeners: Set<@JvmSuppressWildcards SpeedOverrideListener>,
) {
```

New fields — **after the `scope` declaration** (the `stateIn` initializer references `scope`;
declaring it above `scope`, e.g. next to `controller`, is a forward reference and won't
compile):

```kotlin
    /** Prefs snapshot, always current; Eagerly because play() reads .value synchronously. */
    private val prefs: StateFlow<PlayerPrefs> =
        playerPreferences.flow.stateIn(scope, SharingStarted.Eagerly, PlayerPrefs())

    private var currentMediaType: MediaType? = null
    private var currentOverride: Float? = null
```

In `init`, after `connect()`, live re-apply on settings change:

```kotlin
        scope.launch {
            prefs.collect { applySpeed() }
        }
```

Replace the body of `play(request)`:

```kotlin
    fun play(request: PlayRequest) {
        val c = controller ?: return
        currentMediaType = request.mediaType
        currentOverride = request.speedOverride
        activeQueueInfo.onNewQueue(request.chapterBoundariesMs)
        val items = request.items.map { MediaItemFactory.from(it, request.mediaType) }
        c.setMediaItems(items, request.startIndex, request.startPositionMs)
        c.prepare()
        applySpeed()
        c.play()
    }
```

Add the new policy/seek members:

```kotlin
    private fun applySpeed() {
        val c = controller ?: return
        val type = currentMediaType ?: return
        c.setPlaybackSpeed(
            SpeedResolver.resolve(prefs.value.toSpeedPreferences(), type, currentOverride),
        )
    }

    /** Sets/clears the per-item speed override; features persist it via SpeedOverrideListener. */
    fun setSpeedOverride(speed: Float?) {
        currentOverride = speed
        applySpeed()
        val mediaId = controller?.currentMediaItem?.mediaId ?: return
        scope.launch {
            speedOverrideListeners.forEach { it.onSpeedOverrideChanged(mediaId, speed) }
        }
    }

    /** Skip ±N within the current item, clamped to [0, duration]. */
    fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        val duration = c.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        c.seekTo((c.currentPosition + deltaMs).coerceIn(0, duration))
    }

    /** Absolute seek within the current item (slider drags). */
    fun seekWithinCurrent(positionMs: Long) {
        val c = controller ?: return
        c.seekTo(positionMs.coerceAtLeast(0))
    }
```

In `updateState()` add to the constructed state:

```kotlin
            speed = c.playbackParameters.speed,
```

New imports needed: `kotlinx.coroutines.flow.SharingStarted`, `kotlinx.coroutines.flow.stateIn`.
Leave `playBundledSample()` in place for now — `PlayerViewModel` still calls it; both die
together in Chunk 5's screen rewrite.

- [x] **Step 3: Build everything that compiles against these APIs**

Run: `./gradlew :core:playback:compileDebugKotlin :feature:audiobooks:compileDebugKotlin :feature:player:compileDebugKotlin`
Expected: BUILD SUCCESSFUL — all `PlayRequest`/`PlayableItem` construction sites used named
arguments with defaults, so nothing else changes.

Hilt note: the empty `Set<SpeedOverrideListener>` needs a declaration even before any feature
binds one. `PlaybackModule.kt` already holds an **abstract class** with a
`@Multibinds positionListeners()` method (this is how Phase 2 solved the same problem) — add
two more methods beside it:

```kotlin
    @Multibinds
    abstract fun speedOverrideListeners(): Set<SpeedOverrideListener>

    @Multibinds
    abstract fun playbackEventListeners(): Set<PlaybackEventListener>
```

- [x] **Step 4: Run module tests + commit**

Run: `./gradlew :core:playback:testDebugUnitTest`
Expected: all green.

```bash
git add core/playback/src
git commit -m "feat: connection applies resolved speed, override API, seek helpers"
```

---

## Chunk 3: Service-side enforcement

Media3's audio-pipeline classes (`DefaultRenderersFactory`, `DefaultAudioSink`,
`SilenceSkippingAudioProcessor`) are marked `@UnstableApi`; annotate the classes that touch
them with `@androidx.annotation.OptIn(UnstableApi::class)` exactly as shown — do not
suppress globally.

### Task 7: `SilenceTrim` + `LoudnessBooster`

**Files:**
- Create: `core/playback/src/main/java/com/orator/core/playback/SilenceTrim.kt`
- Create: `core/playback/src/main/java/com/orator/core/playback/LoudnessBooster.kt`

No JVM tests: both are thin wrappers over platform/Media3 effect objects that Robolectric
can't exercise meaningfully (no real audio pipeline). The error-handling contract
(`LoudnessEnhancer` may throw → boost silently disabled) is encoded here; behavior is verified
audibly in Chunk 6.

- [x] **Step 1: Write `SilenceTrim.kt`**

```kotlin
package com.orator.core.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the silence-skipping audio processor. The processor must be baked into the player's
 * audio sink at build time (renderersFactory), but its enabled flag can be flipped at runtime —
 * PlaybackService binds it to the silenceTrim preference.
 */
@OptIn(UnstableApi::class)
@Singleton
class SilenceTrim @Inject constructor() {

    private val processor = SilenceSkippingAudioProcessor()

    fun renderersFactory(context: Context): RenderersFactory =
        object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf(processor))
                .build()
        }

    fun setEnabled(enabled: Boolean) = processor.setEnabled(enabled)
}
```

If `buildAudioSink`'s signature differs in Media3 1.5.1 (it has shifted between releases),
check the actual override the IDE/compiler expects with
`./gradlew :core:playback:compileDebugKotlin` and adapt parameter names only — the body stays
identical.

- [x] **Step 2: Write `LoudnessBooster.kt`**

```kotlin
package com.orator.core.playback

import android.media.audiofx.LoudnessEnhancer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Volume boost via the platform LoudnessEnhancer, bound to the player's audio session.
 * Creation and every call can throw on some devices (vendor audiofx bugs) — failures
 * silently disable boost rather than crash playback (spec error-handling decision).
 */
@Singleton
class LoudnessBooster @Inject constructor() {

    private var enhancer: LoudnessEnhancer? = null
    private var gainMb: Int = 0

    fun attach(audioSessionId: Int) {
        release()
        if (audioSessionId == 0) return // AudioManager.ERROR / unset
        enhancer = try {
            LoudnessEnhancer(audioSessionId)
        } catch (_: RuntimeException) {
            null
        }
        applyGain()
    }

    fun setGain(mb: Int) {
        gainMb = mb.coerceIn(0, MAX_GAIN_MB)
        applyGain()
    }

    private fun applyGain() {
        val e = enhancer ?: return
        try {
            e.setTargetGain(gainMb)
            e.enabled = gainMb > 0
        } catch (_: RuntimeException) {
            release()
        }
    }

    fun release() {
        try {
            enhancer?.release()
        } catch (_: RuntimeException) {
            // already dead; nothing to do
        }
        enhancer = null
    }

    companion object {
        /** Clipping safeguard: +15 dB is already a lot; refuse anything higher. */
        const val MAX_GAIN_MB = 1500
    }
}
```

- [x] **Step 3: Compile + commit**

Run: `./gradlew :core:playback:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

```bash
git add core/playback/src
git commit -m "feat: silence-trim sink wrapper and loudness booster"
```

### Task 8: `SmartRewindController` (testable resume logic)

**Files:**
- Create: `core/playback/src/main/java/com/orator/core/playback/SmartRewindController.kt`
- Test: `core/playback/src/test/java/com/orator/core/playback/SmartRewindControllerTest.kt`

- [x] **Step 1: Write the failing test**

```kotlin
package com.orator.core.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartRewindControllerTest {

    private val c = SmartRewindController()

    @Test
    fun `resume after a long pause on the same item rewinds`() {
        c.onPaused("book/1/0", nowMs = 1_000_000)
        val rewind = c.onResumed("book/1/0", nowMs = 1_000_000 + 10 * 60_000, enabled = true)
        assertEquals(15_000L, rewind)
    }

    @Test
    fun `short pause rewinds nothing`() {
        c.onPaused("book/1/0", nowMs = 0)
        assertEquals(0L, c.onResumed("book/1/0", nowMs = 5_000, enabled = true))
    }

    @Test
    fun `different item rewinds nothing`() {
        c.onPaused("book/1/0", nowMs = 0)
        assertEquals(0L, c.onResumed("book/2/0", nowMs = 10 * 60_000, enabled = true))
    }

    @Test
    fun `disabled per type rewinds nothing`() {
        c.onPaused("book/1/0", nowMs = 0)
        assertEquals(0L, c.onResumed("book/1/0", nowMs = 10 * 60_000, enabled = false))
    }

    @Test
    fun `initial play with no prior pause rewinds nothing`() {
        assertEquals(0L, c.onResumed("book/1/0", nowMs = 10 * 60_000, enabled = true))
    }

    @Test
    fun `a consumed pause does not rewind twice`() {
        c.onPaused("book/1/0", nowMs = 0)
        c.onResumed("book/1/0", nowMs = 10 * 60_000, enabled = true)
        assertEquals(0L, c.onResumed("book/1/0", nowMs = 20 * 60_000, enabled = true))
    }

    @Test
    fun `loading a new queue clears the pending pause`() {
        // The cold-resume path (BookDetailViewModel) already subtracts its own rewind and
        // rebuilds the queue with the SAME mediaId; without this reset the warm path would
        // rewind again on top of it. Same for chapter/bookmark taps after a pause.
        c.onPaused("book/1/0", nowMs = 0)
        c.reset()
        assertEquals(0L, c.onResumed("book/1/0", nowMs = 10 * 60_000, enabled = true))
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:playback:testDebugUnitTest --tests "com.orator.core.playback.SmartRewindControllerTest"`
Expected: FAIL — unresolved reference.

- [x] **Step 3: Write the implementation**

```kotlin
package com.orator.core.playback

import javax.inject.Inject

/**
 * Warm-resume smart rewind: remembers when (and on which item) playback paused; on resume
 * returns how far to seek back. Pure bookkeeping — the service passes clocks and does the
 * actual seek. Cold resumes (process death) use SmartRewind directly from lastPlayedAtMs
 * (feature:audiobooks, Chunk 4).
 */
class SmartRewindController @Inject constructor() {

    private var pausedAtMs: Long = 0
    private var pausedMediaId: String? = null

    fun onPaused(mediaId: String?, nowMs: Long) {
        pausedMediaId = mediaId
        pausedAtMs = nowMs
    }

    /** Returns ms to seek back (0 = nothing). Consumes the pending pause either way. */
    fun onResumed(mediaId: String?, nowMs: Long, enabled: Boolean): Long {
        val pending = pausedMediaId
        pausedMediaId = null
        if (!enabled || mediaId == null || mediaId != pending) return 0
        return SmartRewind.rewindMs(nowMs - pausedAtMs)
    }

    /**
     * Call when a NEW queue is loaded (playlist change). A fresh play() chooses its own start
     * position — the cold-resume path already applies SmartRewind there, and chapter/bookmark
     * taps are exact positions — so a pause pending from before the load must not fire on top.
     */
    fun reset() {
        pausedMediaId = null
    }
}
```

- [x] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:playback:testDebugUnitTest --tests "com.orator.core.playback.SmartRewindControllerTest"`
Expected: 7 tests PASS.

- [x] **Step 5: Commit**

```bash
git add core/playback/src
git commit -m "feat: warm-resume smart-rewind controller"
```

### Task 9: Rewire `PlaybackService` (trim, boost, rewind, sleep timer, events)

**Files:**
- Modify: `core/playback/src/main/java/com/orator/core/playback/PlaybackService.kt`

Pure wiring around already-tested policy; verified by compile here and on device in Chunk 6.
Replace the whole file with:

```kotlin
package com.orator.core.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.orator.core.model.MediaType
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Background-capable playback service: MediaSessionService gives lock-screen/notification
 * controls, Bluetooth buttons, and playback that survives the UI being swiped away.
 *
 * Phase 3: also the enforcement point for playback policy that must work no matter which
 * surface issued the command — silence trim, volume boost, smart rewind on resume, the sleep
 * timer, and start/end events for play history.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var positionListeners: Set<@JvmSuppressWildcards PlaybackPositionListener>
    @Inject lateinit var eventListeners: Set<@JvmSuppressWildcards PlaybackEventListener>
    @Inject lateinit var playerPreferences: PlayerPreferences
    @Inject lateinit var silenceTrim: SilenceTrim
    @Inject lateinit var loudnessBooster: LoudnessBooster
    @Inject lateinit var sleepTimer: SleepTimer
    @Inject lateinit var activeQueueInfo: ActiveQueueInfo
    @Inject lateinit var rewindController: SmartRewindController

    private var mediaSession: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var reportJob: Job? = null
    private var sleepJob: Job? = null

    private val latestPrefs = MutableStateFlow(PlayerPrefs())

    /** The item whose start we last reported, so ends pair with starts. */
    private var startedMediaId: String? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this, silenceTrim.renderersFactory(this)).build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    applySmartRewind(player)
                    reportStartIfNew(player)
                    startReporting(player)
                } else {
                    rewindController.onPaused(player.currentMediaItem?.mediaId, System.currentTimeMillis())
                    stopReporting()
                    reportNow(player) // final position on pause/stop
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                    // New queue: any pause pending from before the load must not fire on top
                    // of a cold-resume rewind or an exact chapter/bookmark position.
                    rewindController.reset()
                }
                reportEnd(
                    positionMs = 0, // previous item's final position was already pinged
                    completed = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                )
                if (player.isPlaying) reportStartIfNew(player)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    reportEnd(positionMs = player.currentPosition, completed = true)
                }
            }
        })

        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioSessionIdChanged(
                eventTime: AnalyticsListener.EventTime,
                audioSessionId: Int,
            ) = loudnessBooster.attach(audioSessionId)
        })
        loudnessBooster.attach(player.audioSessionId)

        scope.launch {
            playerPreferences.flow.collect { prefs ->
                latestPrefs.value = prefs
                silenceTrim.setEnabled(prefs.silenceTrim)
                loudnessBooster.setGain(prefs.boostMb)
            }
        }
        scope.launch {
            sleepTimer.state.collect { st -> onSleepTimerState(player, st) }
        }

        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    // --- smart rewind -------------------------------------------------------------------

    private fun applySmartRewind(player: Player) {
        val mediaId = player.currentMediaItem?.mediaId
        val type = player.currentMediaItem?.let { MediaItemFactory.mediaTypeOf(it.mediaMetadata) }
        val enabled = type != null && latestPrefs.value.smartRewind[type] == true
        val rewind = rewindController.onResumed(mediaId, System.currentTimeMillis(), enabled)
        if (rewind > 0) {
            player.seekTo((player.currentPosition - rewind).coerceAtLeast(0))
        }
    }

    // --- sleep timer --------------------------------------------------------------------

    private fun onSleepTimerState(player: Player, state: SleepTimerState) {
        sleepJob?.cancel()
        sleepJob = when (state) {
            SleepTimerState.Off -> null

            is SleepTimerState.Duration -> scope.launch {
                delay((state.endsAtMs - System.currentTimeMillis()).coerceAtLeast(0))
                player.pause()
                sleepTimer.cancel()
            }

            SleepTimerState.EndOfBoundary -> scope.launch {
                val target = SleepTimer.nextBoundary(
                    activeQueueInfo.chapterBoundariesMs.value,
                    player.currentPosition,
                )
                if (target != null) {
                    while (isActive && player.currentPosition < target) delay(500)
                } else {
                    val startItem = player.currentMediaItemIndex
                    while (isActive && player.currentMediaItemIndex == startItem &&
                        player.playbackState != Player.STATE_ENDED
                    ) delay(500)
                }
                if (isActive) {
                    player.pause()
                    sleepTimer.cancel()
                }
            }
        }
    }

    // --- history events -----------------------------------------------------------------

    private fun reportStartIfNew(player: Player) {
        val item = player.currentMediaItem ?: return
        if (item.mediaId == startedMediaId) return
        startedMediaId = item.mediaId
        val title = item.mediaMetadata.title?.toString().orEmpty()
        val type: MediaType? = MediaItemFactory.mediaTypeOf(item.mediaMetadata)
        scope.launch {
            eventListeners.forEach { it.onItemStarted(item.mediaId, title, type) }
        }
    }

    private fun reportEnd(positionMs: Long, completed: Boolean) {
        val mediaId = startedMediaId ?: return
        startedMediaId = null
        scope.launch {
            eventListeners.forEach { it.onItemEnded(mediaId, positionMs, completed) }
        }
    }

    // --- position pings (unchanged from Phase 2) -----------------------------------------

    private fun startReporting(player: Player) {
        reportJob?.cancel()
        reportJob = scope.launch {
            while (isActive) {
                reportNow(player)
                delay(3_000)
            }
        }
    }

    private fun stopReporting() {
        reportJob?.cancel()
        reportJob = null
    }

    private fun reportNow(player: Player) {
        val mediaId = player.currentMediaItem?.mediaId ?: return
        val positionMs = player.currentPosition.coerceAtLeast(0)
        val durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0
        scope.launch {
            positionListeners.forEach { it.onPositionChanged(mediaId, positionMs, durationMs) }
        }
    }

    override fun onDestroy() {
        loudnessBooster.release()
        scope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
```

Behavioral notes (read before assuming these are bugs):

- **Rewind order:** `applySmartRewind` runs when `isPlaying` flips true — the seek happens a
  beat after audio starts. That brief blip is the accepted UX (the reference app behaves the
  same); do not try to pre-empt the play.
- **Rewind clamp is ≥ 0 only** — with clipping, position 0 *is* the clip start (spec decision;
  no double compensation).
- **The PLAYLIST_CHANGED reset is what prevents double rewinds.** Every `play()` from a
  feature rebuilds the queue with the *same deterministic mediaIds*, so without the reset, a
  detail-screen Resume after a >30 s pause would get the warm rewind on top of the
  cold-resume rewind, and a chapter tap after a pause would get rewound off its exact
  position. Do not remove it.
- **A pause consumed by a seek-while-paused (same queue, e.g. notification seek) still
  rewinds.** Accepted for v1; revisit only if it annoys in practice.
- **`reportEnd(positionMs = 0)` on transitions:** the final in-item position was already
  persisted by the 3-second pings; history rows don't need a better number (history is
  "what did I listen to", not a resume mechanism).

- [x] **Step 1: Apply the file replacement above**

- [x] **Step 2: Compile + full core tests**

Run: `./gradlew :core:playback:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all green.

- [x] **Step 3: Commit**

```bash
git add core/playback/src
git commit -m "feat: service enforces trim, boost, smart rewind, sleep timer, emits history events"
```

---

## Chunk 4: Persistence — DB v2, history recorder, override persistence, cold rewind

**One deliberate spec deviation, decided here:** the spec said dangling history rows (app
killed mid-listen) get "closed lazily at next session start using the last position ping
time" — but history rows don't see position pings, so there is no honest timestamp to close
them with. Instead: `endedAtUtc` stays NULL and *means* "interrupted session". No bookkeeping,
no invented numbers; the history UI renders it as such. Record this in the plan's execution
notes when you get here.

### Task 10: Database v2

**Files:**
- Create: `core/database/src/main/java/com/orator/core/database/HistoryEntity.kt`
- Create: `core/database/src/main/java/com/orator/core/database/HistoryDao.kt`
- Modify: `core/database/src/main/java/com/orator/core/database/BookEntity.kt`
- Modify: `core/database/src/main/java/com/orator/core/database/BookDao.kt`
- Modify: `core/database/src/main/java/com/orator/core/database/OratorDatabase.kt`
- Modify: `core/database/src/main/java/com/orator/core/database/DatabaseModule.kt`
- Test: `core/database/src/test/java/com/orator/core/database/HistoryDaoTest.kt`

- [x] **Step 1: Write the failing test**

`runBlocking`, in-memory database, same shape as `OratorDatabaseTest`:

```kotlin
package com.orator.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
class HistoryDaoTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        OratorDatabase::class.java,
    ).allowMainThreadQueries().build()

    private val dao = db.historyDao()

    @After
    fun tearDown() = db.close()

    @Test
    fun `insert and observe newest first`() = runBlocking {
        dao.insert(HistoryEntity(mediaId = "a/1/0", title = "One", mediaType = "AUDIOBOOK", startedAtUtc = 100))
        dao.insert(HistoryEntity(mediaId = "a/2/0", title = "Two", mediaType = "AUDIOBOOK", startedAtUtc = 200))

        val rows = dao.observeRecent(limit = 10).first()
        assertEquals(listOf("Two", "One"), rows.map { it.title })
        assertNull(rows.first().endedAtUtc) // open row = interrupted/ongoing
    }

    @Test
    fun `close marks the open row for a mediaId`() = runBlocking {
        val id = dao.insert(HistoryEntity(mediaId = "a/1/0", title = "One", mediaType = null, startedAtUtc = 100))
        dao.close(mediaId = "a/1/0", endedAtUtc = 500, completed = true)

        val row = dao.observeRecent(limit = 1).first().single()
        assertEquals(id, row.id)
        assertEquals(500L, row.endedAtUtc)
        assertEquals(true, row.completed)
    }

    @Test
    fun `close only touches open rows`() = runBlocking {
        dao.insert(HistoryEntity(mediaId = "a/1/0", title = "Old", mediaType = null, startedAtUtc = 100))
        dao.close(mediaId = "a/1/0", endedAtUtc = 150, completed = false)
        dao.insert(HistoryEntity(mediaId = "a/1/0", title = "New", mediaType = null, startedAtUtc = 200))
        dao.close(mediaId = "a/1/0", endedAtUtc = 900, completed = true)

        val rows = dao.observeRecent(limit = 10).first()
        assertEquals(150L, rows.single { it.title == "Old" }.endedAtUtc)
        assertEquals(900L, rows.single { it.title == "New" }.endedAtUtc)
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:database:testDebugUnitTest --tests "com.orator.core.database.HistoryDaoTest"`
Expected: FAIL — unresolved references.

- [x] **Step 3: Implement**

`HistoryEntity.kt`:

```kotlin
package com.orator.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One listening session of one queue item. [endedAtUtc] == null means the session is either
 * still running or was interrupted (process killed) — the UI treats both as "no end time";
 * we never invent one. [mediaType] is the MediaType enum name, nullable because items not
 * built by MediaItemFactory carry none.
 */
@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaId: String,
    val title: String,
    val mediaType: String?,
    val startedAtUtc: Long,
    val endedAtUtc: Long? = null,
    val completed: Boolean = false,
)
```

`HistoryDao.kt`:

```kotlin
package com.orator.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(row: HistoryEntity): Long

    /** Closes the newest open row for [mediaId]; no-op if none (e.g. service restarted). */
    @Query(
        """UPDATE history SET endedAtUtc = :endedAtUtc, completed = :completed
           WHERE id = (SELECT id FROM history WHERE mediaId = :mediaId AND endedAtUtc IS NULL
                       ORDER BY startedAtUtc DESC LIMIT 1)""",
    )
    suspend fun close(mediaId: String, endedAtUtc: Long, completed: Boolean)

    @Query("SELECT * FROM history ORDER BY startedAtUtc DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<HistoryEntity>>
}
```

`BookEntity.kt` — add two columns at the end of the data class (defaults keep the importer
and every existing constructor call compiling):

```kotlin
    val lastPlayedAtMs: Long = 0,
    val speedOverride: Float? = null,
```

`BookDao.kt` — replace `updatePosition` with, and add the override setter:

```kotlin
    @Query("UPDATE books SET positionMs = :positionMs, lastPlayedAtMs = :lastPlayedAtMs WHERE id = :id")
    suspend fun updateProgress(id: String, positionMs: Long, lastPlayedAtMs: Long)

    @Query("UPDATE books SET speedOverride = :speed WHERE id = :id")
    suspend fun updateSpeedOverride(id: String, speed: Float?)
```

(`AudiobookPositionListener` is the only `updatePosition` caller — it is updated in Task 12;
expect `:feature:audiobooks` to be red between these tasks, which is fine because commits in
this chunk are per-module and the full build runs at the end of Task 12.)

`OratorDatabase.kt` — add the entity, bump the version, add the dao accessor:

```kotlin
@Database(
    entities = [BookEntity::class, ChapterEntity::class, BookmarkEntity::class, HistoryEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class OratorDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun historyDao(): HistoryDao
}
```

`DatabaseModule.kt` — destructive fallback (pre-release decision, spec §Database) + new dao:

```kotlin
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): OratorDatabase =
        Room.databaseBuilder(context, OratorDatabase::class.java, "orator.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideHistoryDao(db: OratorDatabase): HistoryDao = db.historyDao()
```

(Room 2.7.1: the parameterless `fallbackToDestructiveMigration()` is deprecated; the
`dropAllTables = true` overload is the current one. If the compiler disagrees, use whichever
overload exists — the intent is "wipe on schema change until first release".)

- [x] **Step 4: Run the module's tests**

Run: `./gradlew :core:database:testDebugUnitTest`
Expected: `HistoryDaoTest` 3 PASS; `OratorDatabaseTest` still green.

- [x] **Step 5: Commit**

```bash
git add core/database/src
git commit -m "feat: db v2 - history table, lastPlayedAt and speedOverride on books"
```

### Task 11: `HistoryRecorder` in feature:player

**Files:**
- Modify: `feature/player/build.gradle.kts`
- Create: `feature/player/src/main/java/com/orator/feature/player/HistoryRecorder.kt`
- Modify: `feature/player/src/main/java/com/orator/feature/player/PlayerFeatureModule.kt`
- Test: `feature/player/src/test/java/com/orator/feature/player/HistoryRecorderTest.kt`

- [x] **Step 1: Add deps to `feature/player/build.gradle.kts`**

After the existing `implementation(project(":core:playback"))` line:

```kotlin
    implementation(project(":core:database"))
    implementation(project(":core:model"))
```

and test deps + `testOptions { unitTests { isIncludeAndroidResources = true } }` if not present
(copy the block shape from `feature/audiobooks/build.gradle.kts`):

```kotlin
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
```

- [x] **Step 2: Write the failing test**

```kotlin
package com.orator.feature.player

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.orator.core.database.OratorDatabase
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
class HistoryRecorderTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        OratorDatabase::class.java,
    ).allowMainThreadQueries().build()

    private val recorder = HistoryRecorder(db.historyDao())

    @After
    fun tearDown() = db.close()

    @Test
    fun `start then end writes one closed row`() = runBlocking {
        recorder.onItemStarted("audiobook/b/0", "Book", MediaType.AUDIOBOOK)
        recorder.onItemEnded("audiobook/b/0", positionMs = 0, completed = true)

        val row = db.historyDao().observeRecent(10).first().single()
        assertEquals("Book", row.title)
        assertEquals("AUDIOBOOK", row.mediaType)
        assertEquals(true, row.completed)
        assertEquals(true, row.endedAtUtc != null)
    }

    @Test
    fun `interrupted session stays open`() = runBlocking {
        recorder.onItemStarted("audiobook/b/0", "Book", null)
        val row = db.historyDao().observeRecent(10).first().single()
        assertNull(row.endedAtUtc)
        assertNull(row.mediaType)
    }
}
```

- [x] **Step 3: Run test to verify it fails**

Run: `./gradlew :feature:player:testDebugUnitTest --tests "com.orator.feature.player.HistoryRecorderTest"`
Expected: FAIL — unresolved reference `HistoryRecorder`.

- [x] **Step 4: Implement**

`HistoryRecorder.kt`:

```kotlin
package com.orator.feature.player

import com.orator.core.database.HistoryDao
import com.orator.core.database.HistoryEntity
import com.orator.core.model.MediaType
import com.orator.core.playback.PlaybackEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Persists play history from service events (PlaybackEventListener @IntoSet — the same seam
 * positions use, keeping core:playback Room-free). Rows whose session was interrupted keep
 * endedAtUtc == null; we never invent an end time.
 */
class HistoryRecorder @Inject constructor(
    private val historyDao: HistoryDao,
) : PlaybackEventListener {

    override suspend fun onItemStarted(mediaId: String, title: String, mediaType: MediaType?) {
        withContext(Dispatchers.IO) {
            historyDao.insert(
                HistoryEntity(
                    mediaId = mediaId,
                    title = title,
                    mediaType = mediaType?.name,
                    startedAtUtc = System.currentTimeMillis(),
                ),
            )
        }
    }

    override suspend fun onItemEnded(mediaId: String, positionMs: Long, completed: Boolean) {
        withContext(Dispatchers.IO) {
            historyDao.close(mediaId, endedAtUtc = System.currentTimeMillis(), completed = completed)
        }
    }
}
```

In `PlayerFeatureModule.kt` add the binding:

```kotlin
    @Binds
    @IntoSet
    fun bindHistoryRecorder(recorder: HistoryRecorder): PlaybackEventListener
```

(plus imports `com.orator.core.playback.PlaybackEventListener`, `dagger.multibindings.IntoSet`
— `@IntoSet` is already imported there).

- [x] **Step 5: Run test to verify it passes, commit**

Run: `./gradlew :feature:player:testDebugUnitTest`
Expected: PASS.

```bash
git add feature/player
git commit -m "feat: play-history recorder bound to playback events"
```

### Task 12: feature:audiobooks — lastPlayedAt, override persistence, boundaries, cold rewind

**Files:**
- Modify: `feature/audiobooks/src/main/java/com/orator/feature/audiobooks/data/AudiobookPositionListener.kt`
- Create: `feature/audiobooks/src/main/java/com/orator/feature/audiobooks/data/BookSpeedOverrideListener.kt`
- Modify: `feature/audiobooks/src/main/java/com/orator/feature/audiobooks/data/QueueBuilder.kt`
- Modify: `feature/audiobooks/src/main/java/com/orator/feature/audiobooks/AudiobooksFeatureModule.kt`
- Modify: `feature/audiobooks/src/main/java/com/orator/feature/audiobooks/BookDetailViewModel.kt`
- Tests: existing `AudiobookPositionListenerTest.kt`, `QueueBuilderTest.kt`; new `BookSpeedOverrideListenerTest.kt`

- [x] **Step 1: Update the position listener** — in `AudiobookPositionListener.onPositionChanged`,
replace the `bookDao.updatePosition(book.id, global)` line with:

```kotlin
            bookDao.updateProgress(book.id, global, System.currentTimeMillis())
```

Fix `AudiobookPositionListenerTest` to assert `lastPlayedAtMs > 0` after a ping (read the
book back with `bookDao.getById`; the exact assertion shape follows the test's existing reads).

- [x] **Step 2: `BookSpeedOverrideListener` + failing test**

Full test (same Room-in-memory shape as `AudiobookPositionListenerTest`, which deliberately
has no `@Config` line — mirror it):

```kotlin
package com.orator.feature.audiobooks.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.orator.core.database.BookEntity
import com.orator.core.database.OratorDatabase
import com.orator.core.database.SourceKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BookSpeedOverrideListenerTest {

    private lateinit var db: OratorDatabase
    private lateinit var listener: BookSpeedOverrideListener

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            OratorDatabase::class.java,
        ).allowMainThreadQueries().build()
        listener = BookSpeedOverrideListener(db.bookDao())
        runBlocking {
            db.bookDao().upsert(
                listOf(
                    BookEntity(
                        id = "b1", title = "B", author = null, coverPath = null,
                        sourceUri = "uri://b", sourceKind = SourceKind.M4B, durationMs = 60_000,
                        positionMs = 0, addedAtUtc = 0,
                    ),
                ),
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `persists and clears the override for the owning book`() = runBlocking {
        listener.onSpeedOverrideChanged(AudiobookMediaId.encode("b1", 0), 1.4f)
        assertEquals(1.4f, db.bookDao().getById("b1")?.speedOverride)

        listener.onSpeedOverrideChanged(AudiobookMediaId.encode("b1", 0), null)
        assertEquals(null, db.bookDao().getById("b1")?.speedOverride)
    }

    @Test
    fun `ignores non-audiobook mediaIds`() = runBlocking {
        listener.onSpeedOverrideChanged("podcast/x/3", 2.0f) // must not throw
        assertEquals(null, db.bookDao().getById("b1")?.speedOverride)
    }
}
```

Implementation:

```kotlin
package com.orator.feature.audiobooks.data

import com.orator.core.database.BookDao
import com.orator.core.playback.SpeedOverrideListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Persists per-book speed overrides set from the player UI (which doesn't know about books). */
class BookSpeedOverrideListener @Inject constructor(
    private val bookDao: BookDao,
) : SpeedOverrideListener {

    override suspend fun onSpeedOverrideChanged(mediaId: String, speed: Float?) {
        val parsed = AudiobookMediaId.parse(mediaId) ?: return
        withContext(Dispatchers.IO) {
            bookDao.updateSpeedOverride(parsed.bookId, speed)
        }
    }
}
```

Bind in `AudiobooksFeatureModule`:

```kotlin
    @Binds
    @IntoSet
    fun bindBookSpeedOverrideListener(listener: BookSpeedOverrideListener): SpeedOverrideListener
```

- [x] **Step 3: `QueueBuilder` boundaries + override**

In the `M4B` branch of `build()` add to the `PlayRequest`:

```kotlin
                chapterBoundariesMs = chapters.map { it.startMs }.filter { it > 0 },
                speedOverride = book.speedOverride,
```

In the `MP3_DIR` branch add only:

```kotlin
                    speedOverride = book.speedOverride,
```

(multi-file books pause at item transitions; no in-item boundaries). Extend `QueueBuilderTest`:
give its `book()` helper a `speedOverride: Float? = null` parameter passed through to the
`BookEntity`, then add:

```kotlin
    @Test
    fun `m4b carries chapter boundaries (excluding zero) and the speed override`() {
        val chapters = listOf(chapter(0, "uri://book", 0, 30_000), chapter(1, "uri://book", 30_000, 30_000))

        val request = QueueBuilder.build(
            book(SourceKind.M4B, speedOverride = 1.3f), chapters, startAtMs = 0,
        )

        // startMs == 0 must NOT appear: "pause at the next boundary" from position 0
        // would otherwise stop instantly.
        assertEquals(listOf(30_000L), request.chapterBoundariesMs)
        assertEquals(1.3f, request.speedOverride)
    }

    @Test
    fun `mp3 collection has no in-item boundaries but keeps the override`() {
        val chapters = listOf(chapter(0, "uri://f1", 0, 30_000), chapter(1, "uri://f2", 0, 30_000))

        val request = QueueBuilder.build(
            book(SourceKind.MP3_DIR, speedOverride = 0.9f), chapters, startAtMs = 0,
        )

        assertEquals(emptyList<Long>(), request.chapterBoundariesMs)
        assertEquals(0.9f, request.speedOverride)
    }
```

- [x] **Step 4: Cold-start rewind in `BookDetailViewModel`**

Constructor gains `private val playerPreferences: PlayerPreferences` (import
`com.orator.core.playback.PlayerPreferences`, `com.orator.core.playback.SmartRewind`,
`com.orator.core.model.MediaType`, `kotlinx.coroutines.flow.first`). Replace `onPlayResume`:

```kotlin
    fun onPlayResume() {
        viewModelScope.launch {
            val b = repository.observeBook(bookId).first() ?: return@launch
            val prefs = playerPreferences.flow.first()
            val rewind = if (prefs.smartRewind[MediaType.AUDIOBOOK] == true && b.lastPlayedAtMs > 0) {
                SmartRewind.rewindMs(System.currentTimeMillis() - b.lastPlayedAtMs)
            } else {
                0
            }
            playFrom((b.positionMs - rewind).coerceAtLeast(0))
        }
    }
```

Warm-vs-cold note: this play() rebuilds the queue with the *same* mediaIds, which is exactly
why Task 8/9 reset the warm-rewind controller on `MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED`
— without that reset the warm path would rewind again on top of the rewind already subtracted
here (and chapter/bookmark taps after a pause would get rewound off their exact positions).
If rewind behaves strangely on device, check that reset first.

- [x] **Step 5: Full module tests + commit**

Run: `./gradlew :feature:audiobooks:testDebugUnitTest :core:database:testDebugUnitTest`
Expected: all green (including the updated position-listener and queue-builder tests).

```bash
git add feature/audiobooks core/database
git commit -m "feat: cold-start rewind, speed-override persistence, chapter boundaries in queues"
```

---

## Chunk 5: Screens + navigation glue (placeholder styling throughout)

Standing decision: UI stays placeholder-quality until the backend is complete. Plain Material3
components, no theming work, no animation, no art. Resist improving it.

### Task 13: `CommonRoutes` in core:navigation

**Files:**
- Create: `core/navigation/src/main/java/com/orator/core/navigation/CommonRoutes.kt`
- Delete: `feature/player/src/main/java/com/orator/feature/player/PlayerRoute.kt`

- [x] **Step 1: Create `CommonRoutes.kt`** — cross-feature navigation targets live in core so
features can navigate to each other without depending on each other:

```kotlin
package com.orator.core.navigation

/**
 * Routes that other features navigate to. Owning features register the destinations;
 * keeping the strings here avoids feature→feature dependencies.
 */
object CommonRoutes {
    const val Player = "player"
    const val Settings = "settings"
    const val History = "history"
}
```

- [x] **Step 2: Delete `PlayerRoute.kt`**, and in `PlayerFeatureEntry.kt` replace
`override val route: String = PlayerRoute` with
`override val route: String = CommonRoutes.Player` (import `com.orator.core.navigation.CommonRoutes`).

- [x] **Step 3: Compile + commit**

Run: `./gradlew :feature:player:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

```bash
git add core/navigation feature/player
git commit -m "feat: shared cross-feature route constants"
```

### Task 14: Now-Playing rewrite + History screen (feature:player)

**Files:**
- Rewrite: `feature/player/src/main/java/com/orator/feature/player/PlayerViewModel.kt`
- Rewrite: `feature/player/src/main/java/com/orator/feature/player/PlayerScreen.kt`
- Create: `feature/player/src/main/java/com/orator/feature/player/HistoryViewModel.kt`
- Create: `feature/player/src/main/java/com/orator/feature/player/HistoryScreen.kt`
- Modify: `feature/player/src/main/java/com/orator/feature/player/PlayerFeatureEntry.kt`
- Modify: `core/playback/src/main/java/com/orator/core/playback/PlaybackConnection.kt` (delete `playBundledSample`)
- Delete: `core/playback/src/main/res/raw/sample.mp3`

No new unit tests: the ViewModels are thin relays over already-tested singletons (connection,
timer, prefs, dao) and the screens are placeholder Compose; behavior lands in the Chunk 6
device checklist. (Project precedent: Phase 2 screens shipped the same way.)

**Two declared deviations from the spec's Now-Playing description** (record in execution
notes): (1) no chapter *name* line — the header shows the item title only, since mapping a
position back to a chapter name needs feature-side chapter data the player screen deliberately
doesn't have; revisit in the UI phase. (2) The speed stepper uses plain +/− buttons that write
the per-item override directly, not a long-press gesture — long-press is interaction polish,
deferred with the rest of UI work. Trim/boost toggles ARE on the screen, as specced.

- [x] **Step 1: Rewrite `PlayerViewModel.kt`**

```kotlin
package com.orator.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orator.core.playback.PlaybackConnection
import com.orator.core.playback.PlaybackUiState
import com.orator.core.playback.PlayerPreferences
import com.orator.core.playback.PlayerPrefs
import com.orator.core.playback.SleepTimer
import com.orator.core.playback.SleepTimerState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playbackConnection: PlaybackConnection,
    private val sleepTimer: SleepTimer,
    private val playerPreferences: PlayerPreferences,
) : ViewModel() {

    val uiState: StateFlow<PlaybackUiState> = playbackConnection.state

    val sleepState: StateFlow<SleepTimerState> = sleepTimer.state

    val prefs: StateFlow<PlayerPrefs> = playerPreferences.flow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerPrefs())

    fun onTrimToggle(on: Boolean) {
        viewModelScope.launch { playerPreferences.setSilenceTrim(on) }
    }

    fun onBoostStep(deltaMb: Int) {
        viewModelScope.launch {
            playerPreferences.setBoostMb((prefs.value.boostMb + deltaMb).coerceIn(0, 1500))
        }
    }

    fun onPlayPauseClick() = playbackConnection.playPause()

    fun onSeekBy(deltaMs: Long) = playbackConnection.seekBy(deltaMs)

    fun onSeekTo(positionMs: Long) = playbackConnection.seekWithinCurrent(positionMs)

    /** Steps the per-item override relative to the currently effective speed. */
    fun onSpeedStep(delta: Float) {
        val next = (uiState.value.speed + delta).coerceIn(0.5f, 3.0f)
        playbackConnection.setSpeedOverride((next * 100).toInt() / 100f)
    }

    fun onSpeedReset() = playbackConnection.setSpeedOverride(null)

    fun onSleepDuration(minutes: Int) = sleepTimer.armDuration(minutes)

    fun onSleepBoundary() = sleepTimer.armBoundary()

    fun onSleepCancel() = sleepTimer.cancel()

    fun onDefaultSleep() {
        viewModelScope.launch {
            sleepTimer.armDuration(playerPreferences.flow.first().defaultSleepMinutes)
        }
    }
}
```

- [x] **Step 2: Rewrite `PlayerScreen.kt`**

```kotlin
package com.orator.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orator.core.playback.SleepTimerState

@Composable
fun PlayerScreen(viewModel: PlayerViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val sleep by viewModel.sleepState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = state.title.ifEmpty { "Nothing playing" },
            style = MaterialTheme.typography.titleLarge,
        )
        Text("${formatMs(state.positionMs)} / ${formatMs(state.durationMs)}")

        Slider(
            value = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f,
            onValueChange = { f -> viewModel.onSeekTo((f * state.durationMs).toLong()) },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { viewModel.onSeekBy(-10_000) }) { Text("−10s") }
            Button(onClick = viewModel::onPlayPauseClick) {
                Text(if (state.isPlaying) "Pause" else "Play")
            }
            OutlinedButton(onClick = { viewModel.onSeekBy(30_000) }) { Text("+30s") }
        }

        Spacer(Modifier.height(8.dp))
        Text("Speed ${"%.2f".format(state.speed)}×")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { viewModel.onSpeedStep(-0.1f) }) { Text("−") }
            OutlinedButton(onClick = { viewModel.onSpeedStep(+0.1f) }) { Text("+") }
            OutlinedButton(onClick = viewModel::onSpeedReset) { Text("Reset") }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            when (val s = sleep) {
                SleepTimerState.Off -> "Sleep timer off"
                is SleepTimerState.Duration -> "Sleeping at ${formatClock(s.endsAtMs)}"
                SleepTimerState.EndOfBoundary -> "Sleeping at end of chapter"
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.onSleepDuration(15) }) { Text("15m") }
            OutlinedButton(onClick = { viewModel.onSleepDuration(30) }) { Text("30m") }
            OutlinedButton(onClick = viewModel::onSleepBoundary) { Text("Chapter") }
            OutlinedButton(onClick = viewModel::onSleepCancel) { Text("Off") }
        }

        Spacer(Modifier.height(8.dp))
        val prefs by viewModel.prefs.collectAsStateWithLifecycle()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Trim silence")
            Switch(checked = prefs.silenceTrim, onCheckedChange = viewModel::onTrimToggle)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Boost ${prefs.boostMb} mB")
            OutlinedButton(onClick = { viewModel.onBoostStep(-300) }) { Text("−") }
            OutlinedButton(onClick = { viewModel.onBoostStep(+300) }) { Text("+") }
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun formatClock(epochMs: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(epochMs))
```

- [x] **Step 3: History screen**

`HistoryViewModel.kt`:

```kotlin
package com.orator.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orator.core.database.HistoryDao
import com.orator.core.database.HistoryEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(historyDao: HistoryDao) : ViewModel() {

    val rows: StateFlow<List<HistoryEntity>> = historyDao.observeRecent(limit = 100)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
```

`HistoryScreen.kt`:

```kotlin
package com.orator.feature.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(viewModel: HistoryViewModel = hiltViewModel()) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.US)

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        items(rows, key = { it.id }) { row ->
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(row.title, style = MaterialTheme.typography.titleMedium)
                val end = row.endedAtUtc?.let { fmt.format(Date(it)) } ?: "interrupted"
                val mark = if (row.completed) " ✓" else ""
                Text(
                    "${fmt.format(Date(row.startedAtUtc))} → $end$mark",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
```

- [x] **Step 4: Register the history route** — in `PlayerFeatureEntry.register`, after the
existing player composable:

```kotlin
        navGraphBuilder.composable(CommonRoutes.History) {
            HistoryScreen()
        }
```

- [x] **Step 5: Delete the Phase 1 sample machinery** — now nothing references it:
remove `playBundledSample()` and the `RawResourceDataSource`/`R` imports from
`PlaybackConnection.kt`, and delete `core/playback/src/main/res/raw/sample.mp3` (240 KB out
of the APK). `SpeedResolver`/`MediaType` imports stay.

```bash
git rm core/playback/src/main/res/raw/sample.mp3
```

- [x] **Step 6: Build + commit**

Run: `./gradlew :feature:player:testDebugUnitTest :core:playback:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all green.

```bash
git add feature/player core/playback
git commit -m "feat: now-playing controls, history screen; drop phase-1 sample clip"
```

### Task 15: `feature:settings` module

**Files:**
- Create: `feature/settings/build.gradle.kts`, `feature/settings/.gitignore` (single line: `/build`)
- Create: `feature/settings/src/main/java/com/orator/feature/settings/SettingsViewModel.kt`
- Create: `feature/settings/src/main/java/com/orator/feature/settings/SettingsScreen.kt`
- Create: `feature/settings/src/main/java/com/orator/feature/settings/SettingsFeatureEntry.kt`
- Create: `feature/settings/src/main/java/com/orator/feature/settings/SettingsFeatureModule.kt`
- Modify: `settings.gradle.kts` (add `include(":feature:settings")` next to the other features)
- Modify: `app/build.gradle.kts` (add `implementation(project(":feature:settings"))` after `:feature:player`)

- [x] **Step 1: `build.gradle.kts`** — copy `feature/player/build.gradle.kts` wholesale, then:
namespace `com.orator.feature.settings`; dependencies are `core:model`, `core:navigation`,
`core:designsystem`, `core:playback` + the same Compose/Hilt blocks; no database, no test
block (this module has no tests — it's a thin prefs UI).

- [x] **Step 2: `SettingsViewModel.kt`**

```kotlin
package com.orator.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orator.core.model.MediaType
import com.orator.core.playback.PlayerPreferences
import com.orator.core.playback.PlayerPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: PlayerPreferences,
) : ViewModel() {

    val state: StateFlow<PlayerPrefs> = prefs.flow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerPrefs())

    fun setGlobalSpeed(v: Float) = viewModelScope.launch { prefs.setGlobalSpeed(v) }
    fun setTypeSpeed(t: MediaType, v: Float?) = viewModelScope.launch { prefs.setTypeSpeed(t, v) }
    fun setSilenceTrim(on: Boolean) = viewModelScope.launch { prefs.setSilenceTrim(on) }
    fun setBoostMb(mb: Int) = viewModelScope.launch { prefs.setBoostMb(mb) }
    fun setSmartRewind(t: MediaType, on: Boolean) = viewModelScope.launch { prefs.setSmartRewind(t, on) }
    fun setDefaultSleepMinutes(m: Int) = viewModelScope.launch { prefs.setDefaultSleepMinutes(m) }
}
```

- [x] **Step 3: `SettingsScreen.kt`** — a scrolling column of labeled steppers/switches.
Helper composables keep it readable:

```kotlin
package com.orator.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orator.core.model.MediaType
import com.orator.core.playback.SpeedResolver

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val p by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Playback speed", style = MaterialTheme.typography.titleMedium)
        Stepper(
            label = "Global: ${"%.2f".format(p.globalSpeed)}×",
            onMinus = { viewModel.setGlobalSpeed((p.globalSpeed - 0.1f).coerceAtLeast(0.5f)) },
            onPlus = { viewModel.setGlobalSpeed((p.globalSpeed + 0.1f).coerceAtMost(3.0f)) },
        )
        MediaType.entries.forEach { t ->
            val v = p.perTypeSpeed[t]
            Stepper(
                label = "${t.name.lowercase()}: ${v?.let { "%.2f×".format(it) } ?: "global"}",
                onMinus = {
                    viewModel.setTypeSpeed(t, ((v ?: p.globalSpeed) - 0.1f).coerceAtLeast(0.5f))
                },
                onPlus = {
                    viewModel.setTypeSpeed(t, ((v ?: p.globalSpeed) + 0.1f).coerceAtMost(3.0f))
                },
                extra = { OutlinedButton(onClick = { viewModel.setTypeSpeed(t, null) }) { Text("Clear") } },
            )
        }

        Text("Effects", style = MaterialTheme.typography.titleMedium)
        LabeledSwitch("Trim silence", p.silenceTrim, viewModel::setSilenceTrim)
        Stepper(
            label = "Volume boost: ${p.boostMb} mB",
            onMinus = { viewModel.setBoostMb((p.boostMb - 300).coerceAtLeast(0)) },
            onPlus = { viewModel.setBoostMb((p.boostMb + 300).coerceAtMost(1500)) },
        )

        Text("Smart rewind on resume", style = MaterialTheme.typography.titleMedium)
        MediaType.entries.forEach { t ->
            LabeledSwitch(t.name.lowercase(), p.smartRewind[t] ?: true) { on ->
                viewModel.setSmartRewind(t, on)
            }
        }

        Text("Sleep timer", style = MaterialTheme.typography.titleMedium)
        Stepper(
            label = "Default: ${p.defaultSleepMinutes} min",
            onMinus = { viewModel.setDefaultSleepMinutes((p.defaultSleepMinutes - 15).coerceAtLeast(15)) },
            onPlus = { viewModel.setDefaultSleepMinutes((p.defaultSleepMinutes + 15).coerceAtMost(120)) },
        )
    }
}

@Composable
private fun Stepper(
    label: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onMinus) { Text("−") }
        OutlinedButton(onClick = onPlus) { Text("+") }
        extra()
    }
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
```

(`SpeedResolver` import is unused if the coercions stay literal — drop it.)

- [x] **Step 4: Entry + module**

`SettingsFeatureEntry.kt`:

```kotlin
package com.orator.feature.settings

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.orator.core.navigation.CommonRoutes
import com.orator.core.navigation.FeatureEntry
import javax.inject.Inject

class SettingsFeatureEntry @Inject constructor() : FeatureEntry {

    override val route: String = CommonRoutes.Settings

    override fun register(navGraphBuilder: NavGraphBuilder, navController: NavController) {
        navGraphBuilder.composable(route) {
            SettingsScreen()
        }
    }
}
```

`SettingsFeatureModule.kt`: copy `PlayerFeatureModule` shape — `@Binds @IntoSet`
`SettingsFeatureEntry` as `FeatureEntry`.

- [x] **Step 5: Wire into the build** — `settings.gradle.kts` include + `app/build.gradle.kts`
dependency (paths in the Files list above).

- [x] **Step 6: Build + commit**

Run: `./gradlew :feature:settings:compileDebugKotlin :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

```bash
git add feature/settings settings.gradle.kts app/build.gradle.kts
git commit -m "feat: settings feature module over player preferences"
```

### Task 16: Navigation glue on the library screen

**Files:**
- Modify: `feature/audiobooks/src/main/java/com/orator/feature/audiobooks/AudiobookListViewModel.kt`
- Modify: `feature/audiobooks/src/main/java/com/orator/feature/audiobooks/AudiobookListScreen.kt`
- Modify: `feature/audiobooks/src/main/java/com/orator/feature/audiobooks/AudiobooksFeatureEntry.kt`

- [x] **Step 1: Expose playback state to the list** — `AudiobookListViewModel` constructor gains
`playbackConnection: PlaybackConnection` (already a dependency of the module) and exposes:

```kotlin
    val playback: StateFlow<PlaybackUiState> = playbackConnection.state
```

- [x] **Step 2: Screen affordances** — `AudiobookListScreen` gains three callbacks
(`onOpenSettings: () -> Unit`, `onOpenHistory: () -> Unit`, `onOpenPlayer: () -> Unit`).
At the top of the existing Column, the right-aligned History/Settings button row shown in
Step 3. Below the book list (after the LazyColumn, which should get `Modifier.weight(1f)` so
the bar sticks to the bottom), a now-playing bar shown only when something is loaded:

```kotlin
        val playback by viewModel.playback.collectAsStateWithLifecycle()
        if (playback.title.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenPlayer)
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = (if (playback.isPlaying) "▶ " else "⏸ ") + playback.title,
                    maxLines = 1,
                )
            }
        }
```

- [x] **Step 3: Wire navigation** — in `AudiobooksFeatureEntry.register`, the
`AudiobookListScreen(...)` call gains:

```kotlin
                onOpenSettings = { navController.navigate(CommonRoutes.Settings) },
                onOpenHistory = { navController.navigate(CommonRoutes.History) },
                onOpenPlayer = { navController.navigate(CommonRoutes.Player) },
```

(import `com.orator.core.navigation.CommonRoutes`). The screen therefore takes **three**
callbacks; the top row holds both text buttons:

```kotlin
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onOpenHistory) { Text("History") }
            TextButton(onClick = onOpenSettings) { Text("Settings") }
        }
```

New imports for `AudiobookListScreen.kt`: `androidx.compose.material3.TextButton`,
`androidx.compose.foundation.clickable`, `androidx.compose.ui.Alignment`; for
`AudiobookListViewModel.kt`: `com.orator.core.playback.PlaybackConnection`,
`com.orator.core.playback.PlaybackUiState`, `kotlinx.coroutines.flow.StateFlow`.

- [x] **Step 4: Full build + commit**

Run: `./gradlew :feature:audiobooks:testDebugUnitTest :app:assembleDebug`
Expected: tests green, APK builds.

```bash
git add feature/audiobooks
git commit -m "feat: library screen links to settings, history, and now-playing"
```

---

## Chunk 6: Full build + on-device verification

### Task 17: Whole-project verification

- [x] **Step 1: Full test suite**

Run: `./gradlew --console=plain test > /tmp/p3-test.log 2>&1; echo "GRADLE_EXIT=$?"`
Expected: log ends `BUILD SUCCESSFUL`; grep the `TEST-*.xml` results for `failures="0"`.

- [x] **Step 2: Install on the Pixel 7a**

Wireless adb (pair first if the host was forgotten). Then:

Run: `./gradlew --console=plain installDebug`
Expected: `Installed on 1 device.` The DB schema changed (v2) — the destructive fallback wipes
the library on first launch; **re-pick the folder** (test books from Phase 2 are still at
`/sdcard/OratorTest/`).

- [x] **Step 3: Manual verification checklist (user drives, agent waits for report)**

1. Open Orator → pick/confirm the `OratorTest` folder → books appear. Tap the now-playing
   bar's absence sanity check: bar hidden when nothing has played yet.
2. **Speed:** open *Raising Good Humans* → Play. Library → Settings → set global speed 1.5× →
   audio audibly speeds up live. Set audiobook per-type to 1.0× → drops back.
3. **Per-item override:** now-playing bar → Player screen → speed `+` to 1.2× → Settings
   global to 2.0× → playback stays 1.2× (override wins). `Reset` → jumps to 2.0×.
   Kill the app, reopen, resume the book → still the override speed (persisted).
4. **Smart rewind (warm):** pause from the *notification*, wait >30 s, resume from the
   notification → playback audibly steps back ~5 s.
5. **Smart rewind (cold):** note the position, force-stop/swipe away, wait >30 s, reopen →
   Resume → starts ~5 s before where you left off. Toggle audiobook rewind off in Settings →
   repeat → no rewind.
6. **Sleep timer (duration):** Player screen → 15m → text shows the wall-clock stop time →
   cancel works. (Optionally re-arm 15m and actually wait it out later in the evening.)
7. **Sleep timer (boundary):** use *Raising Good Humans* — it is the test book with real
   `chpl` chapter marks (13 chapters); the mp3 book only exercises the item-transition
   fallback. Seek near a chapter end (chapter list on the book screen, then player slider
   close to the next boundary) → arm "Chapter" → playback pauses right at the chapter mark.
8. **Silence trim:** Settings → Trim silence ON during a slow-narration passage → pauses
   audibly tighten. (Subtle; any audible change passes.)
9. **Volume boost:** Settings → boost +600 mB → audibly louder; 0 → back to normal. (If the
   device's audiofx is broken this silently does nothing — that is the designed behavior;
   note it and move on.)
10. **History:** after the above, Library → History → rows for the items played, newest
    first, ✓ on any item that ran to its end, "interrupted" on the force-stop session
    from step 5.
11. **mp3 book regression:** open *The Ballad of Black Tom* → chapter 2 → plays; position
    still tracks across files (Phase 2 behavior intact).

- [x] **Step 4: Fix anything that fails, then finish**

Likely first-run suspects, in order: `buildAudioSink` override signature (Media3 version
drift), smart-rewind firing on queue start (check the `onResumed` consume logic),
`LoudnessEnhancer` device quirks (acceptable: silently disabled), boundary-timer poll
missing a pause because the user seeked while armed (re-arm and retest before calling it
a bug).

- [x] **Step 5: Tick plan checkboxes, record deviations in an "Execution notes" section
(including the history lazy-close deviation noted in Chunk 4), update
`docs/architecture.md` §15 status line (Phase 2 ✅, Phase 3 ✅), commit.**

- [x] **Step 6: Push and hand off**

```bash
git push -u origin phase-3-player-experience
```

then the superpowers:finishing-a-development-branch flow (PR against `main`).
