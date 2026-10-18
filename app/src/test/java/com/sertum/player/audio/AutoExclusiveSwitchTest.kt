package com.sertum.player.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * When an arriving DAC may select the exclusive route by itself.
 *
 * The decision is deliberately narrow, because the failure mode of getting it wrong is the one this
 * audit started with: a route the reader did not choose, or a claim the hardware did not honour.
 */
class AutoExclusiveSwitchTest {

    @Test
    fun `an arriving dac selects exclusive when the reader left it on`() {
        assertThat(
            shouldAutoSwitchToExclusive(autoSwitchEnabled = true, alreadyExclusive = false, dacPresent = true),
        ).isTrue()
    }

    @Test
    fun `nothing happens when the preference is off`() {
        assertThat(
            shouldAutoSwitchToExclusive(autoSwitchEnabled = false, alreadyExclusive = false, dacPresent = true),
        ).isFalse()
    }

    @Test
    fun `an already exclusive route is left alone`() {
        // Re-selecting it would stop and re-prepare the player for no reason.
        assertThat(
            shouldAutoSwitchToExclusive(autoSwitchEnabled = true, alreadyExclusive = true, dacPresent = true),
        ).isFalse()
    }

    @Test
    fun `no dac means no switch`() {
        assertThat(
            shouldAutoSwitchToExclusive(autoSwitchEnabled = true, alreadyExclusive = false, dacPresent = false),
        ).isFalse()
    }
}
