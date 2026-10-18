package com.sertum.player.data.scan

import android.content.Context
import android.provider.MediaStore
import java.io.File

/**
 * Finds CUE sheets and reads them.
 *
 * MediaStore indexes the sheets as files, so they can be found without walking the filesystem -
 * which matters because walking storage is exactly what scoped storage removed. Their content
 * is then read from the path MediaStore reports.
 *
 * This class is the platform shell around [CueSheetLoader], which holds the decisions: a sheet
 * that cannot be read is skipped rather than failing the scan, and one describing a per-track rip
 * is dropped because it needs no splitting.
 */
class CueSource(private val context: Context) {

    private val loader = CueSheetLoader { path ->
        runCatching { File(path).readBytes() }.getOrNull()
    }

    /** Every readable CUE sheet that describes a disc needing a split, as path to content. */
    fun scan(): Map<String, CueSheet> = loader.load(cuePaths())

    private fun cuePaths(): List<String> {
        val projection = arrayOf(MediaStore.Files.FileColumns.DATA)
        val selection = "${MediaStore.Files.FileColumns.DATA} LIKE ?"
        val result = mutableListOf<String>()
        runCatching {
            context.contentResolver
                .query(MediaStore.Files.getContentUri("external"), projection, selection, arrayOf("%.cue"), null)
                ?.use { cursor ->
                    val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                    while (cursor.moveToNext()) {
                        cursor.getString(dataColumn)?.takeIf { it.isNotBlank() }?.let { result += it }
                    }
                }
        }
        return result
    }
}
