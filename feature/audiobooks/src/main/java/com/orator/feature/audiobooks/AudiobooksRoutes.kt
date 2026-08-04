package com.orator.feature.audiobooks

import android.net.Uri
import com.orator.core.navigation.CommonRoutes

const val AudiobooksRoute = CommonRoutes.Audiobooks

// ABS book ids contain a colon (abs:<uuid>); encode it into the path segment. Nav decodes on read.
internal const val AudiobookDetailRoutePattern = "audiobooks/{bookId}"
internal fun audiobookDetailRoute(bookId: String) = "audiobooks/" + Uri.encode(bookId)

// Distinct top segment: "audiobooks/search" would collide with the audiobooks/{bookId} detail
// wildcard (same pitfall the podcast code documents). Value is Uri.encode'd like the detail route.
internal const val AudiobookSearchRoute = "audiobook-search"
internal const val AudiobookFilterRoutePattern = "audiobook-filter/{type}/{value}"

// The {type} segment is produced here and read in AudiobookFilterViewModel; shared constants keep
// a typo at either end from silently resolving to the other filter.
internal const val FilterBySeries = "series"
internal const val FilterByAuthor = "author"

internal fun audiobookFilterRoute(type: String, value: String) =
    "audiobook-filter/$type/" + Uri.encode(value)
