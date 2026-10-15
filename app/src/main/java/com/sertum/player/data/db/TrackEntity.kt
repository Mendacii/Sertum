package com.sertum.player.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tracks",
    indices = [Index("uri"), Index("albumKey")],
)
data class TrackEntity(
    @PrimaryKey val id: Long,
    val uri: String,
    val title: String,
    val artist: String?,
    val albumArtist: String?,
    val albumTitle: String?,
    val albumKey: String,
    val discNumber: Int?,
    val trackNumber: Int?,
    /**
     * The directory this track sits in, used as the first key when ordering an album.
     *
     * A multi-disc release normally has no disc number to read - the platform exposes a track
     * number tag but no disc tag - and its directories already run disc 1 first, so the
     * directory orders the discs without anything being guessed. Sorting on the track number
     * alone interleaves them: 1, 1, 2, 2 rather than 1..13 then 1..13.
     */
    val discBand: String?,
    val year: Int?,
    val genre: String?,
    val durationMs: Long,
    val sampleRate: Int?,
    val bitDepth: Int?,
    val mimeType: String,
    val source: SourceType,
    val coverRef: String?,
    val isPlayable: Boolean,
)
