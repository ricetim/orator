package com.akouo.feature.audiobooks

const val AudiobooksRoute = "audiobooks"

internal const val BookDetailRoutePattern = "audiobooks/{bookId}"

internal fun bookDetailRoute(bookId: String) = "audiobooks/$bookId"
