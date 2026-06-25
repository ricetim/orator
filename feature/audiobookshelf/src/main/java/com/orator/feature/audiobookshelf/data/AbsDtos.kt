package com.orator.feature.audiobookshelf.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class AbsLoginResponse(val user: AbsUser)
@Serializable data class AbsUser(val id: String, val username: String? = null, val token: String)

@Serializable data class AbsLibrariesResponse(val libraries: List<AbsLibrary> = emptyList())
@Serializable data class AbsLibrary(val id: String, val name: String, val mediaType: String? = null)

@Serializable data class AbsLibraryItemsResponse(val results: List<AbsLibraryItem> = emptyList())

@Serializable data class AbsLibraryItem(
    val id: String,
    val media: AbsMedia = AbsMedia(),
)

@Serializable data class AbsMedia(
    val metadata: AbsMetadata = AbsMetadata(),
    val numAudioFiles: Int = 0,
    val duration: Double = 0.0,             // seconds
    val audioFiles: List<AbsAudioFile> = emptyList(),
    val chapters: List<AbsChapter> = emptyList(),
)

@Serializable data class AbsMetadata(
    val title: String = "",
    @SerialName("authorName") val authorName: String? = null,
)

@Serializable data class AbsAudioFile(
    val ino: String,
    val index: Int = 0,
    val duration: Double = 0.0,             // seconds
)

@Serializable data class AbsChapter(
    val id: Int = 0,
    val start: Double = 0.0,                // seconds
    val end: Double = 0.0,
    val title: String = "",
)
