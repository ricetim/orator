# Phase 2: Local Audiobooks Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Import audiobooks (`.m4b` files and directories of `.mp3` files) from a user-picked SAF folder into a Room library; play them through the existing Media3 service with resume-where-you-left-off and bookmarks.

**Architecture:** Two new modules — `core:database` (Room: books/chapters/bookmarks, the app's first persistent store) and `feature:audiobooks` (SAF scanning, metadata import, library + book-detail screens). `core:playback` gains a generic `play(PlayRequest)` API and a `PlaybackPositionListener` multibinding so the service reports positions without knowing what an audiobook is — the same registry-style decoupling as `FeatureEntry`. Spec: `docs/architecture.md` §6, §7.2, roadmap Phase 2.

**Tech Stack:** Room 2.7.1 (KSP), Robolectric 4.14.1 (JVM DAO tests — no emulator on this box), DataStore Preferences 1.1.1, `androidx.documentfile` (SAF), Media3 1.5.1, Hilt 2.53.1.

---

## Execution notes (deviations from the written plan)

- **`runTest` → `runBlocking`** in every Room-backed JVM test: Room 2.7's invalidation
  coroutines outlive `runTest`'s leak audit (`UncompletedCoroutinesError`). Plain-logic tests
  are unaffected.
- **"Verify red" steps skipped**: with 7–23 min builds, compile-failure runs are
  information-free. Tests were still written first; verification batched per chunk.
- **`feature:audiobooks` needs an explicit `activity-compose` dependency** (Task 17 uses
  `rememberLauncherForActivityResult`; nothing else on the compile classpath provides it).
- **Real-library finding** (`/media/Public/Books/Audiobooks`): some books are a directory of
  many m4b files. v1 imports each m4b as its own book — multi-m4b grouping is a known gap
  for a later phase.

## Orientation (read first)

**Existing modules** (all under `/home/tim/projects/akouo`):

| Module | What's in it |
|---|---|
| `app` | `MainActivity` (injects `Set<FeatureEntry>`), `AkouoNavHost`, `AkouoApplication` |
| `core:model` | `MediaType` enum (`PODCAST`, `AUDIOBOOK`) |
| `core:navigation` | `FeatureEntry` interface |
| `core:designsystem` | `AkouoTheme` |
| `core:playback` | `PlaybackService` (Media3 `MediaSessionService`), `PlaybackConnection` (StateFlow of `PlaybackUiState`), `SpeedResolver` (+tests) |
| `feature:player` | Phase-1 smoke-test screen (Load sample / Play–Pause) |

**Rules:** dependencies flow `app → feature → core`, never reverse, never feature→feature. UI is deliberately placeholder-quality (user decision 2026-06-09: design iteration happens after the backend is complete) — do not polish screens.

**Environment quirks (important):**
- CLI-only server, **no emulator**. JVM tests (plain JUnit + Robolectric) are the verification loop; on-device checks happen once at the end via wireless adb (Chunk 6).
- Builds are slow (incremental 3–10 min). Always run the *narrowest* Gradle task, e.g. `./gradlew :core:database:testDebugUnitTest`, not `./gradlew test`. Run Gradle as `./gradlew -p /home/tim/projects/akouo --console=plain <task>`.
- `ANDROID_HOME=~/Android/Sdk` must be exported.
- If Kotlin compilation dies with an RMI/daemon timeout, run `./gradlew --stop` and retry — it's memory pressure, not your code.
- `ffmpeg` is installed (used in Phase 1 to generate `sample.mp3`) — Chunk 2 and Chunk 6 use it to build fixtures.

**Branch setup:** PR #1 (`phase-1-foundation`) is not merged yet, so branch from it:

```bash
cd /home/tim/projects/akouo
git checkout phase-1-foundation
git checkout -b phase-2-local-audiobooks
```

**Key design decisions baked into this plan:**
1. **Book identity** = first 16 hex chars of SHA-256 of the book's source document URI. Stable across rescans; changes if the user moves files (acceptable v1).
2. **One global position per book** (`BookEntity.positionMs`, milliseconds from the start of the whole book). For `.m4b` that's just the file position. For mp3 collections, `PositionMapper` converts global ↔ (file index, offset). Bookmarks store global positions too.
3. **m4b chapters** come from the Nero `chpl` MP4 box (`moov/udta/chpl`), which ffmpeg and audiobook tools write by default. Files without it get a single full-length chapter. The QuickTime chapter-track variant is out of scope for v1.
4. **An mp3 directory = one book**; direct child `.mp3` files (natural-sorted) = its chapters. Subdirectories are scanned for *more* books, not merged.
5. **Position persistence is push-based from the service**: `PlaybackService` polls every 3 s while playing and notifies `Set<PlaybackPositionListener>` (Hilt multibinding). `feature:audiobooks` contributes a listener that writes to Room. The service never references audiobook types.
6. **Playback queue shape:** m4b → one `MediaItem` (chapters are seek targets); mp3 collection → one `MediaItem` per file.

---

## Chunk 1: `core:database` — Room foundation

### Task 1: Version catalog + module scaffold

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `settings.gradle.kts`
- Create: `core/database/build.gradle.kts`
- Create: `core/database/.gitignore`

- [x] **Step 1: Add versions and libraries to the catalog**

In `gradle/libs.versions.toml`, add to `[versions]` (keep alphabetical-ish grouping):

```toml
datastore = "1.1.1"
documentfile = "1.0.1"
robolectric = "4.14.1"
room = "2.7.1"
androidxTestCore = "1.6.1"
```

Add to `[libraries]`:

```toml
androidx-datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
androidx-documentfile = { group = "androidx.documentfile", name = "documentfile", version.ref = "documentfile" }
androidx-test-core = { group = "androidx.test", name = "core-ktx", version.ref = "androidxTestCore" }
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
```

- [x] **Step 2: Register the module**

In `settings.gradle.kts`, after the existing `include(":core:playback")` line add:

```kotlin
include(":core:database")
```

- [x] **Step 3: Create `core/database/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.akouo.core.database"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // api, not implementation: AkouoDatabase extends RoomDatabase, so consumers (and their
    // Robolectric tests, which call Room.inMemoryDatabaseBuilder) need Room on their compile
    // classpath too.
    api(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

`isIncludeAndroidResources = true` is what lets Robolectric run Room on the JVM — our only practical way to test DAOs on an emulator-less box.

- [x] **Step 4: Create `core/database/.gitignore`** containing the single line `/build`.

- [x] **Step 5: Verify the empty module configures**

Run: `./gradlew -p /home/tim/projects/akouo --console=plain :core:database:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL` (module has no sources yet; configuration succeeding is the check).

- [x] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml settings.gradle.kts core/database
git commit -m "build: scaffold core:database module with Room + Robolectric"
```

### Task 2: Entities, database, DAOs (TDD via Robolectric)

**Files:**
- Create: `core/database/src/main/java/com/akouo/core/database/BookEntity.kt`
- Create: `core/database/src/main/java/com/akouo/core/database/ChapterEntity.kt`
- Create: `core/database/src/main/java/com/akouo/core/database/BookmarkEntity.kt`
- Create: `core/database/src/main/java/com/akouo/core/database/BookDao.kt`
- Create: `core/database/src/main/java/com/akouo/core/database/ChapterDao.kt`
- Create: `core/database/src/main/java/com/akouo/core/database/BookmarkDao.kt`
- Create: `core/database/src/main/java/com/akouo/core/database/AkouoDatabase.kt`
- Test: `core/database/src/test/java/com/akouo/core/database/AkouoDatabaseTest.kt`

- [x] **Step 1: Write the failing tests**

`core/database/src/test/java/com/akouo/core/database/AkouoDatabaseTest.kt`:

```kotlin
package com.akouo.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AkouoDatabaseTest {

    private lateinit var db: AkouoDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AkouoDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun book(id: String = "b1", positionMs: Long = 0) = BookEntity(
        id = id,
        title = "A Book",
        author = "An Author",
        coverPath = null,
        sourceUri = "content://tree/doc/$id",
        sourceKind = SourceKind.M4B,
        durationMs = 100_000,
        positionMs = positionMs,
        addedAtUtc = 1_000,
    )

    private fun chapter(bookId: String, index: Int) = ChapterEntity(
        bookId = bookId,
        chapterIndex = index,
        title = "Chapter $index",
        fileUri = "content://tree/doc/$bookId",
        startMs = index * 10_000L,
        durationMs = 10_000,
    )

    @Test
    fun `book roundtrip and position update`() = runTest {
        db.bookDao().upsert(listOf(book()))

        db.bookDao().updatePosition("b1", 42_000)

        val loaded = db.bookDao().getById("b1")!!
        assertEquals(42_000, loaded.positionMs)
        assertEquals("A Book", loaded.title)
        assertEquals(SourceKind.M4B, loaded.sourceKind)
    }

    @Test
    fun `observeAll emits books sorted by title`() = runTest {
        db.bookDao().upsert(listOf(book("b1").copy(title = "Zebra"), book("b2").copy(title = "Aardvark")))

        val titles = db.bookDao().observeAll().first().map { it.title }

        assertEquals(listOf("Aardvark", "Zebra"), titles)
    }

    @Test
    fun `chapters come back ordered by index`() = runTest {
        db.bookDao().upsert(listOf(book()))
        db.chapterDao().upsertAll(listOf(chapter("b1", 2), chapter("b1", 0), chapter("b1", 1)))

        val indices = db.chapterDao().getForBook("b1").map { it.chapterIndex }

        assertEquals(listOf(0, 1, 2), indices)
    }

    @Test
    fun `deleting a book cascades to chapters and bookmarks`() = runTest {
        db.bookDao().upsert(listOf(book()))
        db.chapterDao().upsertAll(listOf(chapter("b1", 0)))
        db.bookmarkDao().insert(
            BookmarkEntity(bookId = "b1", positionMs = 5_000, note = "hi", createdAtUtc = 1_000),
        )

        db.bookDao().deleteByIds(listOf("b1"))

        assertTrue(db.chapterDao().getForBook("b1").isEmpty())
        assertTrue(db.bookmarkDao().observeForBook("b1").first().isEmpty())
    }

    @Test
    fun `bookmarks observe newest first`() = runTest {
        db.bookDao().upsert(listOf(book()))
        db.bookmarkDao().insert(BookmarkEntity(bookId = "b1", positionMs = 1_000, note = null, createdAtUtc = 1))
        db.bookmarkDao().insert(BookmarkEntity(bookId = "b1", positionMs = 2_000, note = null, createdAtUtc = 2))

        val positions = db.bookmarkDao().observeForBook("b1").first().map { it.positionMs }

        assertEquals(listOf(2_000L, 1_000L), positions)
    }
}
```

- [x] **Step 2: Run tests to verify they fail**

Run: `./gradlew -p /home/tim/projects/akouo --console=plain :core:database:testDebugUnitTest`
Expected: FAIL — compilation errors (`Unresolved reference: AkouoDatabase`, etc.). A compile failure *is* the red state here.

- [x] **Step 3: Implement the schema**

`BookEntity.kt`:

```kotlin
package com.akouo.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/** How a book arrived in the library; determines how a playback queue is built. */
enum class SourceKind { M4B, MP3_DIR }

/**
 * One audiobook. [positionMs] is the global resume position measured from the start of the
 * whole book (across all files); PositionMapper in feature:audiobooks converts it to a
 * (file, offset) pair for multi-file books.
 */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String?,
    val coverPath: String?,
    val sourceUri: String,
    val sourceKind: SourceKind,
    val durationMs: Long,
    val positionMs: Long = 0,
    val addedAtUtc: Long,
)
```

(Room stores enums by name automatically — no TypeConverter needed.)

`ChapterEntity.kt`:

```kotlin
package com.akouo.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * A chapter. For M4B books every chapter shares the book's fileUri and startMs is an offset
 * into that file. For MP3_DIR books each chapter is its own file and startMs is 0.
 */
@Entity(
    tableName = "chapters",
    primaryKeys = ["bookId", "chapterIndex"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bookId")],
)
data class ChapterEntity(
    val bookId: String,
    val chapterIndex: Int,
    val title: String,
    val fileUri: String,
    val startMs: Long,
    val durationMs: Long,
)
```

`BookmarkEntity.kt`:

```kotlin
package com.akouo.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A saved position within a book. [positionMs] is global (same scale as BookEntity.positionMs). */
@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bookId")],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val positionMs: Long,
    val note: String?,
    val createdAtUtc: Long,
)
```

`BookDao.kt`:

```kotlin
package com.akouo.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Upsert
    suspend fun upsert(books: List<BookEntity>)

    @Query("SELECT * FROM books ORDER BY title")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    fun observeById(id: String): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getById(id: String): BookEntity?

    @Query("SELECT id FROM books")
    suspend fun getAllIds(): List<String>

    @Query("UPDATE books SET positionMs = :positionMs WHERE id = :id")
    suspend fun updatePosition(id: String, positionMs: Long)

    @Query("DELETE FROM books WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}
```

`ChapterDao.kt`:

```kotlin
package com.akouo.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterDao {
    @Upsert
    suspend fun upsertAll(chapters: List<ChapterEntity>)

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY chapterIndex")
    suspend fun getForBook(bookId: String): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY chapterIndex")
    fun observeForBook(bookId: String): Flow<List<ChapterEntity>>
}
```

`BookmarkDao.kt`:

```kotlin
package com.akouo.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Insert
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdAtUtc DESC")
    fun observeForBook(bookId: String): Flow<List<BookmarkEntity>>

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)
}
```

`AkouoDatabase.kt`:

```kotlin
package com.akouo.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * exportSchema is off while the schema is pre-release and allowed to change freely.
 * It MUST be enabled (with committed schema files + migration tests) before the first
 * public release — tracked under roadmap Phase 9.
 */
@Database(
    entities = [BookEntity::class, ChapterEntity::class, BookmarkEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AkouoDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun bookmarkDao(): BookmarkDao
}
```

- [x] **Step 4: Run tests to verify they pass**

Run: `./gradlew -p /home/tim/projects/akouo --console=plain :core:database:testDebugUnitTest`
Expected: PASS (5 tests). If Robolectric complains about the SDK level, pin the test class with `@Config(sdk = [34])` from `org.robolectric.annotation.Config`.

- [x] **Step 5: Commit**

```bash
git add core/database
git commit -m "feat: add core:database with book/chapter/bookmark schema"
```

### Task 3: Hilt wiring for the database

**Files:**
- Create: `core/database/src/main/java/com/akouo/core/database/DatabaseModule.kt`

- [x] **Step 1: Implement the module**

```kotlin
package com.akouo.core.database

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AkouoDatabase =
        Room.databaseBuilder(context, AkouoDatabase::class.java, "akouo.db").build()

    @Provides
    fun provideBookDao(db: AkouoDatabase): BookDao = db.bookDao()

    @Provides
    fun provideChapterDao(db: AkouoDatabase): ChapterDao = db.chapterDao()

    @Provides
    fun provideBookmarkDao(db: AkouoDatabase): BookmarkDao = db.bookmarkDao()
}
```

- [x] **Step 2: Verify it compiles**

Run: `./gradlew -p /home/tim/projects/akouo --console=plain :core:database:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [x] **Step 3: Commit**

```bash
git add core/database
git commit -m "feat: provide AkouoDatabase and DAOs via Hilt"
```

---

## Chunk 2: `feature:audiobooks` scaffold + pure logic (TDD)

### Task 4: Module scaffold

**Files:**
- Modify: `settings.gradle.kts`
- Create: `feature/audiobooks/build.gradle.kts`
- Create: `feature/audiobooks/.gitignore`

- [x] **Step 1: Register the module**

In `settings.gradle.kts`, after `include(":feature:player")` add:

```kotlin
include(":feature:audiobooks")
```

- [x] **Step 2: Create `feature/audiobooks/build.gradle.kts`**

Mirrors `feature/player/build.gradle.kts` plus database/playback/model deps, SAF, DataStore, and Robolectric:

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.akouo.feature.audiobooks"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:navigation"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:playback"))
    implementation(project(":core:database"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

- [x] **Step 3: Create `feature/audiobooks/.gitignore`** containing `/build`.

- [x] **Step 4: Verify configuration**

Run: `./gradlew -p /home/tim/projects/akouo --console=plain :feature:audiobooks:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [x] **Step 5: Commit**

```bash
git add settings.gradle.kts feature/audiobooks
git commit -m "build: scaffold feature:audiobooks module"
```

### Task 5: Book identity + playback media-id codec (TDD)

**Files:**
- Create: `feature/audiobooks/src/main/java/com/akouo/feature/audiobooks/data/BookIds.kt`
- Create: `feature/audiobooks/src/main/java/com/akouo/feature/audiobooks/data/AudiobookMediaId.kt`
- Test: `feature/audiobooks/src/test/java/com/akouo/feature/audiobooks/data/AudiobookMediaIdTest.kt`

- [x] **Step 1: Write the failing tests**

```kotlin
package com.akouo.feature.audiobooks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiobookMediaIdTest {

    @Test
    fun `bookId is stable and uri-safe`() {
        val a = BookIds.fromUri("content://com.android.externalstorage.documents/tree/primary%3AAudiobooks/document/primary%3AAudiobooks%2Fbook.m4b")
        val b = BookIds.fromUri("content://com.android.externalstorage.documents/tree/primary%3AAudiobooks/document/primary%3AAudiobooks%2Fbook.m4b")
        val c = BookIds.fromUri("content://other")

        assertEquals(a, b)
        assertTrue(a != c)
        assertEquals(16, a.length)
        assertTrue(a.all { it in "0123456789abcdef" })
    }

    @Test
    fun `mediaId roundtrips`() {
        val id = AudiobookMediaId.encode("abc123", 4)
        val parsed = AudiobookMediaId.parse(id)!!

        assertEquals("abc123", parsed.bookId)
        assertEquals(4, parsed.fileIndex)
    }

    @Test
    fun `parse rejects foreign mediaIds`() {
        assertNull(AudiobookMediaId.parse("podcast/xyz/2"))
        assertNull(AudiobookMediaId.parse(""))
        assertNull(AudiobookMediaId.parse("audiobook/missing-index"))
        assertNull(AudiobookMediaId.parse("audiobook/x/notanumber"))
    }
}
```

- [x] **Step 2: Run to verify failure**

Run: `./gradlew -p /home/tim/projects/akouo --console=plain :feature:audiobooks:testDebugUnitTest`
Expected: FAIL (unresolved references).

- [x] **Step 3: Implement**

`BookIds.kt`:

```kotlin
package com.akouo.feature.audiobooks.data

import java.security.MessageDigest

/**
 * A book's identity is a hash of its source document URI: stable across rescans, no
 * coordination needed, and safe to embed in route strings and media ids. Moving the
 * file changes the id (and loses position) — accepted for v1.
 */
object BookIds {
    fun fromUri(uri: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(uri.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }
}
```

`AudiobookMediaId.kt`:

```kotlin
package com.akouo.feature.audiobooks.data

/**
 * Encodes which (book, file-in-queue) a Media3 MediaItem represents, so the position
 * listener can route service callbacks back to a Room row. Format: "audiobook/<bookId>/<fileIndex>".
 */
object AudiobookMediaId {
    private const val PREFIX = "audiobook"

    data class Parsed(val bookId: String, val fileIndex: Int)

    fun encode(bookId: String, fileIndex: Int): String = "$PREFIX/$bookId/$fileIndex"

    fun parse(mediaId: String): Parsed? {
        val parts = mediaId.split('/')
        if (parts.size != 3 || parts[0] != PREFIX) return null
        val index = parts[2].toIntOrNull() ?: return null
        return Parsed(bookId = parts[1], fileIndex = index)
    }
}
```

- [x] **Step 4: Run to verify pass**

Run: `./gradlew -p /home/tim/projects/akouo --console=plain :feature:audiobooks:testDebugUnitTest`
Expected: PASS (3 tests).

- [x] **Step 5: Commit**

```bash
git add feature/audiobooks
git commit -m "feat: book identity hash and audiobook mediaId codec"
```

### Task 6: Natural-order filename comparator (TDD)

**Files:**
- Create: `feature/audiobooks/src/main/java/com/akouo/feature/audiobooks/data/NaturalOrder.kt`
- Test: `feature/audiobooks/src/test/java/com/akouo/feature/audiobooks/data/NaturalOrderTest.kt`

Why: `"Track 10.mp3"` must sort *after* `"Track 2.mp3"`; plain lexicographic sort breaks chapter order for most real audiobook rips.

- [x] **Step 1: Write the failing tests**

```kotlin
package com.akouo.feature.audiobooks.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalOrderTest {

    @Test
    fun `numbers compare numerically not lexically`() {
        val sorted = listOf("Track 10.mp3", "Track 2.mp3", "Track 1.mp3").sortedWith(NaturalOrder)
        assertEquals(listOf("Track 1.mp3", "Track 2.mp3", "Track 10.mp3"), sorted)
    }

    @Test
    fun `leading zeros do not change order`() {
        val sorted = listOf("007.mp3", "8.mp3", "06.mp3").sortedWith(NaturalOrder)
        assertEquals(listOf("06.mp3", "007.mp3", "8.mp3"), sorted)
    }

    @Test
    fun `comparison is case-insensitive`() {
        val sorted = listOf("chapter 2", "Chapter 1").sortedWith(NaturalOrder)
        assertEquals(listOf("Chapter 1", "chapter 2"), sorted)
    }

    @Test
    fun `plain strings still sort`() {
        val sorted = listOf("b", "a", "ab").sortedWith(NaturalOrder)
        assertEquals(listOf("a", "ab", "b"), sorted)
    }
}
```

- [x] **Step 2: Run to verify failure**

Run: `./gradlew -p /home/tim/projects/akouo --console=plain :feature:audiobooks:testDebugUnitTest --tests "com.akouo.feature.audiobooks.data.NaturalOrderTest"`
Expected: FAIL (unresolved reference `NaturalOrder`).

- [x] **Step 3: Implement**

```kotlin
package com.akouo.feature.audiobooks.data

/**
 * Filename comparator where digit runs compare as numbers ("Track 2" < "Track 10")
 * and letters compare case-insensitively. Used to order an mp3 collection into chapters.
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var endA = i
                while (endA < a.length && a[endA].isDigit()) endA++
                var endB = j
                while (endB < b.length && b[endB].isDigit()) endB++
                val numA = a.substring(i, endA).trimStart('0')
                val numB = b.substring(j, endB).trimStart('0')
                val cmp = if (numA.length != numB.length) numA.length - numB.length else numA.compareTo(numB)
                if (cmp != 0) return cmp
                i = endA
                j = endB
            } else {
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
```

- [x] **Step 4: Run to verify pass**

Same command as Step 2. Expected: PASS (4 tests).

- [x] **Step 5: Commit**

```bash
git add feature/audiobooks
git commit -m "feat: natural-order comparator for mp3 chapter ordering"
```

### Task 7: Global ↔ per-file position mapping (TDD)

**Files:**
- Create: `feature/audiobooks/src/main/java/com/akouo/feature/audiobooks/data/PositionMapper.kt`
- Test: `feature/audiobooks/src/test/java/com/akouo/feature/audiobooks/data/PositionMapperTest.kt`

- [x] **Step 1: Write the failing tests**

```kotlin
package com.akouo.feature.audiobooks.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PositionMapperTest {

    private val durations = listOf(10_000L, 20_000L, 30_000L) // total 60s

    @Test
    fun `global position maps into the right file`() {
        assertEquals(PositionMapper.FilePosition(0, 5_000), PositionMapper.toFilePosition(durations, 5_000))
        assertEquals(PositionMapper.FilePosition(1, 0), PositionMapper.toFilePosition(durations, 10_000))
        assertEquals(PositionMapper.FilePosition(2, 15_000), PositionMapper.toFilePosition(durations, 45_000))
    }

    @Test
    fun `file position maps back to global`() {
        assertEquals(45_000L, PositionMapper.toGlobal(durations, 2, 15_000))
        assertEquals(0L, PositionMapper.toGlobal(durations, 0, 0))
    }

    @Test
    fun `roundtrip is identity`() {
        val global = 33_333L
        val fp = PositionMapper.toFilePosition(durations, global)
        assertEquals(global, PositionMapper.toGlobal(durations, fp.fileIndex, fp.offsetMs))
    }

    @Test
    fun `out-of-range global clamps to the end of the last file`() {
        assertEquals(PositionMapper.FilePosition(2, 30_000), PositionMapper.toFilePosition(durations, 999_999))
    }

    @Test
    fun `negative global clamps to start`() {
        assertEquals(PositionMapper.FilePosition(0, 0), PositionMapper.toFilePosition(durations, -5))
    }

    @Test
    fun `empty duration list yields origin`() {
        assertEquals(PositionMapper.FilePosition(0, 0), PositionMapper.toFilePosition(emptyList(), 1_000))
    }
}
```

- [x] **Step 2: Run to verify failure**

Run: `./gradlew -p /home/tim/projects/akouo --console=plain :feature:audiobooks:testDebugUnitTest --tests "com.akouo.feature.audiobooks.data.PositionMapperTest"`
Expected: FAIL (unresolved reference).

- [x] **Step 3: Implement**

```kotlin
package com.akouo.feature.audiobooks.data

/**
 * A book's resume position and bookmarks are stored as ONE global millisecond offset from
 * the start of the whole book. These functions translate between that and the
 * (file index, offset-in-file) coordinates Media3 queues actually use.
 */
object PositionMapper {

    data class FilePosition(val fileIndex: Int, val offsetMs: Long)

    fun toFilePosition(fileDurationsMs: List<Long>, globalMs: Long): FilePosition {
        if (fileDurationsMs.isEmpty()) return FilePosition(0, 0)
        var remaining = globalMs.coerceAtLeast(0)
        fileDurationsMs.forEachIndexed { index, duration ->
            if (remaining < duration) return FilePosition(index, remaining)
            remaining -= duration
        }
        return FilePosition(fileDurationsMs.lastIndex, fileDurationsMs.last())
    }

    fun toGlobal(fileDurationsMs: List<Long>, fileIndex: Int, offsetMs: Long): Long {
        val priorFiles = fileDurationsMs.take(fileIndex).sum()
        return priorFiles + offsetMs
    }
}
```

- [x] **Step 4: Run to verify pass**

Same command. Expected: PASS (6 tests).

- [x] **Step 5: Commit**

```bash
git add feature/audiobooks
git commit -m "feat: global/per-file position mapping for multi-file books"
```

### Task 8: m4b chapter parser with a real ffmpeg fixture (TDD)

**Files:**
- Create: `feature/audiobooks/src/test/resources/fixture.m4b` (generated, committed — ~30 KB)
- Create: `feature/audiobooks/src/main/java/com/akouo/feature/audiobooks/data/Mp4ChapterParser.kt`
- Test: `feature/audiobooks/src/test/java/com/akouo/feature/audiobooks/data/Mp4ChapterParserTest.kt`

m4b chapters live in the Nero `chpl` MP4 box (`moov` → `udta` → `chpl`), which ffmpeg writes by default. We test against a **real ffmpeg-produced file**, not hand-crafted bytes, so the test validates our understanding of the format rather than echoing it.

- [x] **Step 1: Generate the fixture**

```bash
mkdir -p feature/audiobooks/src/test/resources
cat > /tmp/chapters.txt <<'EOF'
;FFMETADATA1
title=Fixture Book
artist=Test Author

[CHAPTER]
TIMEBASE=1/1000
START=0
END=4000
title=Chapter One

[CHAPTER]
TIMEBASE=1/1000
START=4000
END=8000
title=Chapter Two
EOF
ffmpeg -y -f lavfi -i "sine=frequency=440:duration=8" -i /tmp/chapters.txt \
  -map 0:a -map_metadata 1 -map_chapters 1 -c:a aac -b:a 24k \
  feature/audiobooks/src/test/resources/fixture.m4b
```

Verify the chapters really are in the file:

Run: `ffprobe -v error -show_chapters feature/audiobooks/src/test/resources/fixture.m4b`
Expected: two `[CHAPTER]` blocks with `start_time=0.000000` / `start_time=4.000000` and tags `Chapter One` / `Chapter Two`. Also confirm the chpl box exists: `xxd feature/audiobooks/src/test/resources/fixture.m4b | grep chpl` prints one line.

- [x] **Step 2: Write the failing tests**

```kotlin
package com.akouo.feature.audiobooks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp4ChapterParserTest {

    private fun fixture() = requireNotNull(
        javaClass.classLoader?.getResourceAsStream("fixture.m4b"),
    ) { "fixture.m4b missing from test resources" }

    @Test
    fun `parses Nero chapters from an ffmpeg m4b`() {
        val chapters = Mp4ChapterParser.parse(fixture())

        assertEquals(listOf("Chapter One", "Chapter Two"), chapters.map { it.title })
        assertEquals(0L, chapters[0].startMs)
        assertEquals(4_000L, chapters[1].startMs)
    }

    @Test
    fun `non-mp4 data yields no chapters`() {
        assertTrue(Mp4ChapterParser.parse("definitely not an mp4".byteInputStream()).isEmpty())
    }

    @Test
    fun `empty stream yields no chapters`() {
        assertTrue(Mp4ChapterParser.parse(ByteArray(0).inputStream()).isEmpty())
    }
}
```

- [x] **Step 3: Run to verify failure**

Run: `./gradlew -p /home/tim/projects/akouo --console=plain :feature:audiobooks:testDebugUnitTest --tests "com.akouo.feature.audiobooks.data.Mp4ChapterParserTest"`
Expected: FAIL (unresolved reference `Mp4ChapterParser`).

- [x] **Step 4: Implement the parser**

```kotlin
package com.akouo.feature.audiobooks.data

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Extracts chapter marks from an .m4b (MP4) stream by locating the Nero chapter box at
 * moov/udta/chpl — written by ffmpeg and most audiobook tools by default. Files carrying
 * only the QuickTime chapter-track variant (tref/chap) are out of scope for v1 and fall
 * back to a single full-length chapter at import time.
 *
 * Forward-only on purpose: ContentResolver streams from SAF can't seek, so the walker
 * skips past boxes (including a possibly multi-hundred-MB mdat) rather than seeking.
 * MP4 box wire format: [u32 size][4cc type][payload]; size==1 → u64 size follows;
 * size==0 → box extends to end of file.
 */
object Mp4ChapterParser {

    data class Chapter(val title: String, val startMs: Long)

    private val CONTAINERS = setOf("moov", "udta")

    fun parse(input: InputStream): List<Chapter> =
        try {
            DataInputStream(input.buffered()).use { walkBoxes(it, Long.MAX_VALUE) }
        } catch (_: IOException) {
            emptyList()
        }

    /** Reads boxes until [limit] payload bytes are consumed; returns chapters if chpl found. */
    private fun walkBoxes(stream: DataInputStream, limit: Long): List<Chapter> {
        var remaining = limit
        while (remaining >= 8) {
            val size32: Long
            val type: String
            try {
                size32 = stream.readInt().toLong() and 0xFFFFFFFFL
                type = String(ByteArray(4).also { stream.readFully(it) }, Charsets.US_ASCII)
            } catch (_: EOFException) {
                return emptyList() // clean end of stream at a box boundary
            }
            var headerLen = 8L
            val boxSize = when (size32) {
                1L -> {
                    headerLen = 16L
                    stream.readLong()
                }
                0L -> remaining // "to end of file/container"
                else -> size32
            }
            val payload = boxSize - headerLen
            if (payload < 0) return emptyList() // corrupt; bail quietly

            when {
                type == "chpl" -> return readChpl(stream)
                type in CONTAINERS -> {
                    val found = walkBoxes(stream, payload)
                    if (found.isNotEmpty()) return found
                }
                else -> skipFully(stream, payload)
            }
            remaining -= boxSize
        }
        if (remaining in 1..7) skipFully(stream, remaining)
        return emptyList()
    }

    /**
     * ffmpeg layout: u8 version(=1), u24 flags, u32 reserved, u8 count, then per chapter:
     * u64 start time in 100-nanosecond units, u8 title length, UTF-8 title bytes.
     * If the fixture test fails here, hexdump the box (`xxd fixture.m4b | grep -A4 chpl`):
     * some muxers omit the 4 reserved bytes, putting count right after the flags.
     */
    private fun readChpl(stream: DataInputStream): List<Chapter> {
        val version = stream.readUnsignedByte()
        skipFully(stream, 3) // flags
        if (version != 1) return emptyList()
        skipFully(stream, 4) // reserved
        val count = stream.readUnsignedByte()
        val chapters = ArrayList<Chapter>(count)
        repeat(count) {
            val start100ns = stream.readLong()
            val titleLen = stream.readUnsignedByte()
            val title = ByteArray(titleLen).also { stream.readFully(it) }
            chapters.add(Chapter(String(title, Charsets.UTF_8), start100ns / 10_000))
        }
        return chapters
    }

    private fun skipFully(stream: InputStream, bytes: Long) {
        var left = bytes
        while (left > 0) {
            val skipped = stream.skip(left)
            if (skipped > 0) {
                left -= skipped
            } else {
                if (stream.read() == -1) throw EOFException("EOF while skipping box payload")
                left--
            }
        }
    }
}
```

- [x] **Step 5: Run to verify pass**

Same command as Step 3. Expected: PASS (3 tests). **If the chapter count or offsets are wrong**, the ffmpeg `chpl` layout differs from the comment in `readChpl` — hexdump the box and adjust the reserved-bytes handling; the test (built from real ffmpeg output) is the ground truth, not the parser.

- [x] **Step 6: Commit**

```bash
git add feature/audiobooks
git commit -m "feat: parse m4b Nero (chpl) chapter marks"
```

---

## Chunk 3: Scanning the SAF tree & importing into Room

### Task 9: Document-tree abstraction + scanner (TDD)

**Files:**
- Create: `feature/audiobooks/src/main/java/com/akouo/feature/audiobooks/data/DocumentNode.kt`
- Create: `feature/audiobooks/src/main/java/com/akouo/feature/audiobooks/data/AudiobookScanner.kt`
- Test: `feature/audiobooks/src/test/java/com/akouo/feature/audiobooks/data/AudiobookScannerTest.kt`

`DocumentFile` (SAF) is untestable on the JVM, so the scanner works against a tiny `DocumentNode` interface; the real adapter is three lines and gets exercised on-device in Chunk 6.

- [x] **Step 1: Write the failing tests**

```kotlin
package com.akouo.feature.audiobooks.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AudiobookScannerTest {

    private fun file(name: String) = FakeNode(name, isDirectory = false)
    private fun dir(name: String, vararg children: DocumentNode) =
        FakeNode(name, isDirectory = true, childNodes = children.toList())

    private class FakeNode(
        override val name: String,
        override val isDirectory: Boolean,
        private val childNodes: List<DocumentNode> = emptyList(),
        parentPath: String = "tree:/",
    ) : DocumentNode {
        override val uri: String = "$parentPath$name"
        override fun children(): List<DocumentNode> = childNodes
    }

    @Test
    fun `m4b files become books wherever they sit`() {
        val root = dir("root", file("Solo Book.m4b"), dir("nested", file("Deep Book.m4b")))

        val books = AudiobookScanner.scan(root)

        assertEquals(listOf("Deep Book", "Solo Book"), books.map { it.title }.sorted())
    }

    @Test
    fun `a directory of mp3s becomes one book with naturally ordered files`() {
        val root = dir("root", dir("My Book", file("Track 10.mp3"), file("Track 2.mp3"), file("Track 1.mp3")))

        val books = AudiobookScanner.scan(root)

        val book = books.single() as ScannedBook.Mp3Collection
        assertEquals("My Book", book.title)
        assertEquals(listOf("Track 1.mp3", "Track 2.mp3", "Track 10.mp3"), book.files.map { it.name })
    }

    @Test
    fun `non-audio files are ignored`() {
        val root = dir("root", dir("My Book", file("Track 1.mp3"), file("cover.jpg"), file("notes.txt")))

        val book = AudiobookScanner.scan(root).single() as ScannedBook.Mp3Collection

        assertEquals(1, book.files.size)
    }

    @Test
    fun `a directory with direct mp3s is not recursed further`() {
        // Mixed layout: the dir is claimed as one book from its direct mp3s; subdirs are skipped.
        val root = dir("root", dir("My Book", file("Track 1.mp3"), dir("extras", file("bonus.mp3"))))

        val books = AudiobookScanner.scan(root)

        assertEquals(1, books.size)
    }

    @Test
    fun `empty tree yields nothing`() {
        assertEquals(emptyList<ScannedBook>(), AudiobookScanner.scan(dir("root")))
    }
}
```

- [x] **Step 2: Run to verify failure**

Run: `./gradlew -p /home/tim/projects/akouo --console=plain :feature:audiobooks:testDebugUnitTest --tests "com.akouo.feature.audiobooks.data.AudiobookScannerTest"`
Expected: FAIL (unresolved references).

- [x] **Step 3: Implement**

`DocumentNode.kt`:

```kotlin
package com.akouo.feature.audiobooks.data

import androidx.documentfile.provider.DocumentFile

/**
 * Minimal view of a SAF document tree. Exists so scanning logic is testable on the JVM —
 * DocumentFile requires a device. Production code wraps the picked tree in DocumentFileNode.
 */
interface DocumentNode {
    val name: String
    val uri: String
    val isDirectory: Boolean
    fun children(): List<DocumentNode>
}

class DocumentFileNode(private val doc: DocumentFile) : DocumentNode {
    override val name: String get() = doc.name.orEmpty()
    override val uri: String get() = doc.uri.toString()
    override val isDirectory: Boolean get() = doc.isDirectory
    override fun children(): List<DocumentNode> = doc.listFiles().map { DocumentFileNode(it) }
}
```

`AudiobookScanner.kt`:

```kotlin
package com.akouo.feature.audiobooks.data

/** A book found on disk, before any metadata extraction. */
sealed interface ScannedBook {
    val title: String
    val rootUri: String

    data class M4b(override val title: String, override val rootUri: String) : ScannedBook

    data class Mp3Collection(
        override val title: String,
        override val rootUri: String,
        val files: List<ScannedFile>,
    ) : ScannedBook
}

data class ScannedFile(val name: String, val uri: String)

/**
 * Walks a picked folder and finds books:
 *  - every .m4b file is a book, at any depth;
 *  - every directory whose DIRECT children include .mp3 files is a book of those files
 *    (natural-sorted); its subdirectories are not entered (v1 simplification — a
 *    CD1/CD2-style book without root-level mp3s shows up as two books).
 */
object AudiobookScanner {

    fun scan(root: DocumentNode): List<ScannedBook> {
        val books = mutableListOf<ScannedBook>()
        scanDirectory(root, books)
        return books
    }

    private fun scanDirectory(dir: DocumentNode, sink: MutableList<ScannedBook>) {
        val children = dir.children()

        children
            .filter { !it.isDirectory && it.name.endsWith(".m4b", ignoreCase = true) }
            .forEach { sink.add(ScannedBook.M4b(title = it.name.removeSuffix(".m4b"), rootUri = it.uri)) }

        val mp3s = children
            .filter { !it.isDirectory && it.name.endsWith(".mp3", ignoreCase = true) }
            .sortedWith(compareBy(NaturalOrder) { it.name })
        if (mp3s.isNotEmpty()) {
            sink.add(
                ScannedBook.Mp3Collection(
                    title = dir.name,
                    rootUri = dir.uri,
                    files = mp3s.map { ScannedFile(it.name, it.uri) },
                ),
            )
            return // claimed as a book; don't descend further
        }

        children.filter { it.isDirectory }.forEach { scanDirectory(it, sink) }
    }
}
```

Note the subtlety: the *root* picked folder itself can be an mp3 book (user picks the book folder directly) — this falls out of the algorithm naturally. But a root-level `.m4b` book title comes from the file name, which keeps `removeSuffix` case-sensitive — acceptable; `.M4B` files keep the suffix in their display title (cosmetic only).

- [x] **Step 4: Run to verify pass**

Same command. Expected: PASS (5 tests).

- [x] **Step 5: Commit**

```bash
git add feature/audiobooks
git commit -m "feat: scan SAF tree for m4b and mp3-collection books"
```

### Task 10: Metadata extraction + cover storage

**Files:**
- Create: `feature/audiobooks/src/main/java/com/akouo/feature/audiobooks/data/AudiobookMetadataExtractor.kt`
- Create: `feature/audiobooks/src/main/java/com/akouo/feature/audiobooks/data/CoverStore.kt`
- Test: `feature/audiobooks/src/test/java/com/akouo/feature/audiobooks/data/CoverStoreTest.kt`

`MediaMetadataRetriever` only works on a device, so it sits behind an interface (fakes in importer tests, reality checked in Chunk 6). `CoverStore` is plain file IO and gets a Robolectric test.

- [x] **Step 1: Write the failing CoverStore test**

```kotlin
package com.akouo.feature.audiobooks.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class CoverStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `saves bytes and returns a readable path`() {
        val store = CoverStore(context)
        val bytes = byteArrayOf(1, 2, 3)

        val path = store.save("book1", bytes)!!

        assertArrayEquals(bytes, File(path).readBytes())
    }

    @Test
    fun `null or empty bytes yield no path`() {
        val store = CoverStore(context)
        assertNull(store.save("book1", null))
        assertNull(store.save("book1", ByteArray(0)))
    }
}
```

- [x] **Step 2: Run to verify failure**

Run: `./gradlew -p /home/tim/projects/akouo --console=plain :feature:audiobooks:testDebugUnitTest --tests "com.akouo.feature.audiobooks.data.CoverStoreTest"`
Expected: FAIL (unresolved reference).

- [x] **Step 3: Implement both classes**

`AudiobookMetadataExtractor.kt`:

```kotlin
package com.akouo.feature.audiobooks.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

data class ExtractedMetadata(
    val title: String?,
    val author: String?,
    val durationMs: Long,
    val coverBytes: ByteArray?,
)

/** Reads embedded tags from an audio document. Interface exists for JVM-side fakes. */
interface AudiobookMetadataExtractor {
    fun extract(uri: Uri): ExtractedMetadata
}

class MmrMetadataExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
) : AudiobookMetadataExtractor {

    override fun extract(uri: Uri): ExtractedMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            ExtractedMetadata(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                author = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L,
                coverBytes = retriever.embeddedPicture,
            )
        } catch (e: Exception) {
            // Unreadable/corrupt file: import proceeds with filename-derived metadata.
            ExtractedMetadata(title = null, author = null, durationMs = 0, coverBytes = null)
        } finally {
            retriever.release()
        }
    }
}
```

`CoverStore.kt`:

```kotlin
package com.akouo.feature.audiobooks.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/**
 * Extracted cover art lives in app-internal storage, not the user's SAF folder — it is a
 * regenerable cache derived from the audio files, not user data (architecture §6 applies
 * the shared-folder rule to downloaded/user-owned bytes).
 */
class CoverStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun save(bookId: String, bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty()) return null
        val dir = File(context.filesDir, "covers").apply { mkdirs() }
        val file = File(dir, "$bookId.jpg")
        file.writeBytes(bytes)
        return file.absolutePath
    }
}
```

- [x] **Step 4: Run to verify pass**

Same command. Expected: PASS (2 tests).

- [x] **Step 5: Commit**

```bash
git add feature/audiobooks
git commit -m "feat: metadata extraction interface and cover cache"
```

### Task 11: Importer (TDD with in-memory Room + fakes)

**Files:**
- Create: `feature/audiobooks/src/main/java/com/akouo/feature/audiobooks/data/M4bChapterSource.kt`
- Create: `feature/audiobooks/src/main/java/com/akouo/feature/audiobooks/data/AudiobookImporter.kt`
- Test: `feature/audiobooks/src/test/java/com/akouo/feature/audiobooks/data/AudiobookImporterTest.kt`

- [x] **Step 1: Write the failing tests**

```kotlin
package com.akouo.feature.audiobooks.data

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.akouo.core.database.AkouoDatabase
import com.akouo.core.database.SourceKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AudiobookImporterTest {

    private lateinit var db: AkouoDatabase
    private lateinit var importer: AudiobookImporter

    private val fakeExtractor = object : AudiobookMetadataExtractor {
        override fun extract(uri: Uri) = ExtractedMetadata(
            title = "Tagged Title",
            author = "Tagged Author",
            durationMs = 60_000,
            coverBytes = null,
        )
    }

    private var chaptersInM4b: List<Mp4ChapterParser.Chapter> = emptyList()
    private val fakeChapterSource = object : M4bChapterSource {
        override fun chaptersOf(uri: Uri) = chaptersInM4b
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AkouoDatabase::class.java,
        ).allowMainThreadQueries().build()
        importer = AudiobookImporter(
            bookDao = db.bookDao(),
            chapterDao = db.chapterDao(),
            extractor = fakeExtractor,
            chapterSource = fakeChapterSource,
            coverStore = CoverStore(ApplicationProvider.getApplicationContext()),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `imports an m4b with chpl chapters`() = runTest {
        chaptersInM4b = listOf(
            Mp4ChapterParser.Chapter("One", 0),
            Mp4ChapterParser.Chapter("Two", 30_000),
        )

        importer.import(listOf(ScannedBook.M4b("File Name", "uri://book1")))

        val book = db.bookDao().observeAll().first().single()
        assertEquals("Tagged Title", book.title)
        assertEquals(SourceKind.M4B, book.sourceKind)
        assertEquals(60_000, book.durationMs)

        val chapters = db.chapterDao().getForBook(book.id)
        assertEquals(listOf("One", "Two"), chapters.map { it.title })
        assertEquals(listOf(0L, 30_000L), chapters.map { it.startMs })
        // last chapter runs to end of file
        assertEquals(listOf(30_000L, 30_000L), chapters.map { it.durationMs })
        assertTrue(chapters.all { it.fileUri == "uri://book1" })
    }

    @Test
    fun `m4b without chapters gets one full-length chapter`() = runTest {
        chaptersInM4b = emptyList()

        importer.import(listOf(ScannedBook.M4b("File Name", "uri://book1")))

        val book = db.bookDao().observeAll().first().single()
        val chapter = db.chapterDao().getForBook(book.id).single()
        assertEquals(0L, chapter.startMs)
        assertEquals(60_000L, chapter.durationMs)
    }

    @Test
    fun `imports an mp3 collection with one chapter per file`() = runTest {
        val scanned = ScannedBook.Mp3Collection(
            title = "Dir Name",
            rootUri = "uri://dir",
            files = listOf(ScannedFile("01 Intro.mp3", "uri://f1"), ScannedFile("02 Body.mp3", "uri://f2")),
        )

        importer.import(listOf(scanned))

        val book = db.bookDao().observeAll().first().single()
        assertEquals(SourceKind.MP3_DIR, book.sourceKind)
        assertEquals(120_000, book.durationMs) // 2 files x fake 60s

        val chapters = db.chapterDao().getForBook(book.id)
        assertEquals(listOf("01 Intro", "02 Body"), chapters.map { it.title })
        assertEquals(listOf("uri://f1", "uri://f2"), chapters.map { it.fileUri })
        assertTrue(chapters.all { it.startMs == 0L })
    }

    @Test
    fun `rescan keeps existing books and their positions`() = runTest {
        importer.import(listOf(ScannedBook.M4b("Book", "uri://book1")))
        val id = db.bookDao().observeAll().first().single().id
        db.bookDao().updatePosition(id, 42_000)

        importer.import(listOf(ScannedBook.M4b("Book", "uri://book1")))

        val book = db.bookDao().observeAll().first().single()
        assertEquals(42_000, book.positionMs)
    }

    @Test
    fun `books that vanished from disk are removed`() = runTest {
        importer.import(listOf(ScannedBook.M4b("Book", "uri://book1")))

        importer.import(emptyList())

        assertTrue(db.bookDao().observeAll().first().isEmpty())
    }
}
```

- [x] **Step 2: Run to verify failure**

Run: `./gradlew -p /home/tim/projects/akouo --console=plain :feature:audiobooks:testDebugUnitTest --tests "com.akouo.feature.audiobooks.data.AudiobookImporterTest"`
Expected: FAIL (unresolved references).

- [x] **Step 3: Implement**

`M4bChapterSource.kt`:

```kotlin
package com.akouo.feature.audiobooks.data

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Opens an m4b document and reads its chpl chapters. Interface exists for JVM-side fakes. */
interface M4bChapterSource {
    fun chaptersOf(uri: Uri): List<Mp4ChapterParser.Chapter>
}

class ContentResolverM4bChapterSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : M4bChapterSource {
    override fun chaptersOf(uri: Uri): List<Mp4ChapterParser.Chapter> =
        context.contentResolver.openInputStream(uri)?.use(Mp4ChapterParser::parse).orEmpty()
}
```

`AudiobookImporter.kt`:

```kotlin
package com.akouo.feature.audiobooks.data

import android.net.Uri
import com.akouo.core.database.BookDao
import com.akouo.core.database.BookEntity
import com.akouo.core.database.ChapterDao
import com.akouo.core.database.ChapterEntity
import com.akouo.core.database.SourceKind
import javax.inject.Inject

/**
 * Reconciles a scan result with the library: new books get metadata extracted and chapter
 * rows built; existing books are left untouched (preserving position/bookmarks); books no
 * longer on disk are deleted (cascades to chapters + bookmarks).
 */
class AudiobookImporter @Inject constructor(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val extractor: AudiobookMetadataExtractor,
    private val chapterSource: M4bChapterSource,
    private val coverStore: CoverStore,
) {

    suspend fun import(scanned: List<ScannedBook>) {
        val scannedById = scanned.associateBy { BookIds.fromUri(it.rootUri) }
        val existingIds = bookDao.getAllIds().toSet()

        val vanished = existingIds - scannedById.keys
        if (vanished.isNotEmpty()) bookDao.deleteByIds(vanished.toList())

        scannedById
            .filterKeys { it !in existingIds }
            .forEach { (id, book) -> importNew(id, book) }
    }

    private suspend fun importNew(id: String, book: ScannedBook) {
        when (book) {
            is ScannedBook.M4b -> importM4b(id, book)
            is ScannedBook.Mp3Collection -> importMp3Collection(id, book)
        }
    }

    private suspend fun importM4b(id: String, book: ScannedBook.M4b) {
        val uri = Uri.parse(book.rootUri)
        val meta = extractor.extract(uri)
        val marks = chapterSource.chaptersOf(uri)
            .ifEmpty { listOf(Mp4ChapterParser.Chapter(title = book.title, startMs = 0)) }

        val chapters = marks.mapIndexed { index, mark ->
            val end = marks.getOrNull(index + 1)?.startMs ?: meta.durationMs
            ChapterEntity(
                bookId = id,
                chapterIndex = index,
                title = mark.title,
                fileUri = book.rootUri,
                startMs = mark.startMs,
                durationMs = (end - mark.startMs).coerceAtLeast(0),
            )
        }
        insert(id, book, SourceKind.M4B, meta, durationMs = meta.durationMs, chapters = chapters)
    }

    private suspend fun importMp3Collection(id: String, book: ScannedBook.Mp3Collection) {
        var meta: ExtractedMetadata? = null
        val chapters = book.files.mapIndexed { index, file ->
            val fileMeta = extractor.extract(Uri.parse(file.uri))
            if (meta == null) meta = fileMeta
            ChapterEntity(
                bookId = id,
                chapterIndex = index,
                title = file.name.substringBeforeLast('.'),
                fileUri = file.uri,
                startMs = 0,
                durationMs = fileMeta.durationMs,
            )
        }
        val first = meta ?: ExtractedMetadata(null, null, 0, null)
        // Directory name beats the first file's tag for a collection's title.
        val collectionMeta = first.copy(title = book.title)
        insert(id, book, SourceKind.MP3_DIR, collectionMeta, durationMs = chapters.sumOf { it.durationMs }, chapters = chapters)
    }

    private suspend fun insert(
        id: String,
        book: ScannedBook,
        kind: SourceKind,
        meta: ExtractedMetadata,
        durationMs: Long,
        chapters: List<ChapterEntity>,
    ) {
        bookDao.upsert(
            listOf(
                BookEntity(
                    id = id,
                    title = meta.title?.takeIf { it.isNotBlank() } ?: book.title,
                    author = meta.author,
                    coverPath = coverStore.save(id, meta.coverBytes),
                    sourceUri = book.rootUri,
                    sourceKind = kind,
                    durationMs = durationMs,
                    addedAtUtc = System.currentTimeMillis(),
                ),
            ),
        )
        chapterDao.upsertAll(chapters)
    }
}
```

- [x] **Step 4: Run to verify pass**

Same command. Expected: PASS (5 tests).

- [x] **Step 5: Commit**

```bash
git add feature/audiobooks
git commit -m "feat: import scanned books with metadata and chapters into Room"
```

### Task 12: Folder preference (DataStore) + repository

**Files:**
- Create: `feature/audiobooks/src/main/java/com/akouo/feature/audiobooks/data/AudiobooksPrefs.kt`
- Create: `feature/audiobooks/src/main/java/com/akouo/feature/audiobooks/data/AudiobookRepository.kt`

These are thin glue (DataStore key + orchestration of already-tested parts); no new unit tests — covered by the importer/scanner/DAO tests plus device verification.

- [x] **Step 1: Implement `AudiobooksPrefs.kt`**

```kotlin
package com.akouo.feature.audiobooks.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.audiobooksDataStore by preferencesDataStore(name = "audiobooks")
private val KEY_TREE_URI = stringPreferencesKey("tree_uri")

/** Remembers which folder the user granted us. */
@Singleton
class AudiobooksPrefs @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val treeUri: Flow<String?> = context.audiobooksDataStore.data.map { it[KEY_TREE_URI] }

    suspend fun setTreeUri(uri: String) {
        context.audiobooksDataStore.edit { it[KEY_TREE_URI] = uri }
    }
}
```

- [x] **Step 2: Implement `AudiobookRepository.kt`**

```kotlin
package com.akouo.feature.audiobooks.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.akouo.core.database.BookDao
import com.akouo.core.database.BookEntity
import com.akouo.core.database.BookmarkDao
import com.akouo.core.database.BookmarkEntity
import com.akouo.core.database.ChapterDao
import com.akouo.core.database.ChapterEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** The feature's single data entry point: library queries, rescans, positions, bookmarks. */
@Singleton
class AudiobookRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val bookmarkDao: BookmarkDao,
    private val importer: AudiobookImporter,
    private val prefs: AudiobooksPrefs,
) {
    val treeUri: Flow<String?> = prefs.treeUri

    fun observeBooks(): Flow<List<BookEntity>> = bookDao.observeAll()
    fun observeBook(id: String): Flow<BookEntity?> = bookDao.observeById(id)
    fun observeChapters(bookId: String): Flow<List<ChapterEntity>> = chapterDao.observeForBook(bookId)
    fun observeBookmarks(bookId: String): Flow<List<BookmarkEntity>> = bookmarkDao.observeForBook(bookId)

    suspend fun setFolderAndRescan(treeUri: String) {
        prefs.setTreeUri(treeUri)
        rescan()
    }

    /** Re-walks the granted folder and reconciles the library. No-op if no folder chosen yet. */
    suspend fun rescan() = withContext(Dispatchers.IO) {
        val uri = prefs.treeUri.first() ?: return@withContext
        val root = DocumentFile.fromTreeUri(context, Uri.parse(uri)) ?: return@withContext
        importer.import(AudiobookScanner.scan(DocumentFileNode(root)))
    }

    suspend fun chaptersFor(bookId: String): List<ChapterEntity> = chapterDao.getForBook(bookId)

    suspend fun addBookmark(bookId: String, globalPositionMs: Long) {
        bookmarkDao.insert(
            BookmarkEntity(
                bookId = bookId,
                positionMs = globalPositionMs,
                note = null,
                createdAtUtc = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun deleteBookmark(id: Long) = bookmarkDao.delete(id)
}
```

- [x] **Step 3: Verify compile**

Run: `./gradlew -p /home/tim/projects/akouo --console=plain :feature:audiobooks:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [x] **Step 4: Commit**

```bash
git add feature/audiobooks
git commit -m "feat: audiobook repository and folder preference"
```

---

## Chunk 4: Playback integration

### Task 13: Generic play API on `core:playback`

**Files:**
- Create: `core/playback/src/main/java/com/akouo/core/playback/PlayRequest.kt`
- Modify: `core/playback/src/main/java/com/akouo/core/playback/PlaybackUiState.kt`
- Modify: `core/playback/src/main/java/com/akouo/core/playback/PlaybackConnection.kt`

No new unit tests: every line here either delegates to `MediaController` (device-only) or is already-tested `SpeedResolver`. Runtime behavior is verified in Chunk 6.

- [x] **Step 1: Create `PlayRequest.kt`**

```kotlin
package com.akouo.core.playback

import com.akouo.core.model.MediaType

/** One playable file/stream in a queue. [mediaId] must be globally unique and parseable by its owning feature. */
data class PlayableItem(
    val mediaId: String,
    val uri: String,
    val title: String,
    val artist: String = "",
)

/** A complete "play this" command from a feature: the queue plus where to start in it. */
data class PlayRequest(
    val items: List<PlayableItem>,
    val startIndex: Int = 0,
    val startPositionMs: Long = 0,
    val mediaType: MediaType,
)
```

- [x] **Step 2: Extend `PlaybackUiState.kt`** (replace file contents)

```kotlin
package com.akouo.core.playback

/** Immutable snapshot of what the player UI needs to render. */
data class PlaybackUiState(
    val isPlaying: Boolean = false,
    val title: String = "",
    val mediaId: String? = null,
    val currentIndex: Int = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)
```

- [x] **Step 3: Extend `PlaybackConnection`**

In `PlaybackConnection.kt`:

3a. Add imports:

```kotlin
import androidx.media3.common.C
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
```

3b. Add fields after `private var controllerFuture...`:

```kotlin
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var positionTicker: Job? = null
```

3c. Replace the `listener` object so state refreshes on more events and the ticker follows play state:

```kotlin
    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateState()
            if (isPlaying) startPositionTicker() else stopPositionTicker()
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = updateState()
        override fun onPlaybackStateChanged(playbackState: Int) = updateState()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = updateState()
    }

    /** currentPosition only changes on events; while playing we sample it for the UI once a second. */
    private fun startPositionTicker() {
        positionTicker?.cancel()
        positionTicker = scope.launch {
            while (isActive) {
                updateState()
                delay(1_000)
            }
        }
    }

    private fun stopPositionTicker() {
        positionTicker?.cancel()
        positionTicker = null
    }
```

3d. Replace `updateState()`:

```kotlin
    private fun updateState() {
        val c = controller ?: return
        _state.value = PlaybackUiState(
            isPlaying = c.isPlaying,
            title = c.mediaMetadata.title?.toString().orEmpty(),
            mediaId = c.currentMediaItem?.mediaId,
            currentIndex = c.currentMediaItemIndex,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.takeIf { it != C.TIME_UNSET } ?: 0,
        )
    }
```

3e. Add the new API after `playPause()`:

```kotlin
    /** Loads a feature-built queue and starts playing from the requested spot. */
    fun play(request: PlayRequest) {
        val c = controller ?: return
        val items = request.items.map { item ->
            MediaItem.Builder()
                .setMediaId(item.mediaId)
                .setUri(item.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder().setTitle(item.title).setArtist(item.artist).build(),
                )
                .build()
        }
        c.setMediaItems(items, request.startIndex, request.startPositionMs)
        c.prepare()
        c.setPlaybackSpeed(
            SpeedResolver.resolve(SpeedPreferences(), request.mediaType, itemOverride = null),
        )
        c.play()
    }

    /** Jumps to a queue item + offset (chapter taps, bookmark taps). */
    fun seekTo(index: Int, positionMs: Long) {
        controller?.seekTo(index, positionMs)
    }
```

- [x] **Step 4: Verify compile + existing tests**

Run: `./gradlew -p /home/tim/projects/akouo --console=plain :core:playback:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, 4 existing SpeedResolver tests PASS.

- [x] **Step 5: Commit**

```bash
git add core/playback
git commit -m "feat: generic play/seek API and richer playback state"
```

### Task 14: Position reporting from the service (multibinding)

**Files:**
- Create: `core/playback/src/main/java/com/akouo/core/playback/PlaybackPositionListener.kt`
- Create: `core/playback/src/main/java/com/akouo/core/playback/PlaybackModule.kt`
- Modify: `core/playback/src/main/java/com/akouo/core/playback/PlaybackService.kt`

This is the `FeatureEntry` trick again, pointed the other way: features contribute listeners into a set; the service notifies the set; nobody names anybody.

- [x] **Step 1: Create `PlaybackPositionListener.kt`**

```kotlin
package com.akouo.core.playback

/**
 * Contributed by feature modules (Hilt @IntoSet) to be told where playback is, every few
 * seconds while playing and once on pause/stop. Runs on the main dispatcher — implementations
 * must hop to IO for persistence.
 */
interface PlaybackPositionListener {
    suspend fun onPositionChanged(mediaId: String, positionMs: Long, durationMs: Long)
}
```

- [x] **Step 2: Create `PlaybackModule.kt`**

```kotlin
package com.akouo.core.playback

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackModule {
    /** Declares the set so it exists (empty) even before any feature contributes a listener. */
    @Multibinds
    abstract fun positionListeners(): Set<PlaybackPositionListener>
}
```

- [x] **Step 3: Rewrite `PlaybackService.kt`** (replace file contents)

```kotlin
package com.akouo.core.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Background-capable playback service. Hosting playback in a MediaSessionService is what gives us
 * lock-screen / notification controls, Bluetooth & headset buttons, and playback that survives the
 * UI being swiped away. The UI connects to it through a MediaController (see PlaybackConnection).
 *
 * While playing it reports the current position to all registered PlaybackPositionListeners
 * (features contribute these via Hilt multibinding) so resume positions survive process death.
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject
    lateinit var positionListeners: Set<@JvmSuppressWildcards PlaybackPositionListener>

    private var mediaSession: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var reportJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    startReporting(player)
                } else {
                    stopReporting()
                    reportNow(player) // final position on pause/stop
                }
            }
        })
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

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

- [x] **Step 4: Verify compile + tests**

Run: `./gradlew -p /home/tim/projects/akouo --console=plain :core:playback:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, existing tests PASS.

- [x] **Step 5: Commit**

```bash
git add core/playback
git commit -m "feat: service-side position reporting via listener multibinding"
```

### Task 15: Queue builder (TDD)

**Files:**
- Create: `feature/audiobooks/src/main/java/com/akouo/feature/audiobooks/data/QueueBuilder.kt`
- Test: `feature/audiobooks/src/test/java/com/akouo/feature/audiobooks/data/QueueBuilderTest.kt`

- [x] **Step 1: Write the failing tests**

```kotlin
package com.akouo.feature.audiobooks.data

import com.akouo.core.database.BookEntity
import com.akouo.core.database.ChapterEntity
import com.akouo.core.database.SourceKind
import com.akouo.core.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueBuilderTest {

    private fun book(kind: SourceKind) = BookEntity(
        id = "b1",
        title = "Book",
        author = null,
        coverPath = null,
        sourceUri = "uri://book",
        sourceKind = kind,
        durationMs = 60_000,
        positionMs = 0,
        addedAtUtc = 0,
    )

    private fun chapter(index: Int, fileUri: String, startMs: Long, durationMs: Long) = ChapterEntity(
        bookId = "b1",
        chapterIndex = index,
        title = "Ch $index",
        fileUri = fileUri,
        startMs = startMs,
        durationMs = durationMs,
    )

    @Test
    fun `m4b builds a single-item queue starting at the global position`() {
        val chapters = listOf(chapter(0, "uri://book", 0, 30_000), chapter(1, "uri://book", 30_000, 30_000))

        val request = QueueBuilder.build(book(SourceKind.M4B), chapters, startAtMs = 42_000)

        assertEquals(1, request.items.size)
        assertEquals("uri://book", request.items[0].uri)
        assertEquals(AudiobookMediaId.encode("b1", 0), request.items[0].mediaId)
        assertEquals(0, request.startIndex)
        assertEquals(42_000, request.startPositionMs)
        assertEquals(MediaType.AUDIOBOOK, request.mediaType)
    }

    @Test
    fun `mp3 collection builds one item per file and maps the global position`() {
        val chapters = listOf(chapter(0, "uri://f1", 0, 30_000), chapter(1, "uri://f2", 0, 30_000))

        val request = QueueBuilder.build(book(SourceKind.MP3_DIR), chapters, startAtMs = 42_000)

        assertEquals(listOf("uri://f1", "uri://f2"), request.items.map { it.uri })
        assertEquals(listOf(AudiobookMediaId.encode("b1", 0), AudiobookMediaId.encode("b1", 1)), request.items.map { it.mediaId })
        assertEquals(1, request.startIndex)
        assertEquals(12_000, request.startPositionMs)
    }

    @Test
    fun `chapter titles become item titles for mp3 books`() {
        val chapters = listOf(chapter(0, "uri://f1", 0, 30_000))

        val request = QueueBuilder.build(book(SourceKind.MP3_DIR), chapters, startAtMs = 0)

        assertEquals("Ch 0", request.items[0].title)
    }
}
```

- [x] **Step 2: Run to verify failure**

Run: `./gradlew -p /home/tim/projects/akouo --console=plain :feature:audiobooks:testDebugUnitTest --tests "com.akouo.feature.audiobooks.data.QueueBuilderTest"`
Expected: FAIL (unresolved reference).

- [x] **Step 3: Implement**

```kotlin
package com.akouo.feature.audiobooks.data

import com.akouo.core.database.BookEntity
import com.akouo.core.database.ChapterEntity
import com.akouo.core.database.SourceKind
import com.akouo.core.model.MediaType
import com.akouo.core.playback.PlayRequest
import com.akouo.core.playback.PlayableItem

/**
 * Turns a book + chapters + global start position into a PlayRequest.
 * M4B: one queue item (chapters are seek targets inside it), global position == file position.
 * MP3_DIR: one queue item per file; PositionMapper finds the starting (file, offset).
 */
object QueueBuilder {

    fun build(book: BookEntity, chapters: List<ChapterEntity>, startAtMs: Long): PlayRequest =
        when (book.sourceKind) {
            SourceKind.M4B -> PlayRequest(
                items = listOf(
                    PlayableItem(
                        mediaId = AudiobookMediaId.encode(book.id, 0),
                        uri = book.sourceUri,
                        title = book.title,
                        artist = book.author.orEmpty(),
                    ),
                ),
                startIndex = 0,
                startPositionMs = startAtMs,
                mediaType = MediaType.AUDIOBOOK,
            )

            SourceKind.MP3_DIR -> {
                val start = PositionMapper.toFilePosition(chapters.map { it.durationMs }, startAtMs)
                PlayRequest(
                    items = chapters.map { chapter ->
                        PlayableItem(
                            mediaId = AudiobookMediaId.encode(book.id, chapter.chapterIndex),
                            uri = chapter.fileUri,
                            title = chapter.title,
                            artist = book.author.orEmpty(),
                        )
                    },
                    startIndex = start.fileIndex,
                    startPositionMs = start.offsetMs,
                    mediaType = MediaType.AUDIOBOOK,
                )
            }
        }
}
```

- [x] **Step 4: Run to verify pass**

Same command. Expected: PASS (3 tests).

- [x] **Step 5: Commit**

```bash
git add feature/audiobooks
git commit -m "feat: build playback queues from books"
```

### Task 16: Audiobook position listener (TDD)

**Files:**
- Create: `feature/audiobooks/src/main/java/com/akouo/feature/audiobooks/data/AudiobookPositionListener.kt`
- Test: `feature/audiobooks/src/test/java/com/akouo/feature/audiobooks/data/AudiobookPositionListenerTest.kt`

- [x] **Step 1: Write the failing tests**

```kotlin
package com.akouo.feature.audiobooks.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.akouo.core.database.AkouoDatabase
import com.akouo.core.database.BookEntity
import com.akouo.core.database.ChapterEntity
import com.akouo.core.database.SourceKind
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AudiobookPositionListenerTest {

    private lateinit var db: AkouoDatabase
    private lateinit var listener: AudiobookPositionListener

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AkouoDatabase::class.java,
        ).allowMainThreadQueries().build()
        listener = AudiobookPositionListener(db.bookDao(), db.chapterDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedBook(kind: SourceKind) {
        db.bookDao().upsert(
            listOf(
                BookEntity(
                    id = "b1", title = "B", author = null, coverPath = null,
                    sourceUri = "uri://b", sourceKind = kind, durationMs = 60_000,
                    positionMs = 0, addedAtUtc = 0,
                ),
            ),
        )
        db.chapterDao().upsertAll(
            listOf(
                ChapterEntity("b1", 0, "c0", "uri://f0", 0, 30_000),
                ChapterEntity("b1", 1, "c1", "uri://f1", 0, 30_000),
            ),
        )
    }

    @Test
    fun `m4b position is stored as-is`() = runTest {
        seedBook(SourceKind.M4B)

        listener.onPositionChanged(AudiobookMediaId.encode("b1", 0), 42_000, 60_000)

        assertEquals(42_000, db.bookDao().getById("b1")!!.positionMs)
    }

    @Test
    fun `mp3 position is offset by preceding files`() = runTest {
        seedBook(SourceKind.MP3_DIR)

        listener.onPositionChanged(AudiobookMediaId.encode("b1", 1), 12_000, 30_000)

        assertEquals(42_000, db.bookDao().getById("b1")!!.positionMs)
    }

    @Test
    fun `foreign mediaIds are ignored`() = runTest {
        seedBook(SourceKind.M4B)

        listener.onPositionChanged("podcast/xyz/0", 99_000, 0)

        assertEquals(0, db.bookDao().getById("b1")!!.positionMs)
    }

    @Test
    fun `unknown books are ignored without crashing`() = runTest {
        listener.onPositionChanged(AudiobookMediaId.encode("ghost", 0), 1_000, 0)
    }
}
```

- [x] **Step 2: Run to verify failure**

Run: `./gradlew -p /home/tim/projects/akouo --console=plain :feature:audiobooks:testDebugUnitTest --tests "com.akouo.feature.audiobooks.data.AudiobookPositionListenerTest"`
Expected: FAIL (unresolved reference).

- [x] **Step 3: Implement**

```kotlin
package com.akouo.feature.audiobooks.data

import com.akouo.core.database.BookDao
import com.akouo.core.database.ChapterDao
import com.akouo.core.database.SourceKind
import com.akouo.core.playback.PlaybackPositionListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Receives position pings from PlaybackService (every 3 s while playing + once on pause)
 * and persists the book's global resume position. Non-audiobook mediaIds are ignored —
 * other features get their own listeners.
 */
class AudiobookPositionListener @Inject constructor(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
) : PlaybackPositionListener {

    override suspend fun onPositionChanged(mediaId: String, positionMs: Long, durationMs: Long) {
        val parsed = AudiobookMediaId.parse(mediaId) ?: return
        withContext(Dispatchers.IO) {
            val book = bookDao.getById(parsed.bookId) ?: return@withContext
            val global = when (book.sourceKind) {
                SourceKind.M4B -> positionMs
                SourceKind.MP3_DIR -> PositionMapper.toGlobal(
                    chapterDao.getForBook(book.id).map { it.durationMs },
                    parsed.fileIndex,
                    positionMs,
                )
            }
            bookDao.updatePosition(book.id, global)
        }
    }
}
```

- [x] **Step 4: Run to verify pass**

Same command. Expected: PASS (4 tests).

- [x] **Step 5: Commit**

```bash
git add feature/audiobooks
git commit -m "feat: persist audiobook resume positions from playback pings"
```

---

## Chunk 5: Placeholder UI + app wiring

Reminder: screens here are deliberately bare (user decision: design iteration comes after the backend is done). Functional, ugly, fine. No ViewModel unit tests — they are thin Flow plumbing over already-tested parts; behavior is covered in Chunk 6.

### Task 17: Routes, library ViewModel + screen

**Files:**
- Create: `feature/audiobooks/src/main/java/com/akouo/feature/audiobooks/AudiobooksRoutes.kt`
- Create: `feature/audiobooks/src/main/java/com/akouo/feature/audiobooks/AudiobookListViewModel.kt`
- Create: `feature/audiobooks/src/main/java/com/akouo/feature/audiobooks/AudiobookListScreen.kt`

- [x] **Step 1: Create `AudiobooksRoutes.kt`**

```kotlin
package com.akouo.feature.audiobooks

const val AudiobooksRoute = "audiobooks"

internal const val BookDetailRoutePattern = "audiobooks/{bookId}"

internal fun bookDetailRoute(bookId: String) = "audiobooks/$bookId"
```

- [x] **Step 2: Create `AudiobookListViewModel.kt`**

```kotlin
package com.akouo.feature.audiobooks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.akouo.core.database.BookEntity
import com.akouo.feature.audiobooks.data.AudiobookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AudiobookListViewModel @Inject constructor(
    private val repository: AudiobookRepository,
) : ViewModel() {

    val books: StateFlow<List<BookEntity>> = repository.observeBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val hasFolder: StateFlow<Boolean> = repository.treeUri
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Called with a tree URI the UI has already taken a persistable grant on. */
    fun onFolderPicked(treeUri: String) {
        viewModelScope.launch { repository.setFolderAndRescan(treeUri) }
    }

    fun onRescan() {
        viewModelScope.launch { repository.rescan() }
    }
}
```

- [x] **Step 3: Create `AudiobookListScreen.kt`**

```kotlin
package com.akouo.feature.audiobooks

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.akouo.core.database.BookEntity

@Composable
fun AudiobookListScreen(
    onBookClick: (String) -> Unit,
    viewModel: AudiobookListViewModel = hiltViewModel(),
) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val hasFolder by viewModel.hasFolder.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            // Keep the grant across reboots; without this, rescans fail after restart.
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            viewModel.onFolderPicked(uri.toString())
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "Audiobooks")
        Row {
            Button(onClick = { pickFolder.launch(null) }) {
                Text(if (hasFolder) "Change folder" else "Choose audiobook folder")
            }
            if (hasFolder) {
                OutlinedButton(onClick = viewModel::onRescan) { Text("Rescan") }
            }
        }
        LazyColumn {
            items(books, key = BookEntity::id) { book ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onBookClick(book.id) }
                        .padding(vertical = 12.dp),
                ) {
                    Text(text = book.title)
                    Text(text = book.author ?: "Unknown author")
                }
            }
        }
    }
}
```

- [x] **Step 4: Verify compile**

Run: `./gradlew -p /home/tim/projects/akouo --console=plain :feature:audiobooks:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [x] **Step 5: Commit**

```bash
git add feature/audiobooks
git commit -m "feat: audiobook library screen with SAF folder picker"
```

### Task 18: Book detail ViewModel + screen

**Files:**
- Create: `feature/audiobooks/src/main/java/com/akouo/feature/audiobooks/BookDetailViewModel.kt`
- Create: `feature/audiobooks/src/main/java/com/akouo/feature/audiobooks/BookDetailScreen.kt`

- [x] **Step 1: Create `BookDetailViewModel.kt`**

```kotlin
package com.akouo.feature.audiobooks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.akouo.core.database.BookEntity
import com.akouo.core.database.BookmarkEntity
import com.akouo.core.database.ChapterEntity
import com.akouo.core.database.SourceKind
import com.akouo.core.playback.PlaybackConnection
import com.akouo.core.playback.PlaybackUiState
import com.akouo.feature.audiobooks.data.AudiobookMediaId
import com.akouo.feature.audiobooks.data.AudiobookRepository
import com.akouo.feature.audiobooks.data.PositionMapper
import com.akouo.feature.audiobooks.data.QueueBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BookDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: AudiobookRepository,
    private val playbackConnection: PlaybackConnection,
) : ViewModel() {

    private val bookId: String = checkNotNull(savedStateHandle["bookId"])

    val book: StateFlow<BookEntity?> = repository.observeBook(bookId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val chapters: StateFlow<List<ChapterEntity>> = repository.observeChapters(bookId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val bookmarks: StateFlow<List<BookmarkEntity>> = repository.observeBookmarks(bookId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playback: StateFlow<PlaybackUiState> = playbackConnection.state

    /** True when whatever the service is playing belongs to THIS book. */
    fun isThisBook(state: PlaybackUiState): Boolean =
        state.mediaId?.let { AudiobookMediaId.parse(it)?.bookId } == bookId

    fun onPlayResume() {
        viewModelScope.launch {
            val b = repository.observeBook(bookId).first() ?: return@launch
            playFrom(b.positionMs)
        }
    }

    fun onPlayPause() = playbackConnection.playPause()

    fun onChapterClick(chapter: ChapterEntity) {
        viewModelScope.launch {
            val b = repository.observeBook(bookId).first() ?: return@launch
            val all = repository.chaptersFor(bookId)
            val globalStart = when (b.sourceKind) {
                SourceKind.M4B -> chapter.startMs
                SourceKind.MP3_DIR -> PositionMapper.toGlobal(
                    all.map { it.durationMs },
                    chapter.chapterIndex,
                    0,
                )
            }
            playFrom(globalStart)
        }
    }

    fun onBookmarkClick(bookmark: BookmarkEntity) {
        viewModelScope.launch { playFrom(bookmark.positionMs) }
    }

    fun onAddBookmark() {
        viewModelScope.launch {
            val b = repository.observeBook(bookId).first() ?: return@launch
            val state = playback.value
            val global = if (isThisBook(state)) currentGlobalPosition(b, state) else b.positionMs
            repository.addBookmark(bookId, global)
        }
    }

    fun onDeleteBookmark(id: Long) {
        viewModelScope.launch { repository.deleteBookmark(id) }
    }

    private suspend fun currentGlobalPosition(book: BookEntity, state: PlaybackUiState): Long =
        when (book.sourceKind) {
            SourceKind.M4B -> state.positionMs
            SourceKind.MP3_DIR -> PositionMapper.toGlobal(
                repository.chaptersFor(bookId).map { it.durationMs },
                AudiobookMediaId.parse(state.mediaId.orEmpty())?.fileIndex ?: 0,
                state.positionMs,
            )
        }

    /** v1 keeps it simple: any jump rebuilds the queue and starts playing from globalMs. */
    private suspend fun playFrom(globalMs: Long) {
        val b = repository.observeBook(bookId).first() ?: return
        playbackConnection.play(QueueBuilder.build(b, repository.chaptersFor(bookId), globalMs))
    }
}
```

- [x] **Step 2: Create `BookDetailScreen.kt`**

```kotlin
package com.akouo.feature.audiobooks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun BookDetailScreen(viewModel: BookDetailViewModel = hiltViewModel()) {
    val book by viewModel.book.collectAsStateWithLifecycle()
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()

    val b = book ?: return
    val playingThis = viewModel.isThisBook(playback)

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = b.title)
        Text(text = b.author ?: "Unknown author")
        Text(text = "Position: ${b.positionMs / 1000}s / ${b.durationMs / 1000}s")

        Row {
            Button(onClick = viewModel::onPlayResume) {
                Text(if (b.positionMs > 0) "Resume" else "Play")
            }
            if (playingThis) {
                OutlinedButton(onClick = viewModel::onPlayPause) {
                    Text(if (playback.isPlaying) "Pause" else "Continue")
                }
            }
            OutlinedButton(onClick = viewModel::onAddBookmark) { Text("Bookmark") }
        }

        LazyColumn {
            if (bookmarks.isNotEmpty()) {
                item { Text(text = "Bookmarks", modifier = Modifier.padding(top = 12.dp)) }
                items(bookmarks, key = { "bm-${it.id}" }) { bm ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "@ ${bm.positionMs / 1000}s",
                            modifier = Modifier
                                .clickable { viewModel.onBookmarkClick(bm) }
                                .padding(vertical = 8.dp),
                        )
                        Text(
                            text = "  ✕",
                            modifier = Modifier
                                .clickable { viewModel.onDeleteBookmark(bm.id) }
                                .padding(vertical = 8.dp),
                        )
                    }
                }
            }

            item { Text(text = "Chapters", modifier = Modifier.padding(top = 12.dp)) }
            items(chapters, key = { "ch-${it.chapterIndex}" }) { chapter ->
                Text(
                    text = chapter.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.onChapterClick(chapter) }
                        .padding(vertical = 8.dp),
                )
            }
        }
    }
}
```

- [x] **Step 3: Verify compile**

Run: `./gradlew -p /home/tim/projects/akouo --console=plain :feature:audiobooks:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [x] **Step 4: Commit**

```bash
git add feature/audiobooks
git commit -m "feat: book detail screen with resume, chapters, bookmarks"
```

### Task 19: Feature registration + app wiring + full build

**Files:**
- Create: `feature/audiobooks/src/main/java/com/akouo/feature/audiobooks/AudiobooksFeatureEntry.kt`
- Create: `feature/audiobooks/src/main/java/com/akouo/feature/audiobooks/AudiobooksFeatureModule.kt`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/com/akouo/app/AkouoNavHost.kt`

- [x] **Step 1: Create `AudiobooksFeatureEntry.kt`**

```kotlin
package com.akouo.feature.audiobooks

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.akouo.core.navigation.FeatureEntry
import javax.inject.Inject

class AudiobooksFeatureEntry @Inject constructor() : FeatureEntry {

    override val route: String = AudiobooksRoute

    override fun register(navGraphBuilder: NavGraphBuilder, navController: NavController) {
        navGraphBuilder.composable(AudiobooksRoute) {
            AudiobookListScreen(onBookClick = { bookId ->
                navController.navigate(bookDetailRoute(bookId))
            })
        }
        navGraphBuilder.composable(BookDetailRoutePattern) {
            BookDetailScreen()
        }
    }
}
```

- [x] **Step 2: Create `AudiobooksFeatureModule.kt`**

```kotlin
package com.akouo.feature.audiobooks

import com.akouo.core.navigation.FeatureEntry
import com.akouo.core.playback.PlaybackPositionListener
import com.akouo.feature.audiobooks.data.AudiobookMetadataExtractor
import com.akouo.feature.audiobooks.data.AudiobookPositionListener
import com.akouo.feature.audiobooks.data.ContentResolverM4bChapterSource
import com.akouo.feature.audiobooks.data.M4bChapterSource
import com.akouo.feature.audiobooks.data.MmrMetadataExtractor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
interface AudiobooksFeatureModule {

    @Binds
    @IntoSet
    fun bindFeatureEntry(entry: AudiobooksFeatureEntry): FeatureEntry

    @Binds
    @IntoSet
    fun bindPositionListener(listener: AudiobookPositionListener): PlaybackPositionListener

    @Binds
    fun bindMetadataExtractor(impl: MmrMetadataExtractor): AudiobookMetadataExtractor

    @Binds
    fun bindChapterSource(impl: ContentResolverM4bChapterSource): M4bChapterSource
}
```

- [x] **Step 3: Wire the app**

In `app/build.gradle.kts`, next to the existing `implementation(project(":feature:player"))` add:

```kotlin
    implementation(project(":feature:audiobooks"))
```

In `app/src/main/java/com/akouo/app/AkouoNavHost.kt`, make the audiobook library the start screen:

```kotlin
// replace:  import com.akouo.feature.player.PlayerRoute
import com.akouo.feature.audiobooks.AudiobooksRoute
```

and change the NavHost line to:

```kotlin
    NavHost(navController = navController, startDestination = AudiobooksRoute) {
```

(`feature:player`'s smoke-test screen stays registered but unreachable; it gets folded into the real Now-Playing work in Phase 3.)

- [x] **Step 4: Full build + full test suite**

Run: `./gradlew -p /home/tim/projects/akouo --console=plain :app:assembleDebug test`
Expected: `BUILD SUCCESSFUL`; all unit tests green (SpeedResolver 4, database 5, audiobooks ~26). This is the slow one (~10 min cold) — grab a coffee, don't kill it.

- [x] **Step 5: Commit**

```bash
git add feature/audiobooks app
git commit -m "feat: register audiobooks feature and make it the start screen"
```

---

## Chunk 6: On-device verification (wireless adb)

### Task 20: Generate test books, install, verify end to end

No code changes — this is the runtime proof. Needs the Pixel 7a on the same network.

- [ ] **Step 1: Generate test books with ffmpeg**

```bash
mkdir -p "/tmp/akouo-books/Mp3 Test Book"
cat > /tmp/chapters-device.txt <<'EOF'
;FFMETADATA1
title=M4B Test Book
artist=Test Author

[CHAPTER]
TIMEBASE=1/1000
START=0
END=40000
title=Part One

[CHAPTER]
TIMEBASE=1/1000
START=40000
END=80000
title=Part Two

[CHAPTER]
TIMEBASE=1/1000
START=80000
END=120000
title=Part Three
EOF
ffmpeg -y -f lavfi -i "sine=frequency=440:duration=120" -i /tmp/chapters-device.txt \
  -map 0:a -map_metadata 1 -map_chapters 1 -c:a aac -b:a 32k \
  "/tmp/akouo-books/M4B Test Book.m4b"
for i in 1 2 3; do
  ffmpeg -y -f lavfi -i "sine=frequency=$((220 * i)):duration=60" \
    -metadata title="Part $i" -metadata artist="Mp3 Author" \
    -c:a libmp3lame -b:a 48k "/tmp/akouo-books/Mp3 Test Book/Track $i.mp3"
done
```

Different sine frequencies per mp3 track make file transitions *audible* — you can hear the queue advance.

- [ ] **Step 2: Connect the phone and push the books**

Wireless adb (phone: Developer options → Wireless debugging; pair first if the host was forgotten — `adb pair <ip>:<pairing-port>` with the code, then `adb connect <ip>:<port>`; mDNS usually auto-discovers after pairing):

```bash
adb devices            # expect the Pixel listed as "device"
adb push /tmp/akouo-books /sdcard/Audiobooks
```

- [ ] **Step 3: Install the debug build**

Run: `./gradlew -p /home/tim/projects/akouo --console=plain installDebug`
Expected: `Installed on 1 device.`

- [ ] **Step 4: Manual verification checklist (user drives, agent waits for report)**

1. Open akouo → audiobook library screen shows. Tap **Choose audiobook folder** → pick `Audiobooks`.
2. Both books appear: *M4B Test Book* (Test Author) and *Mp3 Test Book* (directory name as title).
3. Open *M4B Test Book* → three chapters listed (Part One/Two/Three). Tap **Play** → tone plays; notification controls appear.
4. Tap chapter *Part Two* → position jumps (~40 s).
5. Background test: home button → audio keeps playing; pause from the notification.
6. **Resume test:** play ~20 s, pause, swipe the app away (kill it), reopen → book shows a nonzero position; **Resume** continues within a few seconds of where you left off.
7. Tap **Bookmark** while playing, then tap the bookmark entry → playback jumps back to it.
8. Open *Mp3 Test Book* → 3 chapters (Track 1/2/3). Tap chapter *Track 2* → different pitch plays (proves per-file queueing). Let a track end → next starts automatically and the stored position keeps growing across the file boundary (check by pausing and reopening the detail screen).
9. Reboot-grant test (optional but recommended): reboot the phone, open akouo, tap **Rescan** → no crash, books still listed (persisted URI permission works).

- [ ] **Step 5: Fix anything that fails, then finish**

Likely first-run suspects, in order: `chpl` layout mismatch (Task 8 note), SAF grant flags, `MediaMetadataRetriever` on SAF URIs for the m4b. Debug via `adb logcat`, fix, re-run the narrowest test, recommit.

When the checklist passes:

```bash
git push -u origin phase-2-local-audiobooks
```

then offer the user a PR (base: whatever `phase-1-foundation`/PR #1 has become — if PR #1 merged, rebase onto `main` first).

---

## Out of scope for this phase (deliberately)

- Cover art *display* (covers are extracted and stored; showing them is UI-phase work)
- Sleep timer, silence trimming, volume boost, play history → Phase 3
- audiobookshelf source → Phase 6 (the `SourceKind` enum and `BookSource`-shaped seams are ready for it)
- Editing speed preferences (the resolver defaults to 1.0× until the settings feature exists)
- QuickTime chapter-track (`tref/chap`) parsing for m4b files lacking `chpl`
- Room schema export + migrations (must land before first public release — Phase 9)

