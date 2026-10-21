package com.sertum.player.audio.backend

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Float PCM (native-endian IEEE-754) to packed 24-bit little-endian PCM.
 * Every 24-bit integer is exactly representable in float32, so 24-bit source
 * material survives the conversion bit-exactly.
 */
object PcmConversion {

    /**
     * Bit-perfect path: no gain multiplication at all. This is the function the exclusive route
     * calls when software volume is disabled; keeping it separate is what makes "disabled means
     * byte-identical" checkable rather than merely asserted.
     */
    fun float32ToPacked24Le(floatPcm: ByteArray): ByteArray = pack24(floatPcm, gain = null)

    /** Software-volume path: scales the float sample before packing to 24-bit. */
    fun float32ToPacked24Le(floatPcm: ByteArray, gain: Float): ByteArray =
        pack24(floatPcm, gain.coerceIn(0f, 1f))

    fun float32ToPacked16Le(floatPcm: ByteArray): ByteArray = pack16(floatPcm, gain = null)

    /** Software-volume path for 16-bit targets; disabled callers use the overload above. */
    fun float32ToPacked16Le(floatPcm: ByteArray, gain: Float): ByteArray =
        pack16(floatPcm, gain.coerceIn(0f, 1f))

    /**
     * Software-volume path for already-packed integer PCM. Media3 does not force every source
     * through float (16-bit and 24-bit raw sources are delivered directly), and the fallback has to
     * work for those too. Disabled callers never reach this function.
     */
    fun applyGainPacked24Le(pcm: ByteArray, gain: Float): ByteArray = applyGain(pcm, 3, gain)

    fun applyGainPacked16Le(pcm: ByteArray, gain: Float): ByteArray = applyGain(pcm, 2, gain)

    private fun pack24(floatPcm: ByteArray, gain: Float?): ByteArray {
        val floatView = ByteBuffer.wrap(floatPcm).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val out = ByteArray(floatView.remaining() * 3)
        var outIndex = 0
        while (floatView.hasRemaining()) {
            val raw = floatView.get()
            val sample = (if (gain == null) raw else raw * gain).coerceIn(-1f, 1f)
            val scaled = when {
                sample >= 1f -> MAX_24BIT
                sample <= -1f -> -MAX_24BIT - 1
                else -> (sample * (MAX_24BIT + 1)).toInt()
            }
            out[outIndex++] = (scaled and 0xFF).toByte()
            out[outIndex++] = ((scaled shr 8) and 0xFF).toByte()
            out[outIndex++] = ((scaled shr 16) and 0xFF).toByte()
        }
        return out
    }

    private fun pack16(floatPcm: ByteArray, gain: Float?): ByteArray {
        val floatView = ByteBuffer.wrap(floatPcm).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val out = ByteArray(floatView.remaining() * 2)
        var outIndex = 0
        while (floatView.hasRemaining()) {
            val raw = floatView.get()
            val sample = (if (gain == null) raw else raw * gain).coerceIn(-1f, 1f)
            val scaled = when {
                sample >= 1f -> MAX_16BIT
                sample <= -1f -> -MAX_16BIT - 1
                else -> (sample * (MAX_16BIT + 1)).toInt()
            }
            out[outIndex++] = (scaled and 0xFF).toByte()
            out[outIndex++] = ((scaled shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun applyGain(pcm: ByteArray, bytesPerSample: Int, gain: Float): ByteArray {
        val clampedGain = gain.coerceIn(0f, 1f)
        val out = pcm.copyOf()
        val max = if (bytesPerSample == 2) MAX_16BIT else MAX_24BIT
        val min = -max - 1
        var at = 0
        while (at + bytesPerSample <= pcm.size) {
            val raw = when (bytesPerSample) {
                2 -> {
                    val value = (pcm[at].toInt() and 0xFF) or
                        ((pcm[at + 1].toInt() and 0xFF) shl 8)
                    if (value >= 0x8000) value - 0x10000 else value
                }
                else -> {
                    val value = (pcm[at].toInt() and 0xFF) or
                        ((pcm[at + 1].toInt() and 0xFF) shl 8) or
                        ((pcm[at + 2].toInt() and 0xFF) shl 16)
                    if (value and 0x800000 != 0) value - 0x1000000 else value
                }
            }
            val scaled = (raw * clampedGain).toInt().coerceIn(min, max)
            for (byte in 0 until bytesPerSample) {
                out[at + byte] = ((scaled shr (8 * byte)) and 0xFF).toByte()
            }
            at += bytesPerSample
        }
        return out
    }

    private const val MAX_16BIT = 32_767
    private const val MAX_24BIT = 8_388_607
}
