package com.sertum.player.audio.backend

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutputProvider
import com.google.common.truth.Truth.assertThat
import com.sertum.player.domain.playback.AudioOutputBackend
import com.sertum.player.domain.playback.BackendCapabilities
import com.sertum.player.domain.playback.StreamSpec
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Digital-path audit for the USB-exclusive route (PS-PLAN-003 T2a).
 *
 * The device evidence only ever showed that the *audio device* received
 * something; it could not answer whether Sertum's own adapter modified the
 * samples on the way there. These tests pin the adapter's numerical behaviour
 * sample by sample, independently of the decoder, so the answer stops being a
 * listening impression.
 */
@OptIn(UnstableApi::class)
class BackendAudioOutputTransferTest {

    // ---- exact expectations -------------------------------------------------

    /**
     * The 24-bit scale on its own. A 24-bit integer sample is exactly
     * representable in float32, so `k / 2^23` survives the float pipeline and
     * must come back out as `k` - this is the bit-exactness claim under test.
     */
    private fun expectedPacked24(samples: List<Float>): ByteArray {
        val out = ByteArray(samples.size * 3)
        samples.forEachIndexed { i, sample ->
            val scaled = (sample * (8_388_607f + 1f)).toInt()
            out[i * 3] = (scaled and 0xFF).toByte()
            out[i * 3 + 1] = ((scaled shr 8) and 0xFF).toByte()
            out[i * 3 + 2] = ((scaled shr 16) and 0xFF).toByte()
        }
        return out
    }

    private fun expectedPacked16(samples: List<Float>): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, sample ->
            val scaled = (sample * (32_767f + 1f)).toInt()
            out[i * 2] = (scaled and 0xFF).toByte()
            out[i * 2 + 1] = ((scaled shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun packed24ToInts(bytes: ByteArray, channelCount: Int): List<Int> {
        val frames = bytes.size / (3 * channelCount)
        return (0 until frames).flatMap { frame ->
            (0 until channelCount).map { channel ->
                val at = (frame * channelCount + channel) * 3
                val raw = (bytes[at].toInt() and 0xFF) or
                    ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[at + 2].toInt() and 0xFF) shl 16)
                if (raw and 0x800000 != 0) raw - 0x1000000 else raw
            }
        }
    }

    private fun floatLe(samples: List<Float>): ByteBuffer {
        val buffer = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buffer.putFloat(it) }
        buffer.flip()
        return buffer
    }

    private class RecordingBackend : AudioOutputBackend {
        override val capabilities = BackendCapabilities(supportsHardwareVolume = false, isExclusive = true)
        val writes = mutableListOf<ByteArray>()
        override fun open(spec: StreamSpec) = Result.success(Unit)
        override fun writePcm(frame: ByteArray, offset: Int, length: Int): Result<Int> {
            writes += frame.copyOfRange(offset, offset + length)
            return Result.success(length)
        }
        override fun pause() = Result.success(Unit)
        override fun play() = Result.success(Unit)
        override fun flush() = Result.success(Unit)
        override fun stop() = Result.success(Unit)
        override fun release() = Unit
        override fun onVolumeChanged(volume01: Float) = Unit
    }

    private fun outputConfig(
        encoding: Int,
        channels: Int = 2,
        sampleRate: Int = 44_100,
    ): AudioOutputProvider.OutputConfig = AudioOutputProvider.OutputConfig.Builder()
        .setEncoding(encoding)
        .setSampleRate(sampleRate)
        .setChannelMask(if (channels == 2) android.media.AudioFormat.CHANNEL_OUT_STEREO else android.media.AudioFormat.CHANNEL_OUT_MONO)
        .setIsTunneling(false)
        .setIsOffload(false)
        .build()

    /**
     * The adapter deliberately holds samples in `pending` until a batch or a
     * pacing window fills, so a handful of frames is not enough to reach the
     * backend. Media3 signals "flush everything now" with
     * [C.TIME_END_OF_SOURCE] at the end of a stream, which is exactly how a
     * short assertion buffer has to be submitted to be observable.
     */
    private fun BackendAudioOutput.writeToEnd(buffer: ByteBuffer, frameCount: Int): Boolean =
        write(buffer, frameCount, C.TIME_END_OF_SOURCE)

    // ---- the audit ---------------------------------------------------------

    @Test
    fun `float path to packed 24 is bit exact for every 24-bit sample value`() {
        // Values chosen so the float pipeline has nothing to round: every one
        // is an exact multiple of 1/2^23 in [0.5, 1).
        val samples = listOf(
            0.5f + 0f / 8_388_608f,
            0.5f + 4095f / 8_388_608f,
            0.5f + 4096f / 8_388_608f,
            0.5f + 100_000f / 8_388_608f,
            0.5f + 999_999f / 8_388_608f,
            1f - 1f / 8_388_608f,
        )
        val tapped = mutableListOf<ByteArray>()
        val backend = RecordingBackend()
        val output = BackendAudioOutput(
            backend,
            outputConfig(C.ENCODING_PCM_FLOAT),
            targetBitDepth = 24,
            playWhenReady = { true },
            pcmTap = { tapped += it },
        )

        val accepted = output.writeToEnd(floatLe(samples), samples.size)

        assertThat(accepted).isTrue()
        val submitted = tapped.concatAll()
        assertThat(submitted).isEqualTo(expectedPacked24(samples))
        // The backend saw exactly what the tap saw.
        assertThat(backend.writes.concatAll()).isEqualTo(submitted)
    }

    @Test
    fun `packed 24 output preserves the source integer sample values`() {
        val samples = listOf(
            0f,
            1f / 8_388_608f,
            -1f / 8_388_608f,
            4096f / 8_388_608f,
            -4096f / 8_388_608f,
            0.5f,
            -0.5f,
            8_388_607f / 8_388_608f,
            -1f,
        )
        val expectedInts = listOf(0, 1, -1, 4096, -4096, 4_194_304, -4_194_304, 8_388_607, -8_388_608)
        val tapped = mutableListOf<ByteArray>()
        val output = BackendAudioOutput(
            RecordingBackend(),
            outputConfig(C.ENCODING_PCM_FLOAT, channels = 1),
            targetBitDepth = 24,
            playWhenReady = { true },
            pcmTap = { tapped += it },
        )

        output.writeToEnd(floatLe(samples), samples.size)

        assertThat(packed24ToInts(tapped.concatAll(), channelCount = 1)).isEqualTo(expectedInts)
    }

    @Test
    fun `float path to packed 16 is bit exact`() {
        // Stereo, so an even sample count keeps whole frames only; a partial
        // trailing frame is covered by its own test below.
        val samples = listOf(
            0f, 1f / 32_768f,
            -1f / 32_768f, 0.5f,
            -0.5f, 32_767f / 32_768f,
        )
        val tapped = mutableListOf<ByteArray>()
        val backend = RecordingBackend()
        val output = BackendAudioOutput(
            backend,
            outputConfig(C.ENCODING_PCM_FLOAT),
            targetBitDepth = 16,
            playWhenReady = { true },
            pcmTap = { tapped += it },
        )

        output.writeToEnd(floatLe(samples), samples.size / 2)

        val expected = expectedPacked16(samples)
        assertThat(tapped.concatAll()).isEqualTo(expected)
        assertThat(backend.writes.concatAll()).isEqualTo(expected)
    }

    @Test
    fun `a trailing partial frame is refused and backpressured, never converted`() {
        // 7 floats in stereo = 6 whole frames + 1 stray sample. The adapter
        // must consume only whole frames and report that it did not finish the
        // buffer, so Media3 retries instead of losing or mangling audio.
        val frames = 6
        val samples = (1..frames).flatMap { listOf(0.25f, -0.25f) } + 0.9f
        val tapped = mutableListOf<ByteArray>()
        val backend = RecordingBackend()
        val output = BackendAudioOutput(
            backend,
            outputConfig(C.ENCODING_PCM_FLOAT),
            targetBitDepth = 16,
            playWhenReady = { true },
            pcmTap = { tapped += it },
        )
        val buffer = floatLe(samples)

        val accepted = output.writeToEnd(buffer, frames)

        assertThat(accepted).isFalse()
        assertThat(buffer.remaining()).isEqualTo(4) // exactly the stray sample
        assertThat(tapped.concatAll()).isEqualTo(expectedPacked16(samples.dropLast(1)))
        assertThat(backend.writes.concatAll()).isEqualTo(expectedPacked16(samples.dropLast(1)))
    }

    @Test
    fun `samples outside the representable range clamp instead of wrapping`() {
        val samples = listOf(1.5f, -1.5f, 2f, -2f)
        val tapped = mutableListOf<ByteArray>()
        val output = BackendAudioOutput(
            RecordingBackend(),
            outputConfig(C.ENCODING_PCM_FLOAT, channels = 1),
            targetBitDepth = 24,
            playWhenReady = { true },
            pcmTap = { tapped += it },
        )

        output.writeToEnd(floatLe(samples), samples.size)

        assertThat(packed24ToInts(tapped.concatAll(), channelCount = 1))
            .isEqualTo(listOf(8_388_607, -8_388_608, 8_388_607, -8_388_608))
    }

    @Test
    fun `already-packed 24-bit input is forwarded byte for byte`() {
        // When Media3 hands the adapter packed 24-bit PCM there is no
        // conversion step at all; the bytes must pass through untouched.
        val bytes = byteArrayOf(1, 2, 3, 0xFF.toByte(), 0xFF.toByte(), 0x7F, 0x00, 0x00, 0x80.toByte())
        val tapped = mutableListOf<ByteArray>()
        val backend = RecordingBackend()
        val output = BackendAudioOutput(
            backend,
            outputConfig(C.ENCODING_PCM_24BIT, channels = 1),
            targetBitDepth = 24,
            playWhenReady = { true },
            pcmTap = { tapped += it },
        )
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        output.writeToEnd(buffer, bytes.size / 3)

        assertThat(tapped.concatAll()).isEqualTo(bytes)
        assertThat(backend.writes.concatAll()).isEqualTo(bytes)
    }

    @Test
    fun `a failing tap cannot corrupt or stall playback`() {
        val samples = listOf(0.25f, -0.25f)
        val backend = RecordingBackend()
        val output = BackendAudioOutput(
            backend,
            outputConfig(C.ENCODING_PCM_FLOAT),
            targetBitDepth = 24,
            playWhenReady = { true },
            pcmTap = { throw IllegalStateException("tap exploded") },
        )

        val accepted = output.writeToEnd(floatLe(samples), samples.size)

        assertThat(accepted).isTrue()
        assertThat(backend.writes.concatAll()).isEqualTo(expectedPacked24(samples))
    }

    @Test
    fun `a tap never observes the internal pending buffer`() {
        val samples = listOf(0.25f, -0.25f)
        val observed = mutableListOf<ByteArray>()
        val backend = RecordingBackend()
        val output = BackendAudioOutput(
            backend,
            outputConfig(C.ENCODING_PCM_FLOAT),
            targetBitDepth = 24,
            playWhenReady = { true },
            pcmTap = { arr ->
                observed += arr
                // A misbehaving observer must not be able to reach back into
                // the adapter's bookkeeping.
                arr.fill(0)
            },
        )

        output.writeToEnd(floatLe(samples), samples.size)

        // The tap zeroed its copy, yet the backend still received real data.
        assertThat(backend.writes.concatAll()).isEqualTo(expectedPacked24(samples))
    }

    private fun List<ByteArray>.concatAll(): ByteArray {
        val out = ByteArray(sumOf { it.size })
        var at = 0
        forEach { chunk ->
            chunk.copyInto(out, at)
            at += chunk.size
        }
        return out
    }

    @Test
    fun `diagnostic frame accounting`() {
        val samples = (1..7).flatMap { listOf(0.25f, -0.25f) }
        val backend = RecordingBackend()
        val output = BackendAudioOutput(
            backend,
            outputConfig(C.ENCODING_PCM_FLOAT),
            targetBitDepth = 16,
            playWhenReady = { true },
        )
        val buffer = floatLe(samples)
        val first = output.write(buffer, samples.size / 2, C.TIME_END_OF_SOURCE)
        assertThat(first).isTrue()
        assertThat(buffer.remaining()).isEqualTo(0)
    }
}
