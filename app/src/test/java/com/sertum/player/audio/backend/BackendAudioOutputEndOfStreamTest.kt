package com.sertum.player.audio.backend

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import com.google.common.truth.Truth.assertThat
import com.sertum.player.domain.playback.AudioOutputBackend
import com.sertum.player.domain.playback.BackendCapabilities
import com.sertum.player.domain.playback.StreamSpec
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The rule `DefaultAudioSink.isEnded()` needs from an [AudioOutput], and the bound that keeps a
 * stuck end-of-stream drain from being reported as a watchdog timeout.
 *
 * `isEnded()` is `handledEndOfStream && !hasPendingData()`, and `hasPendingData()` is
 * `writtenFrames > Util.durationUsToSampleCount(getPositionUs(), sampleRate)` - strict, no
 * tolerance. A position that never reaches the written frames leaves the player READY with
 * `isPlaying()` true, and Media3 1.11's stuck-playing detector reports that as ERROR_CODE_TIMEOUT
 * ten seconds later. These tests pin the value we report against that exact comparison, and the
 * retry budget that decides between "wait" and "a real write failure".
 *
 * Device evidence for the failure these guard: a 44.1 kHz/16-bit FLAC stalled 207 ms before its
 * end, raised ERROR_CODE_TIMEOUT three times 29 s apart, and the diagnostics log showed the player
 * going to state=1 each time.
 */
@OptIn(UnstableApi::class)
class BackendAudioOutputEndOfStreamTest {

    // ---- the exact comparison Media3 makes ----------------------------------

    /** `Util.durationUsToSampleCount`: microseconds to frames, rounding up (RoundingMode.UP). */
    private fun media3PositionFrames(positionUs: Long, sampleRate: Int): Long =
        (positionUs * sampleRate + 999_999L) / 1_000_000L

    /** `DefaultAudioSink.hasAudioOutputPendingData`: strict, no tolerance. */
    private fun media3HasPendingData(writtenFrames: Long, positionUs: Long, sampleRate: Int): Boolean =
        writtenFrames > media3PositionFrames(positionUs, sampleRate)

    // ---- reported position --------------------------------------------------

    @Test
    fun `a backend head one frame short of the accepted total still ends the stream`() {
        val rate = 44_100
        val accepted = 1_000L

        val position = resolvedPositionUs(
            backendPositionUs = framesToUs(accepted - 1, rate),
            flushedUs = 0L,
            acceptedFrames = accepted,
            submittedFrames = accepted,
            pendingFrames = 0,
            sampleRate = rate,
        )

        // The strict comparison Media3 makes is satisfiable: the sink can report itself ended.
        assertThat(media3HasPendingData(accepted, position, rate)).isFalse()
        assertThat(media3PositionFrames(position, rate)).isEqualTo(accepted)
    }

    @Test
    fun `while frames are still queued the reported position is the backend head`() {
        val rate = 48_000
        val accepted = 48_000L
        val backendUs = framesToUs(12_000, rate)

        val position = resolvedPositionUs(
            backendPositionUs = backendUs,
            flushedUs = 0L,
            acceptedFrames = accepted,
            submittedFrames = 24_000L,
            pendingFrames = 24_000,
            sampleRate = rate,
        )

        assertThat(position).isEqualTo(backendUs)
        // Half a second of audio is genuinely outstanding; the sink must not look drained.
        assertThat(media3HasPendingData(accepted, position, rate)).isTrue()
    }

    @Test
    fun `a backend clock ahead of the accepted frames cannot over-report`() {
        val rate = 44_100
        val accepted = 44_100L
        val pending = 1_024

        val position = resolvedPositionUs(
            // A wall-clock backend position 100 ms past what was accepted.
            backendPositionUs = framesToUs(accepted + 4_410, rate),
            flushedUs = 0L,
            acceptedFrames = accepted,
            submittedFrames = accepted - pending,
            pendingFrames = pending,
            sampleRate = rate,
        )

        assertThat(position).isEqualTo(framesToUs(accepted, rate))
    }

    @Test
    fun `a tail far behind the accepted total is not papered over`() {
        val rate = 44_100
        val accepted = 44_100L
        // The stall in the device log: 207 ms of audio never reached the device.
        val played = accepted - 9_129
        val backendUs = framesToUs(played, rate)

        val position = resolvedPositionUs(
            backendPositionUs = backendUs,
            flushedUs = 0L,
            acceptedFrames = accepted,
            submittedFrames = accepted,
            pendingFrames = 0,
            sampleRate = rate,
        )

        // Saturating here would hide a stalled transport behind a green end-of-stream.
        assertThat(position).isEqualTo(backendUs)
        assertThat(media3HasPendingData(accepted, position, rate)).isTrue()
    }

    @Test
    fun `frames burned by a flush stay on the reported position`() {
        val rate = 48_000
        val flushedFrames = 48_000L
        val flushedUs = framesToUs(flushedFrames, rate)
        val accepted = 1_000L

        val position = resolvedPositionUs(
            backendPositionUs = framesToUs(accepted, rate),
            flushedUs = flushedUs,
            acceptedFrames = accepted,
            submittedFrames = accepted,
            pendingFrames = 0,
            sampleRate = rate,
        )

        // One second of the stream was discarded by the seek; the position does not jump back.
        assertThat(position).isEqualTo(flushedUs + framesToUs(accepted, rate))
    }

    @Test
    fun `an unusable sample rate reports the raw position instead of dividing by zero`() {
        assertThat(
            resolvedPositionUs(
                backendPositionUs = 1_234L,
                flushedUs = 56L,
                acceptedFrames = 10L,
                submittedFrames = 10L,
                pendingFrames = 0,
                sampleRate = 0,
            ),
        ).isEqualTo(1_290L)
    }

    // ---- frame / microsecond conversion -------------------------------------

    @Test
    fun `frames to microseconds and back use the directions Media3 uses`() {
        assertThat(framesToUs(48_000, 48_000)).isEqualTo(1_000_000L)
        assertThat(usToFrames(1_000_000L, 48_000)).isEqualTo(48_000L)
        // Truncating down here is safe because Media3 rounds up on the way back (verified above).
        assertThat(framesToUs(1_000, 44_100)).isEqualTo(22_675L)
        assertThat(media3PositionFrames(framesToUs(1_000, 44_100), 44_100)).isEqualTo(1_000L)
        assertThat(framesToUs(1_000, 0)).isEqualTo(0L)
        assertThat(usToFrames(1_000, 0)).isEqualTo(0L)
    }

    @Test
    fun `the queue estimate compares frames with frames`() {
        val rate = 44_100
        val submitted = 2_048L
        val positionUs = 1_046_439L
        val baseUs = 1_000_000L

        // What the pacing window needs: a frame count for the backend's progress.
        val playedFrames = usToFrames(positionUs - baseUs, rate)
        assertThat(playedFrames).isEqualTo(2_047L)
        assertThat(submitted - playedFrames).isEqualTo(1L)

        // The regression this guards: subtracting microseconds from frames went negative and was
        // clamped to zero, so the pacing window could never close.
        assertThat(submitted - (positionUs - baseUs)).isLessThan(0L)
    }

    // ---- end-of-stream retry budget -----------------------------------------

    @Test
    fun `nothing pending clears the retry deadline`() {
        val step = eosDrainStep(pendingFrames = 0, stalledSinceMs = 5_000L, nowMs = 6_000L)

        assertThat(step.action).isEqualTo(EosDrainAction.WAIT)
        assertThat(step.stalledSinceMs).isEqualTo(0L)
    }

    @Test
    fun `the budget is measured from the first failed drain`() {
        val first = eosDrainStep(pendingFrames = 1_024, stalledSinceMs = 0L, nowMs = 10_000L)
        assertThat(first.action).isEqualTo(EosDrainAction.WAIT)
        assertThat(first.stalledSinceMs).isEqualTo(10_000L)

        val halfway = eosDrainStep(pendingFrames = 1_024, stalledSinceMs = first.stalledSinceMs, nowMs = 11_500L)
        assertThat(halfway.action).isEqualTo(EosDrainAction.WAIT)
        assertThat(halfway.stalledSinceMs).isEqualTo(10_000L)

        val spent = eosDrainStep(pendingFrames = 1_024, stalledSinceMs = halfway.stalledSinceMs, nowMs = 12_000L)
        assertThat(spent.action).isEqualTo(EosDrainAction.FAIL)
    }

    @Test
    fun `a zero budget fails immediately`() {
        val step = eosDrainStep(pendingFrames = 1, stalledSinceMs = 0L, nowMs = 0L, timeoutMs = 0L)

        assertThat(step.action).isEqualTo(EosDrainAction.FAIL)
    }

    // ---- the wiring: a drain that never empties becomes a real write error ----

    private class FullRingBackend : AudioOutputBackend {
        /** Named `headUs`, not `positionUs`: a `positionUs` property would clash with the
         *  interface's `getPositionUs()` on the JVM. */
        var headUs = 0L
        override val capabilities = BackendCapabilities(supportsHardwareVolume = false, isExclusive = true)
        override fun open(spec: StreamSpec) = Result.success(Unit)
        override fun writePcm(frame: ByteArray, offset: Int, length: Int) = Result.success(0)
        override fun pause() = Result.success(Unit)
        override fun play() = Result.success(Unit)
        override fun flush() = Result.success(Unit)
        override fun stop() = Result.success(Unit)
        override fun release() = Unit
        override fun onVolumeChanged(volume01: Float) = Unit
        override fun getPositionUs(): Long = headUs
        override fun getBufferSizeInFrames(): Int = 1_024
    }

    private fun outputConfig(): AudioOutputProvider.OutputConfig =
        AudioOutputProvider.OutputConfig.Builder()
            .setEncoding(C.ENCODING_PCM_FLOAT)
            .setSampleRate(44_100)
            .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO)
            .setIsTunneling(false)
            .setIsOffload(false)
            .build()

    private fun stereoSilence(frames: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(frames * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames * 2) { buffer.putFloat(0f) }
        buffer.flip()
        return buffer
    }

    @Test
    fun `a tail that never drains is reported as a write failure`() {
        var nowMs = 1_000L
        val output = BackendAudioOutput(
            backend = FullRingBackend(),
            outputConfig = outputConfig(),
            targetBitDepth = 16,
            playWhenReady = { true },
            elapsedRealtimeMs = { nowMs },
        )
        val buffer = stereoSilence(1_024)

        // First call accepts everything into `pending`, then cannot hand it to the device.
        assertThat(output.write(buffer, 1_024, C.TIME_END_OF_SOURCE)).isFalse()
        assertThat(buffer.remaining()).isEqualTo(0)

        // Retries with an empty buffer keep the budget running instead of spinning forever.
        nowMs = 2_500L
        assertThat(output.write(buffer, 1_024, C.TIME_END_OF_SOURCE)).isFalse()

        nowMs = 3_001L
        val thrown = runCatching { output.write(buffer, 1_024, C.TIME_END_OF_SOURCE) }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(AudioOutput.WriteException::class.java)
        val writeException = thrown as AudioOutput.WriteException
        assertThat(writeException.errorCode).isEqualTo(PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED)
        assertThat(writeException.isRecoverable).isTrue()
    }

    // ---- the stop() path: Media3 declares the stream drained before it checks isEnded() ----

    @Test
    fun `after media3 stops the sink the position reports the accepted total`() {
        val rate = 44_100
        val accepted = 1_000L

        // Worst case after stop(): the backend head is gone (a reset stream reports 0).
        val position = resolvedPositionUs(
            backendPositionUs = 0L,
            flushedUs = 0L,
            acceptedFrames = accepted,
            submittedFrames = 0L,
            pendingFrames = 0,
            sampleRate = rate,
            stoppedBySink = true,
        )

        assertThat(media3HasPendingData(accepted, position, rate)).isFalse()
    }

    private class GreedyBackend : AudioOutputBackend {
        var headUs = 0L
        override val capabilities = BackendCapabilities(supportsHardwareVolume = false, isExclusive = true)
        override fun open(spec: StreamSpec) = Result.success(Unit)
        override fun writePcm(frame: ByteArray, offset: Int, length: Int) = Result.success(length / 4)
        override fun pause() = Result.success(Unit)
        override fun play() = Result.success(Unit)
        override fun flush() = Result.success(Unit)
        override fun stop() = Result.success(Unit)
        override fun release() = Unit
        override fun onVolumeChanged(volume01: Float) = Unit
        override fun getPositionUs(): Long = headUs
        override fun getBufferSizeInFrames(): Int = 1_024
    }

    @Test
    fun `stopping the sink keeps the reported position at the accepted total`() {
        // The device failure this guards (2026-10-22 03:00:55): the sink had played the whole track,
        // Media3 called AudioOutput.stop() from playToEndOfStream(), and a position that collapsed
        // there kept hasPendingData() true, so the player never reached STATE_ENDED and a stuck-player
        // timeout fired 10 s later.
        var nowMs = 1_000L
        val backend = GreedyBackend()
        val output = BackendAudioOutput(
            backend = backend,
            outputConfig = outputConfig(),
            targetBitDepth = 16,
            playWhenReady = { true },
            elapsedRealtimeMs = { nowMs },
        )
        val frames = 1_024

        assertThat(output.write(stereoSilence(frames), frames, C.TIME_END_OF_SOURCE)).isTrue()

        // The backend head has not moved, yet the whole buffer was accepted: before stop() the
        // clamp still reports the head rather than pretending the tail is played.
        assertThat(output.getPositionUs()).isEqualTo(0L)

        nowMs = 2_000L
        output.stop()

        val position = output.getPositionUs()
        assertThat(media3PositionFrames(position, 44_100)).isEqualTo(frames.toLong())
        assertThat(media3HasPendingData(frames.toLong(), position, 44_100)).isFalse()
    }

    @Test
    fun `a flush after a stop starts a fresh accepted epoch`() {
        var nowMs = 1_000L
        val output = BackendAudioOutput(
            backend = GreedyBackend(),
            outputConfig = outputConfig(),
            targetBitDepth = 16,
            playWhenReady = { true },
            elapsedRealtimeMs = { nowMs },
        )
        val frames = 1_024

        output.write(stereoSilence(frames), frames, C.TIME_END_OF_SOURCE)
        output.stop()
        assertThat(media3PositionFrames(output.getPositionUs(), 44_100)).isEqualTo(frames.toLong())

        // A flush is what a mid-stream stop is followed by; Media3 zeroes its own written count
        // there too, so the position must not keep the old total.
        nowMs = 3_000L
        output.flush()

        assertThat(output.getPositionUs()).isEqualTo(0L)
    }

    @Test
    fun `a tail counts as drained only within the counter slack`() {
        val rate = 44_100
        // 744 frames in microseconds, and the frame below it.
        val atSlack = 16_880L
        val belowSlack = 16_850L

        assertThat(isTailDrained(atSlack, submittedFrames = 1_000L, pendingFrames = 0, sampleRate = rate))
            .isTrue()
        assertThat(isTailDrained(belowSlack, submittedFrames = 1_000L, pendingFrames = 0, sampleRate = rate))
            .isFalse()
        assertThat(isTailDrained(atSlack, submittedFrames = 1_000L, pendingFrames = 1, sampleRate = rate))
            .isFalse()
    }
}
