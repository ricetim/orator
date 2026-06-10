package com.orator.core.navigation

/**
 * Routes that other features navigate to. Owning features register the destinations;
 * keeping the strings here avoids feature→feature dependencies.
 */
object CommonRoutes {
    const val Player = "player"
    const val Settings = "settings"
    const val History = "history"
}
