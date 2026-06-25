package com.orator.core.network

import okhttp3.Interceptor
import org.junit.Assert.assertTrue
import org.junit.Test

class OkHttpInterceptorWiringTest {
    @Test fun `provided client includes injected interceptors`() {
        val marker = Interceptor { chain -> chain.proceed(chain.request()) }
        val client = NetworkModule.provideOkHttpClient(setOf(marker))
        assertTrue(marker in client.interceptors)
    }

    @Test fun `empty set yields a working client`() {
        val client = NetworkModule.provideOkHttpClient(emptySet())
        assertTrue(client.interceptors.isEmpty())
    }
}
