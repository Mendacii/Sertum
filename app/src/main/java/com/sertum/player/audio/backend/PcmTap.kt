package com.sertum.player.audio.backend

import java.security.MessageDigest
import java.util.Locale
import kotlin.math.sqrt

/**
 * Per-second statistics for the debug-only PCM tap.
 *
 * The transport's counters prove that it ran and that its ring had data. They cannot prove that the
 * bytes were still music, so corruption between the decoder and USB is invisible there - and from
 * the transport's side it looks exactly like a DAC fault. This tap fingerprints the bytes the
 * backend accepted once per audio second: SHA-256 (first 16 digest bytes), RMS, peak, DC offset and
 * the fraction of zero samples. A hash that repeats second after second is the signature of a stuck
 * or replayed block; a sudden RMS/DC change points at the decoder or the float-to-24-bit packer
 * rather than at USB.
 *
 * Everything here is pure arithmetic over little-endian packed PCM. The Android and file wiring
 * lives in [RoutedAudioOutputProvider] and exists only in debug builds.
 */
internal object PcmTapStats {

    /** One audio second's numbers. [sha256Prefix] is 16 digest bytes in lowercase hex. */
    data class Second(
        val byteCount: Int,
        val sha256Prefix: String,
        val rms: Double,
        val peak: Double,
        val dc: Double,
        val zeroRatio: Double,
    )

    /**
     * Analyses the first [length] bytes.
     *
     * @param sampleBytes 2 for 16-bit or 3 for 24-bit packed little-endian samples.
     * @param length number of bytes to analyse; must be a whole number of samples.
     */
    fun analyze(bytes: ByteArray, sampleBytes: Int, length: Int = bytes.size): Second {
        require(sampleBytes == 2 || sampleBytes == 3) { "unsupported sample size: $sampleBytes" }
        require(length in 0..bytes.size) { "length $length is outside ${bytes.size}" }
        require(length % sampleBytes == 0) { "length $length is not a whole number of samples" }
        val samples = length / sampleBytes
        val scale = if (sampleBytes == 2) 32_768.0 else 8_388_608.0
        var sum = 0.0
        var sumSquares = 0.0
        var peak = 0.0
        var zeros = 0
        var at = 0
        repeat(samples) {
            val value = sampleAt(bytes, at, sampleBytes)
            at += sampleBytes
            val normalized = value.toDouble()
            sum += normalized
            sumSquares += normalized * normalized
            val magnitude = if (normalized < 0.0) -normalized else normalized
            if (magnitude > peak) peak = magnitude
            if (value == 0) zeros++
        }
        return Second(
            byteCount = length,
            sha256Prefix = sha256PrefixHex(bytes, length, 16),
            rms = if (samples == 0) 0.0 else sqrt(sumSquares / samples) / scale,
            peak = peak / scale,
            dc = if (samples == 0) 0.0 else sum / samples / scale,
            zeroRatio = if (samples == 0) 0.0 else zeros.toDouble() / samples,
        )
    }

    /** The first [prefixBytes] digest bytes as lowercase hex (32 characters for the default 16). */
    fun sha256PrefixHex(
        bytes: ByteArray,
        length: Int = bytes.size,
        prefixBytes: Int = 16,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes, 0, length)
        val hash = digest.digest()
        val count = minOf(prefixBytes, hash.size)
        val out = CharArray(count * 2)
        for (i in 0 until count) {
            val value = hash[i].toInt() and 0xFF
            out[i * 2] = HEX_DIGITS[value ushr 4]
            out[i * 2 + 1] = HEX_DIGITS[value and 0x0F]
        }
        return String(out)
    }

    private fun sampleAt(bytes: ByteArray, at: Int, sampleBytes: Int): Int =
        if (sampleBytes == 2) {
            val raw = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
            if (raw and 0x8000 != 0) raw - 0x1_0000 else raw
        } else {
            val raw = (bytes[at].toInt() and 0xFF) or
                ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                ((bytes[at + 2].toInt() and 0xFF) shl 16)
            if (raw and 0x80_0000 != 0) raw - 0x100_0000 else raw
        }

    private const val HEX_DIGITS = "0123456789abcdef"
}

/**
 * Buffers tapped bytes into exact audio seconds and emits one line per second.
 *
 * A second is [sampleRate] frames, not a wall-clock second, so the numbers describe the PCM itself:
 * a stalled or paused stream produces no line at all, and the lines stay aligned with the audio
 * timeline of the transport's frame counters. [BackendAudioOutput] only taps whole frames, so a
 * second boundary never splits one.
 *
 * The sink is expected to be cheap; file I/O belongs on the provider's writer thread. It is called
 * under this object's lock, which is safe because the tap is only reached from the playback thread.
 */
internal class PcmTapAggregator(
    private val sampleRate: Int,
    channels: Int,
    bitDepth: Int,
    private val sink: (String) -> Unit,
) {
    private val sampleBytes = if (bitDepth >= 24) 3 else 2
    private val frameBytes = packedFrameSize(bitDepth, channels)
    private val secondBytes = sampleRate.coerceAtLeast(0) * frameBytes
    private val enabled = sampleRate > 0 && frameBytes > 0
    private val window = ByteArray(if (enabled) secondBytes else 0)

    private var filled = 0
    private var second = 0L
    private var previousHash: String? = null

    /** Must not throw: a broken tap cannot be allowed to break playback. */
    @Synchronized
    fun accept(bytes: ByteArray) {
        if (!enabled || bytes.isEmpty()) return
        var at = 0
        while (at < bytes.size) {
            val take = minOf(bytes.size - at, secondBytes - filled)
            bytes.copyInto(window, filled, at, at + take)
            filled += take
            at += take
            if (filled == secondBytes) emit()
        }
    }

    private fun emit() {
        val stats = PcmTapStats.analyze(window, sampleBytes)
        val repeated = stats.sha256Prefix == previousHash
        previousHash = stats.sha256Prefix
        val line = String.format(
            Locale.US,
            "t=%d rate=%d frames=%d bytes=%d sha256=%s rms=%.6f peak=%.6f dc=%.6f zero=%.6f repeat=%d",
            second,
            sampleRate,
            stats.byteCount / frameBytes,
            stats.byteCount,
            stats.sha256Prefix,
            stats.rms,
            stats.peak,
            stats.dc,
            stats.zeroRatio,
            if (repeated) 1 else 0,
        )
        filled = 0
        second++
        runCatching { sink(line) }
    }
}
