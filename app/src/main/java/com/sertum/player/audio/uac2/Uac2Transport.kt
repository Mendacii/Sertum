package com.sertum.player.audio.uac2

/**
 * JNI bridge for the UAC2 isochronous transport (libsertumuac2).
 *
 * The transport owns raw usbdevfs URBs on the file descriptor UsbDeviceConnection already holds.
 * The alt setting is deliberately not switched here: only the Java setInterface path allocates
 * isochronous bandwidth in the xHCI scheduler, so the caller does that first.
 */
object Uac2Transport {
    init {
        System.loadLibrary("sertumuac2")
    }

    /** @return a handle, or 0 when the kernel refused the stream. */
    external fun nativeOpen(
        usbFd: Int,
        endpoint: Int,
        sampleRate: Int,
        channels: Int,
        subslotBytes: Int,
        /** Isochronous IN endpoint carrying the device's 16.16 clock feedback; 0 when absent. */
        feedbackEndpoint: Int,
    ): Long

    external fun nativeStart(handle: Long): Boolean
    external fun nativeStop(handle: Long)

    /** Drops queued audio; used on seek. */
    external fun nativeFlush(handle: Long)
    external fun nativeClose(handle: Long)

    /** Queues PCM; returns bytes accepted, 0 when the queue is full. */
    external fun nativeWrite(handle: Long, data: ByteArray, offset: Int, length: Int): Int

    /** `[submitted, completed, failed, framesSent, lastErrno, underruns, feedbackSeen, ratePpm]`. */
    external fun nativeStats(handle: Long): LongArray

    /** Decodes a 16.16 feedback value to parts per million away from [sampleRate]. */
    external fun nativeFeedbackPpm(raw: Int, sampleRate: Int): Int

    /** Decodes the same value to the device's actual rate in Hz. */
    external fun nativeFeedbackRate(raw: Int, sampleRate: Int): Int
}