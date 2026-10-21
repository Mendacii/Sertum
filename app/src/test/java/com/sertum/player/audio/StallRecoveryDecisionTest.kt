package com.sertum.player.audio

import androidx.media3.common.Player
import com.sertum.player.audio.PlaybackCoordinator.Companion.StallDecision
import com.sertum.player.audio.PlaybackCoordinator.Companion.stallRecoveryDecision
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The stall watchdog's decision table.
 *
 * It exists because the first version only made sense while a track was playing: at ENDED,
 * playWhenReady stays true and the position stays put, so the old condition held forever and the
 * watchdog rebuilt the player every nine seconds - 1571 of the 1652 logged recoveries were state=4.
 * These cases pin down the states that must never be treated as a stall.
 */
class StallRecoveryDecisionTest {

    private fun decide(
        state: Int = Player.STATE_BUFFERING,
        playWhenReady: Boolean = true,
        isPlaying: Boolean = false,
        exclusiveRoute: Boolean = true,
        position: Long = 40_000L,
        lastPosition: Long = 40_000L,
        stalledMs: Long = 20_000L,
        recoveries: Int = 0,
    ): StallDecision = stallRecoveryDecision(
        state = state,
        playWhenReady = playWhenReady,
        isPlaying = isPlaying,
        exclusiveRoute = exclusiveRoute,
        position = position,
        lastPosition = lastPosition,
        stalledMs = stalledMs,
        recoveries = recoveries,
    )

    @Test
    fun `a finished track is not a stall`() {
        // The regression: ENDED leaves playWhenReady true and the position frozen forever.
        assertThat(decide(state = Player.STATE_ENDED, stalledMs = 600_000L)).isEqualTo(StallDecision.None)
    }

    @Test
    fun `an idle player is not a stall`() {
        assertThat(decide(state = Player.STATE_IDLE)).isEqualTo(StallDecision.None)
    }

    @Test
    fun `a paused ready player is not a stall`() {
        // READY with isPlaying=false is a pause Media3 chose (focus loss, ducking); re-preparing
        // there reopens the audio output for nothing.
        assertThat(decide(state = Player.STATE_READY, isPlaying = false)).isEqualTo(StallDecision.None)
    }

    @Test
    fun `buffering with a moving position is only observed`() {
        assertThat(decide(position = 41_000L, lastPosition = 40_000L)).isEqualTo(StallDecision.Observe)
    }

    @Test
    fun `buffering below the threshold is only observed`() {
        assertThat(decide(stalledMs = 15_000L - 1)).isEqualTo(StallDecision.Observe)
    }

    @Test
    fun `buffering past the threshold recovers`() {
        assertThat(decide(stalledMs = 15_000L)).isEqualTo(StallDecision.Recover)
    }

    @Test
    fun `recovery stops after three attempts`() {
        assertThat(decide(recoveries = 3)).isEqualTo(StallDecision.GiveUp)
        assertThat(decide(recoveries = 2)).isEqualTo(StallDecision.Recover)
    }

    @Test
    fun `the standard route is left to the platform`() {
        assertThat(decide(exclusiveRoute = false)).isEqualTo(StallDecision.None)
    }

    @Test
    fun `a paused playWhenReady is not a stall`() {
        assertThat(decide(playWhenReady = false)).isEqualTo(StallDecision.None)
    }

    @Test
    fun `a buffering player that reports playing is left alone`() {
        assertThat(decide(isPlaying = true)).isEqualTo(StallDecision.None)
    }
}
