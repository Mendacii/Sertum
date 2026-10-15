package com.sertum.player.ui.screens.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The one-line quality summary on an album page, e.g. `FLAC · 24bit / 96kHz`.
 *
 * The parts are assembled rather than assumed because they are not always all present. A lossy
 * container has no bit depth to report, and the platform does not expose a sample rate for
 * every file, so the label has to degrade to `MP3 · 44.1kHz` or to the format alone instead of
 * printing a placeholder where a number should be.
 */
class AudioQualityLabelTest {

    @Test
    fun `a lossless album shows all three parts`() {
        assertThat(audioQualityLabel("FLAC", sampleRate = 96_000, bitDepth = 24))
            .isEqualTo("FLAC · 24bit / 96kHz")
        assertThat(audioQualityLabel("FLAC", sampleRate = 44_100, bitDepth = 16))
            .isEqualTo("FLAC · 16bit / 44.1kHz")
    }

    @Test
    fun `a lossy album omits the bit depth it does not have`() {
        assertThat(audioQualityLabel("MP3", sampleRate = 44_100, bitDepth = null))
            .isEqualTo("MP3 · 44.1kHz")
    }

    @Test
    fun `a missing sample rate leaves the format alone`() {
        assertThat(audioQualityLabel("MP3", sampleRate = null, bitDepth = null)).isEqualTo("MP3")
        // A bit depth with no sample rate still identifies the file usefully.
        assertThat(audioQualityLabel("FLAC", sampleRate = null, bitDepth = 16)).isEqualTo("FLAC · 16bit")
    }

    @Test
    fun `no format means no label rather than a stray separator`() {
        assertThat(audioQualityLabel(null, sampleRate = 44_100, bitDepth = 16)).isNull()
        assertThat(audioQualityLabel(null, sampleRate = null, bitDepth = null)).isNull()
        assertThat(audioQualityLabel("", sampleRate = 44_100, bitDepth = 16)).isNull()
    }

    @Test
    fun `kHz keeps one decimal and drops a trailing zero`() {
        assertThat(audioQualityLabel("FLAC", sampleRate = 48_000, bitDepth = 24)).contains("48kHz")
        assertThat(audioQualityLabel("FLAC", sampleRate = 44_100, bitDepth = 16)).contains("44.1kHz")
        assertThat(audioQualityLabel("FLAC", sampleRate = 192_000, bitDepth = 24)).contains("192kHz")
        // 11.025kHz rounds to one decimal, and that decimal is a trailing zero, so the rule
        // drops it. The same position must not carry a decimal for one album and not another.
        assertThat(audioQualityLabel("FLAC", sampleRate = 11_025, bitDepth = 16)).contains("11kHz")
        assertThat(audioQualityLabel("FLAC", sampleRate = 11_025, bitDepth = 16)).doesNotContain(".0")
        // A decimal that is not a trailing zero survives.
        assertThat(audioQualityLabel("FLAC", sampleRate = 44_100, bitDepth = null)).contains("44.1")
    }

    @Test
    fun `an implausible sample rate is not shown as fact`() {
        // Defensive: a zero or nonsense value would otherwise read "0kHz".
        assertThat(audioQualityLabel("FLAC", sampleRate = 0, bitDepth = 16)).isEqualTo("FLAC · 16bit")
        assertThat(audioQualityLabel("FLAC", sampleRate = -1, bitDepth = null)).isEqualTo("FLAC")
    }
}
