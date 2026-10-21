package com.sertum.player.audio.backend

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.sqrt

/**
 * Pins the numbers behind the debug PCM tap.
 *
 * The tap exists to answer "was the audio still correct when it reached the transport?", so its
 * statistics must be verifiable against hand-computed values rather than trusted because the log
 * line looked plausible. The hashing, RMS/peak/DC/zero arithmetic and the once-per-audio-second
 * aggregation are pure functions, tested here without a device or an Android runtime.
 */
class PcmTapStatsTest {

    // ---- hashing ------------------------------------------------------------

    @Test
    fun `sha256 prefix of a known buffer is the published digest prefix`() {
        // SHA-256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        val digest = PcmTapStats.sha256PrefixHex("abc".toByteArray(Charsets.US_ASCII))
        assertThat(digest).isEqualTo("ba7816bf8f01cfea414140de5dae2223")
        assertThat(digest).hasLength(32)
    }

    @Test
    fun `sha256 prefix covers exactly the requested bytes`() {
        val buffer = byteArrayOf(1, 2, 3, 4)
        // Same prefix length, different data: the digest must change.
        assertThat(PcmTapStats.sha256PrefixHex(buffer, length = 3))
            .isNotEqualTo(PcmTapStats.sha256PrefixHex(buffer, length = 4))
    }

    // ---- statistics ---------------------------------------------------------

    @Test
    fun `sixteen bit statistics are normalized to full scale`() {
        // 0, +0.5, -0.5, 0 (little-endian signed 16-bit).
        val pcm = pcm16(0, 16_384, -16_384, 0)

        val stats = PcmTapStats.analyze(pcm, sampleBytes = 2)

        assertThat(stats.byteCount).isEqualTo(8)
        assertThat(stats.rms).isWithin(1e-12).of(sqrt(0.125))
        assertThat(stats.peak).isWithin(1e-12).of(0.5)
        assertThat(stats.dc).isWithin(1e-12).of(0.0)
        assertThat(stats.zeroRatio).isWithin(1e-12).of(0.5)
    }

    @Test
    fun `twenty four bit samples are sign extended and scaled by two to the twenty three`() {
        // -1 and +1, the extremes of the smallest audible non-zero pair.
        val pcm = byteArrayOf(
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0x01, 0x00, 0x00,
        )

        val stats = PcmTapStats.analyze(pcm, sampleBytes = 3)

        val lsb = 1.0 / 8_388_608.0
        assertThat(stats.peak).isWithin(1e-15).of(lsb)
        assertThat(stats.rms).isWithin(1e-15).of(lsb)
        assertThat(stats.dc).isWithin(1e-15).of(0.0)
        assertThat(stats.zeroRatio).isEqualTo(0.0)
    }

    @Test
    fun `digital silence reports every sample as zero`() {
        val stats = PcmTapStats.analyze(ByteArray(24), sampleBytes = 3)

        assertThat(stats.byteCount).isEqualTo(24)
        assertThat(stats.zeroRatio).isEqualTo(1.0)
        assertThat(stats.rms).isEqualTo(0.0)
        assertThat(stats.peak).isEqualTo(0.0)
        assertThat(stats.dc).isEqualTo(0.0)
    }

    @Test
    fun `an empty window is all zeroes rather than a division by zero`() {
        val stats = PcmTapStats.analyze(ByteArray(0), sampleBytes = 2)

        assertThat(stats.byteCount).isEqualTo(0)
        assertThat(stats.rms).isEqualTo(0.0)
        assertThat(stats.peak).isEqualTo(0.0)
        assertThat(stats.dc).isEqualTo(0.0)
        assertThat(stats.zeroRatio).isEqualTo(0.0)
    }

    // ---- aggregation --------------------------------------------------------

    @Test
    fun `the aggregator emits one line per audio second and flags a repeated block`() {
        val lines = mutableListOf<String>()
        // 2 Hz mono 16-bit: one audio second is two frames, four bytes.
        val tap = PcmTapAggregator(sampleRate = 2, channels = 1, bitDepth = 16) { lines += it }

        // Three bytes: a partial second must not produce a line.
        tap.accept(byteArrayOf(0x00, 0x40, 0x00))
        assertThat(lines).isEmpty()

        // The fourth byte completes the first second: +0.5, -0.5.
        tap.accept(byteArrayOf(0xC0.toByte()))
        assertThat(lines).hasSize(1)
        assertThat(lines[0]).contains("t=0")
        assertThat(lines[0]).contains("rate=2")
        assertThat(lines[0]).contains("frames=2")
        assertThat(lines[0]).contains("bytes=4")
        assertThat(lines[0]).contains("rms=0.500000")
        assertThat(lines[0]).contains("peak=0.500000")
        assertThat(lines[0]).contains("dc=0.000000")
        assertThat(lines[0]).contains("zero=0.000000")
        assertThat(lines[0]).contains("repeat=0")
        assertThat(lines[0].substringAfter("sha256=").substringBefore(' ')).hasLength(32)

        // The identical second must be flagged: that is the stuck/replayed-block signature.
        tap.accept(byteArrayOf(0x00, 0x40, 0x00, 0xC0.toByte()))
        assertThat(lines).hasSize(2)
        assertThat(lines[1]).contains("t=1")
        assertThat(lines[1]).contains("repeat=1")

        // Different PCM resets the flag.
        tap.accept(byteArrayOf(0x01, 0x00, 0x02, 0x00))
        assertThat(lines).hasSize(3)
        assertThat(lines[2]).contains("t=2")
        assertThat(lines[2]).contains("repeat=0")
    }

    private fun pcm16(vararg samples: Int): ByteArray =
        ByteArray(samples.size * 2).also { out ->
            samples.forEachIndexed { index, sample ->
                out[index * 2] = (sample and 0xFF).toByte()
                out[index * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
            }
        }
}
