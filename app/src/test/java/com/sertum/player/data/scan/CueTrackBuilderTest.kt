package com.sertum.player.data.scan

import com.google.common.truth.Truth.assertThat
import com.sertum.player.data.db.SourceType
import com.sertum.player.data.db.TrackEntity
import org.junit.Test

/**
 * Turning a CUE sheet and the one file it describes into the tracks the disc contains.
 *
 * Two things have to be right and are easy to get wrong:
 *
 * - **Each track's duration is its own, not the file's.** A 54-minute rip becomes eleven tracks
 *   of a few minutes each; storing the file's length on every one would list eleven 54-minute
 *   songs and make the progress bar meaningless.
 * - **The last track has no end.** Its end is the end of the file, which is not known here, so
 *   it is left open rather than invented. Clipping to a made-up end would cut the disc short.
 */
class CueTrackBuilderTest {

    private fun parent() = TrackEntity(
        id = 4242L,
        uri = "content://media/external/audio/media/4242",
        title = "Agony [Japan]",
        artist = "Fleshgod Apocalypse",
        albumArtist = "Fleshgod Apocalypse",
        albumTitle = "Agony",
        albumKey = "Fleshgod Apocalypse|Agony|FLAC",
        discNumber = null,
        trackNumber = null,
        // The whole file: this is the track the CUE describes, before it is carved up.
        startMs = null,
        endMs = null,
        discBand = "/music/Agony",
        year = 2011,
        genre = "Death Metal",
        durationMs = 3_240_000L,
        sampleRate = 44_100,
        bitDepth = 16,
        mimeType = "audio/flac",
        source = SourceType.MEDIA_STORE,
        coverRef = null,
        isPlayable = true,
    )

    private val cue = CueSheet(
        audioFileNames = listOf("Agony (Japan).flac"),
        tracks = listOf(
            CueTrack(number = 1, title = "Temptation", performer = "Fleshgod Apocalypse", startMs = 0L),
            CueTrack(number = 2, title = "The Hypocrisy", performer = "Fleshgod Apocalypse", startMs = 107_546L),
            CueTrack(number = 3, title = "The Imposition", performer = "Fleshgod Apocalypse", startMs = 438_720L),
        ),
    )

    @Test
    fun `each track carries its own span, not the file's`() {
        val built = buildCueTracks(cue, parent(), "/music/Agony/Agony (Japan).flac")

        assertThat(built).hasSize(3)
        assertThat(built[0].startMs).isEqualTo(0L)
        assertThat(built[0].endMs).isEqualTo(107_546L)
        assertThat(built[1].startMs).isEqualTo(107_546L)
        assertThat(built[1].endMs).isEqualTo(438_720L)
        // Durations are the span, not the 3_240_000 the file itself reports.
        assertThat(built[0].durationMs).isEqualTo(107_546L)
        assertThat(built[1].durationMs).isEqualTo(438_720L - 107_546L)
    }

    @Test
    fun `the last track is left open rather than clipped to a guess`() {
        val built = buildCueTracks(cue, parent(), "/music/Agony/Agony (Japan).flac")

        assertThat(built[2].endMs).isNull()
        // Its duration is what is known of the file from this point on.
        assertThat(built[2].durationMs).isEqualTo(3_240_000L - 438_720L)
    }

    @Test
    fun `titles come from the sheet and numbers from its order`() {
        val built = buildCueTracks(cue, parent(), "/music/Agony/Agony (Japan).flac")

        assertThat(built.map { it.title })
            .containsExactly("Temptation", "The Hypocrisy", "The Imposition").inOrder()
        assertThat(built.map { it.trackNumber }).containsExactly(1, 2, 3).inOrder()
    }

    @Test
    fun `every track plays the same file and keeps the album's other facts`() {
        val built = buildCueTracks(cue, parent(), "/music/Agony/Agony (Japan).flac")

        assertThat(built.map { it.uri }.toSet()).hasSize(1)
        assertThat(built.map { it.id }.toSet()).hasSize(3)
        assertThat(built[0].albumKey).isEqualTo("Fleshgod Apocalypse|Agony|FLAC")
        assertThat(built[0].albumTitle).isEqualTo("Agony")
        assertThat(built[0].albumArtist).isEqualTo("Fleshgod Apocalypse")
        assertThat(built[0].year).isEqualTo(2011)
        assertThat(built[0].mimeType).isEqualTo("audio/flac")
        assertThat(built[0].bitDepth).isEqualTo(16)
    }

    @Test
    fun `a title missing from the sheet falls back to the track number`() {
        val untitled = CueSheet(
            audioFileNames = listOf("disc.flac"),
            tracks = listOf(
                CueTrack(number = 1, title = null, performer = null, startMs = 0L),
                CueTrack(number = 2, title = "Named", performer = null, startMs = 60_000L),
            ),
        )
        val built = buildCueTracks(untitled, parent(), "/music/disc.flac")

        assertThat(built[0].title).isNotEqualTo(built[1].title)
        assertThat(built[0].title).contains("1")
        assertThat(built[1].title).isEqualTo("Named")
    }

    @Test
    fun `a single-track sheet is not split`() {
        // One track is not a disc held in one file; there is nothing to divide, and splitting
        // would replace a working track with a copy of itself.
        val one = CueSheet(listOf("disc.flac"), listOf(CueTrack(1, "Only", null, 0L)))
        assertThat(buildCueTracks(one, parent(), "/music/disc.flac")).isEmpty()
    }

    @Test
    fun `tracks are always in ascending time even if the sheet is not`() {
        val unordered = CueSheet(
            audioFileNames = listOf("disc.flac"),
            tracks = listOf(
                CueTrack(number = 2, title = "Second", performer = null, startMs = 120_000L),
                CueTrack(number = 1, title = "First", performer = null, startMs = 0L),
            ),
        )
        val built = buildCueTracks(unordered, parent(), "/music/disc.flac")

        // Ordering decides where each track ends, so it is established before the spans are.
        assertThat(built.map { it.title }).containsExactly("First", "Second").inOrder()
        assertThat(built[0].startMs).isEqualTo(0L)
        assertThat(built[0].endMs).isEqualTo(120_000L)
    }

    @Test
    fun `the sheet fills in what an untagged file left empty`() {
        // A disc ripped to one file often has no tags at all, which is why those releases
        // showed as "Unknown artist" - and as "Unknown album", since the album title went the
        // same way. The sheet names both.
        val untagged = parent().copy(
            artist = null,
            albumArtist = null,
            albumTitle = null,
            genre = null,
            year = null,
        )
        val named = cue.copy(
            albumTitle = "Agony [Japan]",
            albumArtist = "Fleshgod Apocalypse",
            genre = "Symphonic Technical Brutal Death Metal",
            year = 2011,
        )

        val built = buildCueTracks(named, untagged, "/music/Agony/Agony (Japan).flac")

        assertThat(built[0].albumArtist).isEqualTo("Fleshgod Apocalypse")
        assertThat(built[0].albumTitle).isEqualTo("Agony [Japan]")
        assertThat(built[0].artist).isEqualTo("Fleshgod Apocalypse")
        assertThat(built[0].genre).isEqualTo("Symphonic Technical Brutal Death Metal")
        assertThat(built[0].year).isEqualTo(2011)
    }

    @Test
    fun `a value the file already states is not overridden by the sheet`() {
        // Album-level fields describe the whole release, and they are what the rest of the
        // library groups albums by, so the file wins where it states them.
        val tagged = parent().copy(
            albumArtist = "File Artist",
            albumTitle = "File Album",
            genre = "File Genre",
            year = 1999,
        )
        val other = cue.copy(
            albumTitle = "Sheet Album",
            albumArtist = "Sheet Artist",
            genre = "Sheet Genre",
            year = 2011,
        )

        val built = buildCueTracks(other, tagged, "/music/Agony/Agony (Japan).flac")

        assertThat(built[0].albumArtist).isEqualTo("File Artist")
        assertThat(built[0].albumTitle).isEqualTo("File Album")
        assertThat(built[0].genre).isEqualTo("File Genre")
        assertThat(built[0].year).isEqualTo(1999)
    }

    @Test
    fun `the track artist beats a file tag that covers the whole file`() {
        // The failure this pins: a compilation's file carried one artist tag while its sheet
        // named ten performers, and deferring to the tag made all twenty-seven tracks of
        // Muv-Luv Vol.2 the same artist. A per-track value describes the track; a file-wide
        // tag cannot, so the sheet's value is the one that is right.
        val taggedFile = parent().copy(artist = "One Artist For The Whole Disc")
        val compilation = CueSheet(
            audioFileNames = listOf("disc.flac"),
            tracks = listOf(
                CueTrack(1, "First", "Mega NRG Man", 0L),
                CueTrack(2, "Second", "Kageyama Hironobu", 60_000L),
            ),
            albumArtist = "Muv-Luv",
        )

        val built = buildCueTracks(compilation, taggedFile, "/music/disc.flac")

        assertThat(built.map { it.artist })
            .containsExactly("Mega NRG Man", "Kageyama Hironobu").inOrder()
    }

    @Test
    fun `a track with no credit of its own defers to the file, then the album`() {
        val untagged = parent().copy(artist = null, albumArtist = null)
        val noCredits = CueSheet(
            audioFileNames = listOf("disc.flac"),
            tracks = listOf(
                CueTrack(1, "A", null, 0L),
                CueTrack(2, "B", null, 60_000L),
            ),
            albumArtist = "The Band",
        )

        assertThat(buildCueTracks(noCredits, untagged, "/music/disc.flac").map { it.artist })
            .containsExactly("The Band", "The Band").inOrder()

        // With a file artist present it is used ahead of the album credit.
        val tagged = parent().copy(artist = "From File", albumArtist = null)
        assertThat(buildCueTracks(noCredits, tagged, "/music/disc.flac").map { it.artist })
            .containsExactly("From File", "From File").inOrder()
    }
}
