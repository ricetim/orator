package com.orator.feature.podcasts.data

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Filesystem naming for the human-readable cache tree. Pure functions, no I/O. */
object CacheNames {

    private val ILLEGAL = Regex("""[\\/:*?"<>|\p{Cntrl}]""")
    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)

    fun sanitize(name: String): String {
        val cleaned = ILLEGAL.replace(name, "_").trim().trimEnd('.', ' ').take(80).trim()
        return cleaned.ifBlank { "untitled" }
    }

    fun episodeDirName(pubDateUtc: Long, title: String): String {
        val prefix = if (pubDateUtc > 0) DATE.format(Instant.ofEpochMilli(pubDateUtc)) else "0000-00-00"
        return "$prefix - ${sanitize(title)}"
    }

    /** Disambiguates colliding sanitized names ("Show" vs "Show?") deterministically. */
    fun withIdSuffix(base: String, id: String): String = "$base [${id.take(4)}]"
}
