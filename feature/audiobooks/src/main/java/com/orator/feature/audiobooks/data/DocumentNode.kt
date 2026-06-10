package com.orator.feature.audiobooks.data

import androidx.documentfile.provider.DocumentFile

/**
 * Minimal view of a SAF document tree. Exists so scanning logic is testable on the JVM —
 * DocumentFile requires a device. Production code wraps the picked tree in DocumentFileNode.
 */
interface DocumentNode {
    val name: String
    val uri: String
    val isDirectory: Boolean
    fun children(): List<DocumentNode>
}

class DocumentFileNode(private val doc: DocumentFile) : DocumentNode {
    override val name: String get() = doc.name.orEmpty()
    override val uri: String get() = doc.uri.toString()
    override val isDirectory: Boolean get() = doc.isDirectory
    override fun children(): List<DocumentNode> = doc.listFiles().map { DocumentFileNode(it) }
}
