package com.sertum.player.ui.screens.nowplaying

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Which angle the cover should animate to next.
 *
 * These tests exercise the production [nextTurnTarget] rather than a copy, because the
 * arithmetic is the part that decides whether a resume continues or restarts, and a copy
 * would keep passing after the real one regressed.
 *
 * What this cannot cover: whether the angle survives a pause. That depends on the
 * Animatable staying alive across the pause, which is a composition lifetime question, not
 * arithmetic. If the reset is observed again, this test passing would mean the arithmetic is
 * fine and the angle is being lost with the composable.
 */
class CoverRotationTest {

    @Test
    fun `a turn advances by a full circle`() {
        assertThat(nextTurnTarget(0f)).isEqualTo(360f)
        assertThat(nextTurnTarget(90f)).isEqualTo(450f)
    }

    @Test
    fun `resuming from a part-turn continues from that angle rather than restarting`() {
        // The failure this guards against: pausing halfway and resuming from 0, which
        // reads as the cover snapping back to the top.
        val pausedAt = 180f

        val resumed = nextTurnTarget(pausedAt)

        assertThat(resumed).isEqualTo(540f)
        // 540 and 180 are the same angle on a circle, so the cover neither jumps nor
        // restarts - it simply carries on.
        assertThat((resumed - pausedAt) % 360f).isEqualTo(0f)
        assertThat(resumed).isNotEqualTo(360f)
    }

    @Test
    fun `an arbitrary pause angle still resumes in place`() {
        listOf(0f, 1f, 45f, 90f, 179.5f, 270f, 359f).forEach { angle ->
            val target = nextTurnTarget(angle)
            assertThat(target - angle).isEqualTo(360f)
            assertThat(target % 360f).isEqualTo(angle % 360f)
        }
    }

    @Test
    fun `repeated turns keep advancing instead of oscillating`() {
        var angle = 0f
        repeat(5) { angle = nextTurnTarget(angle) }
        assertThat(angle).isEqualTo(1800f)
    }

    @Test
    fun `the turn duration is the chosen 3 rpm`() {
        // rpm is turns per minute, so a turn takes 60,000 / rpm:
        //   60,000 / 3 = 20,000ms
        //
        // The division is asserted, not just the constant. The number was never what went
        // wrong here - 12,000ms was called 30 rpm, and that figure justified 4,000ms (three
        // times faster, described as slower) and later 24,000ms (right value, wrong reason).
        // Asserting the division forces the intended speed to be stated.
        assertThat(COVER_ROTATION_MS).isEqualTo(20_000)
        assertThat(60_000f / COVER_ROTATION_MS).isEqualTo(3f)

        // The figure that was wrong at the start: 30 rpm would have been 2,000ms.
        assertThat(60_000f / 30f).isEqualTo(2_000f)
    }

    /**
     * The holder is what makes a resume continue instead of restarting, so its contract is
     * worth pinning: it keeps whatever angle it is given, and it is where the player reads
     * its starting angle from.
     */
    @Test
    fun `the angle holder keeps the angle it is given`() {
        val original = CoverRotation.angle
        try {
            CoverRotation.angle = 123.5f
            assertThat(CoverRotation.angle).isEqualTo(123.5f)

            // A new player would start here rather than at zero - the whole point of the
            // holder, since a plain remember would hand it 0f.
            assertThat(CoverRotation.angle).isNotEqualTo(0f)
        } finally {
            CoverRotation.angle = original
        }
    }

    @Test
    fun `resuming from the holder lands on the same angle it was paused at`() {
        val original = CoverRotation.angle
        try {
            CoverRotation.angle = 200f

            val resumed = nextTurnTarget(CoverRotation.angle)

            // Carries on; does not jump to 360 or back to 0.
            assertThat(resumed % 360f).isEqualTo(200f)
        } finally {
            CoverRotation.angle = original
        }
    }
}
