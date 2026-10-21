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
 *
 * The second failure it prevents, found on the reference device: one folder spelled two ways
 * (`/Download/...` and `/download/...` - one directory on a case-insensitive volume) split an
 * album into two bands, and BINARY ordering played the upper-cased half first, so the album
 * read 3, 4, 1, 2. [discBandKeyOf] is what a scan writes to the row; [discBandOf] stays the raw
 * extraction that the earlier cases below describe.
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

    @Test
    fun `the same directory spelled in either case makes one band`() {
        // Shared storage is case-insensitive: these are one folder, and the platform's `_data`
        // column can carry either spelling for files inside it.
        val upper = discBandKeyOf("/storage/emulated/0/Download/Flud/Ave Mujica/03.flac")
        val lower = discBandKeyOf("/storage/emulated/0/download/Flud/Ave Mujica/01.flac")

        assertThat(upper).isEqualTo(lower)
        // Folded, so the stored value is canonical rather than whichever spelling won the race.
        assertThat(upper).isEqualTo("/storage/emulated/0/download/flud/ave mujica")
    }

    @Test
    fun `an album stored under two spellings of one folder still plays 1 to 4`() {
        // The measured case: `Ave Mujica|神の名を|FLAC`, four tracks of one folder, two rows indexed
        // under `/Download/` and two under `/download/`. The list below is what the scanner now
        // writes; the order is LibraryDao.tracksForAlbum's - band first, then track number -
        // with BINARY on the band on purpose, so this passes only because the stored keys are
        // genuinely equal rather than because the comparison folds them.
        val stored = listOf(
            Row(discBandKeyOf("/storage/emulated/0/Download/Flud/Ave Mujica/03.flac"), 3),
            Row(discBandKeyOf("/storage/emulated/0/download/Flud/Ave Mujica/01.flac"), 1),
            Row(discBandKeyOf("/storage/emulated/0/Download/Flud/Ave Mujica/04.flac"), 4),
            Row(discBandKeyOf("/storage/emulated/0/download/Flud/Ave Mujica/02.flac"), 2),
        )

        val played = stored.sortedWith(compareBy({ it.discBand }, { it.trackNumber }))

        assertThat(played.map { it.trackNumber }).containsExactly(1, 2, 3, 4).inOrder()
    }

    @Test
    fun `folding the band keeps the discs in order`() {
        // The multi-disc case still has to work through the value actually written, not only
        // through the raw extraction the cases above use.
        val discOne = discBandKeyOf("/x/DENKARE THE BEST/Disc 1/01 A.flac")
        val discTwo = discBandKeyOf("/x/denkare the best/Disc 2/01 B.flac")

        assertThat(discOne).isEqualTo("/x/denkare the best/disc 1")
        assertThat(discOne < discTwo).isTrue()
        // The disc prefix a one-folder release carries in its file name survives the folding.
        // The prefix is the regex's own match, "1." - what follows it is the first track number.
        assertThat(discBandKeyOf("/music/Wink - Selection/1.01. Especially for You.flac"))
            .isEqualTo("/music/wink - selection/1.")
    }

    @Test
    fun `a SAF document id keeps its case`() {
        // Not a filesystem path: some document providers are case-sensitive, so two ids that
        // differ only by case are two different documents and must not be merged into one band.
        assertThat(discBandKeyOf("content://p/tree/Root/document/Root/Album/01.flac"))
            .isEqualTo("content://p/tree/Root/document/Root/Album")
    }

    /** One stored row, as far as album ordering is concerned. */
    private data class Row(val discBand: String, val trackNumber: Int)
}
