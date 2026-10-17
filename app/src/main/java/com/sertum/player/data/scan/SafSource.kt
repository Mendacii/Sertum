package com.sertum.player.data.scan

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * SAF folder source (US-3 AC2). Walks a persisted tree URI and reports every supported audio file.
 *
 * One cursor per directory, not one provider query per property.
 *
 * This used to walk with `DocumentFile`, where `child.isDirectory`, `child.length()` and
 * `child.lastModified()` each issue their own query. A 1,173-file folder therefore cost roughly
 * 4,700 round trips and measured **21 seconds** of enumeration before the scanner could report a
 * single candidate - which the reader saw as the scan sitting on "正在扫描文件夹…" with no progress
 * at all. `DocumentsContract` returns every column for a directory's children in one cursor, so the
 * same tree is walked with one query per directory.
 *
 * [onProgress] is called with the running count of discovered files so the settings screen can show
 * movement during the walk rather than only once it is over. It is throttled inside the walk; the
 * caller just renders the number.
 */
class SafSource {

    /**
     * @param onProgress running total of supported files found so far. Called at most every
     *   [PROGRESS_EVERY] files and once when the walk finishes.
     */
    fun scan(
        context: Context,
        treeUri: Uri,
        maxDepth: Int = 16,
        onProgress: (Int) -> Unit = {},
    ): List<ScanCandidate> {
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return emptyList()
        val result = mutableListOf<ScanCandidate>()
        walk(context, treeUri, rootId, maxDepth, result, onProgress)
        onProgress(result.size)
        return result
    }

    private fun walk(
        context: Context,
        treeUri: Uri,
        documentId: String,
        depth: Int,
        out: MutableList<ScanCandidate>,
        onProgress: (Int) -> Unit,
    ) {
        if (depth <= 0) return
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val directories = mutableListOf<String>()
        val cursor = runCatching { context.contentResolver.query(childrenUri, COLUMNS, null, null, null) }
            .getOrNull() ?: return
        cursor.use {
            val idColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            if (idColumn < 0 || nameColumn < 0 || mimeColumn < 0) return
            while (it.moveToNext()) {
                val childId = it.getString(idColumn) ?: continue
                if (it.getString(mimeColumn) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    // Collected and walked after this cursor is closed, so no nested query happens
                    // while the parent cursor is still open on the provider.
                    directories += childId
                    continue
                }
                val name = it.getString(nameColumn).orEmpty()
                if (!AudioExtensions.isSupported(name)) continue
                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId).toString()
                out += ScanCandidate(
                    uri = childUri,
                    // The tree URI is what playable access hangs off, so the path stays the
                    // document URI exactly as the DocumentFile walk produced it.
                    path = childUri,
                    sizeBytes = if (sizeColumn >= 0 && !it.isNull(sizeColumn)) it.getLong(sizeColumn) else 0L,
                    modifiedAtEpochMs =
                        if (modifiedColumn >= 0 && !it.isNull(modifiedColumn)) it.getLong(modifiedColumn) else 0L,
                )
                if (out.size % PROGRESS_EVERY == 0) onProgress(out.size)
            }
        }
        directories.forEach { walk(context, treeUri, it, depth - 1, out, onProgress) }
    }

    private companion object {
        /**
         * How many found files between progress reports. Low enough that the number on screen moves
         * a few times a second while a folder is being walked, high enough not to recompose the
         * settings screen once per file.
         */
        const val PROGRESS_EVERY = 25

        val COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}
