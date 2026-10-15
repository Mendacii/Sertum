package com.sertum.player.data.scan

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Reading a CUE sheet, and deciding whether it describes a disc that needs splitting.
 *
 * The format is simple, but this library shows the parts that make a naive reader wrong:
 *
 * - **A CUE may list one file or many.** Sixteen of the twenty-six here list one file per
 *   track, which is a per-track rip that already plays correctly. Splitting those would be
 *   wrong twice over - the album already works, and each file is a whole track. Only a
 *   single-file CUE describes a disc held in one stream.
 * - **INDEX 00 is a pregap, not a start.** A nine-track CUE can carry thirteen INDEX lines.
 *   Reading every INDEX as a track start invents tracks out of the gaps.
 * - **The declared name often does not exist.** Rips point at the `.wav` they were cut from
 *   while the collection keeps a `.flac`, so the file named in the CUE is frequently absent
 *   and the audio has to be found by base name instead.
 * - **Some files are not UTF-8.** Four of the twenty-six are Shift-JIS, which is why parsing
 *   takes bytes and decodes leniently rather than assuming.
 */
class CueSheetTest {

    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8)

    private val singleFile = """
        REM GENRE "Death Metal"
        PERFORMER "Fleshgod Apocalypse"
        TITLE "Agony [Japan]"
        FILE "Agony (Japan).flac" WAVE
          TRACK 01 AUDIO
            TITLE "Temptation"
            INDEX 01 00:00:00
          TRACK 02 AUDIO
            TITLE "The Hypocrisy"
            INDEX 01 01:47:41
          TRACK 03 AUDIO
            TITLE "The Imposition"
            INDEX 00 07:15:00
            INDEX 01 07:18:54
    """.trimIndent()

    @Test
    fun `a single-file cue yields one entry per track with its start time`() {
        val cue = CueSheet.parse(bytes(singleFile))!!

        assertThat(cue.audioFileNames).containsExactly("Agony (Japan).flac")
        assertThat(cue.tracks).hasSize(3)
        assertThat(cue.tracks.map { it.title })
            .containsExactly("Temptation", "The Hypocrisy", "The Imposition").inOrder()

        // Frames are 1/75 second: 01:47:41 is 107s + 41/75 = 107546ms.
        assertThat(cue.tracks[0].startMs).isEqualTo(0L)
        assertThat(cue.tracks[1].startMs).isEqualTo((107 * 1000) + (41 * 1000 / 75))
        assertThat(cue.tracks[2].startMs).isEqualTo((7 * 60 + 18) * 1000L + 54 * 1000 / 75)
    }

    @Test
    fun `a pregap index is not mistaken for a track start`() {
        val cue = CueSheet.parse(bytes(singleFile))!!

        // Track three carries INDEX 00 at 07:15:00 and INDEX 01 at 07:18:54. Using the pregap
        // would place the track four seconds early and add a phantom track.
        val third = cue.tracks[2]
        assertThat(third.startMs).isNotEqualTo((7 * 60 + 15) * 1000L)
        assertThat(cue.tracks).hasSize(3)
    }

    @Test
    fun `titles are optional and a missing one is not invented`() {
        val cue = CueSheet.parse(
            bytes(
                """
                FILE "disc.flac" WAVE
                  TRACK 01 AUDIO
                    INDEX 01 00:00:00
                  TRACK 02 AUDIO
                    TITLE "Named"
                    INDEX 01 03:00:00
                """.trimIndent(),
            ),
        )!!

        assertThat(cue.tracks[0].title).isNull()
        assertThat(cue.tracks[1].title).isEqualTo("Named")
    }

    @Test
    fun `a multi-file cue is reported as already split`() {
        // One FILE per track: a per-track rip. Every file is one whole track, so there is
        // nothing to divide and the album already plays.
        val cue = CueSheet.parse(
            bytes(
                """
                FILE "01 We Stand.flac" WAVE
                  TRACK 01 AUDIO
                    TITLE "We Stand"
                    INDEX 01 00:00:00
                FILE "02 Not Alone.flac" WAVE
                  TRACK 02 AUDIO
                    TITLE "Not Alone"
                    INDEX 01 00:00:00
                """.trimIndent(),
            ),
        )!!

        assertThat(cue.audioFileNames).hasSize(2)
        assertThat(cue.tracks[1].startMs).isEqualTo(0L)
    }

    @Test
    fun `windows separators in a declared name are normalised`() {
        val cue = CueSheet.parse(
            bytes(
                """
                FILE "Album [CD-FLAC]\01. Prologue.flac" WAVE
                  TRACK 01 AUDIO
                    INDEX 01 00:00:00
                """.trimIndent(),
            ),
        )!!

        // A backslash in the declared name refers to the CUE's own directory, not a subpath.
        assertThat(cue.audioFileNames).containsExactly("01. Prologue.flac")
    }

    @Test
    fun `non-utf8 bytes do not abort the parse`() {
        // Four CUE files here are Shift-JIS. The titles are unreadable but the timing and the
        // file reference - the parts the split depends on - must survive.
        // "アレ" in Shift-JIS, which is not valid UTF-8.
        val shiftJisTitle = byteArrayOf(0x83.toByte(), 0x41, 0x83.toByte(), 0x8C.toByte())
        val head = "FILE \"disc.flac\" WAVE\n  TRACK 01 AUDIO\n    TITLE \"".toByteArray()
        val tail = "\"\n    INDEX 01 01:00:00\n".toByteArray()
        val cue = CueSheet.parse(head + shiftJisTitle + tail)!!

        assertThat(cue.audioFileNames).containsExactly("disc.flac")
        assertThat(cue.tracks).hasSize(1)
        assertThat(cue.tracks[0].startMs).isEqualTo(60_000L)
    }

    @Test
    fun `a file with no tracks is rejected rather than producing an empty album`() {
        assertThat(CueSheet.parse(bytes("REM nothing here"))).isNull()
        assertThat(CueSheet.parse(ByteArray(0))).isNull()
    }

    @Test
    fun `windows line endings parse the same as unix ones`() {
        // Every CUE file in the reference library uses CRLF - the format is written by
        // Windows ripping tools - while the cases above use LF. Parsing has to handle both, so
        // this is the same sheet with the endings swapped.
        val crlf = "FILE \"disc.flac\" WAVE\r\n" +
            "  TRACK 01 AUDIO\r\n" +
            "    TITLE \"First\"\r\n" +
            "    INDEX 01 00:00:00\r\n" +
            "  TRACK 02 AUDIO\r\n" +
            "    TITLE \"Second\"\r\n" +
            "    INDEX 01 02:30:00\r\n"

        val cue = CueSheet.parse(crlf.toByteArray())!!

        assertThat(cue.audioFileNames).containsExactly("disc.flac")
        assertThat(cue.tracks).hasSize(2)
        // A stray carriage return would land inside the captured title.
        assertThat(cue.tracks[0].title).isEqualTo("First")
        assertThat(cue.tracks[1].title).isEqualTo("Second")
        assertThat(cue.tracks[1].startMs).isEqualTo(150_000L)
        assertThat(cue.isSingleFileDisc).isTrue()
    }

    @Test
    fun `case and spacing of the directives do not matter`() {
        val cue = CueSheet.parse(
            bytes(
                """
                file "disc.FLAC" wave
                  track 01 audio
                    title "Lower"
                    index 01 00:30:00
                """.trimIndent(),
            ),
        )!!

        assertThat(cue.audioFileNames).containsExactly("disc.FLAC")
        assertThat(cue.tracks[0].title).isEqualTo("Lower")
        assertThat(cue.tracks[0].startMs).isEqualTo(30_000L)
    }
}
