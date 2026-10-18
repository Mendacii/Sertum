package com.sertum.player.data.covers

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Finding a user cover after the album key changed.
 *
 * The image is stored under `sha256(albumKey)`, and the key is built from tags - `artist|title`,
 * or `artist|title|format` since the container joined it. When the key changes the file name
 * changes with it and the cover becomes unreachable without ever being deleted.
 *
 * That is not hypothetical. Adding the format segment on 2026-10-16 orphaned nine covers on the
 * reference device at once; the visible symptom was having to add them again by hand, and the
 * only reason they were recoverable is that the images were still on disk under the old names.
 * These cases pin the look-up that makes that recoverable instead of lossy.
 */
class CoverStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store() = CoverStore(folder.root)

    @Test
    fun `a cover saved under the current key is found`() {
        val store = store()
        store.save("Death|Symbolic", byteArrayOf(1, 2, 3))

        assertThat(store.userCoverFor("Death|Symbolic")).isNotNull()
        assertThat(store.hasOnlyFallbackCover("Death|Symbolic")).isFalse()
    }

    @Test
    fun `a cover saved before the format joined the key is still found`() {
        val store = store()
        // Saved when the key had no format segment.
        store.save("Death|Symbolic", byteArrayOf(1, 2, 3))

        // Read back under the key it has now.
        val found = store.userCoverFor("Death|Symbolic|FLAC")

        assertThat(found).isNotNull()
        assertThat(store.hasOnlyFallbackCover("Death|Symbolic|FLAC")).isTrue()
    }

    @Test
    fun `adopting a fallback makes the next look-up direct`() {
        val store = store()
        store.save("Death|Symbolic", byteArrayOf(1, 2, 3))

        assertThat(store.adoptFallback("Death|Symbolic|FLAC")).isTrue()
        assertThat(store.hasOnlyFallbackCover("Death|Symbolic|FLAC")).isFalse()
        assertThat(store.userCoverFor("Death|Symbolic|FLAC")).isNotNull()
        // The old file is left where it was: a copy, not a move.
        assertThat(store.userCoverFor("Death|Symbolic")).isNotNull()
    }

    @Test
    fun `adopting does nothing when the cover is already where it belongs`() {
        val store = store()
        store.save("Death|Symbolic|FLAC", byteArrayOf(9))

        // Nothing to adopt, and the existing file must not be overwritten by an older one.
        store.save("Death|Symbolic", byteArrayOf(1, 2, 3))
        assertThat(store.adoptFallback("Death|Symbolic|FLAC")).isFalse()
        assertThat(store.fileFor("Death|Symbolic|FLAC").readBytes()).isEqualTo(byteArrayOf(9))
    }

    @Test
    fun `the format labels a key can carry are recognised`() {
        // The fallback only fires for a suffix that is a real format label, so this is the
        // contract it rests on. Pinned separately because a failure here looks like a storage
        // bug when it is really a naming one.
        val labels = com.sertum.player.data.scan.AudioFormat.formatLabels()
        assertThat(labels).containsAtLeast("FLAC", "MP3", "OGG", "M4A", "WAV", "AIFF", "OPUS")
        assertThat(labels).doesNotContain("Reissue]")
        assertThat(labels).doesNotContain("")
    }

    @Test
    fun `the fallback path is the file the older key names`() {
        // Pins the mechanism directly rather than through a look-up, so a failure says which of
        // the two halves is wrong: the name the older key produces, or the search for it.
        val store = store()
        val older = "At the Gates|Slaughter of the Soul [Reissue]"
        val newer = "$older|FLAC"
        store.save(older, byteArrayOf(1))

        assertThat(store.fileFor(older).exists()).isTrue()
        // The newer key's own file must not exist yet, or the fallback never runs.
        assertThat(store.fileFor(newer).exists()).isFalse()
        // And the newer key is not the same name as the older one.
        assertThat(store.fileFor(newer).name).isNotEqualTo(store.fileFor(older).name)
        // Sanity: the label the key carries is one the format list knows.
        assertThat(com.sertum.player.data.scan.AudioFormat.formatLabels()).contains("FLAC")
    }

    @Test
    fun `a flac key and an mp3 key differ only in their label`() {
        // Both suffixes must be recognised, or the fallback fires for one format and not the
        // other, which is what a passing FLAC case and a failing MP3 case would look like.
        val labels = com.sertum.player.data.scan.AudioFormat.formatLabels()
        assertThat(labels).contains("FLAC")
        assertThat(labels).contains("MP3")

        val older = "At the Gates|Slaughter of the Soul [Reissue]"
        val flac = "$older|FLAC"
        val mp3 = "$older|MP3"

        val store = store()
        assertThat(store.candidateKeys(flac)).containsExactly(flac, older).inOrder()
        assertThat(store.candidateKeys(mp3)).containsExactly(mp3, older).inOrder()
    }

    @Test
    fun `a cover is found through the candidate its key falls back to`() {
        // Isolates the search from the key arithmetic: the candidate list is asserted in the
        // test above, and this checks that a file existing at one of those candidates is
        // actually returned.
        val store = store()
        val older = "At the Gates|Slaughter of the Soul [Reissue]"
        val mp3 = "$older|MP3"
        store.save(older, byteArrayOf(1))

        val candidates = store.candidateKeys(mp3)
        assertThat(candidates).hasSize(2)
        // The second candidate is the one the file was written under, and it exists.
        assertThat(candidates[1]).isEqualTo(older)
        assertThat(store.fileFor(candidates[1]).exists()).isTrue()
        assertThat(store.fileFor(candidates[1]).length()).isGreaterThan(0L)
        // So the look-up has every reason to return it.
        assertThat(store.userCoverFor(mp3)).isNotNull()
    }

    @Test
    fun `a release in two formats both read the cover stored before the format joined the key`() {
        // The fallback drops the format segment, so it reaches the key this album had *before*
        // that segment existed - not the sibling key of another format. Both entries of the same
        // release therefore find the one cover that was chosen, which is the intended shape:
        // FLAC and MP3 copies are one album in two containers, and the same sleeve suits both.
        //
        // Pinned because the neighbouring key is a tempting wrong guess. Reaching
        // `...|FLAC` from `...|MP3` would work in this test and make the fallback depend on
        // which format the user happened to pick first.
        val store = store()
        val beforeFormat = "At the Gates|Slaughter of the Soul [Reissue]"
        store.save(beforeFormat, byteArrayOf(1))

        assertThat(store.userCoverFor("$beforeFormat|FLAC")).isNotNull()
        assertThat(store.userCoverFor("$beforeFormat|MP3")).isNotNull()
        // And the sibling key's own file still does not exist, so neither was served the other's.
        assertThat(store.fileFor("$beforeFormat|FLAC").exists()).isFalse()
    }

    @Test
    fun `one format does not read the other format's own cover`() {
        // The counterpart: a cover picked for the FLAC entry belongs to that entry. The MP3 copy
        // does not inherit it, because the fallback only ever drops the suffix.
        val store = store()
        store.save("At the Gates|Slaughter of the Soul [Reissue]|FLAC", byteArrayOf(1))

        assertThat(store.userCoverFor("At the Gates|Slaughter of the Soul [Reissue]|FLAC")).isNotNull()
        assertThat(store.userCoverFor("At the Gates|Slaughter of the Soul [Reissue]|MP3")).isNull()
    }

    @Test
    fun `a cover is never reached across two different albums`() {
        // The guard: dropping the format segment makes a key less specific, never different, so
        // it cannot land on another album's name.
        val store = store()
        store.save("At the Gates|Slaughter of the Soul [Reissue]|FLAC", byteArrayOf(1))

        assertThat(store.userCoverFor("At the Gates|Slaughter of the Soul|MP3")).isNull()
        assertThat(store.userCoverFor("Dark Tranquillity|Slaughter of the Soul|MP3")).isNull()
    }

    @Test
    fun `a folder keyed album has no fallback`() {
        // A folder key ends in a path segment; dropping it would produce a different path, not
        // an older name for the same album.
        val store = store()
        store.save("__FOLDER__/storage/emulated/0/Music/Some Album", byteArrayOf(1))

        val withFormat = "__FOLDER__/storage/emulated/0/Music/Some Album|FLAC"
        assertThat(store.userCoverFor(withFormat)).isNull()
        assertThat(store.hasOnlyFallbackCover(withFormat)).isFalse()
    }

    @Test
    fun `a suffix that is not a format is not treated as one`() {
        val store = store()
        // A genuinely different album whose name happens to contain a pipe.
        store.save("Band|Album With | Pipe", byteArrayOf(1))

        // Nothing should reach across from an unrelated key.
        assertThat(store.userCoverFor("Band|Something Else|XY")).isNull()
    }

    @Test
    fun `an album with no cover anywhere resolves to nothing`() {
        val store = store()
        assertThat(store.userCoverFor("Nobody|Nothing")).isNull()
        assertThat(store.hasOnlyFallbackCover("Nobody|Nothing")).isFalse()
        assertThat(store.adoptFallback("Nobody|Nothing")).isFalse()
    }

    @Test
    fun `an empty file is not a cover`() {
        val store = store()
        store.fileFor("Death|Symbolic").writeBytes(ByteArray(0))

        assertThat(store.userCoverFor("Death|Symbolic")).isNull()
    }

    // --- The mirror: the reader's own covers outliving the app's data directory ---------------

    private fun mirroredStore(): Pair<CoverStore, java.io.File> {
        val data = folder.newFolder("data")
        val mirror = folder.newFolder("mirror")
        return CoverStore(data, mirror) to mirror
    }

    @Test
    fun `saving a cover leaves a copy outside the app data directory`() {
        val (store, mirror) = mirroredStore()

        store.save("Death|Symbolic", byteArrayOf(7, 8, 9))

        val mirrored = store.mirrorFileFor("Death|Symbolic")
        assertThat(mirrored).isNotNull()
        assertThat(mirrored!!.parentFile).isEqualTo(mirror)
        assertThat(mirrored.readBytes()).isEqualTo(byteArrayOf(7, 8, 9))
    }

    @Test
    fun `a cover is restored when the app's own copy is gone`() {
        val (store, _) = mirroredStore()
        store.save("Death|Symbolic", byteArrayOf(7, 8, 9))
        // What an uninstall or "clear data" does to filesDir.
        assertThat(store.delete("Death|Symbolic")).isTrue()
        assertThat(store.userCoverFor("Death|Symbolic")).isNull()

        assertThat(store.restoreFromMirror("Death|Symbolic")).isTrue()

        assertThat(store.userCoverFor("Death|Symbolic")).isNotNull()
    }

    @Test
    fun `restoring leaves an existing cover alone`() {
        val (store, _) = mirroredStore()
        store.save("Death|Symbolic", byteArrayOf(7, 8, 9))

        assertThat(store.restoreFromMirror("Death|Symbolic")).isFalse()
    }

    @Test
    fun `restoring does nothing when nothing was mirrored`() {
        val (store, _) = mirroredStore()
        assertThat(store.restoreFromMirror("Nobody|Nothing")).isFalse()
    }

    @Test
    fun `a mirror that cannot be written does not lose the cover`() {
        val data = folder.newFolder("data2")
        val notADirectory = folder.newFile("mirror-is-a-file")
        val store = CoverStore(data, notADirectory)

        val path = store.save("Death|Symbolic", byteArrayOf(7, 8, 9))

        assertThat(java.io.File(path).readBytes()).isEqualTo(byteArrayOf(7, 8, 9))
        assertThat(store.userCoverFor("Death|Symbolic")).isNotNull()
    }

    @Test
    fun `a cover mirrored under an older key is still restored`() {
        val (store, _) = mirroredStore()
        // Mirrored before the format segment joined the key, then the app data was lost.
        store.save("Death|Symbolic", byteArrayOf(7, 8, 9))
        store.delete("Death|Symbolic")

        assertThat(store.restoreFromMirror("Death|Symbolic|FLAC")).isTrue()
        assertThat(store.userCoverFor("Death|Symbolic|FLAC")).isNotNull()
    }

    @Test
    fun `a store without a mirror never restores and never fails`() {
        val store = store()
        store.save("Death|Symbolic", byteArrayOf(7, 8, 9))
        store.delete("Death|Symbolic")

        assertThat(store.mirrorFileFor("Death|Symbolic")).isNull()
        assertThat(store.restoreFromMirror("Death|Symbolic")).isFalse()
    }

    @Test
    fun `a cover chosen before the mirror existed is mirrored afterwards`() {
        val (store, _) = mirroredStore()
        // Saved when this store had no mirror directory: only the app's own copy exists.
        val unmirrored = CoverStore(folder.newFolder("data-before-mirror"))
        unmirrored.save("Death|Symbolic", byteArrayOf(7, 8, 9))
        val bytes = unmirrored.fileFor("Death|Symbolic").readBytes()
        store.fileFor("Death|Symbolic").writeBytes(bytes)

        assertThat(store.mirrorFileFor("Death|Symbolic")!!.exists()).isFalse()

        assertThat(store.ensureMirrored("Death|Symbolic")).isTrue()

        assertThat(store.mirrorFileFor("Death|Symbolic")!!.readBytes()).isEqualTo(byteArrayOf(7, 8, 9))
    }

    @Test
    fun `mirroring again does nothing once the copy is there`() {
        val (store, _) = mirroredStore()
        store.save("Death|Symbolic", byteArrayOf(7, 8, 9))

        assertThat(store.ensureMirrored("Death|Symbolic")).isFalse()
    }

    @Test
    fun `there is nothing to mirror for an album with no cover`() {
        val (store, _) = mirroredStore()
        assertThat(store.ensureMirrored("Nobody|Nothing")).isFalse()
        assertThat(store.ensureMirrored("Death|Symbolic")).isFalse()
    }

    @Test
    fun `a store without a mirror has nothing to backfill`() {
        val store = store()
        store.save("Death|Symbolic", byteArrayOf(7, 8, 9))

        assertThat(store.ensureMirrored("Death|Symbolic")).isFalse()
    }
}
