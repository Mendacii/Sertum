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