# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

Baseline scaffold is in place: Kotlin + Jetpack Compose single-module Android app, building
successfully. GitHub repo: https://github.com/ricetim/akouo

Next step is architectural planning before any feature work begins.

## What this is

**akouo** (Greek *ἀκούω*, "I hear / I listen") is an **Android-only** app that plays both
**podcasts** and **audiobooks**. Two principles from the plan are hard constraints that
should drive most decisions:

- **Minimal and lightweight.** No feature bloat; the app must stay quick and responsive.
  Be skeptical of new dependencies and features that don't serve this goal.
- **Modular.** Features must be independently *addable and removable*. Build is incremental,
  "piece by piece." Avoid coupling feature areas to each other or to the UI.
- **Paywall-ready.** A paid version is planned. Feature modules should be structured so they
  can be gated/toggled without touching core playback. Current paywall candidates:
  auto-transcription and ad removal.

UI is explicitly **deferred** until baseline functionality works, and must itself be modular
and easy to iterate on — so don't over-invest in UI before the core playback/data layers exist.

## Build / test / run

Always use the Gradle wrapper (`./gradlew`), never the system `gradle` binary.

```bash
# Debug APK
./gradlew assembleDebug

# Release APK (requires signing config)
./gradlew assembleRelease

# Run unit tests
./gradlew test

# Run a single test class
./gradlew :app:testDebugUnitTest --tests "com.akouo.app.ExampleUnitTest"

# Lint
./gradlew lint

# Install on connected device/emulator
./gradlew installDebug
```

`ANDROID_HOME` must be set to `~/Android/Sdk`. Add to your shell profile:
```bash
export ANDROID_HOME=~/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools
```

## Stack decisions made

- **Language / UI:** Kotlin + Jetpack Compose
- **Min SDK:** 26 (Android 8.0), **Target/Compile SDK:** 35 (Android 15)
- **Build:** Gradle 8.11.1 via wrapper, AGP 8.7.3, Kotlin 2.1.0

## Open decisions (not yet made — do not assume)

Confirm with the user before proceeding:

- Media playback library (e.g. Media3/ExoPlayer)
- Local persistence (database vs. files) and the "human-readable" storage format for podcasts
- Module boundaries and how paywall gating is implemented

## Feature scope (from `initial_plan.md`)

Roadmap so future work stays aligned with intended scope:

- **Podcasts:** subscribe via RSS; download + store episodes locally in human-readable
  formats; download show/episode artwork; store show notes and support navigating linked
  timestamps and embedded sections within them.
- **Audiobooks:** play `.m4b` and collections of `.mp3` files; read embedded metadata and
  display cover art; set bookmarks for later review; source from **local files or an
  audiobookshelf server**.
- **Playback:** adjustable speed **globally and per media type** (podcasts vs. audiobooks);
  silence trimming; volume boost; sleep timer by **duration or media boundary** (end of
  episode/chapter); tracked play history.
- **Playlists:** mixed podcasts + audiobooks; auto-insertion rules (e.g. new episodes added
  to top or bottom).
- **Paywall / research:** auto-transcription (open question whether on-device transcription
  is viable) and automatic ad removal from podcasts.

## Store

The plan calls for beginning **Google Play Store registration** for the app.
