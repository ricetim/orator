package com.orator.core.model

/**
 * The kind of media being played. Drives per-type behaviour such as the default
 * playback speed used when an item has no explicit override.
 */
enum class MediaType {
    PODCAST,
    AUDIOBOOK,
}
