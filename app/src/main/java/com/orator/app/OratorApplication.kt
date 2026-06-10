package com.orator.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point. @HiltAndroidApp generates the app-wide dependency-injection
 * container that every other @AndroidEntryPoint / @HiltViewModel hooks into.
 */
@HiltAndroidApp
class OratorApplication : Application()
