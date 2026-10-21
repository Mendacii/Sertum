package com.sertum.player.audio.backend

import android.media.AudioDeviceInfo
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import com.sertum.player.domain.playback.AudioOutputBackend
import com.sertum.player.domain.playback.StreamSpec
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Media3 [androidx.media3.exoplayer.audio.AudioOutput] backed by an
 * [AudioOutputBackend]. Used for the USB-exclusive AAudio path (ADR-0001):
 * Media3 decodes to PCM, this adapter packs it to the backend's native
 * format and submits it in bounded, paced batches.
 *
 * Two device findings drive the batching and pacing below (evidence:
 * docs/evidence/m5/logcat-96k-exclusive-*.txt):
 * 1. Xiaomi's AAudio MMAP stream must not be written before
 *    `AAudioStream_requestStart`; the stream is therefore started lazily on
 *    the first write when [playWhenReady] is already true.
 * 2. The adapter must backpressure Media3 against the backend playback
 *    position. Consuming whole codec buffers without pacing makes Media3
 *    decode far ahead of real time, which the MMAP HAL on this device turns
 *    into zero-frame writes and a renderer timeout.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private const val TAG = "SertumAudio"

/**
 * How long an end-of-stream drain may stay stuck before it is reported as a real write failure.
 *
 * Media3 has its own bound - the stuck-playing detector raises ERROR_CODE_TIMEOUT after 10 s of a
 * frozen position - but by then the cause is gone: the player goes IDLE and every layer above can
 * only guess. Two seconds is long enough for a USB ring to drain at any rate in the PRD matrix
 * (the deepest buffer is a few hundred milliseconds) and short enough that a dead device is
 * reported as a dead device.
 */
internal const val EOS_DRAIN_TIMEOUT_MS = 2_000L

internal enum class EosDrainAction {
    /** Keep waiting: Media3 retries the same buffer on the next render iteration. */
    WAIT,

    /** The retry budget is spent; report a write failure instead of letting a watchdog do it. */
    FAIL,
}

/** Outcome of one end-of-stream drain attempt, including the retry deadline to carry forward. */
internal data class EosDrainStep(
    val action: EosDrainAction,
    val stalledSinceMs: Long,
)

/**
 * Pure decision for "the end-of-stream drain could not empty `pending`".
 *
 * `stalledSinceMs == 0` means the stall was not being timed yet; the first attempt stamps the
 * clock and waits, so the bound is measured from the first failure rather than from the start of
 * the stream. A negative or zero [timeoutMs] gives up immediately, which is what a unit test uses
 * to pin the failing branch without sleeping.
 */
internal fun eosDrainStep(
    pendingFrames: Int,
    stalledSinceMs: Long,
    nowMs: Long,
    timeoutMs: Long = EOS_DRAIN_TIMEOUT_MS,
): EosDrainStep {
    if (pendingFrames <= 0) return EosDrainStep(EosDrainAction.WAIT, 0L)
    val since = if (stalledSinceMs == 0L) nowMs else stalledSinceMs
    return if (nowMs - since >= timeoutMs) {
        EosDrainStep(EosDrainAction.FAIL, 0L)
    } else {
        EosDrainStep(EosDrainAction.WAIT, since)
    }
}

/** Frames -> microseconds, truncating down (the same direction as `Util.sampleCountToDurationUs`). */
internal fun framesToUs(frames: Long, sampleRate: Int): Long =
    if (sampleRate <= 0) 0L else frames * 1_000_000L / sampleRate

/** Microseconds -> frames, truncating down. */
internal fun usToFrames(us: Long, sampleRate: Int): Long =
    if (sampleRate <= 0) 0L else us * sampleRate / 1_000_000L

/**
 * How far the backend's play head may lag the accepted total before the position stops saturating.
 *
 * Deliberately small - a couple of HAL bursts at the highest rate - because this is meant to absorb
 * the granularity of the backend's counter, not audio still in flight. Saturating over a whole
 * buffer would make `AudioSink.isEnded()` true while the tail was still queued, which for this
 * player means cutting the end off a track. 256 frames is 5.8 ms at 44.1 kHz and 1.3 ms at 192 kHz.
 */
internal const val POSITION_SATURATION_SLACK_FRAMES = 256L

/**
 * The position reported to Media3, clamped the way `AudioTrackPositionTracker.getCurrentPositionUs`
 * clamps `AudioTrackAudioOutput`: never beyond the frames Media3 handed us.
 *
 * The clamp is not cosmetic. `DefaultAudioSink.isEnded()` is
 * `handledEndOfStream && !(writtenFrames > usToFrames(getPositionUs()))` - a strict comparison with
 * no tolerance - so a position that never reaches the written frames leaves the player READY with
 * `isPlaying()` true at the end of every track, and Media3 1.11's stuck-playing detector turns that
 * into ERROR_CODE_TIMEOUT ten seconds later (30 s on an emulator). Rounding is aligned with Media3:
 * it converts back with `Util.durationUsToSampleCount`, which rounds up, so truncating down here
 * still yields `positionFrames >= writtenFrames` once the clamp is reached, for any sample rate
 * below 1 MHz.
 *
 * When nothing is queued and the backend head is within [slackFrames] of the accepted total, every
 * frame but counter quantization is behind us and the position is the accepted total - the value
 * `AudioTrackPositionTracker` also returns once its estimate reaches `writtenFrames`, and the only
 * one that opens `isEnded()`. A deeper shortfall is real audio in flight or a stalled device: the
 * raw backend position is reported so the stall stays visible instead of being papered over.
 */
internal fun resolvedPositionUs(
    backendPositionUs: Long,
    flushedUs: Long,
    acceptedFrames: Long,
    submittedFrames: Long,
    pendingFrames: Int,
    sampleRate: Int,
    slackFrames: Long = POSITION_SATURATION_SLACK_FRAMES,
): Long {
    if (sampleRate <= 0) return backendPositionUs + flushedUs
    val raw = backendPositionUs + flushedUs
    // The cap is in the same epoch as `raw`: frames burned by the last flush are added to the
    // accepted frames of this stretch, so a seek does not make the position jump backwards.
    val cap = framesToUs(acceptedFrames, sampleRate) + flushedUs
    val playedFrames = usToFrames(backendPositionUs, sampleRate)
    val tailOnly = pendingFrames == 0 &&
        submittedFrames > 0L &&
        playedFrames >= submittedFrames - slackFrames
    return if (tailOnly) cap else minOf(raw, cap)
}

class BackendAudioOutput(
    private val backend: AudioOutputBackend,
    private val outputConfig: androidx.media3.exoplayer.audio.AudioOutputProvider.OutputConfig,
    private val targetBitDepth: Int = 24,
    private val playWhenReady: () -> Boolean = { false },
    /**
     * `AudioDeviceInfo.id` of the DAC this stream must open on; 0 leaves the choice to the
     * platform. Naming it is what keeps an "exclusive" stream on the device the UI claims.
     */
    private val deviceId: Int = 0,
    /**
     * Append-only observation hook for the digital-path audit (PS-PLAN-003 T2a): receives the exact
     * bytes the backend accepted, in order.
     *
     * Contract: the callback must not mutate the array, and a throwing callback must not break
     * playback. `null` (the production default) means this adapter behaves exactly as it did before
     * the hook existed. It runs after `writePcm` returns and covers only the frames the backend
     * consumed: tapping the offered buffer would replay the unconsumed remainder on the next drain
     * under backpressure, so the observer's stream would contain duplicate fragments instead of
     * contiguous PCM.
     */
    private val pcmTap: ((ByteArray) -> Unit)? = null,
    /**
     * PRD A-22 software-volume fallback. Both default to "off/unity" so every existing caller
     * (and every unit test that does not opt in) stays on the unmodified path.
     */
    private val softwareVolumeEnabled: () -> Boolean = { false },
    private val softwareVolumeGain: () -> Float = { 1f },
    /**
     * Monotonic clock for the end-of-stream drain bound. Injectable so [eosDrainStep] can be
     * exercised on the JVM without sleeping; production always uses the platform clock.
     */
    private val elapsedRealtimeMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) : androidx.media3.exoplayer.audio.AudioOutput {

    private val listeners = CopyOnWriteArrayList<androidx.media3.exoplayer.audio.AudioOutput.Listener>()
    private val sampleRate = outputConfig.sampleRate
    private val targetDepth = targetBitDepth.coerceIn(16, 24)
    private val channels = Integer.bitCount(outputConfig.channelMask).coerceAtLeast(1)
    private val packedFrameBytes = channels * when (outputConfig.encoding) {
        C.ENCODING_PCM_FLOAT -> targetDepth / 8
        C.ENCODING_PCM_24BIT -> 3
        else -> 2
    }
    private val batchFrames: Int
    private val pacingWindowFrames: Int
    private var playbackParameters = PlaybackParameters.DEFAULT
    private var volume = 1f
    private var released = false
    private var startedOnce = false

    /** Packed PCM waiting to be submitted in backend-burst-sized writes. */
    private var pending = ByteArray(0)

    private var pendingFrames = 0

    /** Frames discarded by [stop]/[flush]; keeps position ahead of Media3's written-frame count. */
    private var flushedFrames = 0L

    /** Frames handed to the native backend; paced against its playback position. */
    private var backendSubmittedFrames = 0L

    /**
     * Frames accepted from Media3 since the last [flush]/[stop] - the adapter's copy of the
     * `writtenFrames` Media3 compares the reported position against in
     * `DefaultAudioSink.hasAudioOutputPendingData`. Kept as its own counter (rather than derived
     * from `backendSubmittedFrames + pendingFrames`) because it is the number the end-of-stream
     * clamp is defined against.
     */
    private var acceptedFrames = 0L

    /** Backend position (microseconds) at the last flush/stop; pacing is relative to this base. */
    private var positionBaseUs = 0L

    /** When the current end-of-stream drain first failed to empty `pending`, or 0 when none. */
    private var eosDrainStartedMs = 0L

    /** Whether the end-of-stream bookkeeping line has been logged since the last flush/stop. */
    private var eosLogged = false

    init {
        val bitDepth = when (outputConfig.encoding) {
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_FLOAT -> targetDepth
            else -> 16
        }
        backend.open(StreamSpec(sampleRate, channels, bitDepth, isExclusive = true, deviceId = deviceId))
            .getOrElse { throw androidx.media3.exoplayer.audio.AudioOutputProvider.InitializationException(it) }
        batchFrames = backend.getBufferSizeInFrames().coerceAtLeast(1_024)
        pacingWindowFrames = (batchFrames * 2).coerceAtLeast(2_048)
        positionBaseUs = backend.getPositionUs()
    }

    override fun play() {
        android.util.Log.i(TAG, "sink.play")

        if (released) return
        // Never throw from AudioSink.play(): Media3 calls it outside a try, so a backend that cannot
        // start (the DAC left just as play was pressed) would kill the process. Leaving the stream
        // unstarted makes the next write retry, and a lost device reports itself out of band.
        if (backend.play().isSuccess) startedOnce = true
    }

    override fun pause() {
        android.util.Log.i(TAG, "sink.pause")

        if (released) return
        // AudioSink.pause() may not throw: Media3 calls it while tearing renderers down and does not
        // catch WriteException there, so a backend that failed to drain (a DAC unplugged mid-stream,
        // typically) would take the whole process down. Whatever is left stays pending.
        val before = pendingFrames
        runCatching { drainPending() }
        backendSubmittedFrames += (before - pendingFrames).toLong()
        runCatching { backend.pause() }
        startedOnce = false
    }

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        if (released) return false
        val atEndOfStream = presentationTimeUs == C.TIME_END_OF_SOURCE
        if (pendingFrames == 0) eosDrainStartedMs = 0L
        if (!buffer.hasRemaining()) {
            // Reachable on a retry, not on a first call: DefaultAudioSink.handleBuffer returns true
            // for an empty buffer before it ever reaches AudioOutput.write, but a drain that accepted
            // the whole buffer and then failed to empty `pending` is retried with remaining() == 0.
            // The answer is the pending state - "fully consumed" must never be reported while frames
            // are still queued - and the end-of-stream budget keeps running so the retry cannot spin.
            drainPending()
            if (pendingFrames > 0) {
                if (atEndOfStream) onEndOfStreamDrainStalled()
                return false
            }
            if (atEndOfStream) logEndOfStream()
            return true
        }

        // Media3's second argument is the encoded access-unit count, not the
        // channel count; the layout comes from OutputConfig.channelMask.
        val sourceFrameBytes = when (outputConfig.encoding) {
            C.ENCODING_PCM_FLOAT -> 4 * channels
            C.ENCODING_PCM_24BIT -> 3 * channels
            else -> 2 * channels
        }
        val alignedBytes = buffer.remaining() - (buffer.remaining() % sourceFrameBytes)
        if (alignedBytes <= 0) return true

        if (!startedOnce) {
            if (!playWhenReady()) return false
            backend.play().getOrElse {
                throw androidx.media3.exoplayer.audio.AudioOutput.WriteException(
                    PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
                    true,
                )
            }
            startedOnce = true
        }

        // Flush any undelivered pending data before accepting new input so the
        // frame accounting stays byte-exact and Media3 backpressure retries
        // the same source buffer.
        if (pendingFrames > 0) {
            val before = pendingFrames
            drainPending()
            backendSubmittedFrames += (before - pendingFrames).toLong()
            if (pendingFrames > 0) {
                if (atEndOfStream) onEndOfStreamDrainStalled()
                return false
            }
        }

        // Total frames outstanding (Java pending + native queue) must stay
        // inside a small window, otherwise Media3 decodes far ahead of real
        // time and the renderer never observes backpressure. Both terms are
        // frames: the backend reports microseconds, and the conversion below is
        // what the previous version forgot, leaving the queue estimate
        // permanently negative and clamped to zero - a pacing window that never
        // closed.
        val backendPlayedFrames = framesForBackendProgress()
        val backendQueuedFrames = (backendSubmittedFrames - backendPlayedFrames).coerceAtLeast(0L)
        val allowedFrames = (
            pacingWindowFrames - pendingFrames.toLong() - backendQueuedFrames
        ).coerceAtLeast(0L)
        val incomingFrames = alignedBytes / sourceFrameBytes
        val acceptFrames = minOf(incomingFrames.toLong(), batchFrames.toLong(), allowedFrames).toInt()
        if (acceptFrames <= 0) return false

        appendPacked(buffer, acceptFrames, sourceFrameBytes)
        buffer.position(buffer.position() + acceptFrames * sourceFrameBytes)

        val forceDrain = pendingFrames >= batchFrames || atEndOfStream
        if (forceDrain) {
            val before = pendingFrames
            drainPending()
            backendSubmittedFrames += (before - pendingFrames).toLong()
            if (pendingFrames > 0) {
                if (atEndOfStream) onEndOfStreamDrainStalled()
                return false
            }
        }

        if (atEndOfStream) logEndOfStream()
        return !buffer.hasRemaining()
    }

    /** Frames the backend has played since the last flush/stop, in frames (not microseconds). */
    private fun framesForBackendProgress(): Long =
        usToFrames((backend.getPositionUs() - positionBaseUs).coerceAtLeast(0L), sampleRate)

    /**
     * The end-of-stream drain could not empty `pending`: wait for the next render iteration, or
     * spend the retry budget and report a write failure.
     *
     * Without this, a tail that never reaches the device keeps `write` returning false forever.
     * Media3 then sits at READY with a frozen position and, ten seconds later, its own stuck-playing
     * detector raises ERROR_CODE_TIMEOUT - a code that says "the player stopped moving" and nothing
     * about the audio path that stopped it. A recoverable write error names the failure, and the
     * renderer path that handles it resets the sink rather than rebuilding the player.
     */
    private fun onEndOfStreamDrainStalled() {
        val step = eosDrainStep(pendingFrames, eosDrainStartedMs, elapsedRealtimeMs())
        eosDrainStartedMs = step.stalledSinceMs
        if (step.action != EosDrainAction.FAIL) return
        android.util.Log.e(
            TAG,
            "eos drain stuck: written=$acceptedFrames pos=${usToFrames(backend.getPositionUs(), sampleRate)} " +
                "submitted=$backendSubmittedFrames pending=$pendingFrames retryMs=$EOS_DRAIN_TIMEOUT_MS",
        )
        throw androidx.media3.exoplayer.audio.AudioOutput.WriteException(
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            true,
        )
    }

    /**
     * One line per end of stream. `pos` is in frames (after conversion) so it can be compared with
     * `written` directly: `pos >= written` is the condition `AudioSink.isEnded()` needs, and the
     * next device report can answer "did the sink end" without a bytecode reading.
     */
    private fun logEndOfStream() {
        if (eosLogged) return
        eosLogged = true
        android.util.Log.i(
            TAG,
            "eos written=$acceptedFrames pos=${usToFrames(backend.getPositionUs(), sampleRate)} " +
                "submitted=$backendSubmittedFrames pending=$pendingFrames",
        )
    }

    private fun appendPacked(buffer: ByteBuffer, frames: Int, sourceFrameBytes: Int) {
        val srcBytes = ByteArray(frames * sourceFrameBytes)
        val srcView = buffer.duplicate()
        srcView.limit(srcView.position() + srcBytes.size)
        srcView.get(srcBytes)
        val pcmBytes = when (outputConfig.encoding) {
            C.ENCODING_PCM_FLOAT -> {
                if (softwareVolumeEnabled()) {
                    // This is the deliberate non-bit-perfect path: the float sample is scaled
                    // before packing, and only the system music-volume index feeds the gain.
                    val gain = softwareVolumeGain()
                    if (targetDepth == 16) {
                        PcmConversion.float32ToPacked16Le(srcBytes, gain)
                    } else {
                        PcmConversion.float32ToPacked24Le(srcBytes, gain)
                    }
                } else {
                    // Byte-for-byte the pre-software-volume route. Do not fold the gain branch
                    // into this call site: disabled must not multiply even by 1.0f.
                    if (targetDepth == 16) {
                        PcmConversion.float32ToPacked16Le(srcBytes)
                    } else {
                        PcmConversion.float32ToPacked24Le(srcBytes)
                    }
                }
            }
            else -> {
                if (softwareVolumeEnabled()) {
                    val gain = softwareVolumeGain()
                    when (outputConfig.encoding) {
                        C.ENCODING_PCM_24BIT -> PcmConversion.applyGainPacked24Le(srcBytes, gain)
                        C.ENCODING_PCM_16BIT -> PcmConversion.applyGainPacked16Le(srcBytes, gain)
                        else -> srcBytes
                    }
                } else {
                    // Disabled: already-packed PCM is forwarded byte for byte.
                    srcBytes
                }
            }
        }
        pending = if (pending.isEmpty()) pcmBytes else pending + pcmBytes
        pendingFrames += frames
        acceptedFrames += frames
    }

    private fun drainPending() {
        if (pendingFrames <= 0) return
        val expectedFrames = pending.size / packedFrameBytes
        val writtenFrames = backend.writePcm(pending, 0, pending.size).getOrElse { failure ->
            android.util.Log.w(TAG, "write failed: ${'$'}{failure.message}")
            throw androidx.media3.exoplayer.audio.AudioOutput.WriteException(
                PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
                true,
            )
        }
        val consumedFrames = writtenFrames.coerceIn(0, expectedFrames)
        if (consumedFrames <= 0) return
        val consumedBytes = consumedFrames * packedFrameBytes
        pcmTap?.let { tap ->
            // Only the accepted frames, and only after the backend took them: under backpressure the
            // unconsumed rest of `pending` is drained again, and re-tapping it would duplicate
            // fragments in the observation stream.
            runCatching { tap(pending.copyOf(consumedBytes)) }
        }
        pending = if (consumedBytes >= pending.size) {
            ByteArray(0)
        } else {
            pending.copyOfRange(consumedBytes, pending.size)
        }
        pendingFrames -= consumedFrames
    }

    override fun flush() {
        android.util.Log.i(TAG, "sink.flush")

        if (released) return
        flushedFrames += pendingFrames
        pending = ByteArray(0)
        pendingFrames = 0
        // Media3 zeroes its written-frame count on flush, and the end-of-stream clamp is defined
        // against that count: a counter that survived a seek would report a position past the frames
        // of the new stretch and make the sink look drained before it had played anything.
        acceptedFrames = 0L
        eosDrainStartedMs = 0L
        eosLogged = false
        backend.flush()
        backendSubmittedFrames = 0L
        positionBaseUs = backend.getPositionUs()
    }

    override fun stop() {
        android.util.Log.i(TAG, "sink.stop")

        if (released) return
        flushedFrames += pendingFrames
        pending = ByteArray(0)
        pendingFrames = 0
        acceptedFrames = 0L
        eosDrainStartedMs = 0L
        eosLogged = false
        backend.stop()
        backendSubmittedFrames = 0L
        positionBaseUs = backend.getPositionUs()
        startedOnce = false
    }

    override fun release() {
        android.util.Log.i(TAG, "sink.release")

        if (released) return
        released = true
        startedOnce = false
        pending = ByteArray(0)
        pendingFrames = 0
        acceptedFrames = 0L
        eosDrainStartedMs = 0L
        backend.release()
        listeners.forEach { it.onReleased() }
        listeners.clear()
    }

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        backend.onVolumeChanged(this.volume)
    }

    override fun isOffloadedPlayback(): Boolean = false

    override fun getAudioSessionId(): Int = outputConfig.audioSessionId

    override fun getSampleRate(): Int = sampleRate

    override fun getBufferSizeInFrames(): Long = backend.getBufferSizeInFrames().toLong()

    /**
     * The position Media3 compares against its written-frame count to decide the sink has ended.
     *
     * `flushedUs` keeps a seek from looking like a jump backwards: those frames were handed to the
     * backend before the flush and are not part of the post-flush accepted count.
     */
    override fun getPositionUs(): Long = resolvedPositionUs(
        backendPositionUs = backend.getPositionUs(),
        flushedUs = if (flushedFrames > 0) framesToUs(flushedFrames, sampleRate) else 0L,
        acceptedFrames = acceptedFrames,
        submittedFrames = backendSubmittedFrames,
        pendingFrames = pendingFrames,
        sampleRate = sampleRate,
    )

    override fun getPlaybackParameters(): PlaybackParameters = playbackParameters

    override fun isStalled(): Boolean = false

    override fun addListener(listener: androidx.media3.exoplayer.audio.AudioOutput.Listener) {
        listeners.addIfAbsent(listener)
    }

    override fun removeListener(listener: androidx.media3.exoplayer.audio.AudioOutput.Listener) {
        listeners.remove(listener)
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        this.playbackParameters = playbackParameters
    }

    override fun setOffloadDelayPadding(delayPaddingFrames: Int, delayPaddingForSpeedChangeFrames: Int) = Unit

    override fun setOffloadEndOfStream() = Unit

    override fun attachAuxEffect(auxEffectId: Int) = Unit

    override fun setAuxEffectSendLevel(sendLevel: Float) = Unit

    override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) = Unit
}
