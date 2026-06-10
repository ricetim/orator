package com.orator.feature.audiobooks.data

/**
 * A book's resume position and bookmarks are stored as ONE global millisecond offset from
 * the start of the whole book. These functions translate between that and the
 * (file index, offset-in-file) coordinates Media3 queues actually use.
 */
object PositionMapper {

    data class FilePosition(val fileIndex: Int, val offsetMs: Long)

    fun toFilePosition(fileDurationsMs: List<Long>, globalMs: Long): FilePosition {
        if (fileDurationsMs.isEmpty()) return FilePosition(0, 0)
        var remaining = globalMs.coerceAtLeast(0)
        fileDurationsMs.forEachIndexed { index, duration ->
            if (remaining < duration) return FilePosition(index, remaining)
            remaining -= duration
        }
        return FilePosition(fileDurationsMs.lastIndex, fileDurationsMs.last())
    }

    fun toGlobal(fileDurationsMs: List<Long>, fileIndex: Int, offsetMs: Long): Long {
        val priorFiles = fileDurationsMs.take(fileIndex).sum()
        return priorFiles + offsetMs
    }
}
