package com.orator.feature.audiobooks

import com.orator.core.navigation.CommonRoutes

const val AudiobooksRoute = CommonRoutes.Audiobooks

internal const val BookDetailRoutePattern = "audiobooks/{bookId}"

internal fun bookDetailRoute(bookId: String) = "audiobooks/$bookId"
