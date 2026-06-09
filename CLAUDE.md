# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

**Greenfield — no code exists yet.** As of this writing the repository contains only
`initial_plan.md` and this file. There is no source code, no build system, and it is
**not yet a git repository**. The plan calls for all files to live in a GitHub repo, so
initialize git (and create the remote) before substantial work.

Per the plan, the **first task is to set up the Android development environment**. Once a
build system exists, replace the "Build / test / run" section below with the real commands
(how to build a debug/release APK, run lint, and run a single test).

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

Not defined yet — Android tooling (Gradle, etc.) has not been set up. **Update this section
with actual commands once the project is scaffolded.**

## Open decisions (not yet made — do not assume)

The plan does not specify these. Confirm with the user rather than assuming a default:

- Language / UI toolkit (e.g. Kotlin + Jetpack Compose vs. alternatives)
- Media playback library (e.g. Media3/ExoPlayer)
- Local persistence (database vs. files) and the "human-readable" storage format for podcasts
- Minimum/target SDK versions
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
