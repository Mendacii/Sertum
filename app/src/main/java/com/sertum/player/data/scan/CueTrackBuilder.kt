package com.sertum.player.data.scan

import com.sertum.player.data.db.TrackEntity

/**
 * The tracks a CUE sheet describes, built from the one file it points at.
 *
 * The sheet supplies the boundaries, the numbers and the titles; the file it describes supplies
 * everything else - album, artist, year, format, the sampled audio parameters - so a carved
 * track is indistinguishable from an ordinary one everywhere else in the app. That is what
 * keeps this from needing any interface of its own: a split disc is just a list of tracks.
 *
 * Returns nothing when the sheet holds fewer than two tracks. A single-track sheet is not a
 * disc held in one file, and replacing a working track with a copy of itself would gain nothing.
 */
internal fun buildCueTracks(
    cue: CueSheet,
    audio: TrackEntity,
    audioPath: String,
): List<TrackEntity> {
    if (cue.tracks.size < 2) return emptyList()
    // Ordering decides where each track ends, so it is settled before any span is written -
    // a sheet listing tracks out of order would otherwise produce negative durations.
    val ordered = cue.tracks.sortedBy { it.startMs }

    return ordered.mapIndexed { index, track ->
        val start = track.startMs
        // The next track's start is this one's end. The last one ends where the file does,
        // which is not known from the sheet, so it stays open.
        val end = ordered.getOrNull(index + 1)?.startMs
        val segmentDuration = when {
            end != null -> (end - start).coerceAtLeast(0L)
            audio.durationMs > start -> audio.durationMs - start
            else -> 0L
        }
        audio.copy(
            // Unique per segment. The audio id is stable for the file, so pairing it with the
            // start time keeps ids stable across scans while separating the segments.
            id = "${audio.uri}#$start".hashCode().toLong(),
            // The album title stays the album's; only the track title comes from the sheet.
            title = track.title ?: "${track.number}",
            trackNumber = track.number,
            durationMs = segmentDuration,
            startMs = start,
            endMs = end,
        )
    }
}
