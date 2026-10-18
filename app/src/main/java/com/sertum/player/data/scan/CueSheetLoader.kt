package com.sertum.player.data.scan

/**
 * Decides which CUE sheets a scan keeps.
 *
 * Reading the files is the caller's job: the scan lists sheets through MediaStore and reads them
 * from a path, which is Android I/O and is verified against the device rather than on the JVM.
 * What is kept here is the part with consequences:
 *
 * - A sheet that cannot be read is skipped, never fatal. A CUE is an optional companion to the
 *   audio; losing one costs a split that could not be made, while failing the scan would cost the
 *   whole library.
 * - A sheet describing one file per track is dropped. That rip already plays as separate tracks,
 *   and splitting it would invent tracks inside each file.
 */
class CueSheetLoader(private val read: (String) -> ByteArray?) {

    fun load(paths: List<String>): Map<String, CueSheet> {
        if (paths.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, CueSheet>()
        for (path in paths) {
            val bytes = read(path) ?: continue
            val sheet = CueSheet.parse(bytes) ?: continue
            if (!sheet.isSingleFileDisc) continue
            result[path] = sheet
        }
        return result
    }
}
