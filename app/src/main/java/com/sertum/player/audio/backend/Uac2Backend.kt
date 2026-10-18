package com.sertum.player.audio.backend

import com.sertum.player.audio.uac2.Uac2Session
import com.sertum.player.audio.uac2.Uac2Transport
import com.sertum.player.domain.playback.AudioOutputBackend
import com.sertum.player.domain.playback.BackendCapabilities
import com.sertum.player.domain.playback.StreamSpec

/**
 * The UAC2 backend: PCM leaves the app as isochronous USB packets, with no mixer in the path.
 *
 * Position is wall clock based rather than taken from the device. The DAC does publish a feedback
 * endpoint that would give the exact rate, but nothing reads it yet, and a hardware-rate clock would
 * only be better by the crystal's tolerance over a track; the wall clock cannot drift far enough to
 * matter for a seek bar.
 */
class Uac2Backend(private val session: Uac2Session) : AudioOutputBackend {

    override var capabilities =
        BackendCapabilities(supportsHardwareVolume = false, isExclusive = false)
        private set

    private var stream: Uac2Session.OpenStream? = null
    private var frameBytes = 0
    private var sampleRate = 0
    private val position = com.sertum.player.audio.uac2.PositionClock()

    /**
     * Whether the transport is currently submitting URBs.
     *
     * Kept separately from [position] because they answer different questions: the clock can be
     * paused while the transport is still draining, and `nativeStart` refuses a stream that is
     * already running - the failure that showed up as `IllegalStateException: UAC2 start failed`
     * every time Media3 asked to play a stream that was already playing.
     */
    private var transportRunning = false

    /**
     * Invoked once when the transport reports the device is gone.
     *
     * Deliberately not surfaced as a write failure: Media3 treats that as a bad track and skips it,
     * while an unplug should pause and keep the queue.
     */
    var onDeviceLost: (() -> Unit)? = null

    private var deviceLostReported = false

    override fun open(spec: StreamSpec): Result<Unit> {
        releaseStream()
        val opened = session.openStream(spec.sampleRate, spec.channelCount, spec.bitDepth)
            ?: return Result.failure(
                IllegalStateException("UAC2 open failed at ${spec.sampleRate}Hz/${spec.bitDepth}bit"),
            )
        stream = opened
        frameBytes = spec.channelCount * opened.format.subslotBytes
        sampleRate = spec.sampleRate
        position.reset()
        transportRunning = false
        deviceLostReported = false
        capabilities = BackendCapabilities(supportsHardwareVolume = true, isExclusive = true)
        return Result.success(Unit)
    }

    override fun writePcm(frame: ByteArray, offset: Int, length: Int): Result<Int> {
        val current = stream ?: return Result.failure(IllegalStateException("no UAC2 stream"))
        if (frameBytes <= 0) return Result.failure(IllegalStateException("unknown UAC2 frame size"))
        val accepted = Uac2Transport.nativeWrite(current.handle, frame, offset, length)
        if (accepted == DEVICE_GONE) {
            // Report the loss out of band and answer "no frames accepted", never a failure: Media3
            // drains in pause(), where a WriteException is not caught and takes the process down.
            notifyDeviceLost()
            return Result.success(0)
        }
        if (accepted < 0) {
            return Result.failure(IllegalStateException("UAC2 write failed: $accepted"))
        }
        return Result.success(accepted / frameBytes)
    }

    override fun play(): Result<Unit> {
        val current = stream ?: return Result.failure(IllegalStateException("no UAC2 stream"))
        if (!transportRunning) {
            if (!Uac2Transport.nativeStart(current.handle)) {
                return Result.failure(IllegalStateException("UAC2 start failed"))
            }
            transportRunning = true
        }
        position.start()
        return Result.success(Unit)
    }

    override fun pause(): Result<Unit> {
        position.pause()
        stopTransport()
        return Result.success(Unit)
    }

    override fun stop(): Result<Unit> {
        position.pause()
        stopTransport()
        return Result.success(Unit)
    }

    override fun flush(): Result<Unit> {
        val current = stream ?: return Result.success(Unit)
        Uac2Transport.nativeFlush(current.handle)
        // A flush restarts the stream; Media3 re-anchors its own position around it.
        position.reset()
        return Result.success(Unit)
    }

    override fun release() {
        position.pause()
        releaseStream()
        capabilities = BackendCapabilities(supportsHardwareVolume = false, isExclusive = false)
    }

    /** No software volume: attenuation would change the samples this path exists to preserve. */
    override fun onVolumeChanged(volume01: Float) = Unit

    override fun getPositionUs(): Long = position.positionUs()

    /**
     * Fifty milliseconds of slack. At ten it was enough to keep playing but not enough to keep the
     * transport's ring fed, and the difference showed up as silence filling the gaps.
     */
    override fun getBufferSizeInFrames(): Int = if (sampleRate > 0) sampleRate / 20 else 0

    private fun notifyDeviceLost() {
        if (deviceLostReported) return
        deviceLostReported = true
        onDeviceLost?.invoke()
    }

    private fun stopTransport() {
        if (!transportRunning) return
        transportRunning = false
        stream?.let { runCatching { Uac2Transport.nativeStop(it.handle) } }
    }

    private fun releaseStream() {
        transportRunning = false
        val current = stream ?: return
        stream = null
        runCatching { session.closeStream(current) }
    }

    private companion object {
        /** Mirrors `kDeviceLost` in uac2_transport.cpp: the ring is fine, the device is not. */
        const val DEVICE_GONE = -2
    }
}
