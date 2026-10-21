package com.sertum.player.audio.uac2

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The descriptor walk, checked against the real bytes a DAWN PRO2 returned.
 *
 * The fixture is the 361-byte configuration blob captured from the device. Parsing it rather than
 * hard-coding the values is what keeps the driver honest on other DACs: the clock entity id and the
 * alt-setting depths are the two things a wrong guess makes silently useless.
 */
class Uac2DescriptorTest {

    private fun fixture(): ByteArray =
        javaClass.getResourceAsStream("/uac2-dawn-pro2.bin")!!.use { it.readBytes() }

    @Test
    fun `finds the clock entity and the streaming interface`() {
        val info = parseUac2Descriptors(fixture())
        assertThat(info.clockSourceId).isEqualTo(1)
        assertThat(info.audioControlInterfaceNumber).isEqualTo(1)
        assertThat(info.streamingInterfaceNumber).isEqualTo(2)
        // The asynchronous feedback stream: without it the host cannot follow the DAC's crystal.
        assertThat(info.feedbackEndpoint).isEqualTo(0x83)
    }

    @Test
    fun `lists one format per streaming alt setting`() {
        val info = parseUac2Descriptors(fixture())
        assertThat(info.formats.map { it.alternateSetting to it.bitDepth })
            .containsExactly(1 to 16, 2 to 24, 3 to 32, 4 to 32)
            .inOrder()
        assertThat(info.formats.map { it.subslotBytes }).containsExactly(2, 3, 4, 4).inOrder()
        // Every alt setting writes to the same isochronous OUT endpoint.
        assertThat(info.formats.map { it.endpointAddress }.distinct()).containsExactly(0x07)
    }

    @Test
    fun `picks the deepest format that does not exceed the source depth`() {
        val info = parseUac2Descriptors(fixture())
        assertThat(info.formatForBitDepth(16)?.bitDepth).isEqualTo(16)
        assertThat(info.formatForBitDepth(24)?.bitDepth).isEqualTo(24)
        assertThat(info.formatForBitDepth(32)?.bitDepth).isEqualTo(32)
        // A 20-bit source cannot be carried exactly; the driver picks 16 rather than overstating.
        assertThat(info.formatForBitDepth(20)?.bitDepth).isEqualTo(16)
    }

    @Test
    fun `parses the feature unit and records its control bitmap`() {
        val info = parseUac2Descriptors(fixture())
        val unit = info.featureUnit
        assertThat(unit).isNotNull()
        assertThat(unit!!.unitId).isEqualTo(0x0a)
        assertThat(unit.sourceId).isEqualTo(0x03)
        // The captured DAWN PRO2 descriptor has no master volume bit and 0x0c (bass/mid) on both
        // channels. That is a fact about the fixture, not a reason to assume volume works: the
        // runtime GET_MIN/GET_MAX result is the gate for any write.
        assertThat(unit.masterControlBitmap).isEqualTo(0x00000000)
        assertThat(unit.channelControlBitmaps).containsExactly(0x0c, 0x0c).inOrder()
        assertThat(unit.supportsVolume).isFalse()
    }

    @Test
    fun `recognises a volume bit in a synthetic feature unit`() {
        val withVolume = parseUac2Descriptors(featureUnitDescriptor(0x00000003))
        assertThat(withVolume.featureUnit).isNotNull()
        assertThat(withVolume.featureUnit!!.supportsVolume).isTrue()

        val withoutVolume = parseUac2Descriptors(featureUnitDescriptor(0x0000000c))
        assertThat(withoutVolume.featureUnit).isNotNull()
        assertThat(withoutVolume.featureUnit!!.supportsVolume).isFalse()
    }

    private fun featureUnitDescriptor(controlBitmap: Int): ByteArray {
        val bytes = mutableListOf<Byte>()
        fun u8(value: Int) = bytes.add(value.toByte())

        // AudioControl interface (class 1, subclass 1), so the parser accepts the CS_INTERFACE
        // descriptor that follows.
        u8(9); u8(0x04); u8(0); u8(0); u8(0); u8(0x01); u8(0x01); u8(0); u8(0)
        // FEATURE_UNIT, unit 0x0a, source 0x03, one 32-bit master control bitmap, iFeature 0.
        u8(10); u8(0x24); u8(0x06); u8(0x0a); u8(0x03)
        u8(controlBitmap and 0xFF)
        u8((controlBitmap shr 8) and 0xFF)
        u8((controlBitmap shr 16) and 0xFF)
        u8((controlBitmap shr 24) and 0xFF)
        u8(0)
        return bytes.toByteArray()
    }

    @Test
    fun `a truncated blob does not throw`() {
        // A cut chain must not crash the parser. Counts are not asserted: truncation can leave a
        // later interface's endpoint adjacent to an earlier format descriptor, which is not a
        // shape the real device ever produces.
        val raw = fixture()
        for (cut in intArrayOf(0, 8, 20, 40, 100, 200)) {
            parseUac2Descriptors(raw.copyOf(cut))
        }
    }
}