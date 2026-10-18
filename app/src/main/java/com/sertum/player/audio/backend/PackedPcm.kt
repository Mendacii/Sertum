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

/** Bit depth the exclusive route delivers at most; the adapter packs to 24-bit samples. */
internal const val OUTPUT_BIT_DEPTH = 24

/**
 * Whether a source at [sourceBitDepth] survives the route untouched.
 *
 * A 32-bit source is packed down to 24 bits, which the lossless path cannot call bit-perfect. An
 * unknown depth (0) counts as preserved rather than turning the badge yellow over a metadata gap.
 */
internal fun depthIsPreserved(sourceBitDepth: Int, outputBitDepth: Int = OUTPUT_BIT_DEPTH): Boolean =
    sourceBitDepth <= outputBitDepth
