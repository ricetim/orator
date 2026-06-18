package com.orator.feature.podcasts

import com.orator.core.navigation.CommonRoutes

const val PodcastsRoute = CommonRoutes.Podcasts

internal const val PodcastDetailRoutePattern = "podcasts/{podcastId}"
internal fun podcastDetailRoute(podcastId: String) = "podcasts/$podcastId"

// Distinct top segment: "podcasts/search" would sit inside the {podcastId} pattern's space.
internal const val PodcastSearchRoute = CommonRoutes.PodcastSearch
