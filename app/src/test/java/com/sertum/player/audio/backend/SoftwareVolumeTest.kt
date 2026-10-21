package com.sertum.player.audio.backend

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SoftwareVolumeTest {

    private fun floats(vararg values: Float): ByteArray =
        ByteBuffer.allocate(values.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { values.forEach { putFloat(it) } }
            .array()

    @Test
    fun `gain curve is quadratic and maps the volume index to mute and unity`() {
        assertThat(SoftwareVolume.gainFor(0, 15)).isEqualTo(0f)
        assertThat(SoftwareVolume.gainFor(5, 10)).isEqualTo(0.25f)
        assertThat(SoftwareVolume.gainFor(10, 10)).isEqualTo(1f)
        assertThat(SoftwareVolume.gainFor(15, 15)).isEqualTo(1f)
    }

    @Test
    fun `gain curve clamps an out-of-range index`() {
        assertThat(SoftwareVolume.gainFor(-3, 15)).isEqualTo(0f)
        assertThat(SoftwareVolume.gainFor(20, 15)).isEqualTo(1f)
    }

    @Test
    fun `an invalid maximum is unity rather than accidental mute`() {
        assertThat(SoftwareVolume.gainFor(0, 0)).isEqualTo(1f)
        assertThat(SoftwareVolume.gainFor(7, -1)).isEqualTo(1f)
    }

    @Test
    fun `unity gain is byte-identical to the disabled conversion path`() {
        val input = floats(0f, 0.5f, -0.5f, 0.25f, -1f, 1f)
        assertThat(PcmConversion.float32ToPacked24Le(input, 1f))
            .isEqualTo(PcmConversion.float32ToPacked24Le(input))
        assertThat(PcmConversion.float32ToPacked16Le(input, 1f))
            .isEqualTo(PcmConversion.float32ToPacked16Le(input))
    }

    @Test
    fun `zero gain produces digital silence at both target depths`() {
        val input = floats(0.5f, -0.5f, 1f, -1f)
        assertThat(PcmConversion.float32ToPacked24Le(input, 0f))
            .isEqualTo(ByteArray(input.size / 4 * 3))
        assertThat(PcmConversion.float32ToPacked16Le(input, 0f))
            .isEqualTo(ByteArray(input.size / 4 * 2))
    }

    @Test
    fun `unity gain leaves packed integer PCM byte-identical`() {
        val packed24 = byteArrayOf(0x00, 0x00, 0x40, 0x00, 0x00, 0xC0.toByte())
        val packed16 = byteArrayOf(0x00, 0x40, 0x00, 0xC0.toByte())
        assertThat(PcmConversion.applyGainPacked24Le(packed24, 1f)).isEqualTo(packed24)
        assertThat(PcmConversion.applyGainPacked16Le(packed16, 1f)).isEqualTo(packed16)
    }

    @Test
    fun `packed integer gain scales signed samples`() {
        val packed24 = byteArrayOf(0x00, 0x00, 0x40, 0x00, 0x00, 0xC0.toByte())
        val half24 = byteArrayOf(0x00, 0x00, 0x20, 0x00, 0x00, 0xE0.toByte())
        assertThat(PcmConversion.applyGainPacked24Le(packed24, 0.5f)).isEqualTo(half24)
        assertThat(PcmConversion.applyGainPacked24Le(packed24, 0f)).isEqualTo(ByteArray(6))

        val packed16 = byteArrayOf(0x00, 0x40, 0x00, 0xC0.toByte())
        val half16 = byteArrayOf(0x00, 0x20, 0x00, 0xE0.toByte())
        assertThat(PcmConversion.applyGainPacked16Le(packed16, 0.5f)).isEqualTo(half16)
        assertThat(PcmConversion.applyGainPacked16Le(packed16, 0f)).isEqualTo(ByteArray(4))
    }
}
