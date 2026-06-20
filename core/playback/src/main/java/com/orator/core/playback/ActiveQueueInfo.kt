package com.orator.core.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Queue-scoped metadata the service needs but Media3 items don't carry: the global chapter
 * boundary positions and the per-item file durations (to map the playhead to a global
 * position). Written by PlaybackConnection.play(), read by the boundary sleep timer. Shared
 * singleton instead of session-command plumbing (single-process app; see the Phase 3 plan's
 * Orientation section).
 */
@Singleton
class ActiveQueueInfo @Inject constructor() {

    private val _chapterBoundariesMs = MutableStateFlow<List<Long>>(emptyList())
    val chapterBoundariesMs: StateFlow<List<Long>> = _chapterBoundariesMs.asStateFlow()

    private val _fileDurationsMs = MutableStateFlow<List<Long>>(emptyList())
    val fileDurationsMs: StateFlow<List<Long>> = _fileDurationsMs.asStateFlow()

    fun onNewQueue(boundariesMs: List<Long>, fileDurationsMs: List<Long>) {
        _chapterBoundariesMs.value = boundariesMs
        _fileDurationsMs.value = fileDurationsMs
    }
}
