package com.sertum.player.audio.backend

/** JNI bridge for the production AAudio backend (libsertumaaudio). */
object AaudioNative {
    init {
        System.loadLibrary("sertumaaudio")
    }

    /**
     * Opens an output stream, requesting EXCLUSIVE on [deviceId] at [sampleRate] and [bits].
     *
     * [deviceId] is an `AudioDeviceInfo.id` naming the DAC; 0 leaves the choice to the platform.
     */
    external fun nativeOpen(sampleRate: Int, channels: Int, bits: Int, deviceId: Int): Long
    external fun nativeStart(handle: Long): Boolean
    external fun nativeWrite(handle: Long, data: ByteArray, offset: Int, length: Int): Int
    external fun nativePause(handle: Long): Boolean
    external fun nativeFlush(handle: Long): Boolean
    external fun nativeStop(handle: Long): Boolean
    external fun nativeClose(handle: Long)
    external fun nativeGetChannelCount(handle: Long): Int
    external fun nativeGetActualRate(handle: Long): Int
    external fun nativeGetActualFormat(handle: Long): Int
    external fun nativeGetSharingMode(handle: Long): Int
    external fun nativeGetDeviceId(handle: Long): Int
    external fun nativeGetPerformanceMode(handle: Long): Int
    external fun nativeGetFramesPerBurst(handle: Long): Int
    external fun nativeGetFramesRead(handle: Long): Long
}
