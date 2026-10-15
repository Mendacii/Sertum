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

            for (line in text.lineSequence()) {
                FILE.find(line)?.let { match ->
                    // A declared name may carry a directory, sometimes with Windows
                    // separators. It refers to the CUE's own folder in every case seen, so
                    // only the leaf is kept.
                    val raw = match.groupValues[1].trim()
                    fileNames += raw.substringAfterLast('\\').substringAfterLast('/')
                    continue
                }
                TRACK.find(line)?.let { match ->
                    pendingTrackNumber = match.groupValues[1].toIntOrNull()
                    pendingTitle = null
                    continue
                }
                TITLE.find(line)?.let { match ->
                    pendingTitle = match.groupValues[1].trim().takeIf { it.isNotEmpty() }
                    continue
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
                        startMs = ((minutes * 60) + seconds) * 1000 + frames * 1000 / FRAMES_PER_SECOND,
                    )
                    pendingTrackNumber = null
                }
            }

            if (tracks.isEmpty() || fileNames.isEmpty()) return null
            return CueSheet(audioFileNames = fileNames, tracks = tracks)
        }
    }
}

/**
 * One track boundary inside a CUE.
 *
 * [startMs] is where the track begins; where it ends is the next track's start, or the end of
 * the file for the last one, so no end is stored.
 */
data class CueTrack(
    val number: Int,
    val title: String?,
    val startMs: Long,
)
