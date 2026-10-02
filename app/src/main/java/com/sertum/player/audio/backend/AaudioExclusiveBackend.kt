package com.sertum.player.audio.backend

import android.util.Log
import com.sertum.player.domain.playback.AudioOutputBackend
import com.sertum.player.domain.playback.BackendCapabilities
import com.sertum.player.domain.playback.StreamSpec

/** Observed native stream parameters, used for evidence and UI badges. */
data class AaudioStreamInfo(
    val actualRate: Int = 0,
    val actualFormat: Int = 0,
    val sharingMode: Int = 0,
    val deviceId: Int = 0,
    val performanceMode: Int = 0,
    val framesPerBurst: Int = 0,
) {
    val isExclusive: Boolean get() = sharingMode == 1
    val is24Bit: Boolean get() = actualFormat == 3
}

/**
 * USB-exclusive backend built on native AAudio EXCLUSIVE streams (ADR-0001).
 * The native layer requests EXCLUSIVE and falls back to SHARED only when the
 * device refuses; the resulting sharing mode is surfaced in [streamInfo].
 */
class AaudioExclusiveBackend : AudioOutputBackend {

    companion object {
        private const val TAG = "SertumAudio"
        private const val EXCLUSIVE = 1
    }

    @Volatile
    private var handle = 0L

    @Volatile
    private var started = false

    private var spec: StreamSpec? = null

    override var capabilities = BackendCapabilities(supportsHardwareVolume = false, isExclusive = false)
        private set

    var streamInfo = AaudioStreamInfo()
        private set

    override fun open(spec: StreamSpec): Result<Unit> {
        close()
        started = false
        this.spec = spec
        val opened = AaudioNative.nativeOpen(spec.sampleRate, spec.channelCount, spec.bitDepth)
        if (opened == 0L) return Result.failure(IllegalStateException("AAudio open failed"))
        handle = opened
        streamInfo = AaudioStreamInfo(
            actualRate = AaudioNative.nativeGetActualRate(handle),
            actualFormat = AaudioNative.nativeGetActualFormat(handle),
            sharingMode = AaudioNative.nativeGetSharingMode(handle),
            deviceId = AaudioNative.nativeGetDeviceId(handle),
            performanceMode = AaudioNative.nativeGetPerformanceMode(handle),
            framesPerBurst = AaudioNative.nativeGetFramesPerBurst(handle),
        )
        capabilities = BackendCapabilities(
            supportsHardwareVolume = false,
            isExclusive = streamInfo.isExclusive,
        )
        Log.i(
            TAG,
            "opened spec=$spec actual=$streamInfo exclusive=${streamInfo.isExclusive}",
        )
        return Result.success(Unit)
    }

    override fun writePcm(frame: ByteArray, offset: Int, length: Int): Result<Int> {
        val bytesPerFrame = if (streamInfo.is24Bit) 6 else 4
        val expected = length / bytesPerFrame
        val written = AaudioNative.nativeWrite(handle, frame, offset, length)
        if (written < 0) {
            return Result.failure(IllegalStateException("AAudio write failed res=$written expected=$expected"))
        }
        return Result.success(written.coerceAtMost(expected))
    }

    override fun pause(): Result<Unit> {
        if (!started) return Result.success(Unit)
        return if (AaudioNative.nativePause(handle)) {
            started = false
            Log.i(TAG, "paused handle=$handle")
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("AAudio pause failed"))
        }
    }

    override fun play(): Result<Unit> {
        if (started) return Result.success(Unit)
        return if (AaudioNative.nativeStart(handle)) {
            started = true
            Log.i(TAG, "started handle=$handle")
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("AAudio start failed"))
        }
    }

    override fun flush(): Result<Unit> =
        if (AaudioNative.nativeFlush(handle)) Result.success(Unit)
        else Result.failure(IllegalStateException("AAudio flush failed"))

    override fun stop(): Result<Unit> {
        if (!started) return Result.success(Unit)
        return if (AaudioNative.nativeStop(handle)) {
            started = false
            Log.i(TAG, "stopped handle=$handle")
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("AAudio stop failed"))
        }
    }

    override fun release() = close()

    override fun onVolumeChanged(volume01: Float) {
        // Software volume is intentionally not applied on this path (PRD 7.3).
    }

    override fun getPositionUs(): Long {
        val active = handle
        if (active == 0L) return 0L
        val framesRead = AaudioNative.nativeGetFramesRead(active)
        val rate = streamInfo.actualRate.takeIf { it > 0 } ?: spec?.sampleRate ?: 48_000
        return framesRead * 1_000_000L / rate
    }

    override fun getBufferSizeInFrames(): Int =
        streamInfo.framesPerBurst.takeIf { it > 0 }?.times(2) ?: 0

    /**
     * Last observed native stream parameters. Exposed for the digital-path
     * audit (PS-PLAN-003 T2c): `sharingMode`/`deviceId` are the only proof
     * that the route is genuinely exclusive *and* bound to the DAC.
     */
    fun observedStreamInfo(): AaudioStreamInfo = streamInfo

    private fun close() {
        started = false
        if (handle != 0L) {
            AaudioNative.nativeClose(handle)
            handle = 0L
        }
        spec = null
    }
}
