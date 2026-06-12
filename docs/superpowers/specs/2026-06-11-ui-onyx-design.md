# Onyx UI — design spec (2026-06-11)

Replace the placeholder UI with the approved **Onyx v2** design. Visual reference:
`ui-mockups/candidate-j.html` (approved 2026-06-11, plus final tweaks: docked
edge-to-edge mini player with top progress strip; icon-only mic/book bottom-nav
buttons). Codebase grounding: `2026-06-11-ui-onyx-inventory.md`.

**Primary requirement beyond looks:** the user will iterate on this UI over time —
build for easy modification. All color/spacing decisions live in one token file;
every reusable visual element is its own small composable file; screens are thin
compositions of those pieces.

## Scope decisions (confirmed with user 2026-06-11)

| Mockup element | This phase |
|---|---|
| Queue tab interactions (swipe remove / play-next, enqueue-from-show) | **Read-only queue** (user choice): live playback queue, now-playing + upcoming rows, tap to jump (`seekTo(index, 0)`). Swipes and mixed queues arrive with Phase 5 playlists. |
| ABS browser (`abs` screen, ☁ entries) | Hidden entirely — no backend yet. |
| Series tiles / series screen | Flat book grid — `BookEntity` has no series data. |
| Stats drawer entry | Omitted until the stats page is built (END of UI phase; needs trimmed-ms persistence from Phase 5). |
| Export OPML row | Omitted — no export API exists. Import OPML exists and moves to Settings. |
| Swipe-actions configurability in Settings | Deferred with the enqueue swipe; the one live swipe (episode ← delete download) is fixed-default. |
| Drawer trim-savings line ("9h 41m saved") | Omitted until stats data exists; drawer header shows counts only. |

**New dependency: Coil 3** (`coil-compose` + `coil-network-okhttp`). The app renders
artwork from three source kinds — `file://` cover paths (books), SAF `content://`
URIs, and `https://` show art (podcasts) — and Coil is the lightest single library
that handles all three with disk caching (reusing our existing OkHttp). This is the
justified exception to the minimal-deps rule; no other new dependencies.

**Theme is dark-only** (OLED true black is the design). `OratorTheme` stops
branching on `isSystemInDarkTheme()`.

**Start tab: Podcasts** (mockup order mic · book · queue), replacing the current
`"audiobooks"` start destination.

## Design tokens (core:designsystem)

`theme/OnyxTokens.kt` — single source of truth, plain `object` (easy to tweak):

- Colors: background `#000000`, surface `#15171C`, surfaceBorder `#23262C`,
  navBackground `#0A0B0D`, accent `#2DD4BF`, accentBright `#5EEAD4`,
  onAccent `#04211C`, text `#E6E8EA`, textDim `#8F949C`, textFaint `#6E737C`,
  divider `#121419`, barTrack `#1C1E24`, danger `#B3382C`, enqueue `#0F8A6D`.
- Player backdrop gradients: podcast radial `#103C38→#000`, book `#3A2A18→#000`.
- Dimensions: mini player height, nav height, player cover 300dp, tile corner 0
  (flush), standard paddings.

`theme/OratorTheme.kt` maps tokens onto a Material3 `darkColorScheme`
(background/surface/primary/onPrimary/etc.) so M3 components pick the palette up
automatically; custom components read `OnyxTokens` directly.

## App shell (app module)

New `OratorShell` composable hosting everything below `OratorTheme`:

- `Scaffold`-style layout: content (NavHost) + **MiniPlayer** docked edge-to-edge +
  **bottom nav** (3 items: mic icon-only, book icon-only, Queue icon+label).
- Bottom nav shows only on the three tab routes (`podcasts`, `audiobooks`, `queue`).
  MiniPlayer shows on every route **except** the full player, sitting directly above
  the nav when present, flush to the bottom otherwise (mockup `.mini.nonav`).
  MiniPlayer renders only while a queue is loaded (mediaId != null).
- **Drawer** (`ModalNavigationDrawer`): ORATOR header with library counts,
  "Search Podcast Index…" field-style button → `podcast-search` route, ＋ Add RSS
  feed (dialog with URL field → `subscribe()`), APP section: History, Settings.
- MiniPlayer: 2dp progress strip on top edge, 38dp artwork, title, sub-line
  `−remaining · speed` (+ `· trim` when silence trim is on), play/pause button. Tap → player route. Backed by a small
  `MiniPlayerViewModel` combining `PlaybackConnection.state` with an artwork lookup
  (mediaId → book coverPath or podcast artworkUrl via DAOs).

Navigation keeps the existing `FeatureEntry` registration pattern; `queue` is a new
route registered by feature:player. The existing `BookDetail` and `EpisodeDetail`
routes are **retired**: chapters/bookmarks/notes/transcript all live inside the
player (mockup behavior). Their VM logic is reused where it moves.

## Reusable components (core:designsystem/components, one file each)

- `ArtworkImage` — Coil `AsyncImage` wrapper; fallback = deterministic gradient
  (hash of title → two palette colors) with 1–2 letter initials, exactly like the
  mockup placeholder tiles.
- `CoverTile` — flush square grid tile: artwork, bottom caption scrim (title +
  small sub-line), optional accent count badge (top-right), optional 3dp progress
  strip along the bottom edge.
- (Grids are plain 3-column zero-gap `LazyVerticalGrid`s inlined in the screens, with
  bottom content padding clearing mini player + nav — no wrapper component needed.)
- `MiniPlayer` — dumb composable, state hoisted.
- `EpisodeRow` — date block (day + month) or 44dp artwork variant, title, sub-line,
  optional trailing slot.
- `SwipeActionRow` — wraps a row with horizontal drag-to-reveal action background
  (M3 `SwipeToDismissBox`, reveal-and-snap-back so the row never dismisses), used for
  episode ← delete-download now and built so queue swipes plug in at Phase 5.
- `DualProgressBars` — chapter bar (bright accent, thumb) + whole-item bar (accent,
  chapter tick marks), with the small uppercase label rows; chapter bar hidden when
  the item has no chapters.
- `PagerDots` — 3-dot indicator for the player pager.
- `SectionLabel`, `SettingsRow`, `OnyxSwitch`-style pieces as needed (small).

Components in designsystem are stateless; all data/state lives in feature VMs.

## Screens

**Podcasts tab** (feature:podcasts, rework of PodcastListScreen): top bar
☰ / "Podcasts" / ↻ (refreshAll, existing busy + lastResult feedback as a thin
status line). `TileGrid` of shows; badge = unplayed-episode count if cheaply
derivable from existing episode data, else omitted (decide in plan); caption
sub-line = newest-episode recency. Tile → show screen.

**Show screen** (rework of PodcastDetailScreen): header (86dp art, title, author ·
episode count, `✓ Subscribed` pill → confirm unsubscribe, effects-summary pill →
opens shared effects sheet for this show). Episode list: `EpisodeRow` with date
block, duration, `↓ downloaded` marker in accent, `· transcript` marker when one
is stored, trailing **download button with progress circle** (backlog item: ↓ idle → `CircularProgressIndicator` at
`EpisodeDownloader.progress` → done state). Row tap → play episode (existing
play-from-detail logic) and open player. `SwipeActionRow` ← delete download
(only on downloaded rows).

**Books tab** (rework of AudiobookListScreen): top bar ☰ / "Audiobooks" (no ☁).
Flat `TileGrid`; caption sub-line = time left / "not started"; bottom progress
strip = position/duration. Tile → resume/start playback + open player. Folder
picker stays for the empty state.

**Player** (feature:player, full rewrite of PlayerScreen): one layout for both
media types, backdrop gradient keyed by `mediaType`.
- Top row: ⌄ back, uppercase context line (show name / "TITLE · AUTHOR"), ↥ share
  omitted for now (no share target defined) — keep layout balanced with a spacer.
- 348dp pager (`HorizontalPager`, 3 pages) + `PagerDots`:
  cover (300dp, 20dp radius, shadow) · **notes+transcript** (podcast: show notes
  with tappable `HH:MM:SS` timestamps → `seekWithinCurrent`; transcript below,
  fetch via existing transcript logic) **or bookmarks** (book: list → jump;
  "＋ Add bookmark at <pos>" row; reuses BookDetail bookmark logic) ·
  **chapters** (current highlighted, tap → seek).
- Meta block: title + current-chapter line.
- `DualProgressBars`; episode/whole bar is the seek surface (drag), chapter bar
  seeks within chapter.
- Transport: ↺15 · 68dp accent play/pause · ↻30; book adds ⏮/⏭ chapter skip
  (existing chapter boundaries; podcasts with chapters do NOT get them — approved
  design difference).
- Bottom row: Sleep (sheet: duration presets + end-of-chapter/episode →
  `SleepTimer`), Effects (shared sheet), Queue (→ queue route).

**Effects sheet** (feature:player, `ModalBottomSheet`). Always opens **with a
context** — the playing item (from a player) or a show (from its effects pill);
there is no context-free "global mode" (defaults are edited in Settings, below).
Rows:
- **Speed**: preset chips 1×/1.2×/1.5×/1.7×/2× flanked by −/＋ 0.1-step buttons;
  the matching chip highlights, and a non-preset value (e.g. 1.3× from the old UI)
  shows as a highlighted numeric label between the steppers. With the override
  switch OFF, writes `perTypeSpeed[mediaType]` (per-type speed is a CLAUDE.md
  requirement); with it ON, writes the item's `speedOverride` (exists on both
  show and book).
- **Trim silence** toggle — global pref; always writes `PlayerPrefs`
  (sub-line notes it applies everywhere).
- **Volume boost**: toggle + value. `boostMb` is an Int (0–1500 mB); OFF writes 0,
  ON writes 300 if currently 0; while ON, −/＋ steppers adjust in 300 mB steps
  showing the dB value. Global pref.
- **Skip intro / outro** (visible only for podcast contexts): per-show only —
  no global pref exists. Toggle + two small −15s/＋15s stepper values (intro,
  outro), preserving today's `clipIntroMs/clipOutroMs` editing; toggle OFF zeroes
  both, ON restores 30s/30s if both are zero.
- **Override for this show/book only** switch — controls the speed row's target
  as described above (speed is the only per-item field that exists).

**Queue tab** (feature:player, new): read-only. "Now playing" row + "Next · n"
section. Rows are the loaded queue items; for a single-file book (M4B, one queue
item) the rows are its **chapters** instead (from `ActiveQueueInfo` boundaries) so
the tab isn't empty, matching the mockup's chapter rows. Tap → `seekTo(index, 0)`
(or chapter-start seek). Empty state when nothing is loaded: "Nothing playing".
Hint line explains the full mixed queue ships with playlists.

**History** (restyle): rows with artwork/initials, title, relative time, completed
mark. **Settings** (restyle): Library — Import OPML, Storage folder; Playback —
podcast speed and book speed rows (`perTypeSpeed`, with clear-to-global), global
speed, trim silence, volume boost stepper, smart rewind, default sleep minutes —
all existing prefs, restyled as `SettingsRow`s (no sheet involved). **Search**
(restyle): existing P4b discovery flow in Onyx styling.

## Data / API touches (smallest possible)

- `PlaybackConnection`: expose current queue item titles/indices for the Queue tab
  (read-only snapshot or flow — derive from existing state + ActiveQueueInfo;
  add only what the tab needs).
- `PlaybackUiState` gains `mediaType` (recovered from MediaMetadata via the existing
  `MediaItemFactory.mediaTypeOf` inverse) and `artist` — used by the player backdrop,
  mini player, and queue tab. (Plan deviation from the earlier prefix-parsing idea:
  one source of truth, no string parsing.)
- Podcast grid badge: count query on existing episode table (or omit).
- Everything else binds to existing VM flows; VMs are reorganized when their
  screen is (BookDetail/EpisodeDetail logic folds into player VMs).

## Testing & verification

- Unit tests (Robolectric where Android types are needed): time formatting
  (−remaining, "9h 14m left"), queue-row mapping, mini-player state mapping,
  initials/gradient fallback determinism, chapter-index math for ⏮/⏭.
- Existing VM/repository tests must stay green; `./gradlew test` + `lint` per task.
- Device checklist on Pixel 7a (wireless adb) at the end: every screen, swipe
  delete, download progress circle, sleep/effects sheets, mini player on all
  routes, OLED black rendering, cutout/status-bar safety.

## Build order (plan chunks)

1. Foundation: Coil dep, tokens, theme, ArtworkImage, shell (nav + drawer + mini
   player), route restructure. App navigable end-to-end with old screen bodies.
2. Podcasts: grid, show screen, search restyle, download progress circle, swipe
   delete.
3. Books: grid + empty state.
4. Player: unified layout, pager pages, dual bars, transport, sleep + effects
   sheets.
5. Queue tab, History, Settings restyle; retire dead routes/screens.
6. Device checklist + polish. (Stats page: separate later effort.)
