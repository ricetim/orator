package com.orator.core.network

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import okhttp3.Interceptor

/** Declares the interceptor multibinding so the set exists even with zero contributors. */
@Module
@InstallIn(SingletonComponent::class)
abstract class InterceptorModule {
    @Multibinds abstract fun interceptors(): Set<Interceptor>
}
