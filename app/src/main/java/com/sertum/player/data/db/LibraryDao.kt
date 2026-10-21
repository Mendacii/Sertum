package com.sertum.player.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {

    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE")
    fun observeTracks(): Flow<List<TrackEntity>>

    /**
     * Tracks of one album, in play order.
     *
     * `discBand` is compared with `COLLATE NOCASE`, and that is the fix for an ordering bug rather
     * than a detail. The band is a directory path, and shared storage is case-insensitive but
     * case-preserving, so one folder can reach this table under both spellings: the platform's
     * `_data` column keeps whichever one the app that indexed the file declared, and
     * [com.sertum.player.data.scan.MediaStoreSource] takes it verbatim. SQLite's default BINARY
     * collation then puts 'D' (0x44) before 'd' (0x64), so the album splits into two bands and the
     * upper-cased one plays first - measured as 3, 4, 1, 2 on `Ave Mujica|神の名を|FLAC`, whose
     * files sit under both `/Download/...` and `/download/...`. NOCASE folds the ASCII case, the
     * two spellings become one band, and the track number orders inside it.
     *
     * The scanner folds the same case when it writes the column (`discBandKeyOf`), so new rows are
     * canonical. This clause is what repairs the rows a previous scan already stored, without
     * requiring a rescan.
     */
    @Query(
        "SELECT * FROM tracks WHERE albumKey = :albumKey " +
            // discBand first: a multi-disc release numbers each disc from one, so ordering on
            // the track number alone interleaves them. The band is the directory, which
            // already runs disc 1 first, so the discs come out in order without a disc number
            // having to be read - the platform exposes no disc tag. It is constant within a
            // single-disc album, so the track number remains the effective first key there.
            //
            // NOCASE on the band: the directory's spelling is not stable in the platform's index
            // (see the KDoc above), and a case difference is not a disc difference.
            "ORDER BY COALESCE(discBand, '') COLLATE NOCASE ASC, " +
            "COALESCE(discNumber, 999) ASC, COALESCE(trackNumber, 999) ASC, title COLLATE NOCASE ASC",
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

    /**
     * Bulk forms, used by the scan's write phase.
     *
     * A scan writes 1,400-odd rows; calling the single-row form in a loop pays Room's per-call
     * overhead 1,400 times. With a list, Room prepares the statement once and binds it per row.
     */
    @Upsert
    suspend fun upsertTracks(tracks: List<TrackEntity>)

    @Upsert
    suspend fun upsertAlbums(albums: List<AlbumEntity>)

    @Upsert
    suspend fun upsertArtists(artists: List<ArtistEntity>)

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

    /** Every stored track, for the incremental scan's diff against what the sources report. */
    @Query("SELECT * FROM tracks")
    suspend fun allTracks(): List<TrackEntity>

    @Query("SELECT * FROM albums")
    suspend fun allAlbums(): List<AlbumEntity>

    @Query("DELETE FROM tracks WHERE uri IN (:uris)")
    suspend fun deleteTracksByUris(uris: List<String>)

    /**
     * Writes one scan's result as a single transaction.
     *
     * The scan used to write row by row: a library of 1,352 tracks meant 1,352 insert transactions
     * plus the album and artist rows, each of which commits to disk on its own. A transaction turns
     * that into one commit, and - just as importantly - means a cancellation or a crash mid-scan
     * leaves the previous library in place instead of a half-written one.
     *
     * [deleteAllSources] rewrites the table (full scan); otherwise only [urisToDelete] goes, which
     * is what an incremental scan touches: the files it re-read and the files that vanished.
     */
    @Transaction
    suspend fun applyLibrary(
        deleteAllSources: Boolean,
        urisToDelete: List<String>,
        tracks: List<TrackEntity>,
        albums: List<AlbumEntity>,
        artists: List<ArtistEntity>,
    ) {
        if (deleteAllSources) {
            deleteTracksBySource(SourceType.MEDIA_STORE)
            deleteTracksBySource(SourceType.SAF)
        }
        // Chunked: SQLite binds at most 999 variables per statement, and a full-library delete list
        // is far longer than that.
        urisToDelete.chunked(SQLITE_MAX_VARIABLES).forEach { deleteTracksByUris(it) }
        if (tracks.isNotEmpty()) upsertTracks(tracks)
        clearAlbumsAndArtistsAlbums()
        clearAlbumsAndArtistsArtists()
        if (albums.isNotEmpty()) upsertAlbums(albums)
        if (artists.isNotEmpty()) upsertArtists(artists)
    }

    companion object {
        /** Conservative against SQLite's 999-variable limit, leaving room for the query's own. */
        const val SQLITE_MAX_VARIABLES = 500
    }
}
