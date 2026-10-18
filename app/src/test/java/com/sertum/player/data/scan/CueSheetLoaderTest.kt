package com.sertum.player.data.scan

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Which CUE sheets a scan carries forward.
 *
 * Two decisions live here and both have a cost when they are wrong: a sheet that cannot be read
 * must not take the scan down with it, and a per-track rip must not be split.
 */
class CueSheetLoaderTest {

    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8)

    private val singleFileDisc = bytes(
        """
        PERFORMER "Fleshgod Apocalypse"
        TITLE "Agony"
        FILE "Agony.flac" WAVE
          TRACK 01 AUDIO
            TITLE "Temptation"
            INDEX 01 00:00:00
          TRACK 02 AUDIO
            TITLE "The Violation"
            INDEX 01 04:12:00
        """.trimIndent(),
    )

    private val perTrackRip = bytes(
        """
        PERFORMER "Fleshgod Apocalypse"
        TITLE "Agony"
        FILE "01 Temptation.flac" WAVE
          TRACK 01 AUDIO
            INDEX 01 00:00:00
        FILE "02 The Violation.flac" WAVE
          TRACK 02 AUDIO
            INDEX 01 00:00:00
        """.trimIndent(),
    )

    @Test
    fun `no paths means nothing to return`() {
        val loader = CueSheetLoader { error("must not read anything") }
        assertThat(loader.load(emptyList())).isEmpty()
    }

    @Test
    fun `a single-file sheet is kept under its path`() {
        val loader = CueSheetLoader { singleFileDisc }
        val sheets = loader.load(listOf("/music/a.cue"))

        assertThat(sheets.keys).containsExactly("/music/a.cue")
        assertThat(sheets.getValue("/music/a.cue").isSingleFileDisc).isTrue()
    }

    @Test
    fun `an unreadable sheet is skipped and the rest survive`() {
        val loader = CueSheetLoader { path ->
            if (path == "/music/gone.cue") null else singleFileDisc
        }

        val sheets = loader.load(listOf("/music/gone.cue", "/music/ok.cue"))

        assertThat(sheets.keys).containsExactly("/music/ok.cue")
    }

    @Test
    fun `an unparsable sheet is skipped`() {
        val loader = CueSheetLoader { bytes("this is not a cue sheet") }
        assertThat(loader.load(listOf("/music/bad.cue"))).isEmpty()
    }

    @Test
    fun `a per-track rip is dropped because it needs no splitting`() {
        val loader = CueSheetLoader { perTrackRip }
        assertThat(loader.load(listOf("/music/rip.cue"))).isEmpty()
    }
}
