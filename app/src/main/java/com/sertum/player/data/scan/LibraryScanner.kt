package com.sertum.player.data.scan

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.sertum.player.data.covers.CoverResolver
import com.sertum.player.data.covers.CoverStore
import com.sertum.player.data.db.AlbumEntity
import com.sertum.player.data.db.ArtistEntity
import com.sertum.player.data.db.CoverEntity
import com.sertum.player.data.db.LibraryDao
import com.sertum.player.data.db.SourceType
import com.sertum.player.data.db.TrackEntity
import com.sertum.player.domain.model.AlbumKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
/**
 * How many files are parsed at once, and how many chunks each worker gets before the results are
 * applied and progress is posted.
 *
 * The work per file is a metadata read, so most of the wall time is spent waiting on the content
 * provider - the same wait N files at a time costs roughly once. Capped by the device's core count
 * where that is lower, so a small phone is not asked to run six provider reads at once.
 */
private const val PARSE_WORKERS = 6

private const val PARSE_CHUNKS_PER_WORKER = 4

class LibraryScanner(
    private val context: Context,
    private val dao: LibraryDao,
    private val coverStore: CoverStore,
    private val safDirectories: SafDirectoryStore,
    /**
     * Whether this scan may look at the whole device.
     *
     * A provider rather than a value: a scan can start long after the scanner was constructed, and
     * the answer belongs to the settings screen. The default keeps the scanner usable in tests and
     * anywhere the switch does not exist, and `false` is the safe end - only files the reader
     * pointed at.
     */
    private val fullScanEnabled: () -> Boolean = { false },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _progress = MutableStateFlow(ScanProgress())
    val progress: StateFlow<ScanProgress> = _progress.asStateFlow()

    private data class ParsedTrack(
        val track: TrackEntity,
        val folderPath: String,
        /**
         * The file this track came from.
         *
         * Kept because a track's uri is a `content://` address while a CUE sheet names a file,
         * so the two cannot be compared directly - and CUE matching is entirely about files.
         */
        val sourcePath: String,
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
        // Timed so the cost of what the parse loop reads can be measured rather than argued
        // about. The per-file metadata extraction added to fill discBand, sampleRate and
        // bitDepth reads from the header the retriever has already parsed, so it is expected
        // to be free; this is what says whether it is.
        val startedAt = android.os.SystemClock.elapsedRealtime()
        // The whole-device source runs only when the reader has switched it on.
        //
        // MediaStore is the system's index of every audio file on the phone, so running it
        // unconditionally meant a library built from the entire device no matter what the
        // full-scan switch said - the switch was written by the settings screen and read by
        // nothing, which made it look like a permission the app did not honour. Off now means the
        // library comes from the chosen folders and nowhere else; with none chosen it is empty,
        // which is what "scan nothing until I say so" has to mean.
        val fullScan = fullScanEnabled()
        val mediaCandidates = if (fullScan) {
            withContext(Dispatchers.IO) { MediaStoreSource(context).scan() }
        } else {
            emptyList()
        }
        // The folder walk reports how many files it has found as it goes. Without that the reader
        // stares at a static "正在扫描文件夹… for the whole enumeration - 21 seconds on a 1,173-file
        // folder before the walk itself was made single-query-per-directory.
        val safCandidates = safDirectories.load().flatMap { treeUri ->
            runCatching {
                SafSource().scan(context, treeUri) { found ->
                    _progress.value = ScanProgress(phase = "collecting", candidates = found)
                }
            }.getOrDefault(emptyList())
        }
        val candidates = (mediaCandidates + safCandidates).distinctBy { it.uri }
        _progress.value = ScanProgress(phase = "parsing", candidates = candidates.size)
        val parseStartedAt = android.os.SystemClock.elapsedRealtime()
        var parsed = 0
        var failed = 0
        val parsedTracks = mutableListOf<ParsedTrack>()
        // Keep at most one extracted cover per album. Storing every track's
        // embedded picture while scanning a large library exhausts the heap.
        val embeddedPaths = mutableMapOf<String, String?>()
        // Concurrent, because the parse loop now runs several files at a time: this is what
        // decides which track of an album pays for the embedded picture.
        val embeddedAttempted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        // Parsing is one metadata read per file, and most of that is waiting on the content
        // provider rather than using the CPU - so the wait is overlapped instead of paid once per
        // file. Measured on a 1,173-file SAF folder: 34.8s one file at a time.
        val workers = PARSE_WORKERS.coerceAtMost(Runtime.getRuntime().availableProcessors())
        candidates.chunked(workers * PARSE_CHUNKS_PER_WORKER).forEach { chunk ->
            val results = coroutineScope {
                chunk.map { candidate ->
                    async(Dispatchers.IO) { runCatching { parse(candidate, embeddedAttempted) }.getOrNull() }
                }.awaitAll()
            }
            // Applied here, in chunk order, on this coroutine: the tracks keep the order the
            // sources produced and the album grouping downstream stays deterministic. Cover files
            // are written here too, so writes stay single-threaded.
            results.forEach { result ->
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
            }
            _progress.value = ScanProgress("parsing", candidates.size, parsed, failed)
        }
        // A single-file disc rip is one track at this point: the file parses as a whole. Where a
        // CUE sheet says what is inside it, that one track is replaced by the tracks the sheet
        // describes, so the disc becomes playable as separate tracks. Done before anything is
        // written or grouped, so every count downstream - the album's track count, the album
        // list - reflects the split rather than the file.
        val expandedTracks = applyCueSheets(parsedTracks, CueSource(context))
        val tracks = expandedTracks.map { it.track }

        dao.deleteTracksBySource(SourceType.MEDIA_STORE)
        dao.deleteTracksBySource(SourceType.SAF)
        tracks.forEach { dao.upsertTrack(it) }

        val albums = mutableListOf<AlbumEntity>()
        for ((key, rows) in expandedTracks.groupBy { it.track.albumKey }) {
            val first = rows.first()
            val folderCover = resolveFolderCover(first.folderPath)
            val embeddedPath = embeddedPaths[key]
            // The file decides, not the covers row. The row records that the user picked a cover
            // for this album; the file is what carries it, and its name comes from the album key,
            // which changes when tags or the key rules do. Asking the row alone meant a key
            // change silently dropped the cover from the album even though the image was still
            // on disk - nine albums lost theirs that way when the format joined the key.
            val chosen = dao.coverForAlbum(key)?.userCoverPath
            val inherited = coverStore.hasOnlyFallbackCover(key)
            val onDisk = coverStore.userCoverFor(key)
            val userCover = onDisk ?: chosen?.takeIf { File(it).exists() }
            // A cover found under an older key is copied to the name this key expects, and the
            // row is written back, so the next scan finds it directly and the user's choice is
            // not forgotten by the following key change.
            if (inherited) {
                runCatching {
                    if (coverStore.adoptFallback(key)) {
                        dao.insertCover(
                            CoverEntity(key, coverStore.fileFor(key).absolutePath, System.currentTimeMillis()),
                        )
                    }
                }
            } else if (userCover != null && chosen == null) {
                runCatching {
                    dao.insertCover(CoverEntity(key, userCover, System.currentTimeMillis()))
                }
            }
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
                // Every track in a group shares a format, because the format is part of the
                // key the group was formed by.
                format = AudioFormat.labelFor(first.track.mimeType),
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
        val finishedAt = android.os.SystemClock.elapsedRealtime()
        android.util.Log.i(
            "SertumScanTiming",
            "fullScan=$fullScan candidates=${candidates.size} parsed=$parsed failed=$failed " +
                "enumerateMs=${parseStartedAt - startedAt} parseMs=${finishedAt - parseStartedAt} " +
                "totalMs=${finishedAt - startedAt}",
        )
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
            // Read before the album key, which now depends on the container: a lossless release
            // and a lossy copy of it are separate albums rather than one list holding both.
            val mimeType = runCatching {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: mimeTypeFromExtension(candidate.path)
                ?: "audio/*"
            val albumKey = AlbumKey.resolve(
                title = albumTitle,
                albumArtist = albumArtist,
                artist = artist,
                folderPath = candidate.path.substringBeforeLast('/', "").takeIf { it.isNotBlank() },
                format = AudioFormat.labelFor(mimeType),
            )
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.toIntOrNull()
            val trackNumber = runCatching {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.toIntOrNull()
            }.getOrNull() ?: trackNumberFromFileName(candidate.path)
            // Sampled from the same already-parsed header, so these cost no extra file access.
            // Absent for lossy containers, which have no bit depth to report - MP3 shows a
            // sample rate and no depth, which is the honest answer rather than a guess.
            val sampleRate = runCatching {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()
            }.getOrNull()
            val bitDepth = runCatching {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull()
            }.getOrNull()
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
                discBand = discBandOf(candidate.path),
                // Both null: an ordinary track is the whole file. Only a track carved out of a
                // single-file disc rip carries a span, and those are built after parsing.
                startMs = null,
                endMs = null,
                year = year,
                genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE),
                durationMs = durationMs,
                sampleRate = sampleRate,
                bitDepth = bitDepth,
                mimeType = mimeType,
                source = SourceType.MEDIA_STORE,
                coverRef = null,
                isPlayable = true,
            )
            ParseResult(
                parsed = ParsedTrack(track, candidate.path.substringBeforeLast('/', ""), candidate.path),
                embeddedPicture = embeddedPicture,
            )
        } catch (_: Exception) {
            ParseResult(parsed = null, embeddedPicture = null)
        } finally {
            runCatching { retriever.release() }
        }
    }

/**
 * Replaces a single-file disc rip with the tracks a CUE sheet says it holds.
 *
 * Every input track comes back unchanged except the ones a CUE describes, which become the
 * tracks inside that file.
 *
 * The sheet is trusted only when [matchCueAudio] finds the audio it names already in the
 * library. That check is what stops four CUE files here from doing harm: they outlived the rips
 * they describe, and splitting on them would create tracks pointing at files that no longer
 * exist while hiding the per-track files that replaced them.
 */
    private fun applyCueSheets(
        parsed: List<ParsedTrack>,
        cueSource: CueSource,
    ): List<ParsedTrack> {
        val sheets = runCatching { cueSource.scan() }.getOrDefault(emptyMap())
        if (sheets.isEmpty()) return parsed

        // Matched by file path, not by uri. A track's uri is a content:// address while a CUE
        // names a file, so comparing the two directly never succeeds.
        val libraryPaths = parsed.map { it.sourcePath }.toSet()
        // One file is one disc, so the first sheet to claim it wins. Agony ships both a
        // ".flac.cue" and a ".wav.cue" naming the same FLAC, and they describe the same disc.
        val claimed = mutableMapOf<String, CueSheet>()
        for ((cuePath, sheet) in sheets) {
            val declared = sheet.audioFileName ?: continue
            val audioPath = matchCueAudio(cuePath, declared, libraryPaths) ?: continue
            claimed.putIfAbsent(audioPath, sheet)
        }
        if (claimed.isEmpty()) return parsed

        // One line per scan. This stage is silent when it does nothing and does nothing when it
        // is wrong, so without the counts a failure here reads as "no CUE was split" with no
        // way to tell whether the sheet was unreadable, unmatched, or simply absent. Kept
        // deliberately short: the per-disc breakdown that diagnosed the first attempt was
        // useful then and would be noise every scan.
        android.util.Log.i(
            "SertumCue",
            "sheets=${sheets.size} discs=${claimed.size} tracks=${claimed.values.sumOf { it.tracks.size }}",
        )

        return parsed.flatMap { parsedTrack ->
            val sheet = claimed[parsedTrack.sourcePath] ?: return@flatMap listOf(parsedTrack)
            val carved = buildCueTracks(sheet, parsedTrack.track, parsedTrack.sourcePath)
            if (carved.isEmpty()) {
                listOf(parsedTrack)
            } else {
                carved.map { ParsedTrack(it, parsedTrack.folderPath, parsedTrack.sourcePath) }
            }
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

/**
 * The directory a track sits in, used as the first key when ordering an album.
 *
 * A multi-disc release usually carries no disc number anywhere - the platform exposes a track
 * number tag but no disc tag - so the disc cannot be read from metadata. It does not need to
 * be: the directories a release is stored in already run disc 1 first, so the directory is a
 * disc ordering that gets read instead of guessed. DENKARE THE BEST is the case in point: its
 * two discs are `Disc 1` and `Disc 2` folders, and each now lists 1..17 rather than the two
 * interleaving.
 *
 * When a release keeps both discs in **one** folder, the directory is the same for every track
 * and orders nothing. In that case the file name carries the disc as a `disc.track.` prefix -
 * the same prefix [trackNumberFromFileName] already discards to find the track - so it is
 * appended here, which separates the discs without a disc number ever being interpreted.
 *
 * The limitation is lexicographic ordering, so a tenth disc sorts between the first and the
 * second ("Disc 10" before "Disc 2"). Releases with that many discs are not in the library, and
 * reading a number out of the directory instead would reintroduce the guessing this avoids.
 */
internal fun discBandOf(path: String): String {
    val directory = path.substringBeforeLast('/', "")
    val fileName = path.substringAfterLast('/')
    val discPrefix = DISC_DOT_TRACK_PREFIX.find(fileName)?.value ?: return directory
    return "$directory/$discPrefix"
}

/**
 * MIME type from a file extension, for when the platform does not report one.
 *
 * A backstop rather than the primary source: MediaStore reports a type for every audio file in
 * this library, but the SAF source can hand over a path whose type never reaches the retriever,
 * and without a type such a track would fall out of its album's format group.
 */
private fun mimeTypeFromExtension(path: String): String? =
    when (path.substringAfterLast('.', "").lowercase()) {
        "flac" -> "audio/flac"
        "mp3" -> "audio/mpeg"
        "m4a", "mp4", "aac" -> "audio/mp4"
        "ogg", "oga", "opus" -> "audio/ogg"
        "wav", "wave" -> "audio/x-wav"
        "aif", "aiff", "aifc" -> "audio/x-aiff"
        else -> null
    }
