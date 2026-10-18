package com.sertum.player.audio.uac2

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Position semantics of a backend that gets no clock from the device. */
class PositionClockTest {

    private var now = 1_000_000_000L
    private val clock = PositionClock { now }

    @Test
    fun `a stopped clock reads zero`() {
        assertThat(clock.positionUs()).isEqualTo(0L)
    }

    @Test
    fun `running advances with wall time`() {
        clock.start()
        now += 2_500_000_000L // 2.5 s
        assertThat(clock.positionUs()).isEqualTo(2_500_000L)
    }

    @Test
    fun `pausing freezes and resuming continues`() {
        clock.start()
        now += 1_000_000_000L
        clock.pause()
        assertThat(clock.positionUs()).isEqualTo(1_000_000L)
        now += 5_000_000_000L // paused time must not count
        assertThat(clock.positionUs()).isEqualTo(1_000_000L)
        clock.start()
        now += 500_000_000L
        assertThat(clock.positionUs()).isEqualTo(1_500_000L)
    }

    @Test
    fun `a reset restarts the stream at zero`() {
        clock.start()
        now += 3_000_000_000L
        clock.reset()
        assertThat(clock.positionUs()).isEqualTo(0L)
        clock.start()
        now += 1_000_000_000L
        assertThat(clock.positionUs()).isEqualTo(1_000_000L)
    }
}