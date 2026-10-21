package com.sertum.player.audio.backend

import com.google.common.truth.Truth.assertThat
import com.sertum.player.audio.uac2.Uac2VolumeRange
import com.sertum.player.data.prefs.Uac2VolumeHistory
import org.junit.Test

class Uac2VolumeHistoryTest {

    private val range = Uac2VolumeRange(minRaw = -10_240, maxRaw = 0, resolutionRaw = 256)

    private fun history(
        raw: Int = -8_000,
        minRaw: Int = -10_240,
        maxRaw: Int = 0,
        resolutionRaw: Int? = 256,
        unitId: Int = 0x0a,
    ) = Uac2VolumeHistory(raw, minRaw, maxRaw, resolutionRaw, unitId)

    @Test
    fun `same unit and range is a valid restore fingerprint`() {
        assertThat(isSameVolumeFingerprint(history(), range, 0x0a)).isTrue()
    }

    @Test
    fun `a changed range invalidates the raw value`() {
        assertThat(isSameVolumeFingerprint(history(minRaw = -20_000), range, 0x0a)).isFalse()
        assertThat(isSameVolumeFingerprint(history(maxRaw = 1_000), range, 0x0a)).isFalse()
        assertThat(isSameVolumeFingerprint(history(resolutionRaw = 512), range, 0x0a)).isFalse()
    }

    @Test
    fun `a changed feature unit invalidates the raw value`() {
        assertThat(isSameVolumeFingerprint(history(unitId = 0x0b), range, 0x0a)).isFalse()
        assertThat(isSameVolumeFingerprint(history(unitId = 0x0a), range, null)).isFalse()
    }
}
