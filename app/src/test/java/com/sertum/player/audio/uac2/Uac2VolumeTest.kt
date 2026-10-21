package com.sertum.player.audio.uac2

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Uac2VolumeTest {

    /** A realistic -40 dB .. 0 dB range in UAC2's 1/256 dB units. */
    private val range = Uac2VolumeRange(minRaw = -10_240, maxRaw = 0)

    @Test
    fun `decodes signed volume as little endian`() {
        assertThat(decodeVolumeRaw(byteArrayOf(0x00.toByte(), 0x00.toByte()))).isEqualTo(0)
        assertThat(decodeVolumeRaw(byteArrayOf(0x00.toByte(), 0xFF.toByte()))).isEqualTo(-256)
        assertThat(decodeVolumeRaw(byteArrayOf(0xFF.toByte(), 0x7F.toByte()))).isEqualTo(32_767)
        assertThat(decodeVolumeRaw(byteArrayOf(0x00.toByte(), 0x80.toByte()))).isEqualTo(-32_768)
        assertThat(decodeVolumeRaw(byteArrayOf(0x01.toByte()))).isNull()
    }

    @Test
    fun `decodes the unsigned resolution value`() {
        assertThat(decodeVolumeResolution(byteArrayOf(0x00.toByte(), 0x01.toByte()))).isEqualTo(256)
        assertThat(decodeVolumeResolution(byteArrayOf(0xFF.toByte(), 0x00.toByte()))).isEqualTo(255)
        assertThat(decodeVolumeResolution(byteArrayOf(0x01.toByte()))).isNull()
    }

    @Test
    fun `encodes signed volume as little endian`() {
        assertThat(encodeVolumeRaw(0).toList())
            .containsExactly(0x00.toByte(), 0x00.toByte()).inOrder()
        assertThat(encodeVolumeRaw(-256).toList())
            .containsExactly(0x00.toByte(), 0xFF.toByte()).inOrder()
        assertThat(encodeVolumeRaw(-32_768).toList())
            .containsExactly(0x00.toByte(), 0x80.toByte()).inOrder()
    }

    @Test
    fun `maps the proven range to a clamped percentage`() {
        assertThat(range.percent(-10_240)).isEqualTo(0)
        assertThat(range.percent(-512)).isEqualTo(95)
        assertThat(range.percent(0)).isEqualTo(100)
        assertThat(range.percent(-20_000)).isEqualTo(0)
        assertThat(range.percent(50)).isEqualTo(100)
    }

    @Test
    fun `clamps to the proven range`() {
        assertThat(range.clamp(-20_000)).isEqualTo(-10_240)
        assertThat(range.clamp(-5_000)).isEqualTo(-5_000)
        assertThat(range.clamp(50)).isEqualTo(0)
    }

    @Test
    fun `restores history when current is dangerous`() {
        val decision = Uac2VolumePolicy.decide(
            currentRaw = 0,
            range = range,
            historyRaw = -8_000,
        )
        assertThat(decision.action).isEqualTo(Uac2VolumeAction.RESTORE)
        assertThat(decision.targetRaw).isEqualTo(-8_000)
    }

    @Test
    fun `restores at exactly 95 percent`() {
        val decision = Uac2VolumePolicy.decide(
            currentRaw = -512,
            range = range,
            historyRaw = -8_000,
        )
        assertThat(decision.action).isEqualTo(Uac2VolumeAction.RESTORE)
    }

    @Test
    fun `absolute minus six dB is dangerous even below 95 percent of range`() {
        // -4 dB is only 90% of a -40..0 range, but it is above the absolute -6 dB danger line.
        val decision = Uac2VolumePolicy.decide(
            currentRaw = -1_024,
            range = range,
            historyRaw = -8_000,
        )
        assertThat(decision.action).isEqualTo(Uac2VolumeAction.RESTORE)
    }

    @Test
    fun `first run at max picks the minus forty dB fail safe`() {
        val high = Uac2VolumePolicy.decide(currentRaw = 0, range = range, historyRaw = null)
        assertThat(high.action).isEqualTo(Uac2VolumeAction.FIRST_RUN_FAIL_SAFE)
        assertThat(high.targetRaw).isEqualTo(-10_240)
    }

    @Test
    fun `first run below the danger threshold only records`() {
        val low = Uac2VolumePolicy.decide(currentRaw = -8_000, range = range, historyRaw = null)
        assertThat(low.action).isEqualTo(Uac2VolumeAction.RECORD_CURRENT)
    }

    @Test
    fun `a narrow range fail safe is the quiet endpoint not twenty percent`() {
        val narrow = Uac2VolumeRange(minRaw = -256, maxRaw = 0)
        val decision = Uac2VolumePolicy.decide(currentRaw = 0, range = narrow, historyRaw = null)
        assertThat(decision.action).isEqualTo(Uac2VolumeAction.FIRST_RUN_FAIL_SAFE)
        // 20% of this range would be about -51; -40 dB clamps to the quiet endpoint instead.
        assertThat(decision.targetRaw).isEqualTo(-256)
        assertThat(Uac2VolumePolicy.failSafeRaw(narrow)).isEqualTo(-256)
    }

    @Test
    fun `fail safe clamps minus forty dB into a wide range`() {
        val wider = Uac2VolumeRange(minRaw = -20_000, maxRaw = 0)
        assertThat(Uac2VolumePolicy.failSafeRaw(wider)).isEqualTo(-10_240)
    }

    @Test
    fun `never requests a write that raises the current value`() {
        val decision = Uac2VolumePolicy.decide(
            currentRaw = -1_024,
            range = range,
            historyRaw = -512,
        )
        assertThat(decision.action).isEqualTo(Uac2VolumeAction.KEEP_CURRENT)
    }

    @Test
    fun `does not restore when current is safely quiet`() {
        val decision = Uac2VolumePolicy.decide(
            currentRaw = -8_000,
            range = range,
            historyRaw = -9_000,
        )
        assertThat(decision.action).isEqualTo(Uac2VolumeAction.KEEP_CURRENT)
    }

    @Test
    fun `does not restore a history outside the proven range`() {
        val decision = Uac2VolumePolicy.decide(
            currentRaw = 0,
            range = range,
            historyRaw = -20_000,
        )
        assertThat(decision.action).isEqualTo(Uac2VolumeAction.HISTORY_OUT_OF_RANGE)
    }

    @Test
    fun `does not act without a proven range`() {
        val decision = Uac2VolumePolicy.decide(
            currentRaw = 0,
            range = Uac2VolumeRange(minRaw = 0, maxRaw = 0),
            historyRaw = -8_000,
        )
        assertThat(decision.action).isEqualTo(Uac2VolumeAction.UNPROVEN_RANGE)
    }

    @Test
    fun `skips a write when current already equals history`() {
        val decision = Uac2VolumePolicy.decide(
            currentRaw = 0,
            range = range,
            historyRaw = 0,
        )
        assertThat(decision.action).isEqualTo(Uac2VolumeAction.ALREADY_AT_HISTORY)
    }

    @Test
    fun `transport write guard only allows a lower in-range target`() {
        assertThat(Uac2VolumePolicy.canWriteDown(-8_000, 0, range)).isTrue()
        assertThat(Uac2VolumePolicy.canWriteDown(-8_000, -8_000, range)).isFalse()
        assertThat(Uac2VolumePolicy.canWriteDown(0, -1_000, range)).isFalse()
        assertThat(Uac2VolumePolicy.canWriteDown(-20_000, 0, range)).isFalse()
        assertThat(
            Uac2VolumePolicy.canWriteDown(-1, 0, Uac2VolumeRange(minRaw = 0, maxRaw = 0)),
        ).isFalse()
    }

    @Test
    fun `monitor correction picks the safe target and never writes up`() {
        assertThat(Uac2VolumePolicy.correctionTarget(0, range, -8_000)).isEqualTo(-8_000)
        assertThat(Uac2VolumePolicy.correctionTarget(0, range, null)).isEqualTo(-10_240)
        assertThat(Uac2VolumePolicy.correctionTarget(-8_000, range, -9_000)).isNull()
        assertThat(Uac2VolumePolicy.correctionTarget(0, range, 0)).isNull()
    }

    @Test
    fun `correction gate allows three writes at least two seconds apart`() {
        val gate = Uac2VolumeCorrectionGate(maxCorrections = 3, minimumIntervalMs = 2_000L)
        assertThat(gate.tryAcquire(1_000L)).isTrue()
        assertThat(gate.tryAcquire(2_000L)).isFalse()
        assertThat(gate.tryAcquire(3_000L)).isTrue()
        assertThat(gate.tryAcquire(5_000L)).isTrue()
        assertThat(gate.tryAcquire(9_000L)).isFalse()
        assertThat(gate.count()).isEqualTo(3)
        gate.reset()
        assertThat(gate.tryAcquire(10_000L)).isTrue()
    }

    @Test
    fun `descriptor gate requires a volume bit`() {
        val withoutVolume = Uac2FeatureUnit(
            unitId = 0x0a,
            sourceId = 0x03,
            masterControlBitmap = 0,
            channelControlBitmaps = listOf(0x0c, 0x0c),
        )
        val withVolume = withoutVolume.copy(masterControlBitmap = 0x0003)
        assertThat(withoutVolume.supportsVolume).isFalse()
        assertThat(withVolume.supportsVolume).isTrue()
    }
}
