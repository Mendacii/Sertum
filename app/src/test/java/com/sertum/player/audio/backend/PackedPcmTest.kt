package com.sertum.player.audio.backend

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The frame size the backend reports writes against.
 *
 * This used to be a constant pair - six bytes for 24-bit, four otherwise - which is only true for
 * stereo. A mono 24-bit stream was then told it had written half the frames it had, and the other
 * half was submitted again.
 */
class PackedPcmTest {

    @Test
    fun `stereo 24 bit frames are six bytes`() {
        assertThat(packedFrameSize(bitDepth = 24, channels = 2)).isEqualTo(6)
    }

    @Test
    fun `mono 24 bit frames are three bytes`() {
        // The case that was wrong: 3000 bytes is 1000 mono frames, not 500.
        assertThat(packedFrameSize(bitDepth = 24, channels = 1)).isEqualTo(3)
        assertThat(3_000 / packedFrameSize(bitDepth = 24, channels = 1)).isEqualTo(1_000)
    }

    @Test
    fun `16 bit halving holds for both layouts`() {
        assertThat(packedFrameSize(bitDepth = 16, channels = 2)).isEqualTo(4)
        assertThat(packedFrameSize(bitDepth = 16, channels = 1)).isEqualTo(2)
    }

    @Test
    fun `an impossible channel count reports nothing rather than dividing by zero`() {
        assertThat(packedFrameSize(bitDepth = 24, channels = 0)).isEqualTo(0)
        assertThat(packedFrameSize(bitDepth = 24, channels = -2)).isEqualTo(0)
    }
}
