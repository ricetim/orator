# Phase 6a — audiobookshelf: connect, browse, play (+ offline download)

**Status:** Approved design, ready for planning
**Date:** 2026-06-22
**Builds on:** Phase 4b (audiobooks: `BookEntity`/`ChapterEntity`, `QueueBuilder`, SAF import),
Phase 5b (WorkManager + Hilt-Worker infra), the `@IntoSet` core-seam pattern used throughout.

## 1. Goal

Connect Orator to a single [audiobookshelf](https://www.audiobookshelf.org/) (ABS) server, mirror
its catalog into the local library, **stream** books with authentication, and optionally
**download** them for offline playback. ABS books appear in the existing Audiobooks tab alongside
local books and reuse the entire audiobook playback pipeline. Local resume works via the existing
position tracking.

**Two-way progress sync is explicitly out of scope** — it is Phase 6b (`AbsSyncWorker` + conflict
resolution + ABS play sessions). 6a writes progress only to the local DB, exactly as local books do.

## 2. Scope decisions (confirmed)

| Decision | Choice |
|---|---|
| Phase split | 6a = connect + browse + play (+ download); 6b = two-way sync |
| Auth | Username/password → bearer token; **password never stored** |
| Token storage | `EncryptedSharedPreferences` (`androidx.security:security-crypto`) |
| Playback | **Both** — stream by default, optional offline download |
| Catalog | **Mirror full catalog metadata** to the local `books` table (browsable offline) |
| Servers | **One** server in 6a; `serverId` column kept now so multi-server is a non-destructive add |
| Module structure | New `feature:audiobookshelf` + shared core seams (Approach A) |
| Download storage | **User-picked SAF folder** (own tree, mirrors `AudiobooksPrefs`) |

## 3. Architecture overview

ABS is a discrete, independently removable capability, so it lives in a **new
`feature:audiobookshelf` module**. It never imports another feature; it meets shared code at
`core` seams. An ABS book is just a `BookEntity` whose `sourceUri` is an `http(s)` stream URL (or a
`content://` local path once downloaded), so it flows through the **unchanged** audiobook playback
pipeline (`AudiobookPlayRequestFactory` → `QueueBuilder` → `PlayRequest` → `PlaybackService`).

**Unified auth via one OkHttp interceptor.** `core:network` exposes a single shared `OkHttpClient`.
Coil already streams covers through it; in 6a Media3 also streams audio through it (via
`media3-datasource-okhttp`). A single host-scoped interceptor that adds `Authorization: Bearer
<token>` therefore authenticates **all three** traffic types — API calls, cover images, and audio
streaming — in one place. The interceptor is contributed by `feature:audiobookshelf` via the same
`@IntoSet` multibinding pattern already used for `PlaybackEventListener`/`NewEpisodeListener`, so
`core:network` stays ignorant of ABS.

### New / changed modules

- **`feature:audiobookshelf`** (new): login, `AbsApi`, credential store, catalog mirror, auth
  interceptor, lazy-expand `BookDetailResolver`, SAF download, settings/connect UI.
- **`core:network`**: add a `@Multibinds Set<Interceptor>` seam folded into the shared client.
- **`core:playback`**: build the player's media source from an OkHttp-backed data source
  (`core:playback → core:network` dependency added; no cycle).
- **`core:model`**: `BookOrigin`, `DownloadState`, and the `BookDetailResolver` seam interface.
- **`core:database`**: `BookEntity` v8 columns + two `BookDao` reconciliation queries.

### New dependencies

- `org.jetbrains.kotlinx:kotlinx-serialization-json` + the `org.jetbrains.kotlin.plugin.serialization` plugin
- `androidx.media3:media3-datasource-okhttp` (aligned to media3 `1.5.1`)
- `androidx.security:security-crypto` (EncryptedSharedPreferences)

(WorkManager, Hilt-Worker, `documentfile`, OkHttp, Coil are already present.)

## 4. Data model

DB bumps **v7 → v8** (destructive, per existing `fallbackToDestructiveMigration(dropAllTables=true)`
convention — no hand-written migration).

**`core:model` enums** (persisted natively, no TypeConverter — matches `SourceKind`/`AutoInsertRule`):

```kotlin
enum class BookOrigin { LOCAL, ABS }
enum class DownloadState { NONE, DOWNLOADING, DOWNLOADED }
```

**`BookEntity` gains four defaulted columns** (local-book construction unchanged):

```kotlin
val origin: BookOrigin = BookOrigin.LOCAL,
val serverId: String? = null,      // ABS server; null for LOCAL. One server in 6a.
val absItemId: String? = null,     // ABS libraryItem id; null for LOCAL. For 6b sync + re-streaming.
val downloadState: DownloadState = DownloadState.NONE,
```

**Conventions:**
- ABS book **PK** = `"abs:$absItemId"` (globally unique, self-describing). `absItemId` is *also* its
  own column because 6b sync queries by it.
- `sourceUri` remains the single "where the bytes are" field: a stream-only ABS book's `sourceUri`
  is its computed `http(s)` stream URL; once downloaded it is rewritten to the `content://` SAF path
  and `downloadState → DOWNLOADED`. No separate `remoteUri` column — the stream URL is deterministic
  from `serverId + absItemId + base URL` and can be recomputed.
- **A blank `sourceUri` means "details not fetched yet."** The catalog mirror stores metadata only;
  `sourceUri` is `""` and there are no `ChapterEntity` rows until the book is first opened
  (lazy-expand, §8). This one convention powers mirror, stream, and un-download.
- `coverPath` holds the ABS cover **URL** for ABS books (lazy authed Coil fetch, not prefetched);
  local books keep using a file path. `ArtworkImage`/Coil handle both transparently.
- **Chapters** reuse `ChapterEntity` unchanged: an ABS item with **1 audio track → `SINGLE_FILE`**
  (ABS chapter offsets become internal chapters, like a local `.m4b`); **N audio tracks →
  `MULTI_FILE`** (one `ChapterEntity` per track, `fileUri` = that track's stream/local URI). Finer
  chapters *within* a multi-track book are deferred (documented limitation) — this maps cleanly onto
  `QueueBuilder`/`ChapterTimeline` with zero changes.

**`BookDao` additions** (for reconciliation):

```kotlin
@Query("SELECT * FROM books WHERE origin = :origin")
suspend fun getByOrigin(origin: BookOrigin): List<BookEntity>

@Query("SELECT id FROM books WHERE origin = :origin")
suspend fun getIdsByOrigin(origin: BookOrigin): List<String>
```

Existing `upsert`, `deleteByIds`, `updateProgress`, `updateSpeedOverride` cover the rest.

## 5. Login + secure storage

```kotlin
data class AbsServerConfig(
    val serverId: String,   // normalized base URL (scheme+host+port) — stable id / future multi-server key
    val baseUrl: String,    // e.g. https://abs.example.com
    val username: String,
    val token: String,      // bearer; NEVER the password
)
```

**`AbsAuthRepository.login(baseUrl, username, password)`:**
1. `POST {baseUrl}/login` with `{"username":…,"password":…}` → response carries `user.token`.
2. Persist `AbsServerConfig` (token, not password); derive `serverId` from the normalized base URL.
3. Password held only for the call's duration; discarded.

**`AbsCredentialStore`:** the token is stored in `EncryptedSharedPreferences`
(AndroidKeyStore-backed `MasterKey`). Because the auth interceptor runs on OkHttp I/O threads and
must read the token **synchronously per request**, the store loads the config once into an in-memory
`AtomicReference<AbsServerConfig?>` at startup; login/logout update both the encrypted store and the
reference. The interceptor reads the `AtomicReference` (lock-free); the UI observes a
`StateFlow<AbsConnectionState>` derived from it.

```kotlin
sealed interface AbsConnectionState {
    data object Disconnected : AbsConnectionState
    data object Connecting : AbsConnectionState
    data class Connected(val config: AbsServerConfig) : AbsConnectionState
    data class Error(val message: String) : AbsConnectionState
}
```

**Logout** clears the encrypted store + `AtomicReference`, and (via §7) deletes all `origin = ABS`
books and their downloaded files so a disconnect leaves no orphans.

## 6. API client + auth-interceptor seam

**Seam in `core:network`** (so the set exists with zero contributors → existing podcast/cover
traffic is byte-for-byte unchanged):

```kotlin
@Module @InstallIn(SingletonComponent::class)
abstract class InterceptorModule {
    @Multibinds abstract fun interceptors(): Set<Interceptor>
}
```

`NetworkModule.provideOkHttpClient` injects `Set<@JvmSuppressWildcards Interceptor>` and folds each
in via `.addInterceptor(it)`.

**`feature:audiobookshelf` contributes one interceptor** (bound `@Binds @IntoSet`):

```kotlin
class AbsAuthInterceptor @Inject constructor(
    private val store: AbsCredentialStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val cfg = store.current()                 // AtomicReference — no suspend, no blocking
        val req = chain.request()
        return if (cfg != null && req.url.host == cfg.baseUrl.toHttpUrl().host) {
            chain.proceed(req.newBuilder().header("Authorization", "Bearer ${cfg.token}").build())
        } else chain.proceed(req)                  // never decorates non-ABS hosts
    }
}
```

This single class authenticates API + covers (Coil) + streaming (§8), all scoped to the ABS host.

**`AbsApi`** — suspend functions over the shared `OkHttpClient` + `Json { ignoreUnknownKeys = true }`
(so ABS version drift never breaks parsing). No Retrofit/Moshi.

| Method | Endpoint | Used by |
|---|---|---|
| `login(base, user, pass)` | `POST /login` → `user.token` | §5 |
| `getLibraries()` | `GET /api/libraries` | mirror |
| `getLibraryItems(libId)` | `GET /api/libraries/:id/items` (paginated, minified) | mirror — cheap metadata |
| `getItemExpanded(itemId)` | `GET /api/items/:id?expanded=1` → `audioFiles[].ino`, `chapters[]` | play/download |
| `coverUrl(itemId)` | `GET /api/items/:id/cover` (helper) | Coil |
| `fileStreamUrl(itemId, ino)` | `GET /api/items/:id/file/:ino` (helper) | streaming |

All response types are `@Serializable` with `@SerialName`. **6a is deliberately session-free**
(direct static-file streaming via `/file/:ino`); ABS *play sessions* (server-side "now playing") are
deferred to 6b where they tie into progress sync.

**Minified-list vs expanded-item split:** the full-catalog mirror uses only the cheap paginated
list (metadata for the whole library). The heavier per-item call (audio-file inodes + chapter
offsets) is fetched **lazily on first play/download** (§8) and persisted — this is how a "mirror the
whole catalog" choice stays lightweight.

## 7. Catalog-mirror sync

**`AbsCatalogSync`** reconciles the server's libraries into the `books` table. One pass:

1. `getLibraries()` → keep book/audiobook-media libraries.
2. For each, page through `getLibraryItems(libId)` (minified) → incoming
   `(absItemId, title, author, coverUrl, durationMs, numAudioFiles)`.
3. Reconcile against `bookDao.getByOrigin(ABS)`:

```kotlin
existing = getByOrigin(ABS).associateBy { it.id }   // "abs:$itemId" → BookEntity
incoming = serverItems.map { it.toBookEntity() }     // origin=ABS, id="abs:$itemId", downloadState=NONE
merged = incoming.map { fresh ->
    val old = existing[fresh.id]
    if (old == null) fresh
    else fresh.copy(                                  // refresh metadata, PRESERVE user/runtime state
        positionMs     = old.positionMs,
        lastPlayedAtMs = old.lastPlayedAtMs,
        speedOverride  = old.speedOverride,
        downloadState  = old.downloadState,
        sourceUri      = if (old.downloadState == DownloadState.DOWNLOADED) old.sourceUri else fresh.sourceUri,
    )
}
bookDao.upsert(merged)
val stale = existing.keys - incoming.map { it.id }.toSet()
deleteAbsBooks(stale)                                 // rows + downloaded SAF files (+ chapters via CASCADE)
```

The **preserve-on-merge** discipline is essential: a plain `upsert(incoming)` would reset
`positionMs` to 0 and wipe a downloaded book back to stream-only on every refresh. Server-owned
metadata (title/author/cover/duration) is refreshed; device-owned state (resume position, speed,
download state + local path) is preserved. Extract the merge as a **pure**
`AbsCatalogReconciler.reconcile(existing, incoming): (upserts, deletes)` for unit testing.

**Triggers (6a):**
- **On connect** — initial mirror after a successful login.
- **Manual "Refresh library"** — a user action in the ABS screen.
- **Periodic background sync is deferred to 6b** (shares the WorkManager seam with the progress-sync
  worker). 6a's sync is a plain suspend function on `AbsRepository`.

**Failure handling:** network/parse errors abort the pass and surface
`AbsConnectionState.Error`, leaving the previously-mirrored catalog intact (browsable offline).
Partial pagination failure = abort, keep prior data (no half-wiped catalog).

## 8. Streaming + lazy expand on play

**Player wiring (`core:playback`, `PlaybackService.onCreate`):**

```kotlin
val httpFactory = OkHttpDataSource.Factory { okHttpClient }            // injected from core:network
val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)   // http(s)→OkHttp; file/content→built-in
val player = ExoPlayer.Builder(this, silenceTrim.renderersFactory(this))
    .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
    .build()
```

`DefaultDataSource.Factory` routes only `http(s)` URIs through OkHttp (where `AbsAuthInterceptor`
adds the bearer); `file://`/`content://` local playback uses the built-in sources exactly as today.
`MediaItemFactory`, `QueueBuilder`, smart-rewind, sleep timer, and position pings are untouched and
origin-blind. Adds `core:playback → core:network` (no cycle — `core:network` depends on neither).

**Lazy-expand seam (`core:model`)** so `feature:audiobooks` never imports `feature:audiobookshelf`:

```kotlin
interface BookDetailResolver {
    fun handles(origin: BookOrigin): Boolean
    /** Idempotent: fetch + persist sourceUri + chapters if not already present. */
    suspend fun ensureDetails(bookId: String)
}
```

`AudiobookPlayRequestFactory` injects `Set<@JvmSuppressWildcards BookDetailResolver>` and, before
building the queue:

```kotlin
val book = bookDao.getById(ref.id) ?: return null
resolvers.firstOrNull { it.handles(book.origin) }?.ensureDetails(book.id)
val fresh = bookDao.getById(ref.id)!!                  // re-read: sourceUri + chapters now populated
val chapters = chapterDao.getForBook(ref.id)
return QueueBuilder.build(fresh, chapters, startAtMs = fresh.positionMs)
```

`feature:audiobookshelf` binds the ABS resolver `@IntoSet`. Remove the ABS module → empty set →
local books (which already have `sourceUri`/chapters) play unchanged.

**ABS `ensureDetails` impl:** if `sourceUri` blank / no chapters → `getItemExpanded(absItemId)` →
derive `SINGLE_FILE` (1 track: chapter offsets internal) or `MULTI_FILE` (N tracks: one
`ChapterEntity` per track, `fileUri = /api/items/:id/file/:ino`) → write `sourceUri` + chapter rows
→ `upsert`. Already-downloaded or already-expanded books → **no-op** (so we don't re-hit the server
every play).

**Resume/position:** unchanged — `AudiobookPositionListener` already writes `positionMs` to
`BookDao` for any `BookEntity`, so an ABS book resumes locally. Streaming with no connectivity
surfaces a normal Media3 player error; offline playback is the download story (§9).

## 9. Offline download (user-picked SAF folder)

**Folder grant:** `AbsPrefs { downloadTreeUri }` (DataStore mirroring `AudiobooksPrefs`). The first
download with no granted folder prompts an `OpenDocumentTree` pick + `takePersistableUriPermission`
— the same flow podcasts/audiobooks use. The user picks the folder; the app does not drive
DocumentsUI programmatically.

**`AbsDownloadWorker` (`@HiltWorker` foreground `CoroutineWorker`)** — reuses the WorkManager +
Hilt-Worker infra and the `feature:podcasts` `EpisodeDownloader` `.partial→rename` idiom; no new
dependency:

```
1. downloadState NONE → DOWNLOADING
2. ensureDetails(bookId)                              // §8 — tracks + chapters known
3. tree = DocumentFile.fromTreeUri(downloadTreeUri); bookDir = tree.createDirectory("abs-<bookId>")
4. per track: OkHttp GET (authed) → bookDir.createFile(...) ".partial" → stream bytes → renameTo
5. rewrite book.sourceUri + each ChapterEntity.fileUri → the new content:// URIs; downloadState → DOWNLOADED
6. failure: delete partials, revert downloadState → NONE
```

`content://` URIs play through Media3's built-in `DefaultDataSource` — **no auth needed offline**,
and the ABS interceptor never matches their host.

**`AbsDownloadManager`:** `enqueue(bookId)` (unique work per book → re-taps don't double-download),
`cancel(bookId)`, `remove(bookId)`. **Remove** = delete the SAF files
(`DocumentFile.fromSingleUri(...).delete()`) + the book's `ChapterEntity` rows, set `sourceUri=""`,
`downloadState=NONE` → next play re-streams via lazy-expand. The `deleteAbsBooks` path (stale-on-server
+ logout) also deletes downloaded SAF files. The Audiobooks/ABS UI observes `downloadState` straight
off `BookDao`.

## 10. UI surface (modular, deferred-styling)

Minimal, consistent with the deferred/modular UI principle. A `feature:audiobookshelf`
settings/connect section:
- Connect form (URL, username, password) → shows `AbsConnectionState`.
- "Refresh library" action; "Log out" action.
- Mirrored ABS books appear in the **existing Audiobooks tab** (the list already observes `BookDao`),
  with a per-book download / remove-download affordance driven by `downloadState`.

No new browse screen is required for 6a — mirroring into `books` means the existing Audiobooks list
is the browser. (A dedicated server-browse screen is a possible later refinement, not 6a scope.)

## 11. Testing strategy

Pure logic is unit-tested; device-only seams (EncryptedSharedPreferences, SAF writes, foreground
download, real streaming) are device-verified.

| Target | How | Precedent |
|---|---|---|
| `AbsApi` parsing + paths | MockWebServer + canned ABS JSON fixtures; assert models + paths/headers | `FeedFetcherTest` |
| `AbsAuthInterceptor` | host match → `Bearer`; other host / no config → untouched | — |
| Catalog reconcile | pure `AbsCatalogReconciler.reconcile(existing, incoming)`; preserve/insert/delete | `NewEpisodeIds`, `PlaylistOrdering` |
| Item→`BookEntity` + expanded→`(SourceKind, chapters, streamUrls)` mappers | pure; SINGLE vs MULTI, URL build, `serverId` normalization | — |
| `BookDao.getByOrigin` / `getIdsByOrigin` | in-memory Room, `runBlocking` | `EpisodeDaoTest` |
| ABS `BookDetailResolver` | fake `AbsApi` + fake DAOs; `handles`, idempotent no-op/fill | `feature:playlists` fake-DAO |
| `AbsDownloadWorker` enqueue/unique-work | `WorkManagerTestInitHelper` | `RefreshSchedulerTest` |

The design deliberately shapes the hard-to-test parts to be *thin*: reconciliation, JSON→entity
mapping, URL building, and the auth decision are all pure functions needing no Android. What's left
for the device — Keystore storage, SAF byte-writing, foreground notifications, real HTTP streaming —
has no meaningful JVM test and each has a working sibling already (`EpisodeDownloader`, `AudiobooksPrefs`).

**Device verification checklist** (real server; **all fixtures/tests scrubbed of real URLs+tokens**
— `example.com` + fake tokens, never committed):
1. Login → token persists across restart; wrong creds → `Error`.
2. Catalog mirror populates the Audiobooks tab; covers load (authed Coil).
3. Stream a book (lazy-expand → `Bearer` stream); local resume survives restart.
4. Download to the picked SAF folder; play in airplane mode; "remove" → re-streams.
5. Refresh after a server-side change reconciles without wiping positions/downloads.
6. Logout clears ABS books + downloaded files.

## 12. Security notes

- ABS server URL + credentials are sensitive (treat like the Podcast Index keys). Token in
  `EncryptedSharedPreferences`; password never persisted.
- JSON test fixtures and any logs must use `example.com` + fake tokens — **never commit real
  server URLs or tokens.**
- The auth interceptor adds the bearer **only** to the configured ABS host, never to podcast feeds
  or artwork hosts.

## 13. Out of scope (→ 6b or later)

- Two-way progress sync, ABS play sessions, conflict resolution (`AbsSyncWorker`).
- Periodic background catalog refresh.
- Multiple servers concurrently (column reserved; UI/logic not built).
- Finer chapter navigation *within* a multi-track ABS book.
- A dedicated server-browse screen distinct from the Audiobooks tab.
