package com.sertum.player.data.db

/**
 * One artist's chosen cover album, as read back before a rescan clears the artist table.
 *
 * A projection rather than the whole entity: the scanner only needs to carry this one
 * field across, and asking for the full row would invite exactly the mistake this type
 * exists to avoid - rebuilding an artist from a partially filled entity.
 */
data class ArtistImageChoice(
    val name: String,
    val imageAlbumKey: String?,
)