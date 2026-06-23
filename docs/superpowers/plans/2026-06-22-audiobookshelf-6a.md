# Audiobookshelf 6a Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Connect Orator to a single audiobookshelf (ABS) server, mirror its catalog into the local `books` table, stream books with bearer auth, and optionally download them to a SAF folder for offline playback.

**Architecture:** A new `feature:audiobookshelf` module owns login, the JSON API client, catalog mirror, and download. It never imports another feature — it meets shared code at `core` seams: a `@Multibinds Set<Interceptor>` in `core:network` (one interceptor authenticates API + covers + streaming), `BookDetailResolver`/`BookDownloadController` in `core:model`, and an OkHttp-backed data source in `core:playback`. ABS books are ordinary `BookEntity` rows (`origin=ABS`) that reuse the entire audiobook playback pipeline.

**Tech Stack:** Kotlin, Hilt, Room (v8 destructive bump), OkHttp, kotlinx.serialization, Media3 (`media3-datasource-okhttp`), WorkManager + Hilt-Worker, DataStore, EncryptedSharedPreferences, Compose. Tests: JUnit4, Robolectric, MockWebServer, `WorkManagerTestInitHelper`.

**Spec:** `docs/superpowers/specs/2026-06-22-audiobookshelf-6a-design.md`

---

## File structure (what each new/changed file owns)

**`core:network`**
- `InterceptorModule.kt` (new) — `@Multibinds Set<Interceptor>`.
- `NetworkModule.kt` (modify) — fold the injected interceptor set into the shared `OkHttpClient`.

**`core:model`**
- `BookOrigin.kt`, `DownloadState.kt` (new enums).
- `BookDetailResolver.kt`, `BookDownloadController.kt` (new seam interfaces).

**`core:database`**
- `BookEntity.kt` (modify) — 4 columns. `OratorDatabase.kt` (modify) — version 8. `BookDao.kt` (modify) — `getByOrigin`/`getIdsByOrigin`.

**`core:playback`**
- `build.gradle.kts` (modify) — add `core:network` + `media3-datasource-okhttp`.
- `PlaybackService.kt` (modify) — OkHttp-backed `DefaultMediaSourceFactory`.

**`feature:audiobooks`**
- `AudiobookPlayRequestFactory.kt` (modify) — call `BookDetailResolver` before building the queue.
- `AudiobookListScreen.kt` / `AudiobookListViewModel.kt` (modify) — per-book ABS download affordance via `BookDownloadController`.

**`feature:audiobookshelf`** (new module)
- `data/AbsServerConfig.kt`, `data/AbsSession.kt`, `data/AbsUrl.kt` (normalize), `data/AbsConnectionState.kt`
- `data/AbsCredentialStore.kt` + `data/SecureStringStore.kt` (interface) + `data/EncryptedSecureStringStore.kt`
- `data/AbsAuthInterceptor.kt`
- `data/AbsApi.kt` + `data/AbsDtos.kt` (@Serializable) + `data/AbsJson.kt`
- `data/AbsCatalogReconciler.kt` (pure), `data/AbsBookMapper.kt` (pure), `data/AbsItemDetailMapper.kt` (pure)
- `data/AbsRepository.kt` (login/sync/logout), `data/AbsBookDetailResolver.kt`
- `data/AbsPrefs.kt`, `data/AbsFileDownloader.kt`, `work/AbsDownloadWorker.kt`, `data/AbsDownloadManager.kt`, `data/AbsDownloadController.kt`, `data/AbsDownloadPlan.kt` (pure)
- `AudiobookshelfSettingsSection.kt`, `AudiobookshelfFeatureModule.kt`
- `build.gradle.kts`, `src/main/AndroidManifest.xml`

**root**: `settings.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts` (add module).

**Per-chunk gate (run after every chunk):** `./gradlew test lint assembleDebug` — all green before review.

---

## Chunk 1: Foundation — deps, core seams, data model, module scaffold

### Task 1.1: Add Gradle catalog entries

**Files:** Modify `gradle/libs.versions.toml`

- [ ] **Step 1: Add versions** (under `[versions]`):
```toml
kotlinxSerialization = "1.7.3"
securityCrypto = "1.1.0-alpha06"
```

- [ ] **Step 2: Add libraries** (under `[libraries]`):
```toml
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinxSerialization" }
media3-datasource-okhttp = { group = "androidx.media3", name = "media3-datasource-okhttp", version.ref = "media3" }
androidx-security-crypto = { group = "androidx.security", name = "security-crypto", version.ref = "securityCrypto" }
```

- [ ] **Step 3: Add plugin** (under `[plugins]`):
```toml
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

- [ ] **Step 4: Verify it resolves** — `./gradlew help` (Expected: BUILD SUCCESSFUL; catalog parses).

- [ ] **Step 5: Commit**
```bash
git add gradle/libs.versions.toml
git commit -m "build: catalog entries for kotlinx.serialization, media3-okhttp, security-crypto"
```

### Task 1.2: `core:model` — `BookOrigin` + `DownloadState`

**Files:** Create `core/model/src/main/java/com/orator/core/model/BookOrigin.kt`, `core/model/src/main/java/com/orator/core/model/DownloadState.kt`

- [ ] **Step 1: Create the enums**
```kotlin
// BookOrigin.kt
package com.orator.core.model

/** Where a book came from. LOCAL = SAF/device files; ABS = an audiobookshelf server. */
enum class BookOrigin { LOCAL, ABS }
```
```kotlin
// DownloadState.kt
package com.orator.core.model

/** Offline state of an ABS book. LOCAL books stay NONE. */
enum class DownloadState { NONE, DOWNLOADING, DOWNLOADED }
```

- [ ] **Step 2: Build** — `./gradlew :core:model:compileDebugKotlin` (Expected: BUILD SUCCESSFUL).

- [ ] **Step 3: Commit**
```bash
git add core/model/src/main/java/com/orator/core/model/BookOrigin.kt core/model/src/main/java/com/orator/core/model/DownloadState.kt
git commit -m "feat(model): BookOrigin + DownloadState enums"
```

### Task 1.3: `core:model` — `BookDetailResolver` + `BookDownloadController` seams

**Files:** Create `core/model/src/main/java/com/orator/core/model/BookDetailResolver.kt`, `core/model/src/main/java/com/orator/core/model/BookDownloadController.kt`

- [ ] **Step 1: Create the interfaces** (placed beside `PlaylistItemResolver`, same `@IntoSet` idea):
```kotlin
// BookDetailResolver.kt
package com.orator.core.model

/**
 * Lazily fills in a book's playable detail (sourceUri + chapters) the first time it is played.
 * Each feature contributes one per origin via Hilt @IntoSet. LOCAL books are already complete, so
 * no resolver handles them; ABS books are mirrored as metadata only and resolved on demand.
 */
interface BookDetailResolver {
    fun handles(origin: BookOrigin): Boolean
    /** Idempotent: fetch + persist sourceUri + chapters if not already present; no-op otherwise. */
    suspend fun ensureDetails(bookId: String)
}
```
```kotlin
// BookDownloadController.kt
package com.orator.core.model

/**
 * Offline-download actions for a book, contributed per origin via Hilt @IntoSet so the audiobooks
 * list can offer download/remove for ABS books without importing feature:audiobookshelf.
 */
interface BookDownloadController {
    fun handles(origin: BookOrigin): Boolean
    fun enqueue(bookId: String)
    fun cancel(bookId: String)
    suspend fun remove(bookId: String)
}
```

- [ ] **Step 2: Build** — `./gradlew :core:model:compileDebugKotlin` (Expected: SUCCESSFUL).

- [ ] **Step 3: Commit**
```bash
git add core/model/src/main/java/com/orator/core/model/BookDetailResolver.kt core/model/src/main/java/com/orator/core/model/BookDownloadController.kt
git commit -m "feat(model): BookDetailResolver + BookDownloadController seams"
```

### Task 1.4: `core:database` — `BookEntity` v8 columns + `BookDao` queries + version bump

**Files:** Modify `core/database/.../BookEntity.kt`, `core/database/.../BookDao.kt`, `core/database/.../OratorDatabase.kt`. Test: `core/database/src/test/java/com/orator/core/database/BookDaoOriginTest.kt`

- [ ] **Step 1: Write the failing test** (`BookDaoOriginTest.kt`) — mirrors `EpisodeDaoTest` (in-memory Room, `@Config(sdk=[34])`, `runBlocking`):
```kotlin
package com.orator.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.orator.core.model.BookOrigin
import com.orator.core.model.DownloadState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookDaoOriginTest {
    private lateinit var db: OratorDatabase
    private lateinit var dao: BookDao

    private fun book(id: String, origin: BookOrigin) = BookEntity(
        id = id, title = id, author = null, coverPath = null, sourceUri = "",
        sourceKind = SourceKind.SINGLE_FILE, durationMs = 0, addedAtUtc = 0,
        origin = origin,
    )

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), OratorDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.bookDao()
    }

    @After fun tearDown() = db.close()

    @Test fun `getByOrigin returns only matching books`() = runBlocking {
        dao.upsert(listOf(book("local1", BookOrigin.LOCAL), book("abs:1", BookOrigin.ABS)))
        assertEquals(listOf("abs:1"), dao.getByOrigin(BookOrigin.ABS).map { it.id })
        assertEquals(listOf("abs:1"), dao.getIdsByOrigin(BookOrigin.ABS))
    }

    @Test fun `defaults are LOCAL and NONE`() = runBlocking {
        dao.upsert(listOf(BookEntity(
            id = "l", title = "l", author = null, coverPath = null, sourceUri = "",
            sourceKind = SourceKind.SINGLE_FILE, durationMs = 0, addedAtUtc = 0,
        )))
        val row = dao.getById("l")!!
        assertEquals(BookOrigin.LOCAL, row.origin)
        assertEquals(DownloadState.NONE, row.downloadState)
    }
}
```

- [ ] **Step 2: Run it — fails to compile** — `./gradlew :core:database:testDebugUnitTest --tests "*BookDaoOriginTest*"` (Expected: compile error — `origin`/`getByOrigin` unresolved).

- [ ] **Step 3: Add the columns** to `BookEntity` (after `speedOverride`):
```kotlin
    val origin: BookOrigin = BookOrigin.LOCAL,
    val serverId: String? = null,
    val absItemId: String? = null,
    val downloadState: DownloadState = DownloadState.NONE,
```
Add imports `import com.orator.core.model.BookOrigin` and `import com.orator.core.model.DownloadState`. (Enums persist natively — Room stores them by name, as `SourceKind` already does.)

- [ ] **Step 4: Add the DAO queries** to `BookDao`:
```kotlin
    @Query("SELECT * FROM books WHERE origin = :origin")
    suspend fun getByOrigin(origin: BookOrigin): List<BookEntity>

    @Query("SELECT id FROM books WHERE origin = :origin")
    suspend fun getIdsByOrigin(origin: BookOrigin): List<String>
```
Add `import com.orator.core.model.BookOrigin`.

- [ ] **Step 5: Bump DB version** in `OratorDatabase.kt`: `version = 7` → `version = 8`.

- [ ] **Step 6: Confirm `core:database` depends on `core:model`** — it already does (enums like `AutoInsertRule` live there). If `compileDebugKotlin` cannot resolve `com.orator.core.model`, add `implementation(project(":core:model"))` to `core/database/build.gradle.kts` (it should already be present).

- [ ] **Step 7: Run the test — passes** — `./gradlew :core:database:testDebugUnitTest --tests "*BookDaoOriginTest*"` (Expected: PASS).

- [ ] **Step 8: Commit**
```bash
git add core/database/src/main/java/com/orator/core/database/BookEntity.kt core/database/src/main/java/com/orator/core/database/BookDao.kt core/database/src/main/java/com/orator/core/database/OratorDatabase.kt core/database/src/test/java/com/orator/core/database/BookDaoOriginTest.kt
git commit -m "feat(database): BookEntity ABS columns + origin queries; DB v8"
```

### Task 1.5: Scaffold the `feature:audiobookshelf` module

**Files:** Create `feature/audiobookshelf/build.gradle.kts`, `feature/audiobookshelf/src/main/AndroidManifest.xml`; modify `settings.gradle.kts`

- [ ] **Step 1: Register the module** — append to `settings.gradle.kts`:
```kotlin
include(":feature:audiobookshelf")
```

- [ ] **Step 2: Create `feature/audiobookshelf/build.gradle.kts`** (models on `feature:podcasts` — it has network + work + documentfile — plus the serialization plugin):
```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.orator.feature.audiobookshelf"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:navigation"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:database"))
    implementation(project(":core:network"))

    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
```
Note: the catalog accessor for `kotlinx-serialization-json` is `libs.kotlinx.serialization.json`.

- [ ] **Step 3: Create `feature/audiobookshelf/src/main/AndroidManifest.xml`**:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

- [ ] **Step 4: Build the empty module** — `./gradlew :feature:audiobookshelf:assembleDebug` (Expected: BUILD SUCCESSFUL — empty library compiles).

- [ ] **Step 5: Commit**
```bash
git add settings.gradle.kts feature/audiobookshelf/build.gradle.kts feature/audiobookshelf/src/main/AndroidManifest.xml
git commit -m "build(audiobookshelf): scaffold feature:audiobookshelf module"
```

### Chunk 1 gate
- [ ] Run `./gradlew test lint assembleDebug` — Expected: BUILD SUCCESSFUL. Then dispatch chunk review.

---

## Chunk 2: Auth seam, credential store, API client

### Task 2.1: `core:network` interceptor multibinding seam

**Files:** Create `core/network/.../InterceptorModule.kt`; modify `core/network/.../NetworkModule.kt`. Test: `core/network/src/test/java/com/orator/core/network/OkHttpInterceptorWiringTest.kt`

- [ ] **Step 1: Write the failing test** — pure JVM, no Android:
```kotlin
package com.orator.core.network

import okhttp3.Interceptor
import org.junit.Assert.assertTrue
import org.junit.Test

class OkHttpInterceptorWiringTest {
    @Test fun `provided client includes injected interceptors`() {
        val marker = Interceptor { chain -> chain.proceed(chain.request()) }
        val client = NetworkModule.provideOkHttpClient(setOf(marker))
        assertTrue(marker in client.interceptors)
    }

    @Test fun `empty set yields a working client`() {
        val client = NetworkModule.provideOkHttpClient(emptySet())
        assertTrue(client.interceptors.isEmpty() || client.interceptors.none { false })
    }
}
```

- [ ] **Step 2: Run it — fails** — `./gradlew :core:network:testDebugUnitTest --tests "*OkHttpInterceptorWiringTest*"` (Expected: compile error — `provideOkHttpClient` takes no args).

- [ ] **Step 3: Add the `@Multibinds` module** (`InterceptorModule.kt`):
```kotlin
package com.orator.core.network

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import okhttp3.Interceptor

/** Declares the interceptor multibinding so the set exists even with zero contributors. */
@Module
@InstallIn(SingletonComponent::class)
abstract class InterceptorModule {
    @Multibinds abstract fun interceptors(): Set<Interceptor>
}
```

- [ ] **Step 4: Fold the set into the client** — modify `NetworkModule.provideOkHttpClient`:
```kotlin
import okhttp3.Interceptor

    @Provides
    @Singleton
    fun provideOkHttpClient(
        interceptors: Set<@JvmSuppressWildcards Interceptor>,
    ): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .apply { interceptors.forEach { addInterceptor(it) } }
        .build()
```

- [ ] **Step 5: Run the test — passes** — `./gradlew :core:network:testDebugUnitTest --tests "*OkHttpInterceptorWiringTest*"` (Expected: PASS).

- [ ] **Step 6: Commit**
```bash
git add core/network/src/main/java/com/orator/core/network/InterceptorModule.kt core/network/src/main/java/com/orator/core/network/NetworkModule.kt core/network/src/test/java/com/orator/core/network/OkHttpInterceptorWiringTest.kt
git commit -m "feat(network): @Multibinds Set<Interceptor> folded into shared OkHttpClient"
```

### Task 2.2: ABS URL normalization + config types

**Files:** Create `data/AbsUrl.kt`, `data/AbsServerConfig.kt`, `data/AbsSession.kt`, `data/AbsConnectionState.kt`. Test: `.../data/AbsUrlTest.kt`

- [ ] **Step 1: Write the failing test** (`AbsUrlTest.kt`):
```kotlin
package com.orator.feature.audiobookshelf.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AbsUrlTest {
    @Test fun `serverId strips trailing slash and lowercases host`() {
        assertEquals("https://abs.example.com", AbsUrl.serverId("https://ABS.Example.com/"))
    }
    @Test fun `serverId keeps explicit port`() {
        assertEquals("http://host:13378", AbsUrl.serverId("http://host:13378/"))
    }
    @Test fun `endpoint joins base and path without doubling slash`() {
        assertEquals("https://abs.example.com/login", AbsUrl.endpoint("https://abs.example.com/", "login"))
    }
}
```

- [ ] **Step 2: Run it — fails** — `./gradlew :feature:audiobookshelf:testDebugUnitTest --tests "*AbsUrlTest*"` (Expected: compile error).

- [ ] **Step 3: Implement `AbsUrl`** (uses `okhttp3.HttpUrl` for robust parsing):
```kotlin
package com.orator.feature.audiobookshelf.data

import okhttp3.HttpUrl.Companion.toHttpUrl

object AbsUrl {
    /** Stable id = scheme://host[:port], no trailing slash, lowercased host. */
    fun serverId(baseUrl: String): String {
        val u = baseUrl.trim().toHttpUrl()
        val portPart = if (u.port == HttpUrlDefaults.defaultPort(u.scheme)) "" else ":${u.port}"
        return "${u.scheme}://${u.host}$portPart"
    }
    fun endpoint(baseUrl: String, path: String): String =
        baseUrl.trim().trimEnd('/') + "/" + path.trimStart('/')
}

private object HttpUrlDefaults {
    fun defaultPort(scheme: String) = if (scheme == "https") 443 else 80
}
```

- [ ] **Step 4: Create the config types** (`AbsServerConfig.kt`, `AbsSession.kt`, `AbsConnectionState.kt`):
```kotlin
// AbsServerConfig.kt
package com.orator.feature.audiobookshelf.data

data class AbsServerConfig(
    val serverId: String,
    val baseUrl: String,
    val username: String,
    val token: String,
)
```
```kotlin
// AbsSession.kt
package com.orator.feature.audiobookshelf.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Config plus its pre-parsed base URL, so the interceptor matches host+port without re-parsing. */
data class AbsSession(val config: AbsServerConfig, val baseUrl: HttpUrl) {
    companion object {
        fun of(config: AbsServerConfig) = AbsSession(config, config.baseUrl.toHttpUrl())
    }
}
```
```kotlin
// AbsConnectionState.kt
package com.orator.feature.audiobookshelf.data

sealed interface AbsConnectionState {
    data object Disconnected : AbsConnectionState
    data object Connecting : AbsConnectionState
    data class Connected(val config: AbsServerConfig) : AbsConnectionState
    data class Error(val message: String) : AbsConnectionState
}
```

- [ ] **Step 5: Run the test — passes** — `./gradlew :feature:audiobookshelf:testDebugUnitTest --tests "*AbsUrlTest*"` (Expected: PASS).

- [ ] **Step 6: Commit**
```bash
git add feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/data/AbsUrl.kt feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/data/AbsServerConfig.kt feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/data/AbsSession.kt feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/data/AbsConnectionState.kt feature/audiobookshelf/src/test/java/com/orator/feature/audiobookshelf/data/AbsUrlTest.kt
git commit -m "feat(audiobookshelf): ABS url normalization + config/session/state types"
```

### Task 2.3: Credential store (testable seam + encrypted impl)

**Files:** Create `data/SecureStringStore.kt`, `data/EncryptedSecureStringStore.kt`, `data/AbsCredentialStore.kt`. Test: `.../data/AbsCredentialStoreTest.kt`

- [ ] **Step 1: Write the failing test** (uses an in-memory `SecureStringStore`, so no Keystore needed):
```kotlin
package com.orator.feature.audiobookshelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AbsCredentialStoreTest {
    private class FakeSecure : SecureStringStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun clear() = map.clear()
    }

    @Test fun `save then current returns the session; host matches base`() {
        val store = AbsCredentialStore(FakeSecure())
        val cfg = AbsServerConfig("https://abs.example.com", "https://abs.example.com", "u", "tok")
        store.save(cfg)
        assertEquals(cfg, store.current()!!.config)
        assertEquals("abs.example.com", store.current()!!.baseUrl.host)
    }

    @Test fun `survives a fresh instance backed by the same secure store`() {
        val secure = FakeSecure()
        AbsCredentialStore(secure).save(
            AbsServerConfig("https://abs.example.com", "https://abs.example.com", "u", "tok"),
        )
        assertEquals("tok", AbsCredentialStore(secure).current()!!.config.token)
    }

    @Test fun `clear wipes it`() {
        val store = AbsCredentialStore(FakeSecure())
        store.save(AbsServerConfig("https://x", "https://x", "u", "t"))
        store.clear()
        assertNull(store.current())
    }
}
```

- [ ] **Step 2: Run it — fails** — `./gradlew :feature:audiobookshelf:testDebugUnitTest --tests "*AbsCredentialStoreTest*"` (Expected: compile error).

- [ ] **Step 3: Implement `SecureStringStore` + `AbsCredentialStore`**:
```kotlin
// SecureStringStore.kt
package com.orator.feature.audiobookshelf.data

/** Synchronous secure key/value persistence; the encrypted impl is the production binding. */
interface SecureStringStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun clear()
}
```
```kotlin
// AbsCredentialStore.kt
package com.orator.feature.audiobookshelf.data

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the current ABS session in an AtomicReference (lock-free, read synchronously by the auth
 * interceptor on OkHttp I/O threads) and mirrors it into a SecureStringStore so it survives restart.
 * Lazily hydrates from the secure store on first access — no eager init needed.
 */
@Singleton
class AbsCredentialStore @Inject constructor(
    private val secure: SecureStringStore,
) {
    private val ref = AtomicReference<AbsSession?>(null)
    @Volatile private var hydrated = false

    fun current(): AbsSession? {
        if (!hydrated) hydrate()
        return ref.get()
    }

    @Synchronized private fun hydrate() {
        if (hydrated) return
        val base = secure.get(KEY_BASE)
        val user = secure.get(KEY_USER)
        val token = secure.get(KEY_TOKEN)
        val serverId = secure.get(KEY_SERVER_ID)
        if (base != null && user != null && token != null && serverId != null) {
            ref.set(AbsSession.of(AbsServerConfig(serverId, base, user, token)))
        }
        hydrated = true
    }

    fun save(config: AbsServerConfig) {
        secure.put(KEY_SERVER_ID, config.serverId)
        secure.put(KEY_BASE, config.baseUrl)
        secure.put(KEY_USER, config.username)
        secure.put(KEY_TOKEN, config.token)
        ref.set(AbsSession.of(config))
        hydrated = true
    }

    fun clear() {
        secure.clear()
        ref.set(null)
        hydrated = true
    }

    private companion object {
        const val KEY_SERVER_ID = "server_id"
        const val KEY_BASE = "base_url"
        const val KEY_USER = "username"
        const val KEY_TOKEN = "token"
    }
}
```

- [ ] **Step 4: Implement the encrypted production binding** (`EncryptedSecureStringStore.kt`) — not unit-tested (Keystore is device-only), verified on device in Chunk 6:
```kotlin
package com.orator.feature.audiobookshelf.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class EncryptedSecureStringStore @Inject constructor(
    @ApplicationContext context: Context,
) : SecureStringStore {
    private val prefs by lazy {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, "abs_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun clear() { prefs.edit().clear().apply() }
}
```

- [ ] **Step 5: Run the test — passes** — `./gradlew :feature:audiobookshelf:testDebugUnitTest --tests "*AbsCredentialStoreTest*"` (Expected: PASS).

- [ ] **Step 6: Commit**
```bash
git add feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/data/SecureStringStore.kt feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/data/EncryptedSecureStringStore.kt feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/data/AbsCredentialStore.kt feature/audiobookshelf/src/test/java/com/orator/feature/audiobookshelf/data/AbsCredentialStoreTest.kt
git commit -m "feat(audiobookshelf): AbsCredentialStore (AtomicReference + secure-store seam)"
```

### Task 2.4: `AbsAuthInterceptor` (host+port scoped)

**Files:** Create `data/AbsAuthInterceptor.kt`. Test: `.../data/AbsAuthInterceptorTest.kt`

- [ ] **Step 1: Write the failing test** (drives the interceptor through MockWebServer; a fake store provides the session pointing at the mock's URL):
```kotlin
package com.orator.feature.audiobookshelf.data

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class AbsAuthInterceptorTest {
    private fun storeFor(baseUrl: String): AbsCredentialStore {
        val s = AbsCredentialStore(object : SecureStringStore {
            val m = mutableMapOf<String, String>()
            override fun get(key: String) = m[key]
            override fun put(key: String, value: String) { m[key] = value }
            override fun clear() = m.clear()
        })
        s.save(AbsServerConfig(baseUrl.trimEnd('/'), baseUrl, "u", "secret-token"))
        return s
    }

    @Test fun `adds bearer to the configured host`() {
        val server = MockWebServer().apply { enqueue(MockResponse()); start() }
        val base = server.url("/").toString()
        val client = OkHttpClient.Builder().addInterceptor(AbsAuthInterceptor(storeFor(base))).build()
        client.newCall(Request.Builder().url(server.url("/api/libraries")).build()).execute().close()
        assertEquals("Bearer secret-token", server.takeRequest().getHeader("Authorization"))
        server.shutdown()
    }

    @Test fun `does not add bearer to a different host`() {
        val absServer = MockWebServer().apply { start() }
        val other = MockWebServer().apply { enqueue(MockResponse()); start() }
        val client = OkHttpClient.Builder()
            .addInterceptor(AbsAuthInterceptor(storeFor(absServer.url("/").toString()))).build()
        client.newCall(Request.Builder().url(other.url("/feed.xml")).build()).execute().close()
        assertEquals(null, other.takeRequest().getHeader("Authorization"))
        absServer.shutdown(); other.shutdown()
    }
}
```

- [ ] **Step 2: Run it — fails** — `./gradlew :feature:audiobookshelf:testDebugUnitTest --tests "*AbsAuthInterceptorTest*"` (Expected: compile error).

- [ ] **Step 3: Implement** (`AbsAuthInterceptor.kt`) — matches host AND port:
```kotlin
package com.orator.feature.audiobookshelf.data

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

class AbsAuthInterceptor @Inject constructor(
    private val store: AbsCredentialStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val session = store.current()
        val req = chain.request()
        val base = session?.baseUrl
        return if (base != null && req.url.host == base.host && req.url.port == base.port) {
            chain.proceed(
                req.newBuilder()
                    .header("Authorization", "Bearer ${session.config.token}")
                    .build(),
            )
        } else {
            chain.proceed(req)
        }
    }
}
```

- [ ] **Step 4: Run the test — passes** — same command (Expected: PASS, both cases).

- [ ] **Step 5: Commit**
```bash
git add feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/data/AbsAuthInterceptor.kt feature/audiobookshelf/src/test/java/com/orator/feature/audiobookshelf/data/AbsAuthInterceptorTest.kt
git commit -m "feat(audiobookshelf): host+port scoped AbsAuthInterceptor"
```

### Task 2.5: DTOs + `AbsApi` client

**Files:** Create `data/AbsJson.kt`, `data/AbsDtos.kt`, `data/AbsApi.kt`. Test: `.../data/AbsApiTest.kt`

- [ ] **Step 1: Write the failing test** — MockWebServer with **scrubbed** fixtures (never real tokens/URLs):
```kotlin
package com.orator.feature.audiobookshelf.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class AbsApiTest {
    private val client = OkHttpClient()

    @Test fun `login parses user token`() = runBlocking {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setBody("""{"user":{"id":"u1","username":"reader","token":"abc123"}}"""))
            start()
        }
        val api = AbsApi(client, AbsJson.instance)
        val user = api.login(server.url("/").toString(), "reader", "pw")
        assertEquals("abc123", user.token)
        assertEquals("/login", server.takeRequest().path)
        server.shutdown()
    }

    @Test fun `getLibraryItems parses minified items and ignores unknown fields`() = runBlocking {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setBody(
                """{"results":[{"id":"li1","media":{"metadata":{"title":"Dune","authorName":"Herbert"},
                   "numAudioFiles":3,"duration":42.5},"unknownField":true}]}""".trimIndent(),
            ))
            start()
        }
        val api = AbsApi(client, AbsJson.instance)
        val page = api.getLibraryItems(server.url("/").toString(), "lib1", "tok")
        assertEquals(1, page.results.size)
        assertEquals("Dune", page.results[0].media.metadata.title)
        assertEquals("Herbert", page.results[0].media.metadata.authorName)
        assertEquals(3, page.results[0].media.numAudioFiles)
        server.shutdown()
    }
}
```
(Note: `login` is unauthenticated — it does not go through the interceptor; it sends username/password in the body. Authenticated calls in the app carry the bearer via the interceptor, but `AbsApi` also accepts an explicit `token` arg so it is usable in tests with a plain client.)

- [ ] **Step 2: Run it — fails** — `./gradlew :feature:audiobookshelf:testDebugUnitTest --tests "*AbsApiTest*"` (Expected: compile error).

- [ ] **Step 3: Implement the JSON instance** (`AbsJson.kt`):
```kotlin
package com.orator.feature.audiobookshelf.data

import kotlinx.serialization.json.Json

object AbsJson {
    val instance = Json { ignoreUnknownKeys = true; coerceInputValues = true }
}
```

- [ ] **Step 4: Implement the DTOs** (`AbsDtos.kt`) — only the fields 6a needs; `ignoreUnknownKeys` covers the rest:
```kotlin
package com.orator.feature.audiobookshelf.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class AbsLoginResponse(val user: AbsUser)
@Serializable data class AbsUser(val id: String, val username: String? = null, val token: String)

@Serializable data class AbsLibrariesResponse(val libraries: List<AbsLibrary> = emptyList())
@Serializable data class AbsLibrary(val id: String, val name: String, val mediaType: String? = null)

@Serializable data class AbsLibraryItemsResponse(val results: List<AbsLibraryItem> = emptyList())

@Serializable data class AbsLibraryItem(
    val id: String,
    val media: AbsMedia = AbsMedia(),
)
@Serializable data class AbsMedia(
    val metadata: AbsMetadata = AbsMetadata(),
    val numAudioFiles: Int = 0,
    val duration: Double = 0.0,             // seconds
    val audioFiles: List<AbsAudioFile> = emptyList(),
    val chapters: List<AbsChapter> = emptyList(),
)
@Serializable data class AbsMetadata(
    val title: String = "",
    @SerialName("authorName") val authorName: String? = null,
)
@Serializable data class AbsAudioFile(
    val ino: String,
    val index: Int = 0,
    val duration: Double = 0.0,             // seconds
)
@Serializable data class AbsChapter(
    val id: Int = 0,
    val start: Double = 0.0,                // seconds
    val end: Double = 0.0,
    val title: String = "",
)
```

- [ ] **Step 5: Implement `AbsApi`** (`AbsApi.kt`) — suspend functions over the shared client; helpers for URLs:
```kotlin
package com.orator.feature.audiobookshelf.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject

/**
 * Thin audiobookshelf REST client over the shared OkHttpClient. Authenticated endpoints rely on
 * AbsAuthInterceptor to attach the bearer for the configured host; an explicit [token] arg is kept
 * so the client is exercisable in tests with a plain OkHttpClient.
 */
class AbsApi @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
) {
    suspend fun login(baseUrl: String, username: String, password: String): AbsUser =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(
                LoginBody.serializer(), LoginBody(username, password),
            ).toRequestBody(JSON)
            val req = Request.Builder().url(AbsUrl.endpoint(baseUrl, "login")).post(body).build()
            client.newCall(req).execute().use { resp ->
                check(resp.isSuccessful) { "login failed: HTTP ${resp.code}" }
                json.decodeFromString(AbsLoginResponse.serializer(), resp.body!!.string()).user
            }
        }

    suspend fun getLibraries(baseUrl: String, token: String): List<AbsLibrary> =
        get(AbsUrl.endpoint(baseUrl, "api/libraries"), token, AbsLibrariesResponse.serializer()).libraries

    suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String): AbsLibraryItemsResponse =
        get(AbsUrl.endpoint(baseUrl, "api/libraries/$libraryId/items?minified=1&limit=0"),
            token, AbsLibraryItemsResponse.serializer())

    suspend fun getItemExpanded(baseUrl: String, itemId: String, token: String): AbsLibraryItem =
        get(AbsUrl.endpoint(baseUrl, "api/items/$itemId?expanded=1"), token, AbsLibraryItem.serializer())

    fun coverUrl(baseUrl: String, itemId: String): String =
        AbsUrl.endpoint(baseUrl, "api/items/$itemId/cover")

    fun fileStreamUrl(baseUrl: String, itemId: String, ino: String): String =
        AbsUrl.endpoint(baseUrl, "api/items/$itemId/file/$ino")

    private suspend fun <T> get(url: String, token: String, deserializer: kotlinx.serialization.DeserializationStrategy<T>): T =
        withContext(Dispatchers.IO) {
            // Bearer is added by the interceptor for the ABS host; included explicitly too so a plain
            // test client authenticates and the call is order-independent of interceptor wiring.
            val req = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
            client.newCall(req).execute().use { resp ->
                check(resp.isSuccessful) { "GET $url failed: HTTP ${resp.code}" }
                json.decodeFromString(deserializer, resp.body!!.string())
            }
        }

    @kotlinx.serialization.Serializable
    private data class LoginBody(val username: String, val password: String)

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
```
(`limit=0` asks ABS for all items in one response — acceptable for 6a; pagination is a later refinement noted in the spec.)

- [ ] **Step 6: Provide `Json` to Hilt** — add to `AudiobookshelfFeatureModule` later (Chunk 6); for now `AbsApi` tests construct it directly. To keep the module compiling, add a provider now in a small `data/AbsNetworkModule.kt`:
```kotlin
package com.orator.feature.audiobookshelf.data

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json

@Module
@InstallIn(SingletonComponent::class)
object AbsNetworkModule {
    @Provides fun provideAbsJson(): Json = AbsJson.instance
}
```

- [ ] **Step 7: Run the test — passes** — `./gradlew :feature:audiobookshelf:testDebugUnitTest --tests "*AbsApiTest*"` (Expected: PASS).

- [ ] **Step 8: Commit**
```bash
git add feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/data/AbsJson.kt feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/data/AbsDtos.kt feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/data/AbsApi.kt feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/data/AbsNetworkModule.kt feature/audiobookshelf/src/test/java/com/orator/feature/audiobookshelf/data/AbsApiTest.kt
git commit -m "feat(audiobookshelf): AbsApi client + serializable DTOs"
```

### Chunk 2 gate
- [ ] Run `./gradlew test lint assembleDebug` — Expected: BUILD SUCCESSFUL. Then dispatch chunk review.

---

## Chunk 3: Catalog mirror — reconciler, mappers, repository, login

### Task 3.1: Pure `AbsCatalogReconciler`

**Files:** Create `data/AbsCatalogReconciler.kt`. Test: `.../data/AbsCatalogReconcilerTest.kt`

- [ ] **Step 1: Write the failing test**:
```kotlin
package com.orator.feature.audiobookshelf.data

import com.orator.core.database.BookEntity
import com.orator.core.database.SourceKind
import com.orator.core.model.BookOrigin
import com.orator.core.model.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Test

class AbsCatalogReconcilerTest {
    private fun abs(id: String, title: String, pos: Long = 0, dl: DownloadState = DownloadState.NONE, uri: String = "") =
        BookEntity(
            id = id, title = title, author = null, coverPath = null, sourceUri = uri,
            sourceKind = SourceKind.SINGLE_FILE, durationMs = 0, positionMs = pos, addedAtUtc = 0,
            origin = BookOrigin.ABS, serverId = "s", absItemId = id.removePrefix("abs:"),
            downloadState = dl,
        )

    @Test fun `new items are inserted, missing items deleted, metadata refreshed`() {
        val existing = listOf(abs("abs:1", "Old Title", pos = 5000), abs("abs:2", "Gone"))
        val incoming = listOf(abs("abs:1", "New Title"), abs("abs:3", "Fresh"))
        val r = AbsCatalogReconciler.reconcile(existing, incoming)
        // abs:1 keeps position 5000 but gets the new title; abs:3 inserted; abs:2 deleted.
        assertEquals("New Title", r.upserts.first { it.id == "abs:1" }.title)
        assertEquals(5000, r.upserts.first { it.id == "abs:1" }.positionMs)
        assertEquals(setOf("abs:1", "abs:3"), r.upserts.map { it.id }.toSet())
        assertEquals(listOf("abs:2"), r.deletes)
    }

    @Test fun `downloaded books keep their local sourceUri and download state`() {
        val existing = listOf(abs("abs:1", "T", dl = DownloadState.DOWNLOADED, uri = "content://local/1"))
        val incoming = listOf(abs("abs:1", "T", uri = ""))
        val r = AbsCatalogReconciler.reconcile(existing, incoming)
        val merged = r.upserts.single()
        assertEquals(DownloadState.DOWNLOADED, merged.downloadState)
        assertEquals("content://local/1", merged.sourceUri)
    }
}
```

- [ ] **Step 2: Run it — fails** — `./gradlew :feature:audiobookshelf:testDebugUnitTest --tests "*AbsCatalogReconcilerTest*"` (Expected: compile error).

- [ ] **Step 3: Implement** (`AbsCatalogReconciler.kt`):
```kotlin
package com.orator.feature.audiobookshelf.data

import com.orator.core.database.BookEntity
import com.orator.core.model.DownloadState

data class ReconcileResult(val upserts: List<BookEntity>, val deletes: List<String>)

/** Pure catalog merge: refresh server-owned metadata, preserve device-owned state, delete stale. */
object AbsCatalogReconciler {
    fun reconcile(existing: List<BookEntity>, incoming: List<BookEntity>): ReconcileResult {
        val old = existing.associateBy { it.id }
        val upserts = incoming.map { fresh ->
            val prev = old[fresh.id] ?: return@map fresh
            fresh.copy(
                positionMs = prev.positionMs,
                lastPlayedAtMs = prev.lastPlayedAtMs,
                speedOverride = prev.speedOverride,
                downloadState = prev.downloadState,
                sourceUri = if (prev.downloadState == DownloadState.DOWNLOADED) prev.sourceUri else fresh.sourceUri,
            )
        }
        val incomingIds = incoming.map { it.id }.toSet()
        val deletes = existing.map { it.id }.filter { it !in incomingIds }
        return ReconcileResult(upserts, deletes)
    }
}
```

- [ ] **Step 4: Run the test — passes** (Expected: PASS).

- [ ] **Step 5: Commit**
```bash
git add feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/data/AbsCatalogReconciler.kt feature/audiobookshelf/src/test/java/com/orator/feature/audiobookshelf/data/AbsCatalogReconcilerTest.kt
git commit -m "feat(audiobookshelf): pure catalog reconciler (preserve-on-merge)"
```

### Task 3.2: Pure `AbsBookMapper` (library item → `BookEntity` metadata)

**Files:** Create `data/AbsBookMapper.kt`. Test: `.../data/AbsBookMapperTest.kt`

- [ ] **Step 1: Write the failing test**:
```kotlin
package com.orator.feature.audiobookshelf.data

import com.orator.core.database.SourceKind
import com.orator.core.model.BookOrigin
import com.orator.core.model.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Test

class AbsBookMapperTest {
    @Test fun `maps minified item to metadata-only ABS book`() {
        val item = AbsLibraryItem(
            id = "li1",
            media = AbsMedia(
                metadata = AbsMetadata(title = "Dune", authorName = "Herbert"),
                numAudioFiles = 3, duration = 42.5,
            ),
        )
        val b = AbsBookMapper.toBook(item, serverId = "https://abs.example.com", baseUrl = "https://abs.example.com")
        assertEquals("abs:li1", b.id)
        assertEquals("li1", b.absItemId)
        assertEquals(BookOrigin.ABS, b.origin)
        assertEquals("Dune", b.title)
        assertEquals("Herbert", b.author)
        assertEquals("https://abs.example.com/api/items/li1/cover", b.coverPath)
        assertEquals(SourceKind.MULTI_FILE, b.sourceKind)         // 3 files
        assertEquals(42_500, b.durationMs)
        assertEquals("", b.sourceUri)                              // not expanded yet
        assertEquals(DownloadState.NONE, b.downloadState)
    }

    @Test fun `single audio file maps to SINGLE_FILE`() {
        val item = AbsLibraryItem(id = "li2", media = AbsMedia(numAudioFiles = 1))
        assertEquals(SourceKind.SINGLE_FILE, AbsBookMapper.toBook(item, "s", "https://x").sourceKind)
    }
}
```

- [ ] **Step 2: Run it — fails** (Expected: compile error).

- [ ] **Step 3: Implement** (`AbsBookMapper.kt`):
```kotlin
package com.orator.feature.audiobookshelf.data

import com.orator.core.database.BookEntity
import com.orator.core.database.SourceKind
import com.orator.core.model.BookOrigin

object AbsBookMapper {
    fun toBook(item: AbsLibraryItem, serverId: String, baseUrl: String): BookEntity {
        val md = item.media.metadata
        val multi = item.media.numAudioFiles > 1
        return BookEntity(
            id = "abs:${item.id}",
            title = md.title.ifBlank { item.id },
            author = md.authorName,
            coverPath = AbsUrl.endpoint(baseUrl, "api/items/${item.id}/cover"),
            sourceUri = "",                                   // filled lazily on first play (Chunk 4)
            sourceKind = if (multi) SourceKind.MULTI_FILE else SourceKind.SINGLE_FILE,
            durationMs = (item.media.duration * 1000).toLong(),
            addedAtUtc = System.currentTimeMillis(),
            origin = BookOrigin.ABS,
            serverId = serverId,
            absItemId = item.id,
        )
    }
}
```

- [ ] **Step 4: Run the test — passes** (Expected: PASS).

- [ ] **Step 5: Commit**
```bash
git add feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/data/AbsBookMapper.kt feature/audiobookshelf/src/test/java/com/orator/feature/audiobookshelf/data/AbsBookMapperTest.kt
git commit -m "feat(audiobookshelf): library-item → BookEntity metadata mapper"
```

### Task 3.3: `AbsRepository` — login, sync, logout

**Files:** Create `data/AbsRepository.kt`. Test: `.../data/AbsRepositoryTest.kt`

This task wires `AbsApi` + `AbsCredentialStore` + `BookDao` together. Use **fakes** for `AbsApi` (open the methods or extract an interface) and a fake `BookDao`. To allow faking, introduce a minimal interface the repo depends on.

- [ ] **Step 1: Extract a tiny seam for testability** — add `data/AbsCatalogSource.kt`:
```kotlin
package com.orator.feature.audiobookshelf.data

/** The subset of AbsApi the repository needs for a catalog pass — lets tests fake the network. */
interface AbsCatalogSource {
    suspend fun libraries(baseUrl: String, token: String): List<AbsLibrary>
    suspend fun items(baseUrl: String, libraryId: String, token: String): List<AbsLibraryItem>
}
```
Make `AbsApi` implement it (add `: AbsCatalogSource` and the two thin override methods delegating to `getLibraries`/`getLibraryItems(...).results`). Bind `AbsApi` as `AbsCatalogSource` in `AbsNetworkModule`:
```kotlin
// in AbsNetworkModule (object → keep @Provides; AbsApi has @Inject ctor so just expose it as the interface)
@Provides fun provideCatalogSource(api: AbsApi): AbsCatalogSource = api
```

- [ ] **Step 2: Write the failing test** (fake source + fake DAO):
```kotlin
package com.orator.feature.audiobookshelf.data

import com.orator.core.database.BookEntity
import com.orator.core.model.BookOrigin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AbsRepositoryTest {
    private val store = AbsCredentialStore(object : SecureStringStore {
        val m = mutableMapOf<String, String>()
        override fun get(key: String) = m[key]
        override fun put(key: String, value: String) { m[key] = value }
        override fun clear() = m.clear()
    }).apply { save(AbsServerConfig("https://abs.example.com", "https://abs.example.com", "u", "tok")) }

    private val fakeDao = FakeBookDao()

    private val source = object : AbsCatalogSource {
        override suspend fun libraries(baseUrl: String, token: String) =
            listOf(AbsLibrary("lib1", "Books", "book"))
        override suspend fun items(baseUrl: String, libraryId: String, token: String) =
            listOf(AbsLibraryItem("li1", AbsMedia(metadata = AbsMetadata(title = "Dune"))))
    }

    @Test fun `sync mirrors items into the books table as ABS rows`() = runBlocking {
        AbsRepository(source, store, fakeDao).sync()
        assertEquals(listOf("abs:li1"), fakeDao.getIdsByOrigin(BookOrigin.ABS))
        assertEquals("Dune", fakeDao.getById("abs:li1")!!.title)
    }

    @Test fun `logout clears credentials and removes ABS books`() = runBlocking {
        val repo = AbsRepository(source, store, fakeDao)
        repo.sync()
        repo.logout()
        assertEquals(emptyList<BookEntity>(), fakeDao.getByOrigin(BookOrigin.ABS))
        assertEquals(null, store.current())
    }
}
```
Create `FakeBookDao` in test sources implementing `BookDao` (only the methods used: `upsert`, `getById`, `getByOrigin`, `getIdsByOrigin`, `deleteByIds`; throw `NotImplementedError()` for the Flow/observe ones). Model it on `feature:playlists`' `FakePlaylistDao`.

- [ ] **Step 3: Run it — fails** (Expected: compile error — `AbsRepository` missing).

- [ ] **Step 4: Implement `AbsRepository`** (`AbsRepository.kt`) — a **plain class provided via `@Provides`** (NOT `@Inject`). It carries a `deleteFiles` function seam (defaulting to a no-op now; Chunk 5 Task 5.6 wires it to `AbsFileDownloader.deleteFiles` by editing only the provider). This avoids a mid-plan `@Inject`→`@Provides` switch (which would be a duplicate binding):
```kotlin
package com.orator.feature.audiobookshelf.data

import com.orator.core.database.BookDao
import com.orator.core.model.BookOrigin

class AbsRepository(
    private val source: AbsCatalogSource,
    private val store: AbsCredentialStore,
    private val bookDao: BookDao,
    private val deleteFiles: suspend (String) -> Unit = {},
) {
    /** One reconcile pass; no-op when disconnected. */
    suspend fun sync() {
        val cfg = store.current()?.config ?: return
        val incoming = source.libraries(cfg.baseUrl, cfg.token)
            .filter { it.mediaType == null || it.mediaType == "book" }
            .flatMap { lib -> source.items(cfg.baseUrl, lib.id, cfg.token) }
            .map { AbsBookMapper.toBook(it, cfg.serverId, cfg.baseUrl) }
        val existing = bookDao.getByOrigin(BookOrigin.ABS)
        val result = AbsCatalogReconciler.reconcile(existing, incoming)
        bookDao.upsert(result.upserts)
        if (result.deletes.isNotEmpty()) {
            result.deletes.forEach { deleteFiles(it) }      // no-op until Chunk 5 wires it
            bookDao.deleteByIds(result.deletes)
        }
    }

    suspend fun logout() {
        val ids = bookDao.getIdsByOrigin(BookOrigin.ABS)
        if (ids.isNotEmpty()) {
            ids.forEach { deleteFiles(it) }
            bookDao.deleteByIds(ids)
        }
        store.clear()
    }
}
```
Add the provider to `AbsNetworkModule` (no-op `deleteFiles` for now):
```kotlin
@Provides
@Singleton
fun provideAbsRepository(source: AbsCatalogSource, store: AbsCredentialStore, bookDao: BookDao): AbsRepository =
    AbsRepository(source, store, bookDao)
```
(Add `import javax.inject.Singleton`. `AbsCredentialStore` reads/writes are synchronous and safe from a coroutine.)

- [ ] **Step 5: Run the test — passes** (Expected: PASS).

- [ ] **Step 6: Commit**
```bash
git add feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/data/AbsCatalogSource.kt feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/data/AbsApi.kt feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/data/AbsNetworkModule.kt feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/data/AbsRepository.kt feature/audiobookshelf/src/test/java/com/orator/feature/audiobookshelf/data/AbsRepositoryTest.kt feature/audiobookshelf/src/test/java/com/orator/feature/audiobookshelf/data/FakeBookDao.kt
git commit -m "feat(audiobookshelf): AbsRepository sync/logout over catalog source + BookDao"
```

### Task 3.4: Connection state + login flow

**Files:** Create `data/AbsAuthRepository.kt`. Test: `.../data/AbsAuthRepositoryTest.kt`

- [ ] **Step 1: Write the failing test** (fake login source; assert state transitions + token saved + sync invoked):
```kotlin
package com.orator.feature.audiobookshelf.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AbsAuthRepositoryTest {
    private val store = AbsCredentialStore(object : SecureStringStore {
        val m = mutableMapOf<String, String>()
        override fun get(key: String) = m[key]
        override fun put(key: String, value: String) { m[key] = value }
        override fun clear() = m.clear()
    })

    @Test fun `successful login saves token, syncs, and reports Connected`() = runBlocking {
        var synced = false
        val repo = AbsAuthRepository(
            login = { _, _, _ -> AbsUser(id = "u", token = "tok") },
            store = store,
            onConnected = { synced = true },
        )
        repo.login("https://abs.example.com/", "reader", "pw")
        assertEquals("tok", store.current()!!.config.token)
        assertTrue(synced)
        assertTrue(repo.state.first() is AbsConnectionState.Connected)
    }

    @Test fun `failed login reports Error and stores nothing`() = runBlocking {
        val repo = AbsAuthRepository(
            login = { _, _, _ -> throw RuntimeException("nope") },
            store = store,
            onConnected = {},
        )
        repo.login("https://abs.example.com/", "reader", "pw")
        assertTrue(repo.state.first() is AbsConnectionState.Error)
        assertEquals(null, store.current())
    }
}
```

- [ ] **Step 2: Run it — fails** (Expected: compile error).

- [ ] **Step 3: Implement** (`AbsAuthRepository.kt`) — constructor takes function seams so it is unit-testable without `AbsApi`; the Hilt `@Inject` constructor (Chunk 6 binding) adapts `AbsApi`/`AbsRepository`:
```kotlin
package com.orator.feature.audiobookshelf.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AbsAuthRepository(
    private val login: suspend (baseUrl: String, user: String, pass: String) -> AbsUser,
    private val store: AbsCredentialStore,
    private val onConnected: suspend () -> Unit,
) {
    private val _state = MutableStateFlow<AbsConnectionState>(
        store.current()?.let { AbsConnectionState.Connected(it.config) } ?: AbsConnectionState.Disconnected,
    )
    val state: StateFlow<AbsConnectionState> = _state.asStateFlow()

    suspend fun login(baseUrl: String, username: String, password: String) {
        _state.value = AbsConnectionState.Connecting
        runCatching {
            val user = login(baseUrl, username, password)
            val cfg = AbsServerConfig(
                serverId = AbsUrl.serverId(baseUrl),
                baseUrl = baseUrl.trim().trimEnd('/'),
                username = username,
                token = user.token,
            )
            store.save(cfg)
            onConnected()
            cfg
        }.onSuccess { _state.value = AbsConnectionState.Connected(it) }
            .onFailure { _state.value = AbsConnectionState.Error(it.message ?: "Login failed") }
    }
}
```
The Hilt provider (add to `AbsNetworkModule`):
```kotlin
@Provides
@Singleton
fun provideAuthRepository(api: AbsApi, store: AbsCredentialStore, repo: AbsRepository): AbsAuthRepository =
    AbsAuthRepository(
        login = { base, u, p -> api.login(base, u, p) },
        store = store,
        onConnected = { repo.sync() },
    )
```
Add `import javax.inject.Singleton` and `@Singleton` is on the object's provider method.

- [ ] **Step 4: Run the test — passes** (Expected: PASS).

- [ ] **Step 5: Commit**
```bash
git add feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/data/AbsAuthRepository.kt feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/data/AbsNetworkModule.kt feature/audiobookshelf/src/test/java/com/orator/feature/audiobookshelf/data/AbsAuthRepositoryTest.kt
git commit -m "feat(audiobookshelf): login flow + connection state + initial sync"
```

### Chunk 3 gate
- [ ] Run `./gradlew test lint assembleDebug` — Expected: BUILD SUCCESSFUL. Then dispatch chunk review.

---

## Chunk 4: Streaming — player data source + lazy-expand resolver

### Task 4.1: OkHttp-backed media source in `PlaybackService`

**Files:** Modify `core/playback/build.gradle.kts`, `core/playback/.../PlaybackService.kt`. (Device-verified; no clean unit test for ExoPlayer construction.)

- [ ] **Step 1: Add deps to `core/playback/build.gradle.kts`**:
```kotlin
    implementation(project(":core:network"))
    implementation(libs.media3.datasource.okhttp)
```
(Catalog accessor: `libs.media3.datasource.okhttp`.)

- [ ] **Step 2: Wire the data source** in `PlaybackService.onCreate` — inject the client and build the media source factory. Add field:
```kotlin
    @Inject lateinit var okHttpClient: okhttp3.OkHttpClient
```
Replace the player construction line:
```kotlin
        val httpFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okHttpClient)
        val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(this, httpFactory)
        val player = ExoPlayer.Builder(this, silenceTrim.renderersFactory(this))
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory),
            )
            .build()
```
`DefaultDataSource.Factory` routes `http(s)` through OkHttp (interceptor adds bearer) and keeps `file://`/`content://` on the built-in sources.

- [ ] **Step 3: Build** — `./gradlew :core:playback:assembleDebug` (Expected: BUILD SUCCESSFUL).

- [ ] **Step 4: Commit**
```bash
git add core/playback/build.gradle.kts core/playback/src/main/java/com/orator/core/playback/PlaybackService.kt
git commit -m "feat(playback): stream http(s) via shared OkHttp data source (auth-ready)"
```

### Task 4.2: Pure `AbsItemDetailMapper` (expanded item → SourceKind + chapters + stream URLs)

**Files:** Create `data/AbsItemDetailMapper.kt`. Test: `.../data/AbsItemDetailMapperTest.kt`

- [ ] **Step 1: Write the failing test**:
```kotlin
package com.orator.feature.audiobookshelf.data

import com.orator.core.database.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Test

class AbsItemDetailMapperTest {
    @Test fun `single audio file uses internal chapter offsets`() {
        val item = AbsLibraryItem("li1", AbsMedia(
            audioFiles = listOf(AbsAudioFile(ino = "100", index = 1, duration = 60.0)),
            chapters = listOf(
                AbsChapter(start = 0.0, end = 30.0, title = "Ch1"),
                AbsChapter(start = 30.0, end = 60.0, title = "Ch2"),
            ),
        ))
        val d = AbsItemDetailMapper.map(item, baseUrl = "https://abs.example.com")
        assertEquals(SourceKind.SINGLE_FILE, d.sourceKind)
        assertEquals("https://abs.example.com/api/items/li1/file/100", d.sourceUri)
        assertEquals(2, d.chapters.size)
        assertEquals("Ch1", d.chapters[0].title)
        assertEquals("https://abs.example.com/api/items/li1/file/100", d.chapters[0].fileUri)
        assertEquals(0, d.chapters[0].startMs)
        assertEquals(30_000, d.chapters[1].startMs)
    }

    @Test fun `multiple audio files become one chapter per track`() {
        val item = AbsLibraryItem("li2", AbsMedia(
            audioFiles = listOf(
                AbsAudioFile(ino = "1", index = 1, duration = 60.0),
                AbsAudioFile(ino = "2", index = 2, duration = 90.0),
            ),
        ))
        val d = AbsItemDetailMapper.map(item, baseUrl = "https://abs.example.com")
        assertEquals(SourceKind.MULTI_FILE, d.sourceKind)
        assertEquals("https://abs.example.com/api/items/li2/file/1", d.sourceUri)  // first track
        assertEquals(2, d.chapters.size)
        assertEquals("https://abs.example.com/api/items/li2/file/1", d.chapters[0].fileUri)
        assertEquals(0, d.chapters[0].startMs)                                     // each file starts at 0
        assertEquals(60_000, d.chapters[0].durationMs)
        assertEquals("https://abs.example.com/api/items/li2/file/2", d.chapters[1].fileUri)
        assertEquals(0, d.chapters[1].startMs)
    }
}
```

- [ ] **Step 2: Run it — fails** (Expected: compile error).

- [ ] **Step 3: Implement** (`AbsItemDetailMapper.kt`) — produces values the existing `QueueBuilder`/`ChapterTimeline` consume (see `ChapterEntity`: `startMs` is an offset within `fileUri`; for MULTI_FILE each file's first chapter starts at 0):
```kotlin
package com.orator.feature.audiobookshelf.data

import com.orator.core.database.ChapterEntity
import com.orator.core.database.SourceKind

/** Detail derived from an expanded ABS item, ready to persist as a book's playable layout. */
data class AbsBookDetail(
    val sourceKind: SourceKind,
    val sourceUri: String,
    val chapters: List<ChapterEntity>,
)

object AbsItemDetailMapper {
    fun map(item: AbsLibraryItem, baseUrl: String): AbsBookDetail {
        val bookId = "abs:${item.id}"
        val files = item.media.audioFiles.sortedBy { it.index }
        fun url(ino: String) = AbsUrl.endpoint(baseUrl, "api/items/${item.id}/file/$ino")

        return if (files.size <= 1) {
            val ino = files.firstOrNull()?.ino ?: ""
            val uri = url(ino)
            val chapters = item.media.chapters.mapIndexed { i, c ->
                ChapterEntity(
                    bookId = bookId, chapterIndex = i, title = c.title.ifBlank { "Chapter ${i + 1}" },
                    fileUri = uri, startMs = (c.start * 1000).toLong(),
                    durationMs = ((c.end - c.start) * 1000).toLong(),
                )
            }
            AbsBookDetail(SourceKind.SINGLE_FILE, uri, chapters)
        } else {
            val chapters = files.mapIndexed { i, f ->
                ChapterEntity(
                    bookId = bookId, chapterIndex = i, title = "Track ${f.index}",
                    fileUri = url(f.ino), startMs = 0, durationMs = (f.duration * 1000).toLong(),
                )
            }
            AbsBookDetail(SourceKind.MULTI_FILE, url(files.first().ino), chapters)
        }
    }
}
```

- [ ] **Step 4: Run the test — passes** (Expected: PASS).

- [ ] **Step 5: Commit**
```bash
git add feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/data/AbsItemDetailMapper.kt feature/audiobookshelf/src/test/java/com/orator/feature/audiobookshelf/data/AbsItemDetailMapperTest.kt
git commit -m "feat(audiobookshelf): expanded-item → SourceKind/chapters/streamUrl mapper"
```

### Task 4.3: `AbsBookDetailResolver` (idempotent lazy expand)

**Files:** Create `data/AbsBookDetailResolver.kt`. Test: `.../data/AbsBookDetailResolverTest.kt`

- [ ] **Step 1: Write the failing test** (fake `AbsApi`-detail source + fake DAOs; assert it fills blank books and no-ops when populated):
```kotlin
package com.orator.feature.audiobookshelf.data

import com.orator.core.model.BookOrigin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AbsBookDetailResolverTest {
    @Test fun `handles only ABS`() {
        val r = AbsBookDetailResolver(detail = { error("unused") }, store = connectedStore(),
            bookDao = FakeBookDao(), chapterDao = FakeChapterDao())
        assertEquals(true, r.handles(BookOrigin.ABS))
        assertEquals(false, r.handles(BookOrigin.LOCAL))
    }

    @Test fun `ensureDetails fills sourceUri and chapters when blank, then is a no-op`() = runBlocking {
        val books = FakeBookDao().apply { upsert(listOf(absBook("abs:li1", sourceUri = ""))) }
        val chapters = FakeChapterDao()
        var calls = 0
        val r = AbsBookDetailResolver(
            detail = { _, _ ->
                calls++
                AbsItemDetailMapper.map(
                    AbsLibraryItem("li1", AbsMedia(audioFiles = listOf(AbsAudioFile("100", 1, 60.0)))),
                    "https://abs.example.com",
                )
            },
            store = connectedStore(), bookDao = books, chapterDao = chapters,
        )
        r.ensureDetails("abs:li1")
        assertNotEquals("", books.getById("abs:li1")!!.sourceUri)
        assertEquals(1, chapters.getForBook("abs:li1").size)

        r.ensureDetails("abs:li1")       // already populated
        assertEquals(1, calls)            // network not hit again
    }
}
```
(`absBook(...)`, `connectedStore()`, `FakeChapterDao` are small test helpers; model `FakeChapterDao` on `FakeBookDao`.)

- [ ] **Step 2: Run it — fails** (Expected: compile error).

- [ ] **Step 2b: Add chapter-replace to `ChapterDao`** — the real DAO (`core/database/.../ChapterDao.kt`) has ONLY `upsertAll`, `getForBook`, `observeForBook`. Add a delete query plus a default-method replace. **Do not use `@Transaction`** (that would force converting the `interface` to an `abstract class`); replacing one book's chapters need not be atomic. Add inside `interface ChapterDao`:
```kotlin
    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: String)

    /** Default DAO method (Room supports these on interfaces): swap a book's chapters wholesale. */
    suspend fun replaceForBook(bookId: String, chapters: List<ChapterEntity>) {
        deleteForBook(bookId)
        upsertAll(chapters)
    }
```
(`ChapterEntity` does not need importing — it is in the same package.)

- [ ] **Step 3: Implement** (`AbsBookDetailResolver.kt`) — a **plain class, NOT `@Inject`**: the `detail` function param is supplied by a `@Provides` in Chunk 6 (an `@Inject` constructor with a function-type param is unsatisfiable and, combined with the Chunk-6 `@Provides`, would be a duplicate binding):
```kotlin
package com.orator.feature.audiobookshelf.data

import com.orator.core.database.BookDao
import com.orator.core.database.ChapterDao
import com.orator.core.model.BookDetailResolver
import com.orator.core.model.BookOrigin

class AbsBookDetailResolver(
    private val detail: suspend (baseUrl: String, itemId: String) -> AbsBookDetail,
    private val store: AbsCredentialStore,
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
) : BookDetailResolver {

    override fun handles(origin: BookOrigin) = origin == BookOrigin.ABS

    override suspend fun ensureDetails(bookId: String) {
        val book = bookDao.getById(bookId) ?: return
        if (book.sourceUri.isNotBlank()) return            // already expanded or downloaded
        val cfg = store.current()?.config ?: return
        val itemId = book.absItemId ?: return
        val d = detail(cfg.baseUrl, itemId)
        chapterDao.replaceForBook(bookId, d.chapters)
        bookDao.upsert(listOf(book.copy(sourceKind = d.sourceKind, sourceUri = d.sourceUri)))
    }
}
```
The `detail` lambda is wired to `AbsApi.getItemExpanded` + `AbsItemDetailMapper` via the Chunk-6 `@Provides provideBookDetailResolver`.

**`FakeChapterDao` (test source)** must override **all** `ChapterDao` members — `upsertAll`, `getForBook` (returning rows **sorted by `chapterIndex`**), `observeForBook` (may `throw NotImplementedError()`), `deleteForBook`, and `replaceForBook` (it is a default method, but override it to call the fake's own delete+upsert so the test is in-memory). Model it on `feature:playlists`' `FakePlaylistDao`.

- [ ] **Step 4: Run the test — passes** (Expected: PASS).

- [ ] **Step 5: Commit**
```bash
git add core/database/src/main/java/com/orator/core/database/ChapterDao.kt feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/data/AbsBookDetailResolver.kt feature/audiobookshelf/src/test/java/com/orator/feature/audiobookshelf/data/AbsBookDetailResolverTest.kt feature/audiobookshelf/src/test/java/com/orator/feature/audiobookshelf/data/FakeChapterDao.kt
git commit -m "feat(audiobookshelf): idempotent ABS BookDetailResolver (lazy expand) + ChapterDao.replaceForBook"
```

### Task 4.4: Call resolvers from `AudiobookPlayRequestFactory`

**Files:** Modify `feature/audiobooks/.../AudiobookPlayRequestFactory.kt`. Test: `feature/audiobooks/src/test/java/com/orator/feature/audiobooks/data/AudiobookPlayRequestResolverTest.kt`

- [ ] **Step 1: Write the failing test** — assert the factory invokes a matching resolver before building (fake resolver records the call; fake DAOs supply a book):
```kotlin
package com.orator.feature.audiobooks.data

import com.orator.core.model.BookDetailResolver
import com.orator.core.model.BookOrigin
import com.orator.core.model.MediaRef
import com.orator.core.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiobookPlayRequestResolverTest {
    @Test fun `create calls the resolver that handles the book origin`() = runBlocking {
        val books = FakeBookDao().apply { upsert(listOf(absBook("abs:1"))) }   // origin=ABS, sourceUri set
        var resolved: String? = null
        val resolver = object : BookDetailResolver {
            override fun handles(origin: BookOrigin) = origin == BookOrigin.ABS
            override suspend fun ensureDetails(bookId: String) { resolved = bookId }
        }
        val factory = AudiobookPlayRequestFactory(books, FakeChapterDao(), setOf(resolver))
        val req = factory.create(MediaRef(MediaType.AUDIOBOOK, "abs:1"))
        assertEquals("abs:1", resolved)
        assertTrue(req != null)
    }
}
```
**Test fakes do not exist in `feature:audiobooks` yet** (the `feature:audiobookshelf` fakes are in a different module's test source set and are NOT visible here). Create, in `feature/audiobooks/src/test/java/com/orator/feature/audiobooks/data/`:
- `FakeBookDao.kt` — implements `BookDao`, overriding **every** member (it is an interface): back `upsert`/`getById`/`getByOrigin`/`getIdsByOrigin`/`deleteByIds`/`updateProgress`/`updateSpeedOverride`/`getAllIds` with an in-memory `MutableMap<String, BookEntity>`; `observeAll`/`observeById` may `throw NotImplementedError()`. Model on `feature:playlists`' `FakePlaylistDao`.
- `FakeChapterDao.kt` — implements `ChapterDao`, overriding all members; `getForBook` returns rows sorted by `chapterIndex`.
- `AudiobookTestFixtures.kt` — `fun absBook(id: String, sourceUri: String = "content://x") = BookEntity(id=id, title=id, author=null, coverPath=null, sourceUri=sourceUri, sourceKind=SourceKind.SINGLE_FILE, durationMs=0, addedAtUtc=0, origin=BookOrigin.ABS, absItemId=id.removePrefix("abs:"))` (non-blank `sourceUri` so `QueueBuilder` succeeds without a resolver round-trip).

- [ ] **Step 2: Run it — fails** — `./gradlew :feature:audiobooks:testDebugUnitTest --tests "*AudiobookPlayRequestResolverTest*"` (Expected: compile error — constructor has no resolver set, fakes missing).

- [ ] **Step 3: Modify `AudiobookPlayRequestFactory`** — inject the resolver set and call it:
```kotlin
class AudiobookPlayRequestFactory @Inject constructor(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val detailResolvers: Set<@JvmSuppressWildcards BookDetailResolver>,
) : PlayRequestFactory {
    override val mediaType = MediaType.AUDIOBOOK

    override suspend fun create(ref: MediaRef): PlayRequest? {
        val book = bookDao.getById(ref.id) ?: return null
        detailResolvers.firstOrNull { it.handles(book.origin) }?.ensureDetails(book.id)
        val fresh = bookDao.getById(ref.id) ?: return null
        val chapters = chapterDao.getForBook(ref.id)
        return QueueBuilder.build(fresh, chapters, startAtMs = fresh.positionMs)
    }
}
```
Add imports `com.orator.core.model.BookDetailResolver`. Because Hilt's `Set<BookDetailResolver>` may be empty (ABS module absent), local books (no matching resolver) are unaffected.

- [ ] **Step 4: Confirm `feature:audiobooks` sees an (empty) multibinding** — add a `@Multibinds` for `BookDetailResolver` so injection succeeds with zero contributors. In `AudiobooksFeatureModule` (convert from `interface` is unnecessary — add a sibling abstract module) create `feature/audiobooks/.../AudiobookSeamsModule.kt`:
```kotlin
package com.orator.feature.audiobooks

import com.orator.core.model.BookDetailResolver
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

@Module
@InstallIn(SingletonComponent::class)
abstract class AudiobookSeamsModule {
    @Multibinds abstract fun bookDetailResolvers(): Set<BookDetailResolver>
}
```
**Cross-chunk contract:** `@Multibinds` for a given set may be declared **exactly once** in the whole app graph. This `AudiobookSeamsModule` is the sole declarer of `Set<BookDetailResolver>` (and, in Chunk 6, `Set<BookDownloadController>`). `feature:audiobookshelf` must contribute **only** via `@Binds @IntoSet` / `@Provides @IntoSet` and must **never** re-declare `@Multibinds`, or the build fails with a duplicate-multibinding error.

- [ ] **Step 5: Run the test — passes** (Expected: PASS). Then run the whole audiobooks suite to catch regressions: `./gradlew :feature:audiobooks:testDebugUnitTest`.

- [ ] **Step 6: Commit**
```bash
git add feature/audiobooks/src/main/java/com/orator/feature/audiobooks/data/AudiobookPlayRequestFactory.kt feature/audiobooks/src/main/java/com/orator/feature/audiobooks/AudiobookSeamsModule.kt feature/audiobooks/src/test/java/com/orator/feature/audiobooks/data/AudiobookPlayRequestResolverTest.kt feature/audiobooks/src/test/java/com/orator/feature/audiobooks/data/FakeBookDao.kt feature/audiobooks/src/test/java/com/orator/feature/audiobooks/data/FakeChapterDao.kt feature/audiobooks/src/test/java/com/orator/feature/audiobooks/data/AudiobookTestFixtures.kt
git commit -m "feat(audiobooks): resolve lazy book detail before building the play request"
```

### Chunk 4 gate
- [ ] Run `./gradlew test lint assembleDebug` — Expected: BUILD SUCCESSFUL. Then dispatch chunk review.

---

## Chunk 5: Offline download (SAF)

### Task 5.1: `AbsPrefs` (download folder)

**Files:** Create `data/AbsPrefs.kt`. (Mirrors `AudiobooksPrefs`; trivial — covered by the chunk gate, no separate unit test.)

- [ ] **Step 1: Implement**:
```kotlin
package com.orator.feature.audiobookshelf.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.absDataStore by preferencesDataStore(name = "audiobookshelf")
private val KEY_DOWNLOAD_TREE = stringPreferencesKey("download_tree_uri")

@Singleton
class AbsPrefs @Inject constructor(@ApplicationContext private val context: Context) {
    val downloadTreeUri: Flow<String?> = context.absDataStore.data.map { it[KEY_DOWNLOAD_TREE] }
    suspend fun setDownloadTreeUri(uri: String) {
        context.absDataStore.edit { it[KEY_DOWNLOAD_TREE] = uri }
    }
    suspend fun downloadTreeUriNow(): String? =
        context.absDataStore.data.map { it[KEY_DOWNLOAD_TREE] }.let {
            kotlinx.coroutines.flow.first(it)
        }
}
```
(If `first` import is awkward, use `data.first()[KEY_DOWNLOAD_TREE]` with `import kotlinx.coroutines.flow.first`.)

- [ ] **Step 2: Build** — `./gradlew :feature:audiobookshelf:compileDebugKotlin` (Expected: SUCCESSFUL).

- [ ] **Step 3: Commit**
```bash
git add feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/data/AbsPrefs.kt
git commit -m "feat(audiobookshelf): AbsPrefs (download SAF tree uri)"
```

### Task 5.2: Pure download plan (track files + sourceUri/chapter rewrite)

**Files:** Create `data/AbsDownloadPlan.kt`. Test: `.../data/AbsDownloadPlanTest.kt`

The download worker writes one local file per remote track and then rewrites the book's `sourceUri` + each `ChapterEntity.fileUri` from remote URLs to the new `content://` URIs. The mapping (which remote URL → which local file name, and how to rewrite chapters) is pure and testable.

- [ ] **Step 1: Write the failing test**:
```kotlin
package com.orator.feature.audiobookshelf.data

import com.orator.core.database.ChapterEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class AbsDownloadPlanTest {
    private fun ch(idx: Int, uri: String) =
        ChapterEntity("abs:1", idx, "t$idx", uri, 0, 1000)

    @Test fun `distinct remote file uris get stable sequential names`() {
        val chapters = listOf(ch(0, "https://s/api/items/1/file/100"), ch(1, "https://s/api/items/1/file/100"),
            ch(2, "https://s/api/items/1/file/200"))
        val plan = AbsDownloadPlan.from(sourceUri = "https://s/api/items/1/file/100", chapters = chapters)
        assertEquals(2, plan.files.size)                          // two distinct remote files
        assertEquals("track-000", plan.files[0].localName)
        assertEquals("https://s/api/items/1/file/100", plan.files[0].remoteUrl)
        assertEquals("track-001", plan.files[1].localName)
    }

    @Test fun `rewrite maps remote uris to local content uris`() {
        val chapters = listOf(ch(0, "https://s/api/items/1/file/100"), ch(1, "https://s/api/items/1/file/200"))
        val plan = AbsDownloadPlan.from("https://s/api/items/1/file/100", chapters)
        val local = mapOf(
            "https://s/api/items/1/file/100" to "content://tree/abs-1/track-000",
            "https://s/api/items/1/file/200" to "content://tree/abs-1/track-001",
        )
        val rewrite = plan.rewrite(chapters, "https://s/api/items/1/file/100", local)
        assertEquals("content://tree/abs-1/track-000", rewrite.sourceUri)
        assertEquals("content://tree/abs-1/track-000", rewrite.chapters[0].fileUri)
        assertEquals("content://tree/abs-1/track-001", rewrite.chapters[1].fileUri)
    }
}
```

- [ ] **Step 2: Run it — fails** (Expected: compile error).

- [ ] **Step 3: Implement** (`AbsDownloadPlan.kt`):
```kotlin
package com.orator.feature.audiobookshelf.data

import com.orator.core.database.ChapterEntity

data class RemoteFile(val remoteUrl: String, val localName: String)
data class RewriteResult(val sourceUri: String, val chapters: List<ChapterEntity>)

/** Pure plan: distinct remote files to fetch, plus how to rewrite the book once they are local. */
data class AbsDownloadPlan(val files: List<RemoteFile>) {
    fun rewrite(
        chapters: List<ChapterEntity>,
        sourceUri: String,
        localByRemote: Map<String, String>,
    ): RewriteResult = RewriteResult(
        sourceUri = localByRemote[sourceUri] ?: sourceUri,
        chapters = chapters.map { it.copy(fileUri = localByRemote[it.fileUri] ?: it.fileUri) },
    )

    companion object {
        fun from(sourceUri: String, chapters: List<ChapterEntity>): AbsDownloadPlan {
            val distinct = (listOf(sourceUri) + chapters.map { it.fileUri }).distinct()
            return AbsDownloadPlan(
                distinct.mapIndexed { i, url -> RemoteFile(url, "track-%03d".format(i)) },
            )
        }
    }
}
```

- [ ] **Step 4: Run the test — passes** (Expected: PASS).

- [ ] **Step 5: Commit**
```bash
git add feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/data/AbsDownloadPlan.kt feature/audiobookshelf/src/test/java/com/orator/feature/audiobookshelf/data/AbsDownloadPlanTest.kt
git commit -m "feat(audiobookshelf): pure download plan (file naming + uri rewrite)"
```

### Task 5.3: `AbsFileDownloader` (SAF write, authed OkHttp, .partial→rename)

**Files:** Create `data/AbsFileDownloader.kt`. (SAF byte-writing is device-only; verified in Chunk 6. No unit test — the testable mapping is Task 5.2.)

- [ ] **Step 1: Implement** — closely follows `feature:podcasts` `EpisodeDownloader` (streams an authed OkHttp body into a `.partial` `DocumentFile`, renames on success). The download GET carries the bearer via the interceptor (ABS host) — pass the shared `OkHttpClient`:
```kotlin
package com.orator.feature.audiobookshelf.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.orator.core.database.BookDao
import com.orator.core.database.ChapterDao
import com.orator.core.model.DownloadState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

/**
 * Downloads an ABS book's audio tracks into the user's SAF download folder, then rewrites the book
 * to play from the local content:// URIs. Mirrors EpisodeDownloader's .partial→rename discipline so
 * an interrupted download never masquerades as complete.
 */
class AbsFileDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val prefs: AbsPrefs,
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val detailResolver: AbsBookDetailResolver,
) {
    /** @return true on success. Caller (worker) manages DOWNLOADING/NONE state transitions. */
    suspend fun download(bookId: String): Boolean = withContext(Dispatchers.IO) {
        detailResolver.ensureDetails(bookId)                       // guarantees sourceUri + chapters
        val book = bookDao.getById(bookId) ?: return@withContext false
        val chapters = chapterDao.getForBook(bookId)
        val treeUri = prefs.downloadTreeUriNow() ?: return@withContext false
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return@withContext false
        val bookDir = tree.findFile("abs-$bookId")?.takeIf { it.isDirectory }
            ?: tree.createDirectory("abs-$bookId") ?: return@withContext false

        val plan = AbsDownloadPlan.from(book.sourceUri, chapters)
        val localByRemote = mutableMapOf<String, String>()
        for (file in plan.files) {
            val dest = downloadOne(file.remoteUrl, bookDir, file.localName) ?: return@withContext false
            localByRemote[file.remoteUrl] = dest
        }
        val rewrite = plan.rewrite(chapters, book.sourceUri, localByRemote)
        chapterDao.replaceForBook(bookId, rewrite.chapters)
        bookDao.upsert(listOf(book.copy(sourceUri = rewrite.sourceUri, downloadState = DownloadState.DOWNLOADED)))
        true
    }

    private fun downloadOne(url: String, dir: DocumentFile, name: String): String? {
        dir.findFile("$name.partial")?.delete()
        val partial = dir.createFile("application/octet-stream", "$name.partial") ?: return null
        return try {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) { partial.delete(); return null }
                context.contentResolver.openOutputStream(partial.uri, "wt")!!.use { out ->
                    resp.body!!.byteStream().use { it.copyTo(out, 64 * 1024) }
                }
            }
            if (!partial.renameTo(name)) { partial.delete(); return null }
            dir.findFile(name)?.uri?.toString()
        } catch (e: Exception) {
            partial.delete(); null
        }
    }

    suspend fun deleteFiles(bookId: String) = withContext(Dispatchers.IO) {
        val book = bookDao.getById(bookId) ?: return@withContext
        val uris = (listOf(book.sourceUri) + chapterDao.getForBook(bookId).map { it.fileUri })
            .filter { it.startsWith("content://") }.distinct()
        uris.forEach { runCatching { DocumentFile.fromSingleUri(context, Uri.parse(it))?.delete() } }
    }
}
```

- [ ] **Step 2: Build** — `./gradlew :feature:audiobookshelf:compileDebugKotlin` (Expected: SUCCESSFUL).

- [ ] **Step 3: Commit**
```bash
git add feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/data/AbsFileDownloader.kt
git commit -m "feat(audiobookshelf): SAF file downloader (.partial→rename, authed)"
```

### Task 5.4: `AbsDownloadWorker` (`@HiltWorker`)

**Files:** Create `work/AbsDownloadWorker.kt`. Test: `.../work/AbsDownloadWorkerTest.kt` (enqueue/unique-work via `WorkManagerTestInitHelper`, modeled on `RefreshSchedulerTest`).

- [ ] **Step 1: Write the failing test** (verifies the manager enqueues unique work — see Task 5.5 for `AbsDownloadManager`; write this test after 5.5's manager exists, or stub the enqueue here). Minimal worker-state test:
```kotlin
package com.orator.feature.audiobookshelf.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.orator.feature.audiobookshelf.data.AbsDownloadManager
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AbsDownloadWorkerTest {
    private lateinit var context: Context
    private lateinit var wm: WorkManager

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        wm = WorkManager.getInstance(context)
    }

    @Test fun `enqueue schedules one unique work per book`() {
        val manager = AbsDownloadManager(context)
        manager.enqueue("abs:1")
        manager.enqueue("abs:1")  // re-tap: REPLACE/KEEP keeps a single entry
        assertEquals(1, wm.getWorkInfosForUniqueWork("abs-download-abs:1").get().size)
    }
}
```

- [ ] **Step 2: Run it — fails** (Expected: compile error).

- [ ] **Step 3: Implement the worker** (`AbsDownloadWorker.kt`) — foreground, delegates to `AbsFileDownloader`:
```kotlin
package com.orator.feature.audiobookshelf.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.orator.core.database.BookDao
import com.orator.core.model.DownloadState
import com.orator.feature.audiobookshelf.data.AbsFileDownloader
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class AbsDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val downloader: AbsFileDownloader,
    private val bookDao: BookDao,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val bookId = inputData.getString(KEY_BOOK_ID) ?: return Result.failure()
        bookDao.getById(bookId)?.let {
            bookDao.upsert(listOf(it.copy(downloadState = DownloadState.DOWNLOADING)))
        }
        val ok = runCatching { downloader.download(bookId) }.getOrDefault(false)
        if (!ok) {
            bookDao.getById(bookId)?.let {
                bookDao.upsert(listOf(it.copy(downloadState = DownloadState.NONE)))
            }
            return Result.retry()
        }
        return Result.success()
    }

    companion object { const val KEY_BOOK_ID = "book_id" }
}
```
(Foreground notification via `getForegroundInfo` can be added in Chunk 6/device pass; functionally optional for the unit gate.)

- [ ] **Step 4: Implement `AbsDownloadManager.enqueue` minimal** so the test compiles (full manager in 5.5):
```kotlin
// AbsDownloadManager.kt (initial — extended in 5.5)
package com.orator.feature.audiobookshelf.data

import android.content.Context
import androidx.work.*
import com.orator.feature.audiobookshelf.work.AbsDownloadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AbsDownloadManager @Inject constructor(@ApplicationContext private val context: Context) {
    fun enqueue(bookId: String) {
        val req = OneTimeWorkRequestBuilder<AbsDownloadWorker>()
            .setInputData(workDataOf(AbsDownloadWorker.KEY_BOOK_ID to bookId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("abs-download-$bookId", ExistingWorkPolicy.KEEP, req)
    }
    fun cancel(bookId: String) {
        WorkManager.getInstance(context).cancelUniqueWork("abs-download-$bookId")
    }
}
```

- [ ] **Step 5: Run the test — passes** (Expected: PASS).

- [ ] **Step 6: Commit**
```bash
git add feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/work/AbsDownloadWorker.kt feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/data/AbsDownloadManager.kt feature/audiobookshelf/src/test/java/com/orator/feature/audiobookshelf/work/AbsDownloadWorkerTest.kt
git commit -m "feat(audiobookshelf): AbsDownloadWorker + unique-work enqueue"
```

### Task 5.5: `AbsDownloadController` seam (remove reverts to stream)

**Files:** Modify `data/AbsFileDownloader.kt` (add `removeDownload`); create `data/AbsDownloadController.kt`. Test: `.../data/AbsDownloadControllerTest.kt`

> **Why not put `remove` on `AbsDownloadManager`?** Expanding `AbsDownloadManager`'s constructor would break Task 5.4's `AbsDownloadManager(context)` test (and the Chunk-5 gate). `AbsDownloadManager` stays **context-only** (WorkManager `enqueue`/`cancel`); the file-deletion + DB-revert lives on `AbsFileDownloader`, which already holds `context`/`bookDao`/`chapterDao`.

- [ ] **Step 1: Write the failing test** — remove deletes files + chapters and reverts the book to stream-only:
```kotlin
package com.orator.feature.audiobookshelf.data

import com.orator.core.model.BookOrigin
import com.orator.core.model.DownloadState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AbsDownloadControllerTest {
    @Test fun `remove clears chapters, blanks sourceUri, sets NONE`() = runBlocking {
        val books = FakeBookDao().apply {
            upsert(listOf(absBook("abs:1", sourceUri = "content://x", dl = DownloadState.DOWNLOADED)))
        }
        val chapters = FakeChapterDao().apply { replaceForBook("abs:1", listOf(chapter("abs:1", 0, "content://x"))) }
        val controller = AbsDownloadController(
            handlesOrigin = BookOrigin.ABS,
            enqueueFn = {}, cancelFn = {},
            removeFn = { id ->
                // simulate file deletion no-op in test
                chapters.replaceForBook(id, emptyList())
                val b = books.getById(id)!!
                books.upsert(listOf(b.copy(sourceUri = "", downloadState = DownloadState.NONE)))
            },
        )
        controller.remove("abs:1")
        assertEquals("", books.getById("abs:1")!!.sourceUri)
        assertEquals(DownloadState.NONE, books.getById("abs:1")!!.downloadState)
        assertEquals(0, chapters.getForBook("abs:1").size)
    }
}
```
(Helper `chapter(...)` builds a `ChapterEntity`.)

- [ ] **Step 2: Run it — fails** (Expected: compile error).

- [ ] **Step 3: Add `removeDownload` to `AbsFileDownloader`** (deletes files, clears chapters, reverts the row to stream-only). `AbsFileDownloader` already injects `context`/`bookDao`/`chapterDao`, so no constructor change:
```kotlin
    suspend fun removeDownload(bookId: String) = withContext(Dispatchers.IO) {
        deleteFiles(bookId)
        chapterDao.replaceForBook(bookId, emptyList())
        bookDao.getById(bookId)?.let {
            bookDao.upsert(listOf(it.copy(sourceUri = "", downloadState = DownloadState.NONE)))
        }
    }
```
(`DownloadState` is already imported in `AbsFileDownloader`.) The WorkManager `cancel` is handled by the controller's `removeFn` (Chunk 6 provider: `{ manager.cancel(it); downloader.removeDownload(it) }`).

- [ ] **Step 4: Implement the seam** (`AbsDownloadController.kt`) — bridges `BookDownloadController` to the manager (constructor-injectable for the test; Hilt provider in Chunk 6):
```kotlin
package com.orator.feature.audiobookshelf.data

import com.orator.core.model.BookDownloadController
import com.orator.core.model.BookOrigin

class AbsDownloadController(
    private val handlesOrigin: BookOrigin,
    private val enqueueFn: (String) -> Unit,
    private val cancelFn: (String) -> Unit,
    private val removeFn: suspend (String) -> Unit,
) : BookDownloadController {
    override fun handles(origin: BookOrigin) = origin == handlesOrigin
    override fun enqueue(bookId: String) = enqueueFn(bookId)
    override fun cancel(bookId: String) = cancelFn(bookId)
    override suspend fun remove(bookId: String) = removeFn(bookId)
}
```
Hilt provider (Chunk 6, in `AudiobookshelfFeatureModule`) wires `enqueueFn = manager::enqueue`, etc., with `handlesOrigin = BookOrigin.ABS`.

- [ ] **Step 5: Run the test — passes** (Expected: PASS).

- [ ] **Step 6: Commit**
```bash
git add feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/data/AbsFileDownloader.kt feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/data/AbsDownloadController.kt feature/audiobookshelf/src/test/java/com/orator/feature/audiobookshelf/data/AbsDownloadControllerTest.kt
git commit -m "feat(audiobookshelf): download remove reverts to stream; BookDownloadController seam"
```

### Task 5.6: Wire real file cleanup into `AbsRepository`

**Files:** Modify `data/AbsNetworkModule.kt` (provider now injects `AbsFileDownloader`). Test: extend `AbsRepositoryTest`.

`AbsRepository` already *calls* `deleteFiles` for stale rows (`sync`) and all ABS rows (`logout`) — the seam was front-loaded in Task 3.3 with a no-op default. This task only swaps the no-op for the real downloader in the Hilt provider and locks the behavior with a test.

- [ ] **Step 1: Write the test** (the `deleteFiles` seam already exists, so this is a behavior-locking test that passes — construct the repo with a recording lambda):
```kotlin
    @Test fun `logout deletes downloaded files for abs books`() = runBlocking {
        val deleted = mutableListOf<String>()
        val repo = AbsRepository(source, store, fakeDao, deleteFiles = { deleted += it })
        repo.sync()
        repo.logout()
        assertEquals(listOf("abs:li1"), deleted)
    }
```

- [ ] **Step 2: Run it — passes** — `./gradlew :feature:audiobookshelf:testDebugUnitTest --tests "*AbsRepositoryTest*"` (Expected: PASS — the seam is invoked already).

- [ ] **Step 3: Wire the real downloader** — replace the no-op provider from Task 3.3 in `AbsNetworkModule`:
```kotlin
@Provides
@Singleton
fun provideAbsRepository(
    source: AbsCatalogSource,
    store: AbsCredentialStore,
    bookDao: BookDao,
    downloader: AbsFileDownloader,
): AbsRepository = AbsRepository(source, store, bookDao, deleteFiles = { downloader.deleteFiles(it) })
```
(There must be exactly ONE `provideAbsRepository` — edit the existing one, do not add a second.)

- [ ] **Step 4: Build** — `./gradlew :feature:audiobookshelf:assembleDebug` (Expected: SUCCESSFUL).

- [ ] **Step 5: Commit**
```bash
git add feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/data/AbsNetworkModule.kt feature/audiobookshelf/src/test/java/com/orator/feature/audiobookshelf/data/AbsRepositoryTest.kt
git commit -m "feat(audiobookshelf): wire real SAF file deletion into stale-sync + logout"
```

### Chunk 5 gate
- [ ] Run `./gradlew test lint assembleDebug` — Expected: BUILD SUCCESSFUL. Then dispatch chunk review.

---

## Chunk 6: UI, app wiring, device verification

### Task 6.1: `AudiobookshelfFeatureModule` (bind all seams) + detail-resolver provider

**Files:** Create `AudiobookshelfFeatureModule.kt`.

- [ ] **Step 1: Implement the bindings module**:
```kotlin
package com.orator.feature.audiobookshelf

import com.orator.core.designsystem.contract.SettingsSection
import com.orator.core.model.BookDetailResolver
import com.orator.core.model.BookDownloadController
import com.orator.feature.audiobookshelf.data.AbsAuthInterceptor
import com.orator.feature.audiobookshelf.data.AbsBookDetailResolver
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import okhttp3.Interceptor

@Module
@InstallIn(SingletonComponent::class)
interface AudiobookshelfFeatureModule {
    @Binds @IntoSet fun bindAuthInterceptor(i: AbsAuthInterceptor): Interceptor
    @Binds @IntoSet fun bindDetailResolver(r: AbsBookDetailResolver): BookDetailResolver
    @Binds @IntoSet fun bindSettingsSection(s: AudiobookshelfSettingsSection): SettingsSection
}
```

- [ ] **Step 2: Provide `AbsBookDetailResolver`'s `detail` lambda + the `BookDownloadController`** in `AbsNetworkModule` (object module for the function/constructor providers):
```kotlin
@Provides
@Singleton
fun provideBookDetailResolver(
    api: AbsApi, store: AbsCredentialStore, bookDao: BookDao, chapterDao: ChapterDao,
): AbsBookDetailResolver =
    AbsBookDetailResolver(
        detail = { base, itemId ->
            AbsItemDetailMapper.map(api.getItemExpanded(base, itemId, store.current()?.config?.token ?: ""), base)
        },
        store = store, bookDao = bookDao, chapterDao = chapterDao,
    )

@Provides @IntoSet
fun provideDownloadController(
    manager: AbsDownloadManager, downloader: AbsFileDownloader,
): BookDownloadController =
    AbsDownloadController(
        handlesOrigin = BookOrigin.ABS,
        enqueueFn = manager::enqueue,
        cancelFn = manager::cancel,
        removeFn = { manager.cancel(it); downloader.removeDownload(it) },
    )
```
This is the single binding for `AbsBookDetailResolver` (consumed by both the `@Binds @IntoSet ... BookDetailResolver` above and `AbsFileDownloader`'s `@Inject` constructor — one binding, no duplicate). `AbsBookDetailResolver` and `AbsRepository` are plain classes (no `@Inject`); they exist **only** as these `@Provides`. Add Dagger imports (`@Provides`, `@IntoSet`, `javax.inject.Singleton`) and `com.orator.core.model.BookOrigin`.

- [ ] **Step 3: Add a `@Multibinds Set<BookDownloadController>` in `feature:audiobooks`** (so the list can inject it even if ABS is absent) — extend `AudiobookSeamsModule`:
```kotlin
    @Multibinds abstract fun bookDownloadControllers(): Set<BookDownloadController>
```

- [ ] **Step 4: Build** — `./gradlew :feature:audiobookshelf:assembleDebug` (Expected: SUCCESSFUL).

- [ ] **Step 5: Commit**
```bash
git add feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/AudiobookshelfFeatureModule.kt feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/data/AbsNetworkModule.kt feature/audiobooks/src/main/java/com/orator/feature/audiobooks/AudiobookSeamsModule.kt
git commit -m "feat(audiobookshelf): Hilt bindings for interceptor/resolver/controller/settings"
```

### Task 6.2: `AudiobookshelfSettingsSection` (connect / refresh / logout)

**Files:** Create `AudiobookshelfSettingsSection.kt`. (UI; verified on device.)

- [ ] **Step 1: Implement** the section + ViewModel, modeled on `AudiobooksSettingsSection` + `PodcastsSettingsSection`. `order = 30` (after Audiobooks `20`), `title = "Audiobookshelf"`. Inject `AbsAuthRepository` + `AbsRepository` via a `@HiltViewModel`; observe `AbsAuthRepository.state` as `AbsConnectionState`.
  - **`SettingsRow` has no text-input affordance** (glyph/label/value/onClick only). When **disconnected**: show a "Connect to server" `SettingsRow` whose `onClick` opens an `AlertDialog` containing three `OutlinedTextField`s (URL, username, password — password with `PasswordVisualTransformation`) and a Connect button that calls `viewModel.onConnect(url, user, pass)` → `AbsAuthRepository.login` in `viewModelScope`. Reflect `Connecting`/`Error` in the dialog or row `value`.
  - When **connected** (`AbsConnectionState.Connected`): show the server URL as the row `value`, a "Refresh library" `SettingsRow` (`onClick` → `AbsRepository.sync` in `viewModelScope`), and a "Log out" `SettingsRow` (`onClick` → `AbsRepository.logout`).

- [ ] **Step 2: Build** — `./gradlew :feature:audiobookshelf:assembleDebug` (Expected: SUCCESSFUL).

- [ ] **Step 3: Commit**
```bash
git add feature/audiobookshelf/src/main/java/com/orator/feature/audiobookshelf/AudiobookshelfSettingsSection.kt
git commit -m "feat(audiobookshelf): settings section (connect/refresh/logout)"
```

### Task 6.3: ABS download affordance in the Audiobooks list

**Files:** Modify `feature/audiobooks/.../AudiobookListViewModel.kt`, `feature/audiobooks/.../AudiobookListScreen.kt`. (UI; verified on device.)

- [ ] **Step 1: Fix the cover model for ABS books** — `AudiobookListScreen.kt:84` currently does `artworkModel = book.coverPath?.let(::File)`. For ABS books `coverPath` is an `https://…/cover` **URL**, and wrapping a URL in `java.io.File` makes Coil fail (covers fall back to initials). Coil accepts a raw URL `String`, a `File`, or a content URI, so branch on origin:
```kotlin
artworkModel = book.coverPath?.let { path ->
    if (book.origin == com.orator.core.model.BookOrigin.ABS) path else java.io.File(path)
},
```
(The shared `OkHttpClient` + `AbsAuthInterceptor` authenticate the cover request for the ABS host.)

- [ ] **Step 2: Inject `Set<BookDownloadController>`** into `AudiobookListViewModel`; expose `onDownload(book)`/`onRemoveDownload(book)` that pick `controllers.firstOrNull { it.handles(book.origin) }` and call `enqueue` / `remove` (wrap `remove` in `viewModelScope.launch` — it is `suspend`). The list already observes `BookDao`, so `origin`/`downloadState` are on each row.

- [ ] **Step 3: Show the affordance** in `AudiobookListScreen` only when `book.origin == BookOrigin.ABS`: download icon when `downloadState == NONE`, a spinner/label when `DOWNLOADING`, a "downloaded" check + remove action when `DOWNLOADED`. **`CoverTile` has no trailing slot** (only `onClick`/`onLongClick`) — render the affordance as a small overlay `Box` aligned to a corner of the tile (wrap `CoverTile` in a `Box` and place an `IconButton` with `Modifier.align(Alignment.TopEnd)`), rather than changing `CoverTile`'s signature.

- [ ] **Step 4: Build + run the audiobooks suite** — `./gradlew :feature:audiobooks:testDebugUnitTest :feature:audiobooks:assembleDebug` (Expected: SUCCESSFUL; no regressions).

- [ ] **Step 5: Commit**
```bash
git add feature/audiobooks/src/main/java/com/orator/feature/audiobooks/AudiobookListViewModel.kt feature/audiobooks/src/main/java/com/orator/feature/audiobooks/AudiobookListScreen.kt
git commit -m "feat(audiobooks): ABS cover loading + per-book download/remove affordance via seam"
```

### Task 6.4: Wire the module into the app

**Files:** Modify `app/build.gradle.kts`.

- [ ] **Step 1: Add the dependency** (so Hilt aggregates the module's `@IntoSet` contributions):
```kotlin
    implementation(project(":feature:audiobookshelf"))
```

- [ ] **Step 2: Build + install** — `./gradlew assembleDebug` (Expected: BUILD SUCCESSFUL). The shared `OkHttpClient` now carries `AbsAuthInterceptor`; the Audiobooks tab shows ABS books; Settings shows the Audiobookshelf section.

- [ ] **Step 3: Commit**
```bash
git add app/build.gradle.kts
git commit -m "build(app): include feature:audiobookshelf in the DI graph"
```

### Task 6.5: Device verification (manual; real server, scrubbed)

> Requires the user's ABS server URL + credentials (sensitive — never commit/print). The user performs the SAF folder pick.

- [ ] Install: `./gradlew installDebug`.
- [ ] **Connect:** Settings → Audiobookshelf → enter URL/username/password → state becomes `Connected`; force-stop + reopen → still connected (token persisted in EncryptedSharedPreferences). Wrong creds → `Error`.
- [ ] **Mirror:** Audiobooks tab lists ABS books; covers load (authed Coil).
- [ ] **Stream:** open an ABS book → it plays (lazy-expand → `Bearer` stream via OkHttp data source); verify in logcat the request to the ABS host carries `Authorization`. Pause/restart app → resumes at the saved position.
- [ ] **Download:** trigger download → pick the SAF folder when prompted → `DOWNLOADING` → `DOWNLOADED`; enable airplane mode → playback works from `content://`. "Remove" → reverts to stream (re-streams on next play with network back).
- [ ] **Refresh** after a server-side change → catalog reconciles; an in-progress book keeps its position; a downloaded book keeps its local copy.
- [ ] **Logout** → ABS books disappear from the Audiobooks tab; downloaded files deleted.
- [ ] Confirm no real URLs/tokens were committed (`git log -p` on test fixtures shows only `example.com` + fake tokens).

### Chunk 6 gate
- [ ] Run `./gradlew test lint assembleDebug` — Expected: BUILD SUCCESSFUL. Device checklist green. Then dispatch chunk review and proceed to finishing-a-development-branch.

---

## Notes for the implementer

- **Modularity invariant:** `feature:audiobookshelf` must never appear as a dependency of `feature:audiobooks` (or vice versa). They meet only at `core` seams. If you find yourself adding such a dependency, route it through a `core` interface + `@IntoSet` instead.
- **Secrets:** the ABS token lives only in EncryptedSharedPreferences; never log it. Test fixtures use `example.com` + fake tokens. Never `git add -A` — stage explicit paths (the user keeps untracked private files).
- **Destructive DB bump:** v8 drops all tables on upgrade (pre-release convention). Acceptable now; real migrations arrive in roadmap Phase 9.
- **Pagination:** `getLibraryItems` uses `limit=0` (all items) for 6a simplicity; real pagination is a later refinement.
- **Reuse, don't reinvent:** `AbsFileDownloader` deliberately mirrors `EpisodeDownloader`; `AbsPrefs` mirrors `AudiobooksPrefs`; DAO tests mirror `EpisodeDaoTest`; worker tests mirror `RefreshSchedulerTest`.
- **Hilt validation timing:** `feature:audiobookshelf` is a library with no `@HiltAndroidApp`/component, so Dagger's missing-binding validation does NOT run when the module compiles alone (Chunks 1–5). KSP only generates per-class factories. The full graph is validated when `:app` aggregates the module in **Task 6.4** — so a missing `@Provides` (e.g. for `AbsBookDetailResolver`, consumed by `AbsFileDownloader`'s `@Inject`) surfaces as an error only at `:app` assembly after Task 6.4, not at the Chunk-5 gate. Don't expect a standalone Hilt-validating build of the module to fail before Chunk 6.
- **Provider-only classes:** `AbsRepository` and `AbsBookDetailResolver` are plain classes with **no** `@Inject` constructor — they exist solely via `@Provides` (their function-type params can't be injected). Never add `@Inject` to them; that creates a duplicate binding with the `@Provides`.
