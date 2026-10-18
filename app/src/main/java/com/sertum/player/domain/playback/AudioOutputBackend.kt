package com.sertum.player.domain.playback

/** Single contract every output backend implements (PRD ADR-S1). */
interface AudioOutputBackend {
    val capabilities: BackendCapabilities

    fun open(spec: StreamSpec): Result<Unit>

    /**
     * Writes as many whole frames as the backend currently accepts and
     * returns the number of frames written. 0 means "buffer full / stream
     * not started"; callers must apply backpressure and retry, never
     * busy-loop. Negative native errors are failures.
     */
    fun writePcm(frame: ByteArray, offset: Int, length: Int): Result<Int>
    fun pause(): Result<Unit>
    fun play(): Result<Unit>
    fun flush(): Result<Unit>
    fun stop(): Result<Unit>
    fun release()
    fun onVolumeChanged(volume01: Float)

    /** Playback-head position in microseconds; backends without a head return 0. */
    fun getPositionUs(): Long = 0L

    /** Backend buffer size in frames; 0 means "not applicable / unknown". */
    fun getBufferSizeInFrames(): Int = 0
}

data class StreamSpec(
    val sampleRate: Int,
    val channelCount: Int,
    val bitDepth: Int,
    val isExclusive: Boolean,
    /**
     * `AudioDeviceInfo.id` of the device to open on; 0 leaves the choice to the platform.
     *
     * Naming the device is what makes "exclusive" mean "exclusive on the DAC" rather than
     * "exclusive on whatever the default output happens to be".
     */
    val deviceId: Int = 0,
)

data class BackendCapabilities(
    val supportsHardwareVolume: Boolean,
    val isExclusive: Boolean,
)
