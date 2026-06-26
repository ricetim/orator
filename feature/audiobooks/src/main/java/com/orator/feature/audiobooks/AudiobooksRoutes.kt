package com.orator.feature.audiobooks

import android.net.Uri
import com.orator.core.navigation.CommonRoutes

const val AudiobooksRoute = CommonRoutes.Audiobooks

// ABS book ids contain a colon (abs:<uuid>); encode it into the path segment. Nav decodes on read.
internal const val AudiobookDetailRoutePattern = "audiobooks/{bookId}"
internal fun audiobookDetailRoute(bookId: String) = "audiobooks/" + Uri.encode(bookId)
