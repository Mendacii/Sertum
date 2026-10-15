package com.sertum.player.data.scan

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.sertum.player.data.covers.CoverResolver
import com.sertum.player.data.covers.CoverStore
import com.sertum.player.data.db.AlbumEntity
import com.sertum.player.data.db.ArtistEntity
import com.sertum.player.data.db.LibraryDao
import com.sertum.player.data.db.SourceType
import com.sertum.player.data.db.TrackEntity
import com.sertum.player.domain.model.AlbumKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ScanStats(
    val candidates: Int,
    val parsed: Int,
    val failed: Int,
    val albums: Int,
    val artists: Int,
)

data class ScanProgress(
    val phase: String = "idle",
    val candidates: Int = 0,
    val parsed: Int = 0,
    val failed: Int = 0,
)

/**
 * Bootstrap scanner for the production library: MediaStore source ->
 * MediaMetadataRetriever -> Room (tracks/albums/artists). Replaces all
 * MEDIA_STORE rows on each run (full rescan semantics; incremental diffing
 * stays with ScanEngine for the M3 milestone follow-up).
 */
class LibraryScanner(
    private val context: Context,
    private val dao: LibraryDao,
    private val coverStore: CoverStore,
    private val safDirectories: SafDirectoryStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _progress = MutableStateFlow(ScanProgress())
    val progress: StateFlow<ScanProgress> = _progress.asStateFlow()

    private data class ParsedTrack(
        val track: TrackEntity,
        val folderPath: String,
    )

    private data class ParseResult(
        val parsed: ParsedTrack?,
        val embeddedPicture: ByteArray?,
    )

    fun scanNow(onDone: (Result<ScanStats>) -> Unit = {}) {
        _progress.value = ScanProgress(phase = "collecting")
        scope.launch {
            onDone(runCatching { scanBlocking() })
        }
    }

    suspend fun scanBlocking(): ScanStats {
        val mediaCandidates = withContext(Dispatchers.IO) { MediaStoreSource(context).scan() }
        val safCandidates = safDirectories.load().flatMap { treeUri ->
            runCatching { SafSource().scan(context, treeUri) }.getOrDefault(emptyList())
        }
        val candidates = (mediaCandidates + safCandidates).distinctBy { it.uri }
        _progress.value = ScanProgress(phase = "parsing", candidates = candidates.size)
        var parsed = 0
        var failed = 0
        val parsedTracks = mutableListOf<ParsedTrack>()
        // Keep at most one extracted cover per album. Storing every track's
        // embedded picture while scanning a large library exhausts the heap.
        val embeddedPaths = mutableMapOf<String, String?>()
        val embeddedAttempted = mutableSetOf<String>()
        for (candidate in candidates) {
            val result = runCatching { parse(candidate, embeddedAttempted) }.getOrNull()
            val parsedTrack = result?.parsed
            if (parsedTrack == null) {
                failed += 1
            } else {
                parsedTracks += parsedTrack
                parsed += 1
                val albumKey = parsedTrack.track.albumKey
                result.embeddedPicture?.let { picture ->
                    if (albumKey !in embeddedPaths) {
                        embeddedPaths[albumKey] =
                            runCatching { coverStore.saveEmbedded(albumKey, picture) }.getOrNull()
                    }
                }
            }
            _progress.value = ScanProgress("parsing", candidates.size, parsed, failed)
        }
        val tracks = parsedTracks.map { it.track }

        dao.deleteTracksBySource(SourceType.MEDIA_STORE)
        dao.deleteTracksBySource(SourceType.SAF)
        tracks.forEach { dao.upsertTrack(it) }

        val albums = mutableListOf<AlbumEntity>()
        for ((key, rows) in parsedTracks.groupBy { it.track.albumKey }) {
            val first = rows.first()
            val folderCover = resolveFolderCover(first.folderPath)
            val embeddedPath = embeddedPaths[key]
            val userCover = dao.coverForAlbum(key)?.userCoverPath
            val resolved = CoverResolver.resolve(userCover, embeddedPath, folderCover).reference
            albums += AlbumEntity(
                albumKey = key,
                title = first.track.albumTitle ?: "Unknown album",
                albumArtist = first.track.albumArtist ?: first.track.artist ?: "Unknown artist",
                year = first.track.year,
                coverRef = resolved,
                embeddedCoverPath = embeddedPath,
                folderCoverPath = folderCover,
                trackCount = rows.size,
            )
        }
        val artists = tracks
            .map { (it.albumArtist ?: it.artist ?: "Unknown artist") to it.albumKey }
            .groupBy { it.first }
            .map { (name, rows) ->
                ArtistEntity(
                    name = name,
                    sortKey = name.uppercase(),
                    albumCount = rows.map { it.second }.distinct().size,
                )
            }

        // Which album art each artist shows is the user's choice, not scan output, and the
        // clear below deletes the rows that hold it. Read it first, then write it back
        // with the fresh rows, or every rescan would silently reset every artist.
        val imageChoices = dao.artistImageChoices().associate { it.name to it.imageAlbumKey }

        dao.clearAlbumsAndArtists()
        albums.forEach { dao.upsertAlbum(it) }
        artists.forEach { artist ->
            dao.upsertArtist(artist.copy(imageAlbumKey = imageChoices[artist.name]))
        }
        _progress.value = ScanProgress("done", candidates.size, parsed, failed)
        return ScanStats(candidates.size, parsed, failed, albums.size, artists.size)
    }

    private fun resolveFolderCover(folderPath: String): String? {
        val dir = File(folderPath)
        if (!dir.isDirectory) return null
        return listOf("cover.jpg", "cover.png", "folder.jpg", "Folder.jpg", "Cover.jpg")
            .map { File(dir, it) }
            .firstOrNull { it.isFile }
            ?.absolutePath
    }

    private fun parse(candidate: ScanCandidate, embeddedAttempted: MutableSet<String>): ParseResult {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(candidate.uri))
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() } ?: fallbackTitle(candidate)
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val albumArtist = runCatching {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            }.getOrNull() ?: artist
            val albumTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val albumKey = AlbumKey.resolve(
                title = albumTitle,
                albumArtist = albumArtist,
                artist = artist,
                folderPath = candidate.path.substringBeforeLast('/', "").takeIf { it.isNotBlank() },
            )
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.toIntOrNull()
            val trackNumber = runCatching {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.toIntOrNull()
            }.getOrNull() ?: trackNumberFromFileName(candidate.path)
            val mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                ?: "audio/*"
            val embeddedPicture = if (embeddedAttempted.add(albumKey.key)) {
                runCatching { retriever.embeddedPicture }.getOrNull()
            } else {
                null
            }
            val track = TrackEntity(
                id = candidate.uri.hashCode().toLong(),
                uri = candidate.uri,
                title = title,
                artist = artist,
                albumArtist = albumArtist,
                albumTitle = albumTitle,
                albumKey = albumKey.key,
                discNumber = null,
                trackNumber = trackNumber,
                year = year,
                genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE),
                durationMs = durationMs,
                sampleRate = null,
                bitDepth = null,
                mimeType = mimeType,
                source = SourceType.MEDIA_STORE,
                coverRef = null,
                isPlayable = true,
            )
            ParseResult(
                parsed = ParsedTrack(track, candidate.path.substringBeforeLast('/', "")),
                embeddedPicture = embeddedPicture,
            )
        } catch (_: Exception) {
            ParseResult(parsed = null, embeddedPicture = null)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun fallbackTitle(candidate: ScanCandidate): String =
        candidate.path.substringAfterLast('/').substringBeforeLast('.').ifBlank { "Unknown track" }
}

/**
 * Leading track number in a file name, or null when the name does not carry one.
 *
 * Needed because tags are not always complete: 290 of 1363 tracks in the reference library
 * have no track number in their metadata, and the album ordering falls back to sorting by
 * title when that happens, which scrambles the running order. The file names in those
 * folders do carry the number, so the position is recoverable without retagging anything.
 *
 * Accepted forms, all of which occur in real folders:
 * `01. Title`, `01 - Title`, `01 Title`, `1 Title`, `07_Title`, `Track 05`, `0001 - Title`,
 * and `1.01. Title`, where the leading segment is the disc.
 *
 * Refused, because a number alone does not make a position:
 * - anything not followed by a separator, so `1999.mp3` and `7empest.mp3` are titles;
 * - four or more digits, so a year or catalogue prefix like `2011 Agony.flac` is not read as
 *   track 2011;
 * - zero, which no track list uses as a position.
 *
 * Returning null is the safe outcome: the list stays alphabetical. Guessing here would
 * invent an order rather than recover one, which is what happened when a `disc.track`
 * prefix was read as the track and gave thirteen files the same position.
 */
private val DISC_DOT_TRACK_PREFIX = Regex("""^0*\d{1,2}\.(?=\d)""")

private val TRACK_NUMBER_PREFIX = Regex("""^(?:track\s*)?0*([1-9]\d{0,2})(?=$|\s|[-._])""", RegexOption.IGNORE_CASE)

internal fun trackNumberFromFileName(path: String): Int? {
    val name = path.substringAfterLast('/')
    // "1.13. Title" numbers both the disc and the track; the track is the second number, so
    // the disc segment is dropped before the position is read. Without this, every file on a
    // disc resolves to the same track number.
    val stem = DISC_DOT_TRACK_PREFIX.replace(name.substringBeforeLast('.'), "")
    return TRACK_NUMBER_PREFIX.find(stem)?.groupValues?.get(1)?.toIntOrNull()
}
