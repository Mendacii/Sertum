package com.sertum.player.data.scan

/**
 * A parsed CUE sheet: the audio it names, and the track boundaries within it.
 *
 * Reading a CUE is simple; knowing when *not* to use one is the substance here. See
 * [isSingleFileDisc] for the distinction that decides it.
 */
data class CueSheet(
    /** Names as written in the file, in order, with any directory part stripped. */
    val audioFileNames: List<String>,
    val tracks: List<CueTrack>,
    /**
     * The album's own lines, which for an untagged rip are the only metadata there is.
     *
     * Several rips here left the audio file with no tags at all - a disc ripped to one file has
     * nowhere sensible to put a track's artist - so the sheet is what says who made it. Null
     * when the sheet does not say, never an empty string.
     */
    val albumTitle: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val year: Int? = null,
) {
    /**
     * True when this CUE describes a whole disc held in one stream, which is the only case
     * where the tracks have to be divided.
     *
     * A CUE listing one file per track describes a per-track rip that already plays as
     * separate tracks. Sixteen of the twenty-six CUE files in the reference library are that
     * shape, each with one FILE directive per track and every INDEX at 00:00:00. Splitting
     * those would be wrong twice: the album already works, and each file is a whole track, so
     * dividing it would slice a single song into pieces.
     */
    val isSingleFileDisc: Boolean get() = audioFileNames.size == 1 && tracks.size > 1

    /** The single audio file, when there is exactly one. */
    val audioFileName: String? get() = audioFileNames.singleOrNull()

    companion object {
        /** CUE timestamps are minutes:seconds:frames, where a frame is 1/75 of a second. */
        private const val FRAMES_PER_SECOND = 75

        private val FILE = Regex("""^\s*FILE\s+"?([^"]+?)"?\s+(WAVE|MP3|AIFF|BINARY)\s*$""", RegexOption.IGNORE_CASE)
        private val TRACK = Regex("""^\s*TRACK\s+(\d+)\s+AUDIO""", RegexOption.IGNORE_CASE)
        private val TITLE = Regex("""^\s*TITLE\s+"(.*)"\s*$""", RegexOption.IGNORE_CASE)
        private val PERFORMER = Regex("""^\s*PERFORMER\s+"(.*)"\s*$""", RegexOption.IGNORE_CASE)
        private val GENRE = Regex("""^\s*REM\s+GENRE\s+"(.*)"\s*$""", RegexOption.IGNORE_CASE)
        /** `REM DATE` is written both quoted and bare; the year is the part that matters. */
        private val DATE = Regex("""^\s*REM\s+DATE\s+"?(\d{4})"?\s*$""", RegexOption.IGNORE_CASE)
        private val INDEX = Regex("""^\s*INDEX\s+(\d+)\s+(\d+):(\d+):(\d+)\s*$""", RegexOption.IGNORE_CASE)

        /**
         * Parses [bytes], or returns null when the content is not a CUE describing tracks.
         *
         * Takes bytes rather than text because four CUE files in the reference library are
         * Shift-JIS. Decoding is lenient: an unreadable title costs nothing, while refusing to
         * parse would lose the timings and the file reference, which are the parts that matter.
         *
         * Only `INDEX 01` is taken as a track start. `INDEX 00` is the pregap before it, and a
         * nine-track disc here carries thirteen INDEX lines, so reading every index as a start
         * would both shift the tracks and invent extras.
         */
        fun parse(bytes: ByteArray): CueSheet? {
            if (bytes.isEmpty()) return null
            val text = bytes.toString(Charsets.UTF_8)

            val fileNames = mutableListOf<String>()
            val tracks = mutableListOf<CueTrack>()
            var pendingTrackNumber: Int? = null
            var pendingTitle: String? = null
            var pendingPerformer: String? = null
            var albumTitle: String? = null
            var albumArtist: String? = null
            var genre: String? = null
            var year: Int? = null
            // TITLE and PERFORMER appear at both levels and mean different things. The album's
            // come before any TRACK line, so a TRACK switches the reader into the track section
            // and everything after it belongs to a track.
            var inTrack = false

            for (line in text.lineSequence()) {
                FILE.find(line)?.let { match ->
                    // A declared name may carry a directory, sometimes with Windows
                    // separators. It refers to the CUE's own folder in every case seen, so
                    // only the leaf is kept.
                    val raw = match.groupValues[1].trim()
                    fileNames += raw.substringAfterLast('\\').substringAfterLast('/')
                    return@let
                }
                TRACK.find(line)?.let { match ->
                    pendingTrackNumber = match.groupValues[1].toIntOrNull()
                    pendingTitle = null
                    pendingPerformer = null
                    inTrack = true
                    return@let
                }
                // Read before TITLE, which would otherwise swallow "REM DATE".
                GENRE.find(line)?.let { match ->
                    genre = match.groupValues[1].trim().takeIf { it.isNotEmpty() }
                    return@let
                }
                DATE.find(line)?.let { match ->
                    year = match.groupValues[1].toIntOrNull()?.takeIf { it in 1000..2999 }
                    return@let
                }
                TITLE.find(line)?.let { match ->
                    val value = match.groupValues[1].trim().takeIf { it.isNotEmpty() }
                    if (inTrack) pendingTitle = value else albumTitle = albumTitle ?: value
                    return@let
                }
                PERFORMER.find(line)?.let { match ->
                    val value = match.groupValues[1].trim().takeIf { it.isNotEmpty() }
                    if (inTrack) pendingPerformer = value else albumArtist = albumArtist ?: value
                    return@let
                }
                INDEX.find(line)?.let { match ->
                    val indexNumber = match.groupValues[1].toIntOrNull()
                    if (indexNumber != 1) return@let
                    val minutes = match.groupValues[2].toLongOrNull() ?: return@let
                    val seconds = match.groupValues[3].toLongOrNull() ?: return@let
                    val frames = match.groupValues[4].toLongOrNull() ?: return@let
                    val number = pendingTrackNumber ?: return@let
                    tracks += CueTrack(
                        number = number,
                        title = pendingTitle,
                        performer = pendingPerformer,
                        startMs = ((minutes * 60) + seconds) * 1000 + frames * 1000 / FRAMES_PER_SECOND,
                    )
                    pendingTrackNumber = null
                }
            }

            if (tracks.isEmpty() || fileNames.isEmpty()) return null
            return CueSheet(
                audioFileNames = fileNames,
                tracks = tracks,
                albumTitle = albumTitle,
                albumArtist = albumArtist,
                genre = genre,
                year = year,
            )
        }
    }
}

/**
 * One track boundary inside a CUE.
 *
 * [startMs] is where the track begins; where it ends is the next track's start, or the end of
 * the file for the last one, so no end is stored.
 *
 * [performer] is the track's own credit, which is not always the album's. Four sheets here name
 * a different performer per track - Muv-Luv Vol.1 has nine across twenty-eight - so this is
 * carried per track rather than assumed from the album line.
 */
data class CueTrack(
    val number: Int,
    val title: String?,
    val performer: String?,
    val startMs: Long,
)
