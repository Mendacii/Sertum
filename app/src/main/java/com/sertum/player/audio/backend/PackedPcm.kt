package com.sertum.player.audio.backend

/**
 * Bytes in one packed frame at [bitDepth] across [channels] channels.
 *
 * The backend used to assume two channels - six bytes per 24-bit frame - so a mono 24-bit stream
 * reported half the frames it had actually written and the remaining bytes were submitted again on
 * the next call. The channel count now comes from the open stream, never from an assumption.
 */
internal fun packedFrameSize(bitDepth: Int, channels: Int): Int {
    if (channels <= 0) return 0
    return (if (bitDepth >= 24) 3 else 2) * channels
}
