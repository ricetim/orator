package com.orator.core.designsystem.shell

import androidx.compose.runtime.staticCompositionLocalOf

/** Actions the app shell offers to screens (e.g. the topbar ☰ opens the shell's drawer). */
class ShellControls(
    val openDrawer: () -> Unit,
)

val LocalShellControls = staticCompositionLocalOf { ShellControls(openDrawer = {}) }
