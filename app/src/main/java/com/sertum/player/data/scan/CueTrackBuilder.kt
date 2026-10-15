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

    // Two different kinds of field, with opposite precedence.
    //
    // Album-level values come from the file first. They describe the whole release, they are
    // what the rest of the library groups albums by, and a sheet cannot be more authoritative
    // about a file than the file is. Several rips here have no tags at all and the sheet is
    // the only source, so the sheet is the fallback.
    val albumArtist = audio.albumArtist ?: cue.albumArtist
    val albumTitle = audio.albumTitle ?: cue.albumTitle
    val genre = audio.genre ?: cue.genre
    val year = audio.year ?: cue.year

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
            title = track.title ?: "${track.number}",
            // The track artist is per-track data, so it takes precedence over the file rather
            // than deferring to it. A single artist tag covers the whole file and cannot
            // describe each track of a compilation: Muv-Luv Vol.2 carries two performers in one
            // tag while its sheet names ten, and preferring the tag made all twenty-seven
            // tracks the same artist. The file's value is the fallback for a sheet that does not
            // name the track, then the album credit, which is what a single-artist release
            // wants since its sheet repeats the same performer on every track.
            artist = track.performer ?: audio.artist ?: albumArtist,
            albumArtist = albumArtist,
            albumTitle = albumTitle,
            genre = genre,
            year = year,
            trackNumber = track.number,
            durationMs = segmentDuration,
            startMs = start,
            endMs = end,
        )
    }
}
