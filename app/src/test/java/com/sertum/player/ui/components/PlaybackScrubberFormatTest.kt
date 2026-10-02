package com.sertum.player.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The now-playing screen shows elapsed/total time next to the scrubber
 * (user feedback 2026-10-02), so the formatter is the one piece of that UI
 * with behaviour worth pinning in a JVM test (PRD A-19 excludes Compose UI
 * automation).
 */
class PlaybackScrubberFormatTest {

    @Test
    fun `formats sub-minute durations as zero-padded seconds`() {
        assertThat(formatDuration(0L)).isEqualTo("0:00")
        assertThat(formatDuration(1_000L)).isEqualTo("0:01")
        assertThat(formatDuration(59_999L)).isEqualTo("0:59")
    }

    @Test
    fun `formats minute boundaries without padding the minutes field`() {
        assertThat(formatDuration(60_000L)).isEqualTo("1:00")
        assertThat(formatDuration(3_599_000L)).isEqualTo("59:59")
    }

    @Test
    fun `switches to hours once the track passes an hour`() {
        assertThat(formatDuration(3_600_000L)).isEqualTo("1:00:00")
        assertThat(formatDuration(3_661_000L)).isEqualTo("1:01:01")
        assertThat(formatDuration(36_000_000L)).isEqualTo("10:00:00")
    }

    @Test
    fun `truncates sub-second remainders instead of rounding up`() {
        assertThat(formatDuration(1_999L)).isEqualTo("0:01")
    }

    @Test
    fun `negative or unknown durations collapse to zero`() {
        assertThat(formatDuration(-1L)).isEqualTo("0:00")
        assertThat(formatDuration(Long.MIN_VALUE)).isEqualTo("0:00")
    }

    @Test
    fun `very long durations keep counting hours`() {
        assertThat(formatDuration(360_000_000L)).isEqualTo("100:00:00")
    }
}
