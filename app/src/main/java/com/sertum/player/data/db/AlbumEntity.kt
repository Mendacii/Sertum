package com.sertum.player.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.sertum.player.data.covers.CoverResolver

@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey val albumKey: String,
    val title: String,
    val albumArtist: String,
    val year: Int?,
    /** Currently active cover (PRD 7.7.6 chain), consumed by the UI. */
    val coverRef: String?,
    val embeddedCoverPath: String?,
    val folderCoverPath: String?,
    val trackCount: Int,
)

/**
 * True when this album has artwork that can actually be drawn.
 *
 * `coverRef` is non-null for every album - the scanner writes
 * [CoverResolver.PLACEHOLDER_REF] when it finds nothing - so a null check alone reports
 * artwork that is not there. Three screens now ask this question, so it lives here rather
 * than being spelled out at each call site.
 */
fun AlbumEntity.hasRealCover(): Boolean =
    coverRef != null && coverRef != CoverResolver.PLACEHOLDER_REF