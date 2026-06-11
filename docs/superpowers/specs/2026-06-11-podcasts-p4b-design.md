# Phase 4b: Podcasts — discovery, transcripts, unsubscribe (design)

**Date:** 2026-06-11
**Status:** Approved by user ("approve")
**Builds on:** merged P4a (`core:network` FeedFetcher/OkHttp; `feature:podcasts` repository,
cache writer, downloader, screens). Approach A: search clients live in `feature/podcasts/data`
(they need `org.json` parsing, which the P4a plan-level decision keeps out of `core:network`);
they inject the existing `OkHttpClient`. **Zero new artifacts** (MockWebServer, already in the
version catalog for `core:network` tests, gains a `testImplementation` line in
`feature:podcasts`).

## Goals

A user can search for podcasts by term (Podcast Index primary, iTunes fallback) and subscribe
from the results; episodes with Podcasting-2.0 transcripts get the transcript file cached and
readable in-app; unsubscribing a show removes its rows and its cache-tree folder (downloads
included) after one confirmation.

Non-goals: trending/category browse, full-catalog features (the Podcast Index ToS forbids
crawling the index via API — our only PI calls are interactive `search/byterm`; refreshes hit
publishers' feeds directly and never touch PI), transcript styling/sync (UI phase), on-device
transcription (premium-phase research).

## Search

| Unit | Responsibility |
|---|---|
| `PodcastSearchResult` | (title, author, feedUrl, artworkUrl) — the only thing discovery yields; subscribing reuses `PodcastRepository.subscribe(feedUrl)` unchanged. |
| `SearchProvider` | `suspend fun search(term: String): Result<List<PodcastSearchResult>>`. |
| `PodcastIndexSearchProvider` | GET `https://api.podcastindex.org/api/1.0/search/byterm?q=…&max=25` with headers `X-Auth-Key`, `X-Auth-Date` (epoch seconds), `Authorization` = SHA-1(key+secret+date) hex, and a `User-Agent` (PI rejects requests without one). Credentials flow `local.properties` → `BuildConfig` fields (gitignored; blank ⇒ provider returns "not configured" failure). **Build plumbing is net-new:** `feature/podcasts/build.gradle.kts` must add `buildFeatures.buildConfig = true` (off by default under AGP 8.7) plus a Properties read of `local.properties` feeding two `buildConfigField`s. **Auth verified live 2026-06-11** (HTTP 200 with the user's key/secret). |
| `ItunesSearchProvider` | Keyless GET `https://itunes.apple.com/search?media=podcast&term=…&limit=25`; maps `feedUrl`/`collectionName`/`artistName`/`artworkUrl600`; rows without a feedUrl are dropped. |
| `CompositeSearchProvider` | PI first; on failure (incl. not-configured) falls through to iTunes. Reports which provider answered — shown as a placeholder diagnostic line. |
| Search screen | Placeholder, centered: text field + Search; result rows (title/author) each with a Subscribe button that flips to "Subscribed". Reached via a Search button on the podcast list screen. Route `podcast-search` (a distinct top segment — `podcasts/search` would sit inside the `podcasts/{podcastId}` pattern's space; exact segments do outscore placeholders in navigation-compose 2.8.5, but the unambiguous route costs nothing). |

## Transcripts

- `RssParser`: item-level `<podcast:transcript url="…" type="…">` (prefix-insensitive like all
  tags). One per episode, preferred by type: `text/vtt` > `application/srt` (and `application/x-subrip`)
  > `text/plain` > `application/json`. Unknown types are kept only if nothing better exists.
- `EpisodeEntity` + `transcriptUrl: String?`, `transcriptType: String?`, `transcriptPath: String?`
  → **DB v4, destructive migration** (pre-release policy; device re-imports OPML once).
  Refresh updates transcriptUrl/type via `updateMetadata` without touching `transcriptPath`.
- `TranscriptFetcher` (feature/podcasts/data): downloads to `transcript.<ext>` in the episode's
  tree dir (ext from type: vtt/srt/txt/json), stores `transcriptPath`. Invoked (a) automatically
  after a successful audio download, (b) on demand from the episode screen.
  **Cache-writer change required:** `EpisodeCacheWriter.writeBytes` is currently private and its
  mime map only knows `.jpg`/`.json`/`.html` — add a public
  `writeEpisodeFile(podcast, episode, name, bytes)` and extend the mime map with
  `.vtt → text/vtt`, `.srt`/`.txt → text/plain` (or rely on the octet-stream never-renamed
  fallback for unknowns).
- **Staleness (accepted):** if a refresh changes `transcriptUrl` after a transcript was fetched,
  `transcriptPath` stays set and the existing file is kept — no re-fetch. Fine pre-release.
- **DAO change required:** `EpisodeDao.updateMetadata` (SQL + signature + the
  `PodcastRepository.upsertEpisodes` call site) gains `transcriptUrl`/`transcriptType` columns;
  it must NOT touch `transcriptPath`.
- `TranscriptText`: pure converter → readable plain text. VTT: drop header/cue timing/settings,
  keep cue text, strip `<v>`/`<c>` style tags. SRT: drop indices + timing lines. JSON
  (Podcasting-2.0 segments): concatenate `segments[].body`. Plain: as-is. Unknown: best-effort
  as plain.
- Episode screen: "Get transcript" button when `transcriptUrl != null && transcriptPath == null`;
  once fetched, the rendered text shows in a scrollable block below show notes. Failures surface
  as "Transcript failed: …" (same pattern as download events).

## Unsubscribe

- `PodcastRepository.unsubscribe(podcastId)`: delete episode rows, delete podcast row, then
  best-effort `EpisodeCacheWriter.deleteShowDir(podcast)` (recursive DocumentFile delete —
  removes downloads too, per user decision). DB deletion succeeds even if the tree delete fails.
- Show detail: Unsubscribe button → inline confirm ("Really unsubscribe? Deletes downloads.")
  → act → navigate back. If the show is playing, playback continues (the queue is self-contained);
  its rows are simply gone afterward.

## Error handling

- Provider failures: silent fall-through to the next provider; only the diagnostic line says
  which one answered. Both failing → "Search failed" + message.
- Transcript fetch: per-episode failure message; never affects the audio download result.
- Unsubscribe: tree-delete failure is swallowed (mirror is best-effort, P4a precedent).

## Testing

- `PodcastIndexSearchProvider`: auth-header construction against a fixed clock (deterministic
  SHA-1), request shape + JSON mapping via MockWebServer; 401 → Failure.
- `ItunesSearchProvider`: JSON mapping fixture; missing-feedUrl rows dropped.
- `CompositeSearchProvider`: PI success short-circuits; PI failure falls through; both fail.
- `RssParser`: transcript tag extraction + type preference + absence.
- `TranscriptText`: VTT/SRT/JSON/plain fixtures.
- Repository: unsubscribe removes podcast + episodes and is idempotent.
- Device checklist: search (PI answering), search with secret temporarily blanked (iTunes
  fallback), subscribe-from-search, transcript via "Get transcript" on a Podcasting-2.0 feed
  (e.g. Podcasting 2.0 / Buzzsprout-hosted shows), transcript auto-fetch with a download,
  transcript file visible in the tree, unsubscribe deletes the show folder.

## Plan-level decisions

- Search clients in `feature:podcasts`, not `core:network` (supersedes the P4a spec's passing
  "will join this module" remark, which conflicted with its own no-parsing rule).
- Credentials via `BuildConfig` fields read from `local.properties` at build time; never
  committed; blank = not-configured, composite falls to iTunes.
- PI ToS: interactive search only; no API crawling; refresh never touches PI.
- One transcript per episode (preference order above) — multi-language transcript selection is
  a UI-phase concern.
- DB v4 destructive (one more device wipe + OPML re-import; acceptable pre-release).
