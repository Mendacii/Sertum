package com.sertum.player.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {

    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE")
    fun observeTracks(): Flow<List<TrackEntity>>

    @Query(
        "SELECT * FROM tracks WHERE albumKey = :albumKey " +
            "ORDER BY COALESCE(discNumber, 999) ASC, COALESCE(trackNumber, 999) ASC, title COLLATE NOCASE ASC",
    )
    fun tracksForAlbum(albumKey: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM albums ORDER BY title COLLATE NOCASE, albumArtist COLLATE NOCASE")
    fun observeAlbums(): Flow<List<AlbumEntity>>

    @Query(
        "SELECT * FROM albums WHERE albumArtist = :artist " +
            "ORDER BY title COLLATE NOCASE",
    )
    fun albumsForArtist(artist: String): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM artists ORDER BY sortKey COLLATE NOCASE")
    fun observeArtists(): Flow<List<ArtistEntity>>

    /**
     * Every artist's chosen cover album, for carrying the choices across a rescan.
     *
     * A scan clears the artist table before repopulating it, so anything held only on
     * those rows is destroyed by design. The choices are user data, not scan output, so
     * the scanner reads them here first and writes them back.
     */
    @Query("SELECT name, imageAlbumKey FROM artists WHERE imageAlbumKey IS NOT NULL")
    suspend fun artistImageChoices(): List<ArtistImageChoice>

    /**
     * Sets which album's cover represents an artist.
     *
     * A targeted update rather than an upsert: the scanner rewrites artist rows on every
     * scan, and an upsert built from a partially filled entity would blank the fields it
     * did not carry. This touches one column and nothing else.
     */
    @Query("UPDATE artists SET imageAlbumKey = :albumKey WHERE name = :name")
    suspend fun setArtistImageAlbum(name: String, albumKey: String?)

    @Upsert
    suspend fun upsertTrack(track: TrackEntity)

    @Upsert
    suspend fun upsertAlbum(album: AlbumEntity)

    @Upsert
    suspend fun upsertArtist(artist: ArtistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCover(cover: CoverEntity)

    @Query("SELECT * FROM covers WHERE albumKey = :albumKey")
    suspend fun coverForAlbum(albumKey: String): CoverEntity?

    @Query("SELECT * FROM covers")
    fun observeCovers(): Flow<List<CoverEntity>>

    @Query("UPDATE albums SET coverRef = :coverRef WHERE albumKey = :albumKey")
    suspend fun setAlbumCover(albumKey: String, coverRef: String?)

    @Query("DELETE FROM covers WHERE albumKey = :albumKey")
    suspend fun deleteCover(albumKey: String)

    @Query("DELETE FROM tracks WHERE source = :source")
    suspend fun deleteTracksBySource(source: SourceType)

    @Query("DELETE FROM albums")
    suspend fun clearAlbumsAndArtistsAlbums()

    @Query("DELETE FROM artists")
    suspend fun clearAlbumsAndArtistsArtists()

    suspend fun clearAlbumsAndArtists() {
        clearAlbumsAndArtistsAlbums()
        clearAlbumsAndArtistsArtists()
    }

    @Query("DELETE FROM tracks WHERE uri NOT IN (:uris)")
    suspend fun deleteTracksNotIn(uris: List<String>)

    @Query("UPDATE tracks SET isPlayable = 0 WHERE id = :trackId")
    suspend fun markTrackUnplayable(trackId: Long)

    @Query("SELECT uri FROM tracks")
    suspend fun allTrackUris(): List<String>

    @Query("SELECT * FROM playback_positions WHERE trackId = :trackId")
    suspend fun resumePosition(trackId: Long): PlaybackPositionEntity?

    @Upsert
    suspend fun putResumePosition(position: PlaybackPositionEntity)

    @Query("DELETE FROM playback_positions WHERE updatedAtEpochMs < :cutoffEpochMs")
    suspend fun pruneResumePositions(cutoffEpochMs: Long): Int
}
