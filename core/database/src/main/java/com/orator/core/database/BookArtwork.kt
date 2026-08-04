package com.orator.core.database

import com.orator.core.model.BookOrigin
import java.io.File

/**
 * The cover handle to hand an image loader, resolved against [BookEntity.origin].
 *
 * [BookEntity.coverPath] is overloaded: for ABS books it holds a remote URL, for LOCAL books a
 * filesystem path. Wrapping a URL in [File] yields something no loader can ever resolve, so the
 * branch has to follow the origin — and it lives here, once, because getting it wrong fails
 * silently as a placeholder image rather than as an error.
 */
val BookEntity.artworkModel: Any?
    get() = when (origin) {
        BookOrigin.ABS -> coverPath
        BookOrigin.LOCAL -> coverPath?.let(::File)
    }
