package com.sertum.player.audio

import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import com.sertum.player.domain.playback.RepeatMode
import org.junit.Test

/**
 * The order the queue screen's single repeat button steps through.
 *
 * Off -> all -> one -> off. That cycle is the whole user-visible contract of the button: if it
 * skips a state or gets the order wrong, one of the three modes becomes unreachable from the UI,
 * which is the state US-7 AC1 was in before the button existed at all.
 *
 * What this cannot cover: whether Media3 honours the mode once it is set. The coordinator only
 * translates the enum; the playback behaviour belongs to the player.
 */
class RepeatModeCycleTest {

    @Test
    fun `off goes to all`() {
        assertThat(nextRepeatMode(RepeatMode.OFF)).isEqualTo(RepeatMode.ALL)
    }

    @Test
    fun `all goes to one`() {
        assertThat(nextRepeatMode(RepeatMode.ALL)).isEqualTo(RepeatMode.ONE)
    }

    @Test
    fun `one goes back to off`() {
        assertThat(nextRepeatMode(RepeatMode.ONE)).isEqualTo(RepeatMode.OFF)
    }

    @Test
    fun `every mode is reachable and none is skipped`() {
        val seen = mutableListOf<RepeatMode>()
        var mode = RepeatMode.OFF
        // Three presses from any starting point must have offered all three choices once.
        repeat(3) {
            mode = nextRepeatMode(mode)
            seen += mode
        }
        assertThat(seen).containsExactly(RepeatMode.ALL, RepeatMode.ONE, RepeatMode.OFF).inOrder()
    }

    @Test
    fun `four presses are the same as one`() {
        // Guards against a cycle that drifts after wrapping, which would make the button feel
        // like it sometimes needs an extra press.
        assertThat(nextRepeatMode(nextRepeatMode(nextRepeatMode(nextRepeatMode(RepeatMode.OFF)))))
            .isEqualTo(RepeatMode.ALL)
    }

    @Test
    fun `each mode translates to the player constant it names`() {
        // A copy-paste between ALL and ONE here would make the button cycle through the right
        // labels while the player did something else.
        assertThat(RepeatMode.OFF.toPlayerRepeatMode()).isEqualTo(Player.REPEAT_MODE_OFF)
        assertThat(RepeatMode.ALL.toPlayerRepeatMode()).isEqualTo(Player.REPEAT_MODE_ALL)
        assertThat(RepeatMode.ONE.toPlayerRepeatMode()).isEqualTo(Player.REPEAT_MODE_ONE)
    }

    @Test
    fun `the player constants translate back to the same mode`() {
        listOf(RepeatMode.OFF, RepeatMode.ALL, RepeatMode.ONE).forEach { mode ->
            assertThat(mode.toPlayerRepeatMode().toRepeatMode()).isEqualTo(mode)
        }
    }

    @Test
    fun `an unknown player constant reads as no repeat`() {
        // Media3 could add a mode later. Falling into REPEAT_MODE_ALL would silently loop the
        // queue, so the fallback is the one state that cannot surprise a reader.
        assertThat(42.toRepeatMode()).isEqualTo(RepeatMode.OFF)
    }
}
