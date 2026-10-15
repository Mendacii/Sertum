package com.sertum.player.data.scan

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The descriptive lines a CUE sheet carries, which are the only metadata some rips have.
 *
 * A disc ripped to one file often has no tags at all - the file is the whole disc, so there is
 * nowhere sensible to put a track's artist, and several rips here left the file untagged. The
 * sheet beside it has the album, the artist, the year and the genre, and until it was read the
 * library showed those releases as "Unknown artist".
 *
 * The album artist and the track artist are separate fields, not one repeated: four of the
 * sheets here name a different performer per track. Muv-Luv Vol.1 has nine across its
 * twenty-eight, which is a compilation credit rather than noise.
 */
class CueSheetMetadataTest {

    private fun parse(text: String) = CueSheet.parse(text.toByteArray(Charsets.UTF_8))!!

    @Test
    fun `album level lines are read`() {
        val cue = parse(
            """
            REM GENRE "Symphonic Technical Brutal Death Metal"
            REM DATE 2011
            CATALOG 4988001496809
            PERFORMER "Fleshgod Apocalypse"
            TITLE "Agony [Japan]"
            FILE "Agony (Japan).flac" WAVE
              TRACK 01 AUDIO
                TITLE "Temptation"
                PERFORMER "Fleshgod Apocalypse"
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                TITLE "The Hypocrisy"
                PERFORMER "Fleshgod Apocalypse"
                INDEX 01 01:47:41
            """.trimIndent(),
        )

        assertThat(cue.albumArtist).isEqualTo("Fleshgod Apocalypse")
        assertThat(cue.albumTitle).isEqualTo("Agony [Japan]")
        assertThat(cue.genre).isEqualTo("Symphonic Technical Brutal Death Metal")
        assertThat(cue.year).isEqualTo(2011)
    }

    @Test
    fun `a track performer is read per track, not copied from the album`() {
        val cue = parse(
            """
            PERFORMER "Muv-Luv"
            TITLE "Vol.1"
            FILE "disc.flac" WAVE
              TRACK 01 AUDIO
                TITLE "First"
                PERFORMER "yuki"
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                TITLE "Second"
                PERFORMER "Fuki"
                INDEX 01 01:00:00
              TRACK 03 AUDIO
                TITLE "Third"
                PERFORMER "yuki"
                INDEX 01 02:00:00
            """.trimIndent(),
        )

        // The album credit is one value; the track credits differ, and both are kept.
        assertThat(cue.albumArtist).isEqualTo("Muv-Luv")
        assertThat(cue.tracks.map { it.performer }).containsExactly("yuki", "Fuki", "yuki").inOrder()
    }

    @Test
    fun `a track with no performer of its own has none`() {
        val cue = parse(
            """
            PERFORMER "Album Credit"
            FILE "disc.flac" WAVE
              TRACK 01 AUDIO
                TITLE "Untitled"
                INDEX 01 00:00:00
            """.trimIndent(),
        )

        // Not filled in from the album line: the two are different claims, and inventing the
        // second from the first would make a compilation look like a single-artist album.
        assertThat(cue.tracks[0].performer).isNull()
        assertThat(cue.albumArtist).isEqualTo("Album Credit")
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        // " Spawn of Possession" is how that sheet actually spells it.
        val cue = parse(
            """
            PERFORMER " Spawn of Possession "
            TITLE " Incurso "
            FILE "disc.flac" WAVE
              TRACK 01 AUDIO
                TITLE " Abodement "
                PERFORMER " Spawn of Possession "
                INDEX 01 00:00:00
            """.trimIndent(),
        )

        assertThat(cue.albumArtist).isEqualTo("Spawn of Possession")
        assertThat(cue.albumTitle).isEqualTo("Incurso")
        assertThat(cue.tracks[0].performer).isEqualTo("Spawn of Possession")
        assertThat(cue.tracks[0].title).isEqualTo("Abodement")
    }

    @Test
    fun `an album with no performer line has none rather than an empty string`() {
        // Two sheets here carry no album PERFORMER; the tracks still credit their own.
        val cue = parse(
            """
            TITLE "Magia"
            FILE "disc.flac" WAVE
              TRACK 01 AUDIO
                TITLE "Magia"
                PERFORMER "Kalafina"
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                TITLE "Magia (instrumental)"
                PERFORMER "Kalafina"
                INDEX 01 05:00:00
            """.trimIndent(),
        )

        assertThat(cue.albumArtist).isNull()
        assertThat(cue.albumTitle).isEqualTo("Magia")
        assertThat(cue.tracks.map { it.performer }).containsExactly("Kalafina", "Kalafina").inOrder()
    }

    @Test
    fun `a year that is not a year is not taken as one`() {
        val cue = parse(
            """
            REM DATE "not a year"
            TITLE "T"
            FILE "disc.flac" WAVE
              TRACK 01 AUDIO
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                INDEX 01 01:00:00
            """.trimIndent(),
        )

        assertThat(cue.year).isNull()
    }

    @Test
    fun `a quoted date and a bare one are both accepted`() {
        assertThat(parse("REM DATE \"2006\"\nTITLE \"T\"\nFILE \"d.flac\" WAVE\n  TRACK 01 AUDIO\n    INDEX 01 00:00:00\n  TRACK 02 AUDIO\n    INDEX 01 01:00:00").year)
            .isEqualTo(2006)
        assertThat(parse("REM DATE 2016\nTITLE \"T\"\nFILE \"d.flac\" WAVE\n  TRACK 01 AUDIO\n    INDEX 01 00:00:00\n  TRACK 02 AUDIO\n    INDEX 01 01:00:00").year)
            .isEqualTo(2016)
    }

    @Test
    fun `a track title is not overwritten by the album title`() {
        // TITLE appears at both levels and they mean different things. The album title comes
        // first in the file, so a reader that does not track which section it is in would take
        // it as the first track's title.
        val cue = parse(
            """
            TITLE "The Album"
            FILE "disc.flac" WAVE
              TRACK 01 AUDIO
                TITLE "The First Track"
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                TITLE "The Second Track"
                INDEX 01 01:00:00
            """.trimIndent(),
        )

        assertThat(cue.albumTitle).isEqualTo("The Album")
        assertThat(cue.tracks.map { it.title })
            .containsExactly("The First Track", "The Second Track").inOrder()
    }
}
