package com.orator.feature.audiobookshelf.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject

/**
 * Thin audiobookshelf REST client over the shared OkHttpClient. Authenticated endpoints rely on
 * AbsAuthInterceptor to attach the bearer for the configured host; an explicit [token] arg is kept
 * so the client is exercisable in tests with a plain OkHttpClient.
 */
class AbsApi @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
) {
    suspend fun login(baseUrl: String, username: String, password: String): AbsUser =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(LoginBody.serializer(), LoginBody(username, password))
                .toRequestBody(JSON)
            val req = Request.Builder().url(AbsUrl.endpoint(baseUrl, "login")).post(body).build()
            client.newCall(req).execute().use { resp ->
                check(resp.isSuccessful) { "login failed: HTTP ${resp.code}" }
                json.decodeFromString(AbsLoginResponse.serializer(), resp.body!!.string()).user
            }
        }

    suspend fun getLibraries(baseUrl: String, token: String): List<AbsLibrary> =
        get(AbsUrl.endpoint(baseUrl, "api/libraries"), token, AbsLibrariesResponse.serializer()).libraries

    suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String): AbsLibraryItemsResponse =
        get(
            AbsUrl.endpoint(baseUrl, "api/libraries/$libraryId/items?minified=1&limit=0"),
            token, AbsLibraryItemsResponse.serializer(),
        )

    suspend fun getItemExpanded(baseUrl: String, itemId: String, token: String): AbsLibraryItem =
        get(AbsUrl.endpoint(baseUrl, "api/items/$itemId?expanded=1"), token, AbsLibraryItem.serializer())

    fun coverUrl(baseUrl: String, itemId: String): String =
        AbsUrl.endpoint(baseUrl, "api/items/$itemId/cover")

    fun fileStreamUrl(baseUrl: String, itemId: String, ino: String): String =
        AbsUrl.endpoint(baseUrl, "api/items/$itemId/file/$ino")

    private suspend fun <T> get(url: String, token: String, deserializer: DeserializationStrategy<T>): T =
        withContext(Dispatchers.IO) {
            // Bearer is added by the interceptor for the ABS host; included explicitly too so a plain
            // test client authenticates and the call is order-independent of interceptor wiring.
            val req = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
            client.newCall(req).execute().use { resp ->
                check(resp.isSuccessful) { "GET $url failed: HTTP ${resp.code}" }
                json.decodeFromString(deserializer, resp.body!!.string())
            }
        }

    @kotlinx.serialization.Serializable
    private data class LoginBody(val username: String, val password: String)

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
