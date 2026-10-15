package com.sertum.player.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AlbumKeyTest {

    @Test
    fun `album artist wins over artist`() {
        assertThat(
            AlbumKey.resolve("Title", "AlbumArtist", "TrackArtist", null),
        ).isEqualTo(AlbumKey("Title", "AlbumArtist"))
    }

    @Test
    fun `artist fallback when album artist is missing`() {
        assertThat(
            AlbumKey.resolve("Title", null, "Artist", null),
        ).isEqualTo(AlbumKey("Title", "Artist"))
    }

    @Test
    fun `folder fallback when tags are missing`() {
        assertThat(
            AlbumKey.resolve(null, null, null, "/music/AlbumA"),
        ).isEqualTo(AlbumKey("__FOLDER__/music/AlbumA", "__FOLDER__/music/AlbumA"))
    }

    @Test
    fun `unknown fallback when everything is missing`() {
        assertThat(AlbumKey.resolve(null, null, null, null)).isEqualTo(AlbumKey("__UNKNOWN__", "__UNKNOWN__"))
    }

    @Test
    fun `blank strings are treated as missing`() {
        assertThat(AlbumKey.resolve("   ", " ", "Artist", null)).isEqualTo(AlbumKey("__UNKNOWN__", "__UNKNOWN__"))
    }

    @Test
    fun `without a format the key is unchanged`() {
        // The key predates format, so nothing that reports no format may change identity.
        assertThat(AlbumKey("Title", "Artist").key).isEqualTo("Artist|Title")
        assertThat(AlbumKey.resolve("Title", "Artist", null, null).key).isEqualTo("Artist|Title")
    }

    @Test
    fun `a format separates the key but not the title`() {
        val lossless = AlbumKey.resolve("Agony", "Fleshgod Apocalypse", null, null, "FLAC")
        val lossy = AlbumKey.resolve("Agony", "Fleshgod Apocalypse", null, null, "MP3")

        // Different albums, so the library lists them separately.
        assertThat(lossless.key).isNotEqualTo(lossy.key)
        assertThat(lossless.key).isEqualTo("Fleshgod Apocalypse|Agony|FLAC")
        assertThat(lossy.key).isEqualTo("Fleshgod Apocalypse|Agony|MP3")

        // Same title, so the two entries read identically apart from the quality label.
        assertThat(lossless.title).isEqualTo(lossy.title)
        assertThat(lossless.albumArtist).isEqualTo(lossy.albumArtist)
    }

    @Test
    fun `the folder and unknown fallbacks also carry the format`() {
        assertThat(
            AlbumKey.resolve(null, null, null, "/music/AlbumA", "FLAC").key,
        ).isEqualTo("__FOLDER__/music/AlbumA|__FOLDER__/music/AlbumA|FLAC")
        assertThat(
            AlbumKey.resolve(null, null, null, null, "MP3").key,
        ).isEqualTo("__UNKNOWN__|__UNKNOWN__|MP3")
    }

    @Test
    fun `the same release in two formats is two albums`() {
        val albums = listOf(
            AlbumKey.resolve("INSPIRANTE", "電気式華憐音楽集団", null, null, "FLAC"),
            AlbumKey.resolve("INSPIRANTE", "電気式華憐音楽集団", null, null, "MP3"),
        ).map { it.key }

        assertThat(albums.toSet()).hasSize(2)
    }
}
