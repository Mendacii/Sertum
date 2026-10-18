package com.sertum.player.ui.screens.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * How long an album runs, as the album page states it.
 *
 * The same shape the track rows use, one unit up: seconds, with an hours column that a single
 * track never reaches. Precision to the second is the point - the total is read against the track
 * durations directly beneath it, and a total that disagreed with them would look like an error.
 */
class AlbumDurationTest {

    @Test
    fun `an album under an hour is minutes and seconds`() {
        assertThat(albumDurationLabel(42L * 60_000L)).isEqualTo("42:00")
        assertThat(albumDurationLabel(51L * 60_000L + 23_000L)).isEqualTo("51:23")
        assertThat(albumDurationLabel(20_000L)).isEqualTo("0:20")
    }

    @Test
    fun `an album past an hour gains the hours column`() {
        assertThat(albumDurationLabel((60L + 2L) * 60_000L + 51_000L)).isEqualTo("1:02:51")
        assertThat(albumDurationLabel(2L * 3_600_000L + 5L * 60_000L + 7_000L)).isEqualTo("2:05:07")
    }

    @Test
    fun `a round hour keeps its empty columns`() {
        // No shortening to "1:00": the shape stays fixed so two albums are comparable at a glance.
        assertThat(albumDurationLabel(3_600_000L)).isEqualTo("1:00:00")
    }

    @Test
    fun `just under an hour does not gain an hours column`() {
        assertThat(albumDurationLabel(59L * 60_000L + 59_000L)).isEqualTo("59:59")
    }

    @Test
    fun `seconds are truncated rather than rounded up`() {
        // 42:00.9 must not read as 42:01 - a total is a measurement, not an estimate.
        assertThat(albumDurationLabel(42L * 60_000L + 900L)).isEqualTo("42:00")
    }

    @Test
    fun `an unknown total is omitted rather than shown as zero`() {
        assertThat(albumDurationLabel(0L)).isNull()
        assertThat(albumDurationLabel(-1L)).isNull()
    }
}
