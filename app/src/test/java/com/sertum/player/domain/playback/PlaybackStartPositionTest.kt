package com.sertum.player.domain.playback

import com.sertum.player.audio.PLAYBACK_START_POSITION_MS
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the removal of playback memory (user decision 2026-10-02).
 *
 * The complaint was concrete: a track that had been played to the end resumed at
 * the end, so tapping it meant scrubbing back to the start by hand. The fix was
 * to stop restoring a stored position, which is easy to undo by accident - a
 * later "restore where you left off" change would look like a feature and would
 * silently reintroduce exactly this annoyance.
 *
 * These tests are cheap because the rule is a constant, and they name the
 * behaviour rather than the implementation, so a future change has to argue with
 * the user's stated requirement instead of just editing an argument.
 */
class PlaybackStartPositionTest {

    @Test
    fun `an explicit play starts at the beginning of the track`() {
        assertEquals(0L, PLAYBACK_START_POSITION_MS)
    }

    @Test
    fun `the start position is never a stored position`() {
        // Playback memory is gone: no lookup, no restore, nothing but the start
        // of the track. If this constant ever becomes a call, the memory is back.
        assertEquals("playback must not resume", 0L, PLAYBACK_START_POSITION_MS)
    }
}
