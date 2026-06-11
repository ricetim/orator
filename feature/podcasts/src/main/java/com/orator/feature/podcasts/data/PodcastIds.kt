package com.orator.feature.podcasts.data

import java.security.MessageDigest

/**
 * Stable ids. Episode ids are namespaced by podcast because RSS GUIDs are only unique
 * within one feed (spec plan-level decision).
 */
object PodcastIds {

    fun podcastId(feedUrl: String): String = sha256Hex(feedUrl).take(16)

    fun episodeId(podcastId: String, guidOrEnclosureUrl: String): String =
        sha256Hex("$podcastId|$guidOrEnclosureUrl").take(16)

    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
