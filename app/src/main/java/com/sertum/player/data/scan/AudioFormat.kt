package com.sertum.player.data.scan

/**
 * The container a track is stored in, derived from the MIME type the platform reports.
 *
 * Owns two decisions that would otherwise be made ad hoc in the scanner:
 *
 * - **Which spellings are the same format.** MediaStore reports `audio/flac` for most lossless
 *   files here and `audio/x-flac` for a handful. Left unnormalised, one album becomes two.
 * - **Whether the format is lossless.** Albums are keyed partly on this, so a lossless rip and
 *   a lossy copy of the same release land in separate entries instead of one album holding
 *   both. The user asked for FLAC and MP3 albums to be listed separately.
 *
 * `M4A` is deliberately classified lossy. `audio/mp4` may carry ALAC or AAC and the MIME type
 * does not say which; calling it lossless would risk telling the user a lossy file is
 * lossless, and calling it lossy only risks the reverse, which is the safer error.
 */
enum class AudioFormat(val label: String?, val isLossless: Boolean) {
    FLAC("FLAC", true),
    WAV("WAV", true),
    AIFF("AIFF", true),
    MP3("MP3", false),
    M4A("M4A", false),
    OGG("OGG", false),
    OTHER(null, false),
    ;

    companion object {
        fun from(mimeType: String?): AudioFormat = when (mimeType?.lowercase()?.substringBefore(';')?.trim()) {
            "audio/flac", "audio/x-flac" -> FLAC
            "audio/x-wav", "audio/wav", "audio/vnd.wave" -> WAV
            "audio/x-aiff", "audio/aiff" -> AIFF
            "audio/mpeg", "audio/mp3" -> MP3
            "audio/mp4", "audio/x-m4a", "audio/m4a" -> M4A
            "audio/ogg", "application/ogg" -> OGG
            else -> OTHER
        }

        /**
         * The short name to show for a MIME type, including types this enum does not model.
         *
         * An unmodelled type becomes its subtype in capitals - `audio/opus` reads `OPUS` -
         * rather than nothing, so a format the app has not seen still identifies itself.
         */
        fun labelFor(mimeType: String?): String? =
            from(mimeType).label ?: mimeType?.substringAfter('/', "")?.substringBefore(';')?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
    }
}
