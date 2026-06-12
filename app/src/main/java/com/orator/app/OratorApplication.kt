package com.orator.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * Application entry point. @HiltAndroidApp generates the app-wide dependency-injection
 * container that every other @AndroidEntryPoint / @HiltViewModel hooks into.
 */
@HiltAndroidApp
class OratorApplication : Application(), SingletonImageLoader.Factory {

    /** Shared client from core:network — Coil reuses its pools instead of building its own. */
    @Inject
    lateinit var okHttpClient: OkHttpClient

    /** Coil 3 image loader: files + SAF content URIs are built in; http(s) via OkHttp. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient })) }
            .build()
}
