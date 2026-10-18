package com.sertum.player.data.scan

/** One track discovered by any scan source. Paths are source-normalized. */
data class ScanCandidate(
    val uri: String,
    val path: String,
    val sizeBytes: Long,
    val modifiedAtEpochMs: Long,
)

/** A track already stored in the library database. */
data class ExistingTrack(
    val uri: String,
    val sizeBytes: Long,
    val modifiedAtEpochMs: Long,
)

/**
 * Diffs what the scan sources report against what the library already holds, so a scan can read
 * only the files that changed.
 *
 * ## Why the identity is the URI
 *
 * The stored track's primary key is derived from its URI, and the scan has always collapsed
 * candidates with `distinctBy { it.uri }`, so the URI is the identity every other part of the
 * pipeline already uses. It is also the only field both sides share: the tracks table has no
 * column for the source's file path, and adding one just to diff on it would create a second
 * identity that could disagree with the first.
 *
 * A size or modification-time mismatch counts as changed, and a row written before those columns
 * existed reads as 0 on both - which is a mismatch, so it is re-read once and recorded properly
 * rather than being trusted without evidence.
 */
class ScanEngine {

    data class Plan(
        val toAdd: List<ScanCandidate>,
        val toUpdate: List<ScanCandidate>,
        /** URIs that are in the library but were not reported by any source. */
        val toDeleteUris: List<String>,
        val unchangedCount: Int,
    ) {
        /** Files that have to be opened and parsed. */
        val toParse: List<ScanCandidate> get() = toAdd + toUpdate
    }

    /**
     * The split a scan's write phase acts on: the stored rows it keeps untouched, and the URIs it
     * removes.
     */
    data class WritePlan(
        /** Rows the scan did not rewrite. They are carried into the album rebuild. */
        val keptUris: Set<String>,
        /** URIs passed to the delete step of the transaction. Never "the whole table". */
        val urisToDelete: List<String>,
    )

    /**
     * Decides which stored rows survive a scan and which are removed by its transaction.
     *
     * A row is kept when the scan did not touch it (an unchanged file), or when it is protected:
     * its metadata could not be read this time, or the source it came from could not be enumerated
     * at all (the media permission is missing). A read failure says something about this attempt,
     * not about the file - an album being copied in, a file the retriever cannot open yet, or a
     * denied permission would otherwise remove tracks that still exist. That is exactly how the
     * library was emptied once: a full scan deleted every row and then failed to write back the
     * ones it could not parse.
     *
     * A row is removed when the scan successfully re-read its file (the fresh row is written in the
     * same transaction; this is also how a CUE rip's one-file row is replaced by the tracks carved
     * out of it), or when no source reported the file. Absence is only evidence of deletion for a
     * source that was actually enumerated, which is why [reportedUris] is a parameter rather than
     * being derived from [reparsedUris].
     *
     * URIs, not rows: a CUE rip stores several rows under the same file URI, and the delete step
     * addresses files.
     */
    fun writePlan(
        existing: List<ExistingTrack>,
        reportedUris: Set<String>,
        reparsedUris: Set<String>,
        protectedUris: Set<String>,
    ): WritePlan {
        val kept = LinkedHashSet<String>()
        val delete = LinkedHashSet<String>()
        existing.forEach { row ->
            val keepRow = row.uri in protectedUris ||
                (row.uri in reportedUris && row.uri !in reparsedUris)
            if (keepRow) {
                kept += row.uri
            } else {
                delete += row.uri
            }
        }
        return WritePlan(keptUris = kept, urisToDelete = delete.toList())
    }

    fun normalizePath(path: String): String =
        path.trim().replace('\\', '/').trimEnd('/')

    fun plan(
        existing: List<ExistingTrack>,
        candidates: List<ScanCandidate>,
    ): Plan {
        val existingByUri = LinkedHashMap<String, ExistingTrack>()
        existing.forEach { existingByUri[it.uri] = it }

        val seen = HashSet<String>()
        val add = mutableListOf<ScanCandidate>()
        val update = mutableListOf<ScanCandidate>()
        var unchanged = 0
        candidates.forEach { candidate ->
            if (!seen.add(candidate.uri)) return@forEach
            val current = existingByUri[candidate.uri]
            when {
                current == null -> add += candidate
                current.sizeBytes != candidate.sizeBytes ||
                    current.modifiedAtEpochMs != candidate.modifiedAtEpochMs -> update += candidate
                else -> unchanged += 1
            }
        }

        val delete = existing.filter { it.uri !in seen }.map { it.uri }
        return Plan(add, update, delete, unchanged)
    }
}
