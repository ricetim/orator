package com.orator.feature.audiobookshelf.data

/** The subset of AbsApi the repository needs for a catalog pass — lets tests fake the network. */
interface AbsCatalogSource {
    suspend fun libraries(baseUrl: String, token: String): List<AbsLibrary>
    suspend fun items(baseUrl: String, libraryId: String, token: String): List<AbsLibraryItem>
}
