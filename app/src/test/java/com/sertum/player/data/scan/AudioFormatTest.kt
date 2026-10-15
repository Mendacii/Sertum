package com.sertum.player.data.scan

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Classifying a track's container from the MIME type the platform reports.
 *
 * Two things depend on this. Albums are identified partly by it, so that a lossless rip and a
 * lossy copy of the same release become separate entries rather than one album holding both;
 * and the album page shows it as the quality label.
 *
 * The values below are the ones MediaStore actually returns for this library, including the
 * two spellings of FLAC that would otherwise split one album into two.
 */
class AudioFormatTest {

    @Test
    fun `both spellings of flac are the same format`() {
        // MediaStore reports audio/flac for 1043 files and audio/x-flac for 7. Treating those
        // as different formats would show the same album twice.
        assertThat(AudioFormat.from("audio/flac")).isEqualTo(AudioFormat.FLAC)
        assertThat(AudioFormat.from("audio/x-flac")).isEqualTo(AudioFormat.FLAC)
        assertThat(AudioFormat.from("audio/FLAC")).isEqualTo(AudioFormat.FLAC)
    }

    @Test
    fun `the formats in this library are recognised`() {
        assertThat(AudioFormat.from("audio/mpeg")).isEqualTo(AudioFormat.MP3)
        assertThat(AudioFormat.from("audio/ogg")).isEqualTo(AudioFormat.OGG)
        assertThat(AudioFormat.from("audio/mp4")).isEqualTo(AudioFormat.M4A)
        assertThat(AudioFormat.from("audio/x-wav")).isEqualTo(AudioFormat.WAV)
        assertThat(AudioFormat.from("audio/x-aiff")).isEqualTo(AudioFormat.AIFF)
    }

    @Test
    fun `lossless and lossy are separated`() {
        assertThat(AudioFormat.FLAC.isLossless).isTrue()
        assertThat(AudioFormat.WAV.isLossless).isTrue()
        assertThat(AudioFormat.AIFF.isLossless).isTrue()
        assertThat(AudioFormat.MP3.isLossless).isFalse()
        assertThat(AudioFormat.OGG.isLossless).isFalse()
        // audio/mp4 may carry ALAC, but it may equally be AAC, and the MIME type does not
        // say which. Lossy is the safer claim: it never tells the user a lossy file is
        // lossless.
        assertThat(AudioFormat.M4A.isLossless).isFalse()
    }

    @Test
    fun `an unknown type is not guessed at`() {
        assertThat(AudioFormat.from("audio/whatever")).isEqualTo(AudioFormat.OTHER)
        assertThat(AudioFormat.from("")).isEqualTo(AudioFormat.OTHER)
        assertThat(AudioFormat.from(null)).isEqualTo(AudioFormat.OTHER)
        assertThat(AudioFormat.OTHER.isLossless).isFalse()
    }

    @Test
    fun `the label is what a person would write`() {
        assertThat(AudioFormat.FLAC.label).isEqualTo("FLAC")
        assertThat(AudioFormat.MP3.label).isEqualTo("MP3")
        assertThat(AudioFormat.WAV.label).isEqualTo("WAV")
        assertThat(AudioFormat.AIFF.label).isEqualTo("AIFF")
        assertThat(AudioFormat.OTHER.label).isNull()
    }

    @Test
    fun `an unknown type keeps its raw value for display`() {
        // The enum itself models nothing here, so the label comes from the MIME subtype.
        assertThat(AudioFormat.from("audio/opus")).isEqualTo(AudioFormat.OTHER)
        assertThat(AudioFormat.labelFor("audio/opus")).isEqualTo("OPUS")
        assertThat(AudioFormat.labelFor("audio/whatever")).isEqualTo("WHATEVER")
        // A modelled type uses its own label rather than the subtype.
        assertThat(AudioFormat.labelFor("audio/x-flac")).isEqualTo("FLAC")
        assertThat(AudioFormat.labelFor(null)).isNull()
        assertThat(AudioFormat.labelFor("audio/")).isNull()
    }
}
