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
class BackendAudioOutput(
    private val backend: AudioOutputBackend,
    private val outputConfig: androidx.media3.exoplayer.audio.AudioOutputProvider.OutputConfig,
    private val targetBitDepth: Int = 24,
    private val playWhenReady: () -> Boolean = { false },
    /**
     * Append-only observation hook for the digital-path audit (PS-PLAN-003
     * T2a): receives the exact bytes about to be handed to the backend.
     *
     * Contract: the callback must not mutate the array, and a throwing
     * callback must not break playback. `null` (the production default) means
     * this adapter behaves exactly as it did before the hook existed.
     */
    private val pcmTap: ((ByteArray) -> Unit)? = null,
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

    /** Backend position at the last flush/stop; pacing is relative to this base. */
    private var positionBaseFrames = 0L

    init {
        val bitDepth = when (outputConfig.encoding) {
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_FLOAT -> targetDepth
            else -> 16
        }
        backend.open(StreamSpec(sampleRate, channels, bitDepth, isExclusive = true))
            .getOrElse { throw androidx.media3.exoplayer.audio.AudioOutputProvider.InitializationException(it) }
        batchFrames = backend.getBufferSizeInFrames().coerceAtLeast(1_024)
        pacingWindowFrames = (batchFrames * 2).coerceAtLeast(2_048)
        positionBaseFrames = backend.getPositionUs()
    }

    override fun play() {
        if (released) return
        backend.play().getOrElse {
            throw IllegalStateException("backend play failed", it)
        }
        startedOnce = true
    }

    override fun pause() {
        if (released) return
        val before = pendingFrames
        drainPending()
        backendSubmittedFrames += (before - pendingFrames).toLong()
        backend.pause().getOrElse {
            throw IllegalStateException("backend pause failed", it)
        }
        startedOnce = false
    }

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        if (released) return false
        if (!buffer.hasRemaining()) return true

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
            if (pendingFrames > 0) return false
        }

        // Total frames outstanding (Java pending + native queue) must stay
        // inside a small window, otherwise Media3 decodes far ahead of real
        // time and the renderer never observes backpressure.
        val backendPlayedFrames = (backend.getPositionUs() - positionBaseFrames).coerceAtLeast(0L)
        val backendQueuedFrames = (backendSubmittedFrames - backendPlayedFrames).coerceAtLeast(0L)
        val allowedFrames = (
            pacingWindowFrames - pendingFrames.toLong() - backendQueuedFrames
        ).coerceAtLeast(0L)
        val incomingFrames = alignedBytes / sourceFrameBytes
        val acceptFrames = minOf(incomingFrames.toLong(), batchFrames.toLong(), allowedFrames).toInt()
        if (acceptFrames <= 0) return false

        appendPacked(buffer, acceptFrames, sourceFrameBytes)
        buffer.position(buffer.position() + acceptFrames * sourceFrameBytes)

        val forceDrain = pendingFrames >= batchFrames || presentationTimeUs == C.TIME_END_OF_SOURCE
        if (forceDrain) {
            val before = pendingFrames
            drainPending()
            backendSubmittedFrames += (before - pendingFrames).toLong()
            if (pendingFrames > 0) return false
        }

        return !buffer.hasRemaining()
    }

    private fun appendPacked(buffer: ByteBuffer, frames: Int, sourceFrameBytes: Int) {
        val srcBytes = ByteArray(frames * sourceFrameBytes)
        val srcView = buffer.duplicate()
        srcView.limit(srcView.position() + srcBytes.size)
        srcView.get(srcBytes)
        val pcmBytes = when (outputConfig.encoding) {
            C.ENCODING_PCM_FLOAT -> {
                if (targetDepth == 16) {
                    PcmConversion.float32ToPacked16Le(srcBytes)
                } else {
                    PcmConversion.float32ToPacked24Le(srcBytes)
                }
            }
            else -> srcBytes
        }
        pending = if (pending.isEmpty()) pcmBytes else pending + pcmBytes
        pendingFrames += frames
    }

    private fun drainPending() {
        if (pendingFrames <= 0) return
        val expectedFrames = pending.size / packedFrameBytes
        pcmTap?.let { tap ->
            val submitted = ByteArray(pending.size)
            pending.copyInto(submitted)
            runCatching { tap(submitted) }
        }
        val writtenFrames = backend.writePcm(pending, 0, pending.size).getOrElse {
            throw androidx.media3.exoplayer.audio.AudioOutput.WriteException(
                PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
                true,
            )
        }
        val consumedFrames = writtenFrames.coerceIn(0, expectedFrames)
        if (consumedFrames <= 0) return
        val consumedBytes = consumedFrames * packedFrameBytes
        pending = if (consumedBytes >= pending.size) {
            ByteArray(0)
        } else {
            pending.copyOfRange(consumedBytes, pending.size)
        }
        pendingFrames -= consumedFrames
    }

    override fun flush() {
        if (released) return
        flushedFrames += pendingFrames
        pending = ByteArray(0)
        pendingFrames = 0
        backend.flush()
        backendSubmittedFrames = 0L
        positionBaseFrames = backend.getPositionUs()
    }

    override fun stop() {
        if (released) return
        flushedFrames += pendingFrames
        pending = ByteArray(0)
        pendingFrames = 0
        backend.stop()
        backendSubmittedFrames = 0L
        positionBaseFrames = backend.getPositionUs()
        startedOnce = false
    }

    override fun release() {
        if (released) return
        released = true
        startedOnce = false
        pending = ByteArray(0)
        pendingFrames = 0
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

    override fun getPositionUs(): Long {
        val backendUs = backend.getPositionUs()
        return backendUs + if (flushedFrames > 0) {
            flushedFrames * 1_000_000L / sampleRate
        } else {
            0L
        }
    }

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
