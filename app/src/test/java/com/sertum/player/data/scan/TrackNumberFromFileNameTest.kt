package com.sertum.player.data.scan

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Recovering a track number from a file name.
 *
 * The need is concrete: a fifth of the library carries no track number in its tags, and the
 * album order then falls back to sorting by title, which scrambles the running order on
 * nineteen albums. The file names in those folders do carry the number, and the accepted
 * forms below are the ones this library actually uses.
 *
 * The hard part is not reading "01." - it is not reading a number that is part of a title.
 * A leading number is only taken as a track position when a separator follows it and it is
 * in the range a track number can occupy. Where that test fails, this returns null and the
 * list stays alphabetical rather than being given an invented order.
 */
class TrackNumberFromFileNameTest {

    /** Calls the production parsing without needing a scanner instance. */
    private fun from(name: String): Int? = trackNumberFromFileName(path = "/music/album/$name")

    @Test
    fun `the separators this library uses are all understood`() {
        // "NN. Title" - Hatebreeder, Cause Of Death, Reincarnation and most others
        assertThat(from("01. Warheart.mp3")).isEqualTo(1)
        assertThat(from("09. Downfall.mp3")).isEqualTo(9)
        assertThat(from("13. Angel's Grave.mp3")).isEqualTo(13)
        // "NN - Title" - Ride The Lightning
        assertThat(from("01 - Fight Fire With Fire.m4a")).isEqualTo(1)
        assertThat(from("08 - The Call Of Ktulu.m4a")).isEqualTo(8)
        // "NN Title" with no punctuation - The Zodiac Sign
        assertThat(from("01 Stella Maris.mp3")).isEqualTo(1)
        assertThat(from("12 The Zodiac Sign.mp3")).isEqualTo(12)
        // single digit - Jealousy
        assertThat(from("1 Scharlachrot.mp3")).isEqualTo(1)
        assertThat(from("8 Perverseness.mp3")).isEqualTo(8)
        // underscore, and a "Track" prefix
        assertThat(from("07_Broken Sleep.flac")).isEqualTo(7)
        assertThat(from("Track 05.mp3")).isEqualTo(5)
    }

    @Test
    fun `whatever follows the separator is irrelevant`() {
        assertThat(from("05. Towards Dead End.mp3")).isEqualTo(5)
        assertThat(from("08. 百鬼飛行.mp3")).isEqualTo(8)
        assertThat(from("03. セクサリス.flac")).isEqualTo(3)
        assertThat(from("11. Sleepwalker (Original Version).m4a")).isEqualTo(11)
    }

    @Test
    fun `a disc dot track prefix uses the track, not the disc`() {
        // "1.01. Title" is disc 1 track 1, not disc 1. Reading the first number would label
        // every track on a disc with the same position, which is what an earlier version of
        // this parsing did: thirteen files all became track 1.
        assertThat(from("1.01. Especially for You.flac")).isEqualTo(1)
        assertThat(from("1.13. Special to Me.flac")).isEqualTo(13)
        assertThat(from("2.01. トゥインクル トゥインクル.flac")).isEqualTo(1)
        assertThat(from("2.13. 特別な一日.flac")).isEqualTo(13)
        // The disc part may be wider than one digit.
        assertThat(from("10.07. Something.flac")).isEqualTo(7)
        assertThat(from("1.01 Title.flac")).isEqualTo(1)
    }

    @Test
    fun `a leading number too long to be a track position is refused`() {
        // Years and catalogue numbers are the realistic false positives. Four digits cannot
        // be a track position, so they resolve to nothing and the list is left alone.
        assertThat(from("2011 Agony.flac")).isNull()
        assertThat(from("2001 - A Space Odyssey.mp3")).isNull()
        assertThat(from("1984.mp3")).isNull()
        // Three digits are still a legal track position on a long set.
        assertThat(from("101. Something.mp3")).isEqualTo(101)
        // Leading zeros are allowed and ignored.
        assertThat(from("0001 - Title.mp3")).isEqualTo(1)
    }

    @Test
    fun `zero is not a track position`() {
        assertThat(from("00. Intro.mp3")).isNull()
        assertThat(from("0 - Hidden Track.mp3")).isNull()
    }

    @Test
    fun `a number not followed by a separator is part of the title`() {
        // "1999" is the name, not position 1999; there is nothing separating it from the rest.
        assertThat(from("1999.mp3")).isNull()
        // A title that merely begins with digits and then runs into a word.
        assertThat(from("7empest.mp3")).isNull()
    }

    @Test
    fun `names with no leading number yield nothing`() {
        // This album has no numbering in its file names at all, so there is nothing to
        // recover and it stays alphabetical. That is the honest outcome, not a failure.
        assertThat(from("Night Tempo - 黄昏のBay City [mqms2].ogg")).isNull()
        assertThat(from("Agony (Japan).flac")).isNull()
        assertThat(from("Warheart.mp3")).isNull()
        assertThat(from("In'El.flac")).isNull()
    }

    @Test
    fun `the extension is not read as a number`() {
        assertThat(from("Incurso.mp3")).isNull()
        assertThat(from("Spawn of Possession - Incurso.flac")).isNull()
    }
}
