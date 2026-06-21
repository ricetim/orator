package com.orator.core.designsystem.icons

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

/** Icons not in material-icons-core, hand-specified to avoid the extended-icons dependency. */
object OnyxIcons {

    /** Material "mic". */
    val Mic: ImageVector by lazy {
        materialIcon(name = "Onyx.Mic") {
            materialPath {
                moveTo(12.0f, 14.0f)
                curveTo(13.66f, 14.0f, 15.0f, 12.66f, 15.0f, 11.0f)
                verticalLineTo(5.0f)
                curveTo(15.0f, 3.34f, 13.66f, 2.0f, 12.0f, 2.0f)
                reflectiveCurveTo(9.0f, 3.34f, 9.0f, 5.0f)
                verticalLineTo(11.0f)
                curveTo(9.0f, 12.66f, 10.34f, 14.0f, 12.0f, 14.0f)
                close()
                moveTo(17.3f, 11.0f)
                curveTo(17.3f, 14.0f, 14.76f, 16.1f, 12.0f, 16.1f)
                reflectiveCurveTo(6.7f, 14.0f, 6.7f, 11.0f)
                horizontalLineTo(5.0f)
                curveTo(5.0f, 14.41f, 7.72f, 17.23f, 11.0f, 17.72f)
                verticalLineTo(21.0f)
                horizontalLineTo(13.0f)
                verticalLineTo(17.72f)
                curveTo(16.28f, 17.23f, 19.0f, 14.41f, 19.0f, 11.0f)
                horizontalLineTo(17.3f)
                close()
            }
        }
    }

    /** Simple closed book (hand-drawn; verify on device, tweak freely). */
    val Book: ImageVector by lazy {
        materialIcon(name = "Onyx.Book") {
            materialPath {
                moveTo(18.0f, 2.0f)
                horizontalLineTo(8.0f)
                curveTo(6.34f, 2.0f, 5.0f, 3.34f, 5.0f, 5.0f)
                verticalLineTo(19.0f)
                curveTo(5.0f, 20.66f, 6.34f, 22.0f, 8.0f, 22.0f)
                horizontalLineTo(19.0f)
                verticalLineTo(20.0f)
                horizontalLineTo(8.0f)
                curveTo(7.45f, 20.0f, 7.0f, 19.55f, 7.0f, 19.0f)
                reflectiveCurveTo(7.45f, 18.0f, 8.0f, 18.0f)
                horizontalLineTo(19.0f)
                verticalLineTo(3.0f)
                curveTo(19.0f, 2.45f, 18.55f, 2.0f, 18.0f, 2.0f)
                close()
            }
        }
    }

    /** Queue: three lines + play triangle (material "queue music" simplified). */
    val Queue: ImageVector by lazy {
        materialIcon(name = "Onyx.Queue") {
            materialPath {
                moveTo(15.0f, 6.0f); horizontalLineTo(3.0f); verticalLineTo(8.0f)
                horizontalLineTo(15.0f); close()
                moveTo(15.0f, 10.0f); horizontalLineTo(3.0f); verticalLineTo(12.0f)
                horizontalLineTo(15.0f); close()
                moveTo(3.0f, 16.0f); horizontalLineTo(11.0f); verticalLineTo(14.0f)
                horizontalLineTo(3.0f); close()
                moveTo(17.0f, 6.0f); verticalLineTo(14.18f)
                curveTo(16.69f, 14.07f, 16.35f, 14.0f, 16.0f, 14.0f)
                curveTo(14.34f, 14.0f, 13.0f, 15.34f, 13.0f, 17.0f)
                reflectiveCurveTo(14.34f, 20.0f, 16.0f, 20.0f)
                reflectiveCurveTo(19.0f, 18.66f, 19.0f, 17.0f)
                verticalLineTo(8.0f)
                horizontalLineTo(22.0f)
                verticalLineTo(6.0f)
                close()
            }
        }
    }

    /** Material "pause". */
    val Pause: ImageVector by lazy {
        materialIcon(name = "Onyx.Pause") {
            materialPath {
                moveTo(6.0f, 19.0f); horizontalLineTo(10.0f); verticalLineTo(5.0f)
                horizontalLineTo(6.0f); close()
                moveTo(14.0f, 5.0f); verticalLineTo(19.0f); horizontalLineTo(18.0f)
                verticalLineTo(5.0f); close()
            }
        }
    }

    /** Material "skip previous". */
    val SkipPrevious: ImageVector by lazy {
        materialIcon(name = "Onyx.SkipPrevious") {
            materialPath {
                moveTo(6.0f, 6.0f); horizontalLineTo(8.0f); verticalLineTo(18.0f)
                horizontalLineTo(6.0f); close()
                moveTo(9.5f, 12.0f); lineTo(18.0f, 18.0f); verticalLineTo(6.0f); close()
            }
        }
    }

    /** Material "skip next". */
    val SkipNext: ImageVector by lazy {
        materialIcon(name = "Onyx.SkipNext") {
            materialPath {
                moveTo(6.0f, 18.0f); lineTo(14.5f, 12.0f); lineTo(6.0f, 6.0f); close()
                moveTo(16.0f, 6.0f); verticalLineTo(18.0f); horizontalLineTo(18.0f)
                verticalLineTo(6.0f); close()
            }
        }
    }

    /** Playlists tab: three list lines + a plus (playlist_add feel). */
    val Playlists: ImageVector by lazy {
        materialIcon(name = "Onyx.Playlists") {
            materialPath {
                moveTo(3.0f, 6.0f); horizontalLineTo(17.0f); verticalLineTo(8.0f)
                horizontalLineTo(3.0f); close()
                moveTo(3.0f, 11.0f); horizontalLineTo(17.0f); verticalLineTo(13.0f)
                horizontalLineTo(3.0f); close()
                moveTo(3.0f, 16.0f); horizontalLineTo(11.0f); verticalLineTo(18.0f)
                horizontalLineTo(3.0f); close()
                // plus (bottom-right)
                moveTo(16.0f, 15.0f); horizontalLineTo(18.0f); verticalLineTo(17.0f)
                horizontalLineTo(20.0f); verticalLineTo(19.0f); horizontalLineTo(18.0f)
                verticalLineTo(21.0f); horizontalLineTo(16.0f); verticalLineTo(19.0f)
                horizontalLineTo(14.0f); verticalLineTo(17.0f); horizontalLineTo(16.0f); close()
            }
        }
    }

    /** Plus / add. */
    val Add: ImageVector by lazy {
        materialIcon(name = "Onyx.Add") {
            materialPath {
                moveTo(11.0f, 5.0f); horizontalLineTo(13.0f); verticalLineTo(11.0f)
                horizontalLineTo(19.0f); verticalLineTo(13.0f); horizontalLineTo(13.0f)
                verticalLineTo(19.0f); horizontalLineTo(11.0f); verticalLineTo(13.0f)
                horizontalLineTo(5.0f); verticalLineTo(11.0f); horizontalLineTo(11.0f); close()
            }
        }
    }

    /** Overflow (vertical three-dot). */
    val More: ImageVector by lazy {
        materialIcon(name = "Onyx.More") {
            materialPath {
                moveTo(10.0f, 4.0f); horizontalLineTo(14.0f); verticalLineTo(8.0f)
                horizontalLineTo(10.0f); close()
                moveTo(10.0f, 10.0f); horizontalLineTo(14.0f); verticalLineTo(14.0f)
                horizontalLineTo(10.0f); close()
                moveTo(10.0f, 16.0f); horizontalLineTo(14.0f); verticalLineTo(20.0f)
                horizontalLineTo(10.0f); close()
            }
        }
    }
}
