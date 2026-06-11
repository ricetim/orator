package com.orator.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface FetchResult {
    data class Success(val body: String, val etag: String?, val lastModified: String?) : FetchResult
    data object NotModified : FetchResult
    data class Failure(val message: String) : FetchResult
}

/**
 * One conditional GET. Sends stored validators so unchanged feeds answer 304 with no body —
 * refreshing all subscriptions stays cheap. No parsing here; callers own the body format.
 * Open so tests can fake it without interface ceremony for a single-method class.
 */
@Singleton
open class FeedFetcher @Inject constructor(private val client: OkHttpClient) {

    open suspend fun fetch(
        url: String,
        etag: String? = null,
        lastModified: String? = null,
    ): FetchResult = withContext(Dispatchers.IO) {
        val request = try {
            Request.Builder().url(url).apply {
                etag?.let { header("If-None-Match", it) }
                lastModified?.let { header("If-Modified-Since", it) }
            }.build()
        } catch (e: IllegalArgumentException) {
            return@withContext FetchResult.Failure("bad URL: ${e.message}")
        }
        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 304 -> FetchResult.NotModified
                    response.isSuccessful -> FetchResult.Success(
                        body = response.body?.string().orEmpty(),
                        etag = response.header("ETag"),
                        lastModified = response.header("Last-Modified"),
                    )
                    else -> FetchResult.Failure("HTTP ${response.code}")
                }
            }
        } catch (e: IOException) {
            FetchResult.Failure(e.message ?: e.javaClass.simpleName)
        }
    }
}
