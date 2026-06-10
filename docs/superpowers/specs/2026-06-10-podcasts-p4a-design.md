# Phase 4a: Podcasts — subscribe, cache, play (design)

**Date:** 2026-06-10
**Status:** Approved by user ("approve, we'll make many more UI changes after the skeleton is built")
**Scope split:** Phase 4 is delivered in two cycles. **P4a (this spec):** subscribe via
pasted feed URL or OPML import, refresh, human-readable cache tree, streaming + explicit
downloads, show notes with tappable timestamps, per-show intro/outro clip settings,
per-show speed override. **P4b (separate spec later):** discovery search (Podcast Index
primary / iTunes fallback) and Podcasting-2.0 transcript downloads. The Podcast Index API
key is already in `local.properties` (`podcastindex.apiKey`; secret pending) — unused
until P4b.

## Goals

A user can import their OPML (or paste a feed URL), see subscribed shows and episodes,
stream or download an episode, resume where they left off, tap a timestamp in show notes
to seek, and set per-show intro/outro skip times that are applied automatically — all with
the existing Phase 3 player behaviors (speed, trim, boost, sleep timer, history, smart
rewind) working unchanged for podcasts.

Non-goals (P4a): discovery search, transcripts, auto-refresh in the background,
auto-download rules, playlists, Podcasting-2.0 chapters, artwork display in UI
(files are cached; rendering is deferred with the rest of the UI).

## Constraints carried forward

- Lightweight: **OkHttp is the only new dependency.** RSS/OPML parsing is hand-rolled on
  `XmlPullParser` (the `chpl` parser precedent), JSON via `org.json`, no Retrofit, no
  WorkManager, no image-loading library.
- Modular: new `feature:podcasts` + new `core:network`; feature depends only on `core:*`,
  never on other features. Everything podcast-specific must be deletable as a unit.
- UI stays placeholder (centered menus) until backend phases are complete.
- Privacy: the user's real OPML at `local/podcasts.opml` contains private auth-token URLs
  — it is gitignored and must never be committed or used as a test fixture. Test fixtures
  are synthetic or sanitized.

## Architecture

### New module: `core:network`

- `OkHttpClient` Hilt singleton (sensible timeouts, redirects on).
- `FeedFetcher`: GET a URL with optional `If-None-Match`/`If-Modified-Since` from stored
  validators; returns `NotModified`, `Success(body, etag, lastModified)`, or
  `Failure(reason)`. No parsing here. P4b search clients will join this module.

### New module: `feature:podcasts`

| Unit | Responsibility |
|---|---|
| `RssParser` | XmlPullParser; tolerant: skips items missing title or enclosure, never aborts a feed for one bad item. Extracts show title/author/description/artwork and per-item guid, title, pubDate, `itunes:duration`, enclosure URL + length, description (show notes HTML). |
| `OpmlParser` | XmlPullParser; yields the list of `xmlUrl` feed URLs (with titles). |
| `PodcastRepository` | subscribe(feedUrl), importOpml(uri), refreshAll(), refresh(podcastId); conditional GETs, upsert-by-GUID (positions/download state survive refresh), per-feed failure isolation (one bad feed bumps an error count, others proceed). |
| `EpisodeCacheWriter` | Writes/updates the human-readable SAF tree (below). DB write succeeds even if tree write fails (tree is a mirror, not the source of truth). |
| `EpisodeDownloader` | Explicit per-episode download, sequential (one at a time); OkHttp stream → `audio.partial` → rename on completion; progress as `StateFlow<Map<episodeId, Float>>`; cancel supported; orphaned partials removed on next refresh; delete-download clears file + `audioPath`. |
| `EpisodeQueueBuilder` | Episode → `PlayRequest` (details under Playback). |
| `PodcastPositionListener` | `PlaybackPositionListener` impl (`@IntoSet`); parses `podcast:` mediaIds; persists clip-relative `positionMs` + `lastPlayedAtMs`. |
| `EpisodeSpeedOverrideListener` | `SpeedOverrideListener` impl; persists override **per show** on `PodcastEntity`. |
| `ShowNotes` | HTML → text via `HtmlCompat`; finds `hh:mm:ss`/`mm:ss` patterns; exposes (text, list of timestamp spans) for tappable rendering. |
| Screens | Show list, show detail, episode detail (placeholder UI, centered menus). |

### Database (Room v3, destructive migration like v2; one-time wipe on devices)

- `PodcastEntity`: id (feed-URL hash), feedUrl, title, author, description, artworkUrl,
  lastRefreshUtc, etag, lastModified, **clipIntroMs = 0, clipOutroMs = 0**,
  speedOverride: Float? = null.
- `EpisodeEntity`: id (RSS GUID; fallback enclosure-URL hash), podcastId, title,
  pubDateUtc, durationMs (0 = unknown), enclosureUrl, showNotesPath (file pointer, not
  the blob), audioPath: String? = null, positionMs = 0, lastPlayedAtMs = 0,
  completed = false.
- Room is the single source of truth for all UI; screens never wait on network or
  filesystem.

### On-disk cache tree (settles the "human-readable storage format" open decision)

User picks a base folder once via SAF (same gesture as audiobooks; persistable grant).

```
<picked folder>/Podcasts/
  <Sanitized Show Title>/
    show.json            ← feed-level metadata, pretty-printed (org.json)
    cover.jpg            ← show artwork
    episodes/
      <YYYY-MM-DD - Sanitized Episode Title>/
        episode.json     ← title, guid, pubDate, duration, enclosure URL
        shownotes.html   ← verbatim from the feed
        cover.jpg        ← only if episode has its own artwork
        audio.<ext>      ← only after explicit download (original extension)
        (transcript.*)   ← reserved for P4b; nothing written in P4a
```

Rationale: pretty-printed JSON is greppable, dependency-free, and round-trips; show notes
stay raw HTML because that is what feeds contain; date-prefixed episode dirs sort
chronologically in any file manager. Artwork is downloaded into the tree (aggressive
caching of everything except audio) but not yet rendered in the placeholder UI.

## Playback integration (reuses Phase 3 wholesale)

- `EpisodeQueueBuilder` produces a single-item `PlayRequest`:
  - mediaId `podcast:<episodeId>`; URI = `audioPath` if downloaded else enclosure URL
    (ExoPlayer streams HTTP natively; **add the `INTERNET` permission**, currently absent).
  - `MediaType.PODCAST`; `clipStartMs = clipIntroMs`;
    `clipEndMs = durationMs − clipOutroMs` **only when durationMs > 0 and clipOutroMs > 0**;
    no outro clip when duration is unknown. `durationMs` is backfilled from the player
    once the episode actually plays.
  - speed = show's speedOverride (resolver falls back to per-type → global as in P3).
- Positions are **clip-relative** (P3 invariant) and stored as-is.
- Smart rewind: warm path automatic (service-side); cold path in the episode ViewModel
  from `lastPlayedAtMs` (same logic as `BookDetailViewModel`, gated on the
  `MediaType.PODCAST` preference).
- History, sleep timer, silence trim, boost: zero changes. No chapter boundaries are
  published, so the sleep timer's "end of boundary" is end of episode.
- Timestamp taps seek to `timestamp − clipIntroMs`, clamped ≥ 0 (show-note times refer to
  the original unclipped timeline); if the tapped episode is not the active media item,
  start it first, then seek.

## Screens (placeholder)

1. **Show list** (from a "Podcasts" button on the start screen): subscribed shows;
   actions: Add feed URL (text dialog), Import OPML (SAF file picker), Refresh all
   (with per-feed failure count on completion).
2. **Show detail**: episodes newest-first; per-show settings: intro/outro clip steppers
   (seconds), speed override stepper + Clear.
3. **Episode detail**: Play/Resume (stream or local — label reflects which),
   Download / Cancel / Delete download with progress, show notes text with tappable
   timestamps.

## Error handling

- Parser: skip malformed items (require title + enclosure), never abort the feed.
- Refresh: per-feed isolation; failures reported as a count, successes proceed.
- Network: transient UI message; never crash; streaming failures surface the player error.
- SAF: revoked grant → prompt re-pick; tree-write failures don't block DB writes.

## Testing

- `RssParser`/`OpmlParser`: fixture files (synthetic or sanitized from real feeds — never
  the user's OPML or auth URLs) covering: itunes tags present/absent, missing guid,
  missing enclosure (skipped), HTML entities, CDATA show notes.
- `PodcastRepository`: fake `FeedFetcher`; subscribe / refresh-with-304 / upsert-keeps-
  position / per-feed failure isolation.
- `EpisodeQueueBuilder`: clip math (no duration → no outro clip; zero clips → null
  ClippingConfiguration path), URI selection (downloaded vs stream).
- Listeners: mirror `AudiobookPositionListenerTest` / `BookSpeedOverrideListenerTest`.
- `ShowNotes`: timestamp pattern extraction (hh:mm:ss, mm:ss, boundaries, no false
  positives on dates like 2026-06-10).
- Device checklist ends with importing the real 43-feed OPML on the Pixel 7a (private,
  local-only) and verifying refresh, stream, download, clips, timestamps, resume.

## Plan-level decisions (carried from review discussions)

- Per-show speed override (not per-episode) — podcast analog of per-book.
- Upsert key is GUID with enclosure-URL-hash fallback; refresh must never reset
  positionMs/audioPath for existing rows.
- Sequential downloads; no WorkManager; manual refresh only (auto-refresh arrives with
  P5 auto-insert rules).
- `core:network` carries no parsing or JSON; parsers live in `feature:podcasts`.
- DB v3 destructive migration accepted (devices re-pick folders / re-import OPML once).
