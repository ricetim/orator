package com.orator.core.playback

import com.orator.core.model.MediaType

/**
 * One playable file/stream in a queue. [mediaId] must be globally unique and parseable by its
 * owning feature. [clipStartMs]/[clipEndMs] are the intro/outro auto-skip windows: Media3 clips
 * the item so playback (and all reported positions — which become clip-relative) covers only
 * [clipStartMs, clipEndMs). Null end = play to the end of the file.
 */
data class PlayableItem(
    val mediaId: String,
    val uri: String,
    val title: String,
    val artist: String = "",
    val clipStartMs: Long = 0,
    val clipEndMs: Long? = null,
)

/**
 * A complete "play this" command from a feature: the queue plus where to start in it.
 * [chapterBoundariesMs] are chapter start positions on the **global** book timeline (0 excluded)
 * so the boundary sleep timer can pause at "end of chapter" even when a chapter boundary falls
 * inside a queue item (multi-file books). [fileDurationsMs] are the per-queue-item durations used
 * to convert the playhead's (item index, in-item position) to a global position; for a
 * single-item queue it is just `[bookDuration]`. Empty means non-audiobook (no chapter math).
 * [speedOverride] is the per-item speed (book/episode), resolved by SpeedResolver.
 */
data class PlayRequest(
    val items: List<PlayableItem>,
    val startIndex: Int = 0,
    val startPositionMs: Long = 0,
    val mediaType: MediaType,
    val chapterBoundariesMs: List<Long> = emptyList(),
    val fileDurationsMs: List<Long> = emptyList(),
    val speedOverride: Float? = null,
)
