# Foundation (Phase 1) Implementation Plan

> **For agentic workers:** REQUIRED: Use @superpowers:subagent-driven-development (if subagents available) or @superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up akouo's multi-module skeleton with Hilt DI, a self-registering feature system, and a Media3 background-playback service — proven by playing a bundled audio clip with lock-screen/notification controls.

**Architecture:** A multi-module Android app (`app → feature → core`, never the reverse). Features plug into navigation through a `FeatureEntry` contract collected by Hilt multibindings, so a feature can be added or removed by adding/removing one module. Playback runs in a `MediaSessionService` (Media3); the UI talks to it through a `MediaController` wrapped in a `PlaybackConnection` that exposes player state as a `StateFlow`.

**Tech Stack:** Kotlin 2.1.0, Jetpack Compose (BOM 2024.12.01), Hilt 2.53.1 (+ KSP), Jetpack Media3 1.5.1, Navigation-Compose 2.8.5, Gradle 8.11.1 / AGP 8.7.3. minSdk 26, compile/target 35.

---

## Prerequisites & environment

Read this before starting — it determines how each task is verified.

- **`ANDROID_HOME` must be exported** to `~/Android/Sdk`. Confirm with `echo $ANDROID_HOME`; if empty, run `export ANDROID_HOME=~/Android/Sdk` (and add it to your shell profile).
- **Always use the Gradle wrapper** (`./gradlew`), never a system `gradle`.
- **This dev server has no emulator, no KVM, and no attached device.** Therefore:
  - **Headless-verifiable here:** anything that ends in `assembleDebug`, `:module:build`, or `testDebugUnitTest`. These are the verification steps for every task except the final smoke test.
  - **Requires a physical Android device:** the runtime smoke test in Task 4.3 (actually hearing audio + seeing notification controls). Connect a phone with USB debugging (`adb devices` should list it), or run the app from a workstation that has an emulator. This is the **only** step that cannot be completed on this server.
- **First build downloads dependencies** (Hilt, Media3, etc.) and may take several minutes. Subsequent builds are fast.

### Deliberately out of scope for Phase 1 (deferred, not forgotten)

| Deferred | Why | Lands in |
|----------|-----|----------|
| Gradle convention plugins (`build-logic`) | Only ~5 modules now; the per-module boilerplate isn't yet painful enough to justify the abstraction (YAGNI) | When module count grows (later phase) |
| Room / DataStore | Nothing to persist until there's real content/preferences | Phase 2 (local audiobooks) |
| Type-safe navigation routes | Their payoff is typed *arguments*; Phase 1 has a single no-arg destination | When a destination needs arguments |
| Playback speed **UI**, silence-trim, volume boost, sleep timer | Phase 1 only proves the engine plays; the speed *resolver* logic is built now as the testing exemplar, fed defaults | Phase 3 (player experience) |
| Releasing the `MediaController` on lifecycle events | A process-scoped singleton is acceptable for a smoke test; proper teardown matters once multiple screens connect | Phase 3 |

---

## File structure

Modules created in this plan (all under the repo root). Each has one clear responsibility.

```
akouo/
├── settings.gradle.kts                 # MODIFY: register the new modules
├── build.gradle.kts                    # MODIFY: add plugin aliases (apply false)
├── gradle/libs.versions.toml           # MODIFY: add Hilt/KSP/Media3/Navigation deps
├── app/                                 # MODIFY: becomes the Hilt host + nav host
│   ├── build.gradle.kts                #   add Hilt+KSP, depend on feature:player + core:*
│   └── src/main/
│       ├── AndroidManifest.xml         #   register AkouoApplication
│       └── java/com/akouo/app/
│           ├── AkouoApplication.kt      #   @HiltAndroidApp (NEW)
│           ├── MainActivity.kt          #   @AndroidEntryPoint, injects feature entries (REWRITE)
│           └── AkouoNavHost.kt          #   builds NavHost from the FeatureEntry set (NEW)
├── core/
│   ├── model/                           # NEW — pure Kotlin domain models
│   │   ├── build.gradle.kts            #   kotlin-jvm only
│   │   └── src/main/java/com/akouo/core/model/MediaType.kt
│   ├── designsystem/                    # NEW — Compose theme/tokens only
│   │   ├── build.gradle.kts
│   │   └── src/main/java/com/akouo/core/designsystem/theme/AkouoTheme.kt
│   ├── navigation/                      # NEW — the FeatureEntry contract only
│   │   ├── build.gradle.kts
│   │   └── src/main/java/com/akouo/core/navigation/FeatureEntry.kt
│   └── playback/                        # NEW — Media3 engine + playback state
│       ├── build.gradle.kts
│       └── src/main/
│           ├── AndroidManifest.xml      #   declares PlaybackService + permissions
│           ├── res/raw/sample.mp3        #   bundled smoke-test clip (you add this file)
│           └── java/com/akouo/core/playback/
│               ├── MediaType is imported from core:model
│               ├── PlaybackUiState.kt    #   data class (isPlaying, title)
│               ├── SpeedPreferences.kt    #   data class (global + per-type speeds)
│               ├── SpeedResolver.kt        #   pure speed-resolution logic (TDD)
│               ├── PlaybackService.kt      #   MediaSessionService
│               └── PlaybackConnection.kt   #   @Singleton MediaController wrapper → StateFlow
│       └── src/test/java/com/akouo/core/playback/SpeedResolverTest.kt
└── feature/
    └── player/                          # NEW — the only feature in Phase 1
        ├── build.gradle.kts
        └── src/main/java/com/akouo/feature/player/
            ├── PlayerRoute.kt            #   route constant
            ├── PlayerFeatureEntry.kt      #   implements FeatureEntry
            ├── PlayerFeatureModule.kt     #   Hilt @Binds @IntoSet
            ├── PlayerViewModel.kt         #   @HiltViewModel
            └── PlayerScreen.kt            #   Compose UI
```

---

## Chunk 1: Build foundation & Hilt

Goal of this chunk: the version catalog knows every dependency we'll use, the root build declares the plugins, `:core:model` exists, and the app is a Hilt application. Everything assembles.

### Task 1.1: Add all new dependencies to the version catalog

**Files:**
- Modify: `gradle/libs.versions.toml`

- [ ] **Step 1: Add the new version refs**

In `[versions]`, add these lines (keep the existing ones):

```toml
hilt = "2.53.1"
hiltNavigationCompose = "1.2.0"
ksp = "2.1.0-1.0.29"
media3 = "1.5.1"
navigationCompose = "2.8.5"
coroutines = "1.9.0"
```

- [ ] **Step 2: Add the new library coordinates**

In `[libraries]`, add:

```toml
hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-compiler = { group = "com.google.dagger", name = "hilt-android-compiler", version.ref = "hilt" }
androidx-hilt-navigation-compose = { group = "androidx.hilt", name = "hilt-navigation-compose", version.ref = "hiltNavigationCompose" }
androidx-navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigationCompose" }
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
media3-exoplayer = { group = "androidx.media3", name = "media3-exoplayer", version.ref = "media3" }
media3-session = { group = "androidx.media3", name = "media3-session", version.ref = "media3" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
```

- [ ] **Step 3: Add the new plugin aliases**

In `[plugins]`, add:

```toml
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
```

- [ ] **Step 4: Verify the catalog parses**

Run: `./gradlew help -q`
Expected: completes with no error. (A malformed catalog fails here with a "libs.versions.toml" parse error.)

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml
git commit -m "chore: add Hilt, KSP, Media3, Navigation to version catalog"
```

### Task 1.2: Declare the new plugins in the root build

**Files:**
- Modify: `build.gradle.kts`

- [ ] **Step 1: Add the plugin aliases as `apply false`**

Replace the entire contents of `build.gradle.kts` with:

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
```

(`apply false` means "make this plugin available to subprojects without applying it to the root" — the root project builds nothing itself.)

- [ ] **Step 2: Verify it resolves the plugins**

Run: `./gradlew help -q`
Expected: completes with no error (Gradle resolves each plugin coordinate).

- [ ] **Step 3: Commit**

```bash
git add build.gradle.kts
git commit -m "chore: declare library/jvm/ksp/hilt plugins at root"
```

### Task 1.3: Create the `:core:model` pure-Kotlin module

**Files:**
- Modify: `settings.gradle.kts`
- Create: `core/model/build.gradle.kts`
- Create: `core/model/src/main/java/com/akouo/core/model/MediaType.kt`

- [ ] **Step 1: Register the module**

In `settings.gradle.kts`, below `include(":app")`, add:

```kotlin
include(":core:model")
```

- [ ] **Step 2: Create the module build file**

Create `core/model/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}
```

- [ ] **Step 3: Create the first domain model**

Create `core/model/src/main/java/com/akouo/core/model/MediaType.kt`:

```kotlin
package com.akouo.core.model

/**
 * The kind of media being played. Drives per-type behaviour such as the default
 * playback speed used when an item has no explicit override.
 */
enum class MediaType {
    PODCAST,
    AUDIOBOOK,
}
```

- [ ] **Step 4: Verify it builds**

Run: `./gradlew :core:model:build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts core/model
git commit -m "feat: add core:model module with MediaType"
```

### Task 1.4: Make `:app` a Hilt application

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/akouo/app/AkouoApplication.kt`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Add Hilt + KSP to the app build**

In `app/build.gradle.kts`, add the two plugins to the `plugins { }` block (after the existing ones):

```kotlin
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
```

Then add these to the `dependencies { }` block:

```kotlin
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
```

- [ ] **Step 2: Create the Application class**

Create `app/src/main/java/com/akouo/app/AkouoApplication.kt`:

```kotlin
package com.akouo.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point. @HiltAndroidApp generates the app-wide dependency-injection
 * container that every other @AndroidEntryPoint / @HiltViewModel hooks into.
 */
@HiltAndroidApp
class AkouoApplication : Application()
```

- [ ] **Step 3: Register the Application in the manifest**

In `app/src/main/AndroidManifest.xml`, add `android:name=".AkouoApplication"` to the opening `<application` tag (as its first attribute):

```xml
    <application
        android:name=".AkouoApplication"
        android:label="@string/app_name"
        android:theme="@style/Theme.Akouo"
        android:supportsRtl="true">
```

- [ ] **Step 4: Verify it assembles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. (Hilt code generation runs; if the Application isn't registered correctly Hilt fails the build here.)

- [ ] **Step 5: Commit**

```bash
git add app/
git commit -m "feat: convert app to a Hilt application"
```

---

## Chunk 2: Design system, navigation contract & feature registry

Goal of this chunk: the keystone modularity pattern. A `FeatureEntry` contract, a theme, a `:feature:player` module that registers itself via Hilt multibinding, and an app nav host that renders whatever features are registered — without naming any of them.

### Task 2.1: Create the `:core:designsystem` module

**Files:**
- Modify: `settings.gradle.kts`
- Create: `core/designsystem/build.gradle.kts`
- Create: `core/designsystem/src/main/java/com/akouo/core/designsystem/theme/AkouoTheme.kt`

- [ ] **Step 1: Register the module**

In `settings.gradle.kts`, add:

```kotlin
include(":core:designsystem")
```

- [ ] **Step 2: Create the build file**

Create `core/designsystem/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.akouo.core.designsystem"
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
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
```

- [ ] **Step 3: Create the theme**

Create `core/designsystem/src/main/java/com/akouo/core/designsystem/theme/AkouoTheme.kt`:

```kotlin
package com.akouo.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * App-wide Material 3 theme. Phase 1 uses the default light/dark schemes; a custom
 * palette and typography come later when UI design starts.
 */
@Composable
fun AkouoTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (useDarkTheme) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}
```

- [ ] **Step 4: Verify it assembles**

Run: `./gradlew :core:designsystem:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts core/designsystem
git commit -m "feat: add core:designsystem with AkouoTheme"
```

### Task 2.2: Create the `:core:navigation` module (the FeatureEntry contract)

**Files:**
- Modify: `settings.gradle.kts`
- Create: `core/navigation/build.gradle.kts`
- Create: `core/navigation/src/main/java/com/akouo/core/navigation/FeatureEntry.kt`

- [ ] **Step 1: Register the module**

In `settings.gradle.kts`, add:

```kotlin
include(":core:navigation")
```

- [ ] **Step 2: Create the build file**

Create `core/navigation/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.akouo.core.navigation"
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
}

dependencies {
    implementation(libs.androidx.navigation.compose)
}
```

- [ ] **Step 3: Create the contract**

Create `core/navigation/src/main/java/com/akouo/core/navigation/FeatureEntry.kt`:

```kotlin
package com.akouo.core.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder

/**
 * The contract every feature module implements to plug itself into the app's navigation graph.
 *
 * The app collects all FeatureEntry instances (via a Hilt multibinding) and asks each one to
 * register its destinations. This is what makes features pluggable: adding a feature means
 * shipping a new module that provides a FeatureEntry; removing it means deleting the module.
 * The app never references any specific feature.
 */
interface FeatureEntry {
    /** The unique navigation route this feature owns. */
    val route: String

    /**
     * Adds this feature's composable destination(s) to the navigation graph.
     * Called from inside the app's NavHost builder.
     */
    fun register(navGraphBuilder: NavGraphBuilder, navController: NavController)
}
```

- [ ] **Step 4: Verify it assembles**

Run: `./gradlew :core:navigation:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts core/navigation
git commit -m "feat: add core:navigation with FeatureEntry contract"
```

### Task 2.3: Create the `:feature:player` module that registers itself

**Files:**
- Modify: `settings.gradle.kts`
- Create: `feature/player/build.gradle.kts`
- Create: `feature/player/src/main/java/com/akouo/feature/player/PlayerRoute.kt`
- Create: `feature/player/src/main/java/com/akouo/feature/player/PlayerScreen.kt`
- Create: `feature/player/src/main/java/com/akouo/feature/player/PlayerFeatureEntry.kt`
- Create: `feature/player/src/main/java/com/akouo/feature/player/PlayerFeatureModule.kt`

> Note: this task creates a **placeholder** `PlayerScreen` (static text). It gains a ViewModel and real controls in Chunk 4, after the playback engine exists. This keeps the registry pattern provable on its own.

- [ ] **Step 1: Register the module**

In `settings.gradle.kts`, add:

```kotlin
include(":feature:player")
```

- [ ] **Step 2: Create the build file**

Create `feature/player/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.akouo.feature.player"
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
}

dependencies {
    implementation(project(":core:navigation"))
    implementation(project(":core:designsystem"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
```

- [ ] **Step 3: Create the route constant**

Create `feature/player/src/main/java/com/akouo/feature/player/PlayerRoute.kt`:

```kotlin
package com.akouo.feature.player

/** Navigation route owned by the player feature. */
const val PlayerRoute = "player"
```

- [ ] **Step 4: Create the placeholder screen**

Create `feature/player/src/main/java/com/akouo/feature/player/PlayerScreen.kt`:

```kotlin
package com.akouo.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun PlayerScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = "Player", style = MaterialTheme.typography.titleLarge)
    }
}
```

- [ ] **Step 5: Create the FeatureEntry implementation**

Create `feature/player/src/main/java/com/akouo/feature/player/PlayerFeatureEntry.kt`:

```kotlin
package com.akouo.feature.player

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.akouo.core.navigation.FeatureEntry
import javax.inject.Inject

/** Plugs the player screen into the app navigation graph. */
class PlayerFeatureEntry @Inject constructor() : FeatureEntry {

    override val route: String = PlayerRoute

    override fun register(navGraphBuilder: NavGraphBuilder, navController: NavController) {
        navGraphBuilder.composable(route) {
            PlayerScreen()
        }
    }
}
```

- [ ] **Step 6: Create the Hilt multibinding**

Create `feature/player/src/main/java/com/akouo/feature/player/PlayerFeatureModule.kt`:

```kotlin
package com.akouo.feature.player

import com.akouo.core.navigation.FeatureEntry
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Contributes PlayerFeatureEntry into the app-wide Set<FeatureEntry>.
 * @IntoSet is the mechanism that lets the app collect every feature without knowing them by name.
 */
@Module
@InstallIn(SingletonComponent::class)
interface PlayerFeatureModule {

    @Binds
    @IntoSet
    fun bindPlayerFeatureEntry(entry: PlayerFeatureEntry): FeatureEntry
}
```

- [ ] **Step 7: Verify it assembles**

Run: `./gradlew :feature:player:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add settings.gradle.kts feature/player
git commit -m "feat: add feature:player that self-registers via Hilt @IntoSet"
```

### Task 2.4: Wire the app's NavHost from the feature registry

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/akouo/app/AkouoNavHost.kt`
- Modify: `app/src/main/java/com/akouo/app/MainActivity.kt`

- [ ] **Step 1: Add module dependencies to the app**

In `app/build.gradle.kts`, add to `dependencies { }`:

```kotlin
    implementation(project(":feature:player"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:navigation"))
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
```

- [ ] **Step 2: Create the nav host**

Create `app/src/main/java/com/akouo/app/AkouoNavHost.kt`:

```kotlin
package com.akouo.app

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import com.akouo.core.navigation.FeatureEntry
import com.akouo.feature.player.PlayerRoute

/**
 * Builds the navigation graph by asking every registered feature to add its destinations.
 * The set is supplied by Hilt; this function names no feature except the start route.
 */
@Composable
fun AkouoNavHost(
    featureEntries: Set<@JvmSuppressWildcards FeatureEntry>,
    navController: NavHostController,
) {
    NavHost(navController = navController, startDestination = PlayerRoute) {
        featureEntries.forEach { entry ->
            entry.register(this, navController)
        }
    }
}
```

- [ ] **Step 3: Rewrite MainActivity to inject the feature set**

Replace the entire contents of `app/src/main/java/com/akouo/app/MainActivity.kt`:

```kotlin
package com.akouo.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.akouo.core.designsystem.theme.AkouoTheme
import com.akouo.core.navigation.FeatureEntry
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** All features registered in the app, collected by Hilt from @IntoSet bindings. */
    @Inject
    lateinit var featureEntries: Set<@JvmSuppressWildcards FeatureEntry>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AkouoTheme {
                val navController = rememberNavController()
                AkouoNavHost(featureEntries = featureEntries, navController = navController)
            }
        }
    }
}
```

- [ ] **Step 4: Verify it assembles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. (This proves the full graph compiles: Hilt injects `Set<FeatureEntry>`, the nav host wires up, and `app → feature → core` dependencies resolve.)

- [ ] **Step 5: Commit**

```bash
git add app/
git commit -m "feat: drive app NavHost from the injected FeatureEntry set"
```

---

## Chunk 3: Playback engine

Goal of this chunk: the `:core:playback` module — a pure, unit-tested speed resolver (built test-first), a `MediaSessionService`, and a `PlaybackConnection` that exposes player state as a `StateFlow`. No UI yet.

### Task 3.1: Create the `:core:playback` module shell

**Files:**
- Modify: `settings.gradle.kts`
- Create: `core/playback/build.gradle.kts`
- Create: `core/playback/src/main/java/com/akouo/core/playback/PlaybackUiState.kt`

- [ ] **Step 1: Register the module**

In `settings.gradle.kts`, add:

```kotlin
include(":core:playback")
```

- [ ] **Step 2: Create the build file**

Create `core/playback/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.akouo.core.playback"
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
}

dependencies {
    implementation(project(":core:model"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

- [ ] **Step 3: Create the playback UI state model**

Create `core/playback/src/main/java/com/akouo/core/playback/PlaybackUiState.kt`:

```kotlin
package com.akouo.core.playback

/** Immutable snapshot of what the player UI needs to render. */
data class PlaybackUiState(
    val isPlaying: Boolean = false,
    val title: String = "",
)
```

- [ ] **Step 4: Verify it assembles**

Run: `./gradlew :core:playback:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts core/playback
git commit -m "feat: add core:playback module shell with PlaybackUiState"
```

### Task 3.2: Build the speed resolver test-first (TDD)

**Files:**
- Create: `core/playback/src/test/java/com/akouo/core/playback/SpeedResolverTest.kt`
- Create: `core/playback/src/main/java/com/akouo/core/playback/SpeedPreferences.kt`
- Create: `core/playback/src/main/java/com/akouo/core/playback/SpeedResolver.kt`

> This is the plan's strict-TDD exemplar: pure logic, no Android, fast JVM test. Write the test, watch it fail, then implement.

- [ ] **Step 1: Write the failing test**

Create `core/playback/src/test/java/com/akouo/core/playback/SpeedResolverTest.kt`:

```kotlin
package com.akouo.core.playback

import com.akouo.core.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedResolverTest {

    @Test
    fun itemOverride_takesPrecedenceOverEverything() {
        val prefs = SpeedPreferences(global = 1.0f, perType = mapOf(MediaType.PODCAST to 1.5f))
        val result = SpeedResolver.resolve(prefs, MediaType.PODCAST, itemOverride = 2.0f)
        assertEquals(2.0f, result, 0.0f)
    }

    @Test
    fun perTypeDefault_usedWhenNoItemOverride() {
        val prefs = SpeedPreferences(global = 1.0f, perType = mapOf(MediaType.AUDIOBOOK to 1.25f))
        val result = SpeedResolver.resolve(prefs, MediaType.AUDIOBOOK, itemOverride = null)
        assertEquals(1.25f, result, 0.0f)
    }

    @Test
    fun globalDefault_usedWhenNoTypeOrItemValue() {
        val prefs = SpeedPreferences(global = 1.1f, perType = emptyMap())
        val result = SpeedResolver.resolve(prefs, MediaType.PODCAST, itemOverride = null)
        assertEquals(1.1f, result, 0.0f)
    }

    @Test
    fun fallsBackToDefaultSpeed_whenPrefsAreEmpty() {
        val prefs = SpeedPreferences()
        val result = SpeedResolver.resolve(prefs, MediaType.PODCAST, itemOverride = null)
        assertEquals(SpeedResolver.DEFAULT_SPEED, result, 0.0f)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core:playback:testDebugUnitTest --tests "com.akouo.core.playback.SpeedResolverTest"`
Expected: FAIL — compilation error, `Unresolved reference: SpeedPreferences` / `SpeedResolver`.

- [ ] **Step 3: Create the preferences model**

Create `core/playback/src/main/java/com/akouo/core/playback/SpeedPreferences.kt`:

```kotlin
package com.akouo.core.playback

import com.akouo.core.model.MediaType

/** User playback-speed settings: a global default plus optional per-media-type defaults. */
data class SpeedPreferences(
    val global: Float = SpeedResolver.DEFAULT_SPEED,
    val perType: Map<MediaType, Float> = emptyMap(),
)
```

- [ ] **Step 4: Implement the resolver**

Create `core/playback/src/main/java/com/akouo/core/playback/SpeedResolver.kt`:

```kotlin
package com.akouo.core.playback

import com.akouo.core.model.MediaType

/**
 * Resolves the playback speed to apply, using the most specific value available:
 * per-item override > per-type default > global default > hardcoded fallback.
 *
 * Pure function — no Android, no I/O — so it is unit-tested directly on the JVM.
 */
object SpeedResolver {

    const val DEFAULT_SPEED = 1.0f

    fun resolve(
        preferences: SpeedPreferences,
        mediaType: MediaType,
        itemOverride: Float?,
    ): Float {
        return itemOverride
            ?: preferences.perType[mediaType]
            ?: preferences.global
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :core:playback:testDebugUnitTest --tests "com.akouo.core.playback.SpeedResolverTest"`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add core/playback
git commit -m "feat: add speed resolver with unit tests"
```

### Task 3.3: Create the Media3 playback service

**Files:**
- Create: `core/playback/src/main/AndroidManifest.xml`
- Create: `core/playback/src/main/java/com/akouo/core/playback/PlaybackService.kt`

- [ ] **Step 1: Create the service**

Create `core/playback/src/main/java/com/akouo/core/playback/PlaybackService.kt`:

```kotlin
package com.akouo.core.playback

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Background-capable playback service. Hosting playback in a MediaSessionService is what gives us
 * lock-screen / notification controls, Bluetooth & headset buttons, and playback that survives the
 * UI being swiped away. The UI connects to it through a MediaController (see PlaybackConnection).
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build()
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
```

- [ ] **Step 2: Create the module manifest declaring the service**

Create `core/playback/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />

    <application>
        <service
            android:name=".PlaybackService"
            android:exported="true"
            android:foregroundServiceType="mediaPlayback">
            <intent-filter>
                <action android:name="androidx.media3.session.MediaSessionService" />
            </intent-filter>
        </service>
    </application>

</manifest>
```

(Library manifests are merged into the app's at build time, so this service and these permissions become part of the final app.)

- [ ] **Step 3: Verify it assembles**

Run: `./gradlew :core:playback:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add core/playback
git commit -m "feat: add Media3 MediaSessionService"
```

### Task 3.4: Create the PlaybackConnection and add the sample clip

**Files:**
- Create: `core/playback/src/main/res/raw/sample.mp3` (binary asset — you provide it)
- Create: `core/playback/src/main/java/com/akouo/core/playback/PlaybackConnection.kt`

- [ ] **Step 1: Add a bundled audio clip**

Place a short MP3 at `core/playback/src/main/res/raw/sample.mp3`. Use a clip of at least ~20–30 seconds so background playback is observable in the smoke test.

If `ffmpeg` is available, generate a 30-second tone:

```bash
ffmpeg -f lavfi -i "sine=frequency=440:duration=30" -c:a libmp3lame core/playback/src/main/res/raw/sample.mp3
```

Otherwise drop in any short, legally-redistributable MP3 (e.g. a CC0 clip) at that exact path. The filename must be lowercase letters/digits/underscores only (Android resource naming rule).

- [ ] **Step 2: Create the connection**

Create `core/playback/src/main/java/com/akouo/core/playback/PlaybackConnection.kt`:

```kotlin
package com.akouo.core.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.akouo.core.model.MediaType
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The UI-side handle on playback. Connects a Media3 MediaController to PlaybackService and exposes
 * the player's state as a StateFlow that ViewModels can observe (unidirectional data flow).
 *
 * Phase 1 simplification: a process-scoped singleton that connects once and is never explicitly
 * released. Lifecycle-aware connect/release is added in Phase 3 when multiple screens connect.
 */
@Singleton
class PlaybackConnection @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = updateState()
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = updateState()
    }

    init {
        connect()
    }

    private fun connect() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                val newController = future.get()
                newController.addListener(listener)
                controller = newController
                updateState()
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun updateState() {
        val c = controller ?: return
        _state.value = PlaybackUiState(
            isPlaying = c.isPlaying,
            title = c.mediaMetadata.title?.toString().orEmpty(),
        )
    }

    /** Toggles play/pause for whatever is currently loaded. */
    fun playPause() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    /**
     * Phase 1 smoke-test entry point: loads the bundled sample clip and starts playback.
     * Superseded in Phase 2 when media comes from the library/repository.
     */
    fun playBundledSample() {
        val c = controller ?: return
        val uri = RawResourceDataSource.buildRawResourceUri(R.raw.sample)
        val item = MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Sample clip").build())
            .build()
        c.setMediaItem(item)
        c.prepare()
        c.setPlaybackSpeed(
            SpeedResolver.resolve(SpeedPreferences(), MediaType.PODCAST, itemOverride = null),
        )
        c.play()
    }
}
```

- [ ] **Step 3: Verify it assembles**

Run: `./gradlew :core:playback:assembleDebug`
Expected: BUILD SUCCESSFUL. (Fails if `sample.mp3` is missing — `R.raw.sample` won't resolve. Confirm Step 1 created the file at the exact path.)

- [ ] **Step 4: Commit**

```bash
git add core/playback
git commit -m "feat: add PlaybackConnection and bundled sample clip"
```

---

## Chunk 4: Player UI ↔ engine & end-to-end smoke test

Goal of this chunk: connect the player screen to the engine, handle the notification permission, and verify real background playback on a device.

### Task 4.1: Give the player feature a ViewModel wired to the engine

**Files:**
- Modify: `feature/player/build.gradle.kts`
- Create: `feature/player/src/main/java/com/akouo/feature/player/PlayerViewModel.kt`
- Modify: `feature/player/src/main/java/com/akouo/feature/player/PlayerScreen.kt`

- [ ] **Step 1: Depend on the playback module**

In `feature/player/build.gradle.kts`, add to `dependencies { }`:

```kotlin
    implementation(project(":core:playback"))
```

- [ ] **Step 2: Create the ViewModel**

Create `feature/player/src/main/java/com/akouo/feature/player/PlayerViewModel.kt`:

```kotlin
package com.akouo.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.akouo.core.playback.PlaybackConnection
import com.akouo.core.playback.PlaybackUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playbackConnection: PlaybackConnection,
) : ViewModel() {

    val uiState: StateFlow<PlaybackUiState> =
        playbackConnection.state.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = PlaybackUiState(),
        )

    fun onLoadSampleClick() = playbackConnection.playBundledSample()

    fun onPlayPauseClick() = playbackConnection.playPause()
}
```

- [ ] **Step 3: Replace the placeholder screen with the wired screen**

Replace the entire contents of `feature/player/src/main/java/com/akouo/feature/player/PlayerScreen.kt`:

```kotlin
package com.akouo.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun PlayerScreen(viewModel: PlayerViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = state.title.ifEmpty { "Nothing loaded" },
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = viewModel::onLoadSampleClick) {
                Text("Load sample")
            }
            Button(onClick = viewModel::onPlayPauseClick) {
                Text(if (state.isPlaying) "Pause" else "Play")
            }
        }
    }
}
```

- [ ] **Step 4: Verify the whole app assembles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add feature/player
git commit -m "feat: wire player screen to playback engine via ViewModel"
```

### Task 4.2: Handle the notification permission

**Files:**
- Modify: `core/playback/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/akouo/app/MainActivity.kt`

> On Android 13+ (API 33), the media notification only appears if the app holds the runtime `POST_NOTIFICATIONS` permission. We declare it and request it on launch.

- [ ] **Step 1: Declare the permission**

In `core/playback/src/main/AndroidManifest.xml`, add inside `<manifest>` alongside the other `uses-permission` lines:

```xml
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

- [ ] **Step 2: Request it on launch**

Replace the entire contents of `app/src/main/java/com/akouo/app/MainActivity.kt`:

```kotlin
package com.akouo.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import com.akouo.core.designsystem.theme.AkouoTheme
import com.akouo.core.navigation.FeatureEntry
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var featureEntries: Set<@JvmSuppressWildcards FeatureEntry>

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result ignored */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeRequestNotificationPermission()
        setContent {
            AkouoTheme {
                val navController = rememberNavController()
                AkouoNavHost(featureEntries = featureEntries, navController = navController)
            }
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val alreadyGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!alreadyGranted) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
```

- [ ] **Step 3: Verify it assembles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/ core/playback
git commit -m "feat: request POST_NOTIFICATIONS for media controls"
```

### Task 4.3: End-to-end smoke test on a device (wireless debugging)

> **This is the one task that cannot run on the dev server** (no emulator/KVM/device). It drives a real phone over **wireless debugging** — the phone never plugs into the server. Manual verification: no code, no commit.
>
> **Setup:** `adb` lives at `$ANDROID_HOME/platform-tools/adb`; ensure it's on your PATH. The phone and this server must be on the **same Wi-Fi network** (the server is `192.168.0.233`).

**Files:** none (verification only)

- [ ] **Step 1: Pair the phone over Wi-Fi (one-time per phone)**

On the phone: Settings → Developer options → **Wireless debugging** → turn it on → tap **Pair device with pairing code**. A dialog shows an `IP:port` and a 6-digit code.

On the server, using the values from that dialog:

Run: `adb pair <phone-ip>:<pairing-port>` (enter the 6-digit code when prompted)
Expected: `Successfully paired to <phone-ip>:<pairing-port> ...`.

- [ ] **Step 2: Connect to the phone**

Use the `IP:port` from the **main** Wireless debugging screen — this port is **different** from the pairing port.

Run: `adb connect <phone-ip>:<connect-port>`
Then run: `adb devices`
Expected: the phone appears in the list as `device`.

- [ ] **Step 3: Build and install over Wi-Fi**

Run: `./gradlew :app:installDebug`
Expected: BUILD SUCCESSFUL and `Installed on 1 device`.

> **Fallback if wireless adb is blocked** (some networks isolate clients from each other): sideload instead. Run `./gradlew :app:assembleDebug`, then `cp app/build/outputs/apk/debug/app-debug.apk docs/akouo-debug.apk`, then on the phone open `http://192.168.0.233:8080/akouo-debug.apk` in a browser, download it, and install (enable "install unknown apps" for the browser). This reuses the doc server already running on port 8080.

- [ ] **Step 4: Launch and exercise playback**

Launch the **akouo** app on the phone, then verify each:
- The Player screen shows "Nothing loaded", "Load sample", and "Play".
- (Android 13+) A notification-permission prompt appears on first launch; allow it.
- Tap **Load sample** → the title changes to "Sample clip" and the button shows **Pause** (audio is playing).
- A **media notification** appears in the shade with a working pause/play control.
- **Background test:** press Home (or lock the screen) → audio keeps playing; the notification controls still pause/resume.
- Tapping **Pause** in-app and in the notification both toggle playback consistently.

- [ ] **Step 5: Record the result**

If all checks pass, Phase 1's runtime criterion is met. If any fail, capture logs with `adb logcat | grep -i akouo` around the failure before proceeding.

---

## Chunk 5 (optional): Device-free UI snapshots with Paparazzi

Goal: render the player UI to PNG images **on the JVM with no device/emulator**, viewable in your browser via the doc server — a fast visual-iteration loop on this CLI server. Paparazzi renders UI *appearance* only; it does not run playback/audio (that still needs Task 4.3's device).

> **Version note:** Paparazzi is tightly coupled to the Android Gradle Plugin (it embeds a matching layoutlib). The version below targets AGP 8.7.x. If a task fails with a layoutlib/AGP compatibility error, check the Paparazzi releases (https://github.com/cashapp/paparazzi/releases) and bump `paparazzi` in the catalog to the build that matches AGP 8.7.3. Paparazzi is test-only — nothing here ships in the app.

### Task 5.1: Extract a stateless, snapshot-friendly composable

**Files:**
- Modify: `feature/player/src/main/java/com/akouo/feature/player/PlayerScreen.kt`

> A composable that fetches its own `hiltViewModel()` can't be rendered in isolation. Splitting state-collection (`PlayerScreen`) from rendering (`PlayerContent`) lets Paparazzi render `PlayerContent` with a fixed state — and is good Compose practice regardless.

- [ ] **Step 1: Split PlayerScreen into stateful + stateless halves**

Replace the entire contents of `feature/player/src/main/java/com/akouo/feature/player/PlayerScreen.kt`:

```kotlin
package com.akouo.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.akouo.core.playback.PlaybackUiState

@Composable
fun PlayerScreen(viewModel: PlayerViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PlayerContent(
        state = state,
        onLoadSampleClick = viewModel::onLoadSampleClick,
        onPlayPauseClick = viewModel::onPlayPauseClick,
    )
}

@Composable
internal fun PlayerContent(
    state: PlaybackUiState,
    onLoadSampleClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = state.title.ifEmpty { "Nothing loaded" },
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onLoadSampleClick) {
                Text("Load sample")
            }
            Button(onClick = onPlayPauseClick) {
                Text(if (state.isPlaying) "Pause" else "Play")
            }
        }
    }
}
```

- [ ] **Step 2: Verify the app still builds**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add feature/player
git commit -m "refactor: split PlayerScreen into stateful and stateless halves"
```

### Task 5.2: Add the Paparazzi plugin

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts`
- Modify: `feature/player/build.gradle.kts`

- [ ] **Step 1: Add Paparazzi to the catalog**

In `gradle/libs.versions.toml`, add to `[versions]`:

```toml
paparazzi = "1.3.5"
```

And to `[plugins]`:

```toml
paparazzi = { id = "app.cash.paparazzi", version.ref = "paparazzi" }
```

- [ ] **Step 2: Declare it at the root**

In `build.gradle.kts`, add to the `plugins { }` block:

```kotlin
    alias(libs.plugins.paparazzi) apply false
```

- [ ] **Step 3: Apply it in the player feature**

In `feature/player/build.gradle.kts`, add to the `plugins { }` block (after the existing plugins):

```kotlin
    alias(libs.plugins.paparazzi)
```

- [ ] **Step 4: Verify the Paparazzi tasks exist**

Run: `./gradlew :feature:player:recordPaparazzi --dry-run`
Expected: BUILD SUCCESSFUL, listing `:feature:player:recordPaparazzi` (and its dependency tasks) as work it would run. (If you get "Task 'recordPaparazzi' not found", the plugin didn't apply — recheck Steps 1–3 and the version note above.)

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml build.gradle.kts feature/player/build.gradle.kts
git commit -m "chore: add Paparazzi screenshot-testing plugin to feature:player"
```

### Task 5.3: Write and record the snapshots

**Files:**
- Create: `feature/player/src/test/java/com/akouo/feature/player/PlayerScreenSnapshotTest.kt`

- [ ] **Step 1: Write the snapshot test**

Create `feature/player/src/test/java/com/akouo/feature/player/PlayerScreenSnapshotTest.kt`:

```kotlin
package com.akouo.feature.player

import app.cash.paparazzi.Paparazzi
import com.akouo.core.designsystem.theme.AkouoTheme
import com.akouo.core.playback.PlaybackUiState
import org.junit.Rule
import org.junit.Test

class PlayerScreenSnapshotTest {

    @get:Rule
    val paparazzi = Paparazzi()

    @Test
    fun nothingLoaded() {
        paparazzi.snapshot {
            AkouoTheme {
                PlayerContent(
                    state = PlaybackUiState(),
                    onLoadSampleClick = {},
                    onPlayPauseClick = {},
                )
            }
        }
    }

    @Test
    fun playing() {
        paparazzi.snapshot {
            AkouoTheme {
                PlayerContent(
                    state = PlaybackUiState(isPlaying = true, title = "Sample clip"),
                    onLoadSampleClick = {},
                    onPlayPauseClick = {},
                )
            }
        }
    }
}
```

- [ ] **Step 2: Record the golden images**

Run: `./gradlew :feature:player:recordPaparazzi`
Expected: BUILD SUCCESSFUL. PNGs are written to `feature/player/src/test/snapshots/images/` (one per `@Test`).

- [ ] **Step 3: View them in your browser**

The doc server is already serving `docs/` on port 8080. Copy the snapshots into it:

```bash
mkdir -p docs/snapshots && cp feature/player/src/test/snapshots/images/*.png docs/snapshots/
```

Then browse `http://192.168.0.233:8080/snapshots/` and click the PNGs. (If the server isn't running: `python3 -m http.server 8080 --bind 0.0.0.0 --directory docs &`.)

- [ ] **Step 4: Commit the test and golden images**

```bash
git add feature/player/src/test
git commit -m "test: add Paparazzi snapshots for the player screen"
```

> From now on, `./gradlew :feature:player:verifyPaparazzi` fails the build if the rendered UI drifts from these goldens — a free visual-regression check. When a change is intentional, re-run `recordPaparazzi` to update the goldens.

---

## Definition of done

- [ ] `./gradlew assembleDebug` builds the whole app (all 5 new modules + app).
- [ ] `./gradlew testDebugUnitTest` passes (the `SpeedResolver` suite).
- [ ] On a device: the bundled clip plays, continues in the background, and is controllable from the notification.
- [ ] A feature (`:feature:player`) reaches the screen purely by registering a `FeatureEntry` — the app references no feature internals beyond the start route.

When done, the next plan is **Phase 2: local audiobooks** (introduces Room + the user-chosen local folder via the Storage Access Framework).
