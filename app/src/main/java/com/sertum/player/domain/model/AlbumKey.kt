package com.sertum.player.domain.model

/**
 * Album identity per PRD 7.7.1:
 * (Album Title, Album Artist) -> (Album Title, Artist) -> folder path fallback.
 *
 * [format] participates in the identity but not in the title. A lossless rip and a lossy copy
 * of the same release are a different album, so the user sees two entries rather than one
 * holding both, and the titles stay identical so the difference is carried by the quality
 * label on the album page instead of by the name. Left as OTHER, the key is exactly what it
 * was before format existed, so a track whose type the platform does not report does not
 * silently become a separate album.
 */
data class AlbumKey(
    val title: String,
    val albumArtist: String,
    val format: String? = null,
) {
    /** Stable string used as the Room albumKey primary key. */
    val key: String get() = if (format == null) "$albumArtist|$title" else "$albumArtist|$title|$format"

    companion object {
        fun resolve(
            title: String?,
            albumArtist: String?,
            artist: String?,
            folderPath: String?,
            format: String? = null,
        ): AlbumKey = when {
            !title.isNullOrBlank() && !albumArtist.isNullOrBlank() -> AlbumKey(title, albumArtist, format)
            !title.isNullOrBlank() && !artist.isNullOrBlank() -> AlbumKey(title, artist, format)
            !folderPath.isNullOrBlank() -> AlbumKey("__FOLDER__$folderPath", "__FOLDER__$folderPath", format)
            else -> AlbumKey("__UNKNOWN__", "__UNKNOWN__", format)
        }
    }
}
