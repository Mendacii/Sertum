package com.sertum.player.data.scan

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Matching a CUE's declared audio against the files that are actually in the library.
 *
 * The name a CUE declares is often not a file that exists, and the reference library shows two
 * distinct reasons:
 *
 * - **The extension is wrong.** Rips name the `.wav` they were cut from while the collection
 *   kept a `.flac`. Agony has both `Agony (Japan).flac.cue` and `Agony (Japan).wav.cue` and only
 *   the FLAC exists, so the base name has to be tried against other audio extensions.
 * - **The audio is gone.** ANKM-0002 still names an `.ape` that was deleted, while eight working
 *   per-track FLACs sit beside it. Nothing in the library matches, so nothing resolves.
 *
 * Trusting a CUE whose audio cannot be found is the failure this prevents: it would create
 * tracks pointing at a missing file and hide the working ones already there.
 */
class CueAudioSignatureTest {

    @Test
    fun `a declared name that is in the library matches`() {
        assertThat(
            matchCueAudio(
                cuePath = "/music/Muv-Luv Vol.1/Vol.1 (romaji).flac.cue",
                declaredName = "Muv-Luv Alternative Original Sound Track Vol.1.flac",
                libraryPaths = listOf(
                    "/music/Muv-Luv Vol.1/Muv-Luv Alternative Original Sound Track Vol.1.flac",
                    "/music/other/song.flac",
                ),
            ),
        ).isEqualTo("/music/Muv-Luv Vol.1/Muv-Luv Alternative Original Sound Track Vol.1.flac")
    }

    @Test
    fun `a wrong extension is retried against the other audio types`() {
        // The CUE says .wav; the library holds the same base name as .flac.
        assertThat(
            matchCueAudio(
                cuePath = "/music/Agony/Agony (Japan).wav.cue",
                declaredName = "Agony (Japan).wav",
                libraryPaths = listOf("/music/Agony/Agony (Japan).flac"),
            ),
        ).isEqualTo("/music/Agony/Agony (Japan).flac")
    }

    @Test
    fun `an extension swap never crosses directories`() {
        // A file with the right base name but in another folder is a different release.
        assertThat(
            matchCueAudio(
                cuePath = "/music/Agony/Agony (Japan).wav.cue",
                declaredName = "Agony (Japan).wav",
                libraryPaths = listOf("/music/Elsewhere/Agony (Japan).flac"),
            ),
        ).isNull()
    }

    @Test
    fun `audio one directory up resolves`() {
        assertThat(
            matchCueAudio(
                cuePath = "/music/Album/cue/disc.cue",
                declaredName = "disc.wav",
                libraryPaths = listOf("/music/Album/disc.flac"),
            ),
        ).isEqualTo("/music/Album/disc.flac")
    }

    @Test
    fun `a cue whose audio is gone resolves to nothing`() {
        // ANKM-0002: the .ape was deleted; only per-track FLACs remain, and none of them share
        // its base name, so there is nothing to match and the disc is left alone.
        assertThat(
            matchCueAudio(
                cuePath = "/music/ANKM/ANKM-0002.cue",
                declaredName = "ANKM-0002.ape",
                libraryPaths = listOf(
                    "/music/ANKM/01. yuki - So That A Star Shines at Night Sky.flac",
                    "/music/ANKM/02. Fuki - Rumbling hearts.flac",
                ),
            ),
        ).isNull()
    }

    @Test
    fun `the cue file is never taken as its own audio`() {
        assertThat(
            matchCueAudio(
                cuePath = "/music/Album/disc.cue",
                declaredName = "disc.cue",
                libraryPaths = listOf("/music/Album/disc.cue"),
            ),
        ).isNull()
    }

    @Test
    fun `a declared name with a directory part is compared by its leaf`() {
        assertThat(
            matchCueAudio(
                cuePath = "/music/Album/disc.cue",
                declaredName = "Album\\disc.flac",
                libraryPaths = listOf("/music/Album/disc.flac"),
            ),
        ).isEqualTo("/music/Album/disc.flac")
    }

    @Test
    fun `a library path with the wrong extension is not matched by exact name`() {
        // Guards the retry: the exact-name lookup must not accept a track whose extension
        // differs, or the retry would never be reached and the reason would be lost.
        assertThat(
            matchCueAudio(
                cuePath = "/music/Album/disc.cue",
                declaredName = "disc.wav",
                libraryPaths = listOf("/music/Album/disc.flac"),
            ),
        ).isEqualTo("/music/Album/disc.flac")
    }
}
