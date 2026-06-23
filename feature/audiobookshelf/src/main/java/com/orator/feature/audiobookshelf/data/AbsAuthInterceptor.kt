package com.orator.feature.audiobookshelf.data

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

class AbsAuthInterceptor @Inject constructor(
    private val store: AbsCredentialStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val session = store.current()
        val req = chain.request()
        val base = session?.baseUrl
        // Match host AND port so a bearer never leaks to a different service sharing the host.
        return if (base != null && req.url.host == base.host && req.url.port == base.port) {
            chain.proceed(
                req.newBuilder()
                    .header("Authorization", "Bearer ${session.config.token}")
                    .build(),
            )
        } else {
            chain.proceed(req)
        }
    }
}
