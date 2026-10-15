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
 * A sheet that cannot be read is skipped rather than failing the scan: a CUE is an optional
 * companion to the audio, and losing one costs a split that could not be made, while failing
 * would cost the whole library.
 */
class CueSource(private val context: Context) {

    /** Every readable CUE sheet, as path to content. */
    fun scan(): Map<String, CueSheet> {
        val paths = cuePaths()
        if (paths.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, CueSheet>()
        for (path in paths) {
            val bytes = runCatching { File(path).readBytes() }.getOrNull() ?: continue
            val sheet = CueSheet.parse(bytes) ?: continue
            // A per-track rip needs no splitting, so there is no reason to carry it further.
            if (!sheet.isSingleFileDisc) continue
            result[path] = sheet
        }
        return result
    }

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
