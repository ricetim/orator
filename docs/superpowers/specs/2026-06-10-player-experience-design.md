# Phase 3: Player Experience — Design

**Date:** 2026-06-10
**Status:** Approved (user, 2026-06-10)
**Roadmap row:** Phase 3 — "Now-Playing screen; speed (global/type/item), silence-trim, volume
boost, sleep timer, play history all working" (`docs/architecture.md` §15), plus two new
user-requested features: **smart rewind on resume** and the **per-podcast intro/outro
auto-clip mechanism**.

## Goals and scope

Everything in the roadmap row, plus:

- **Smart rewind on resume** (Smart AudioBook Player is the reference): when playback resumes
  after a pause, seek back a number of seconds that grows with how long the pause lasted.
  Stepped tiers (user-confirmed): pause < 30 s → no rewind; < 5 min → 5 s; < 1 hr → 15 s;
  longer → 30 s. Applies to **both media types, configurable per type** (on by default).
- **Clip windows (intro/outro skip) — mechanism only.** Per-podcast "skip the first N and
  last M seconds of every episode" (user-confirmed semantics). Phase 3 ships the playback
  mechanism end to end; the per-show N/M settings UI and storage arrive in Phase 4 when
  podcast entities exist.
- **A minimal settings screen** (new `feature:settings` module) so the new preferences are
  usable at all. Placeholder styling, per the standing decision to defer UI/design iteration
  until backend functionality is complete.

Out of scope: sleep-timer fade-out (optional in the architecture doc; YAGNI for now),
per-show clip storage (Phase 4), any visual design investment.

## Architecture: core policy layer (approach A)

All playback behaviors live service-side in `core:playback` as small, independent,
individually deletable classes, configured via DataStore preferences. Rationale
(user-confirmed against alternatives):

- **Lightweight:** zero new dependencies. Silence trim is Media3's
  `SilenceSkippingAudioProcessor`, clipping is Media3's `MediaItem.ClippingConfiguration`,
  volume boost is the platform `LoudnessEnhancer`. Everything else is plain Kotlin.
- **Correct:** resume/pause/seek arrive from surfaces feature modules don't control
  (notification, Bluetooth, Android Auto). Policy must sit where those commands land — the
  service. Feature-side implementations would break rewind-on-resume from the notification
  and duplicate logic across features.
- **Modular:** each behavior is one class plus a Hilt binding; removing one (e.g. for a
  free/paid split) touches nothing else.

## Components (`core:playback`)

| Piece | Responsibility | Mechanism |
|---|---|---|
| `PlayerPreferences` | Typed DataStore prefs: speed (global + per-type), silence-trim toggle, boost level (millibels), smart-rewind enable per type, default sleep-timer duration | DataStore Preferences (pattern: `AudiobooksPrefs`) |
| `SmartRewind` | Pure function `(pauseDurationMs) -> rewindMs` implementing the tiers | Pure Kotlin, JVM-tested |
| `SleepTimer` | Duration mode: coroutine, pause on expiry. Boundary mode: pause at the next chapter boundary or item transition | Service-side controller |
| Silence trim | `SilenceSkippingAudioProcessor` installed via a custom `RenderersFactory`; `setEnabled` toggled at runtime from prefs | Media3 built-in |
| Volume boost | `LoudnessEnhancer` bound to the player's audio-session id; gain from prefs | Platform `audiofx` |
| Clip windows | `PlayRequest` items carry optional `clipStartMs`/`clipEndMs`, mapped to `ClippingConfiguration` when building `MediaItem`s | Media3 built-in |

### `PlayRequest` additions

- `clipStartMs` / `clipEndMs` per item (intro/outro skip; any feature may set them).
- `chapterBoundariesMs: List<Long>` per queue — for single-file m4bs, chapters are positions
  inside one item, so the boundary sleep timer needs them; multi-item queues fall back to
  item-transition boundaries.
- Per-item speed override slot, resolved through the existing `SpeedResolver`
  (item ▸ type ▸ global).

### Smart rewind: two call sites, one brain

- **Warm resume** (same service process): service records `pausedAt` on pause; on play,
  seeks back `SmartRewind.calculate(now - pausedAt)`, clamped to ≥ the item/clip start.
- **Cold resume** (app killed): the audiobooks feature computes the same function from the
  new `lastPlayedAtMs` column when it builds the resume position for `QueueBuilder`.

Same pure function in both places; no duplicated policy.

### Play history

Follows the Phase 2 listener pattern so `core:playback` never learns about Room:
`core:database` gains `HistoryEntity`/`HistoryDao` (mediaId, title, mediaType, startedAt,
endedAt, completed); `feature:player` binds a session-event listener
(`@IntoSet`, like `PlaybackPositionListener`) that writes rows on start/complete.

## Screens (placeholder styling)

- **Now-Playing** (extends `feature:player`): text header (title / chapter), seek slider,
  play/pause, skip ±, speed stepper (long-press → per-item override), sleep-timer button
  (duration or end-of-chapter), trim/boost toggles. Binds to existing `PlaybackConnection`
  flows.
- **Settings** (new `feature:settings` module): plain list over the typed prefs. Registered
  via `FeatureEntry`; deletable like any feature.
- **History**: simple list screen inside `feature:player` reading `HistoryDao`.
- **Navigation glue**: library screen gains a settings button and a thin now-playing bar that
  opens the player screen. Nothing more.

## Database changes

Pre-release, destructive migration acceptable:

- New `history` table.
- `books` gains `lastPlayedAtMs` (cold-start rewind; updated by the existing position
  listener, which already writes `books` on every ping) and nullable `speedOverride`.

## Error handling

- `LoudnessEnhancer` creation can throw on some devices → catch, disable boost silently.
- Clip windows wider than the file → ignore the clip.
- Boundary list unsorted or empty → sort; fall back to item transitions.

## Plan-level decisions (from spec review)

- **Per-item speed override write path:** `feature:player` must not write `books` directly
  (it doesn't own audiobook identity). Route the override through a `PlaybackConnection`
  command plus an `@IntoSet` persistence listener, mirroring how positions flow today.
- **Clip-relative positions:** Media3's `ClippingConfiguration` makes `currentPosition`
  relative to the clip start, so positions reaching `PlaybackPositionListener` are
  clip-relative for clipped items. Decide and document (before Phase 4 builds on clips)
  that stored positions are **clip-relative** — symmetric on save and restore, so resume
  stays correct without conversion.
- **History on abnormal termination:** a row opens on start; on app kill the row is closed
  lazily — the next session start closes any dangling open row using the last persisted
  position ping time. No background bookkeeping.
- **Rewind clamp:** with clipping, position 0 already *is* the clip start, so the warm-resume
  clamp is simply ≥ 0 — do not double-compensate for clip offsets.

## Testing

- Pure JVM: `SmartRewind` tiers, boundary-timer math, clip-window → `ClippingConfiguration`
  mapping, extended `SpeedResolver` cases.
- Robolectric: `PlayerPreferences` round-trip, history recorder.
- On-device checklist (end of phase): rewind-from-notification after a timed pause, sleep
  timer at a real m4b chapter boundary, audible trim/boost, speed persistence across restart,
  history rows after a listening session.
