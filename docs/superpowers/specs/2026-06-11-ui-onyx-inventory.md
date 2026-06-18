# UI build groundwork — codebase inventory (2026-06-11)

Pre-spec survey of the existing surface the Onyx v2 UI will bind to.
Design reference: `ui-mockups/candidate-j.html` (approved) + final tweaks
(docked edge-to-edge mini player w/ top progress strip; bottom-nav icons
mic/book, icon-only, Queue keeps label).

## Shell / navigation
- `app/.../MainActivity.kt` — Material3 `OratorTheme` + `Box(fillMaxSize().safeDrawingPadding())` around `OratorNavHost`.
- `app/.../OratorNavHost.kt` — NavHost; start destination `"audiobooks"`; features register via Hilt `@IntoSet` `FeatureEntry { route; register(navGraphBuilder, navController) }`.
- `core/navigation/CommonRoutes`: `Player="player"`, `Settings="settings"`, `History="history"`, `Podcasts="podcasts"`.
- Podcast routes: `"podcasts"`, `"podcasts/{podcastId}"`, `"podcasts/episode/{episodeId}"`, `"podcast-search"`. Audiobooks: `"audiobooks"`, `"audiobooks/{bookId}"`.

## Playback (core/playback)
- `PlaybackConnection` (singleton): `state: StateFlow<PlaybackUiState>` (isPlaying, title, mediaId, currentIndex, positionMs, durationMs, speed); `playPause()`, `play(PlayRequest)`, `seekTo(index, posMs)`, `seekBy(deltaMs)`, `seekWithinCurrent(posMs)`, `setSpeedOverride(Float?)`.
- `PlayRequest(items: List<PlayableItem>, startIndex, startPositionMs, mediaType, chapterBoundariesMs, speedOverride)`; `PlayableItem(mediaId, uri, title, artist, clipStartMs, clipEndMs)`.
- **Whole-queue replacement only — NO add-to-queue/play-next API.** Queue tab + swipe-enqueue need new service surface (or are deferred to Phase 5 playlists).
- `ActiveQueueInfo` (chapter boundaries of loaded queue), `SleepTimer` (`Off|Duration|EndOfBoundary`), `PlayerPreferences` DataStore → `PlayerPrefs(globalSpeed, perTypeSpeed, silenceTrim, boostMb, smartRewind, defaultSleepMinutes)` + suspend setters.
- `MediaType { AUDIOBOOK, PODCAST }`; mediaIds: `audiobook/<id>`, `podcast/<episodeId>`.

## Screens (all placeholder text/buttons; zero image rendering anywhere)
- audiobooks: `AudiobookListScreen/VM` (books, playback, hasFolder), `BookDetailScreen/VM` (book, chapters, bookmarks, playback).
- podcasts: `PodcastListScreen/VM` (podcasts, hasFolder, busy, lastResult, playback), `PodcastDetailScreen/VM` (podcast, episodes; clip/speed live rebuild), `EpisodeDetailScreen/VM` (episode, notes, playback, downloadProgress, downloadEvent, transcript, transcriptEvent), `SearchScreen/VM` (UiState).
- player: `PlayerScreen/VM` (uiState, sleepState, prefs), `HistoryScreen/VM` (rows).
- settings: `SettingsScreen/VM` (PlayerPrefs).

## Artwork
- Books: `MmrMetadataExtractor` (embeddedPicture) → `CoverStore` saves to `filesDir/covers/<bookId>.jpg`; `BookEntity.coverPath` (absolute path or null).
- Podcasts: `EpisodeCacheWriter` writes `<SAF-root>/Podcasts/<Show>/cover.jpg`; `PodcastEntity.artworkUrl` is the http URL. No image-loading library anywhere (no Coil/Glide) — must add one (Coil 3 recommended; weigh against minimal-deps principle, but rendering SAF/file/http images needs it).

## Data model notes
- `BookEntity(id, title, author, coverPath, sourceUri, sourceKind M4B|MP3_DIR, durationMs, positionMs, addedAtUtc, lastPlayedAtMs, speedOverride)` — **no series field** → series grouping deferred (no data).
- `ChapterEntity(bookId, chapterIndex, title, fileUri, startMs, durationMs)`; `BookmarkEntity(id, bookId, positionMs global, note, createdAtUtc)`.
- Podcast entities as per P4 (clipIntro/OutroMs, speedOverride on show; transcript fields on episode).
- `HistoryEntity(mediaId, title, mediaType, startedAtUtc, endedAtUtc, completed)`.

## Theme
- `core/designsystem/theme/OratorTheme.kt` — Material3 defaults, no custom palette yet. Onyx palette: true black `#000`, surface `#15171c`, accent teal `#2DD4BF` (on-accent `#04211C`), text `#E6E8EA`, dim `#8F949C`.

## Scope cuts agreed by reality (to confirm in spec)
- ABS browser: no backend → hide drawer entry until ABS phase.
- Series tiles: no series data → flat book grid for now.
- Queue tab / swipe-enqueue: needs new playback API or Phase 5; decide in spec (option: queue tab shows live Media3 queue read-only + swipe-remove).
- Stats page: end of UI phase (memory has persistence prerequisite note).
