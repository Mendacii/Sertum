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
