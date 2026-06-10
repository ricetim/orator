package com.orator.core.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Queue-scoped metadata the service needs but Media3 items don't carry: the chapter boundary
 * positions for single-file books. Written by PlaybackConnection.play(), read by the boundary
 * sleep timer. Shared singleton instead of session-command plumbing (single-process app; see
 * the Phase 3 plan's Orientation section).
 */
@Singleton
class ActiveQueueInfo @Inject constructor() {

    private val _chapterBoundariesMs = MutableStateFlow<List<Long>>(emptyList())
    val chapterBoundariesMs: StateFlow<List<Long>> = _chapterBoundariesMs.asStateFlow()

    fun onNewQueue(boundariesMs: List<Long>) {
        _chapterBoundariesMs.value = boundariesMs
    }
}
