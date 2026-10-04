package com.sertum.player.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "artists")
data class ArtistEntity(
    @PrimaryKey val name: String,
    val sortKey: String,
    val albumCount: Int,
    /**
     * The album whose cover represents this artist in the list, chosen by the user.
     *
     * Null means no choice has been made, in which case the list falls back to the first
     * album that actually has a cover, and then to the artist's initial. Stored as a key
     * rather than a cover path so it keeps pointing at real artwork after a rescan, which
     * rewrites the paths.
     */
    val imageAlbumKey: String? = null,
)
