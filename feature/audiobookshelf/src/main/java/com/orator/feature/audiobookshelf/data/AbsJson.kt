package com.orator.feature.audiobookshelf.data

import kotlinx.serialization.json.Json

object AbsJson {
    val instance = Json { ignoreUnknownKeys = true; coerceInputValues = true }
}
