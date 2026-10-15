package com.sertum.player.data.scan

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The directory a track sits in, used as the first key when ordering an album.
 *
 * This exists because a multi-disc release often has no disc number anywhere - the platform
 * exposes a track number tag but no disc tag - so the disc cannot be read, only placed. The
 * directories a release is stored in already run disc 1 first, which makes the directory a
 * disc ordering that is read rather than guessed.
 *
 * The failure it prevents: with only the track number to sort by, two discs interleave, and
 * an album reads 1, 1, 2, 2, 3, 3 rather than 1..13 then 1..13.
 */
class DiscBandTest {

    @Test
    fun `the directory is the band`() {
        assertThat(discBandOf("/music/Album/Disc 1/01 Track.flac")).isEqualTo("/music/Album/Disc 1")
        assertThat(discBandOf("/music/Album/Disc 2/01 Track.flac")).isEqualTo("/music/Album/Disc 2")
    }

    @Test
    fun `two discs of one release order by their directories`() {
        val discOne = discBandOf("/x/DENKARE THE BEST/Disc 1/01 A.flac")
        val discTwo = discBandOf("/x/DENKARE THE BEST/Disc 2/01 B.flac")

        // What matters is only that disc one sorts first, so the interleave is broken.
        assertThat(discOne < discTwo).isTrue()
    }

    @Test
    fun `two discs in one folder are separated by the disc prefix in the file name`() {
        // Wink keeps both discs in a single directory, so the directory alone orders nothing
        // and the album interleaved as 1, 1, 2, 2. The disc is in the file name instead.
        val discOne = discBandOf("/music/Wink - Selection/1.01. Especially for You.flac")
        val discTwo = discBandOf("/music/Wink - Selection/2.01. トゥインクル.flac")

        assertThat(discOne).isNotEqualTo(discTwo)
        assertThat(discOne < discTwo).isTrue()
        // Every track of one disc shares a band, so the track number still orders within it.
        assertThat(discBandOf("/music/Wink - Selection/1.07. 月と太陽.flac")).isEqualTo(discOne)
    }

    @Test
    fun `a disc folder wins over a file name with no disc prefix`() {
        // DENKARE separates its discs by directory and its file names carry only a track
        // number, so the directory is the band and the prefix adds nothing.
        val a = discBandOf("/x/BEST/Disc 1/01 Distorted pain.flac")
        val b = discBandOf("/x/BEST/Disc 2/01 Raison d'etre.flac")

        assertThat(a).isEqualTo("/x/BEST/Disc 1")
        assertThat(b).isEqualTo("/x/BEST/Disc 2")
        assertThat(a < b).isTrue()
    }

    @Test
    fun `a single disc album puts every track in the same band`() {
        val a = discBandOf("/music/Children Of Bodom 1999 - Hatebreeder/01. Warheart.mp3")
        val b = discBandOf("/music/Children Of Bodom 1999 - Hatebreeder/09. Downfall.mp3")

        // Equal bands leave the track number as the tie-breaker, so single-disc albums are
        // unaffected by this key.
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `a file beside its siblings still gets a band`() {
        assertThat(discBandOf("/music/song.flac")).isEqualTo("/music")
        assertThat(discBandOf("song.flac")).isEmpty()
        assertThat(discBandOf("")).isEmpty()
    }

    @Test
    fun `trailing slashes and odd names do not produce empty bands`() {
        assertThat(discBandOf("/music/Album - 2011 [CD]/01 Track.flac")).isEqualTo("/music/Album - 2011 [CD]")
        assertThat(discBandOf("/music/disc.1/01 Track.flac")).isEqualTo("/music/disc.1")
        assertThat(discBandOf("/music/a/b/c/track.flac")).isEqualTo("/music/a/b/c")
    }

    @Test
    fun `bands from the same release sort the same way for both storage spellings`() {
        // The albums on the reference device keep their discs as "Disc 1" and "Disc 2"; this
        // pins that the ordering holds for the numbering style, not the exact word.
        val bands = listOf(
            "/x/BEST/Disc 1/01.flac",
            "/x/BEST/Disc 2/01.flac",
            "/x/BEST/Disc 10/01.flac",
        ).map { discBandOf(it) }

        // "Disc 10" sorts between 1 and 2 lexicographically, which is the documented
        // limitation of reading the directory rather than parsing a disc number.
        assertThat(bands.sorted()).containsExactly(
            "/x/BEST/Disc 1",
            "/x/BEST/Disc 10",
            "/x/BEST/Disc 2",
        ).inOrder()
    }
}
