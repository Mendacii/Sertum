package com.sertum.player.audio.uac2

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the attached DAC exposes a usable UAC2 hardware-volume control.
 *
 * Null means "not observed yet" (no UAC2 session, or the route has been torn down). The settings
 * page only shows the "adjust it on the DAC" note when this is explicitly false.
 */
object Uac2HardwareVolumeStatus {
    private val _supported = MutableStateFlow<Boolean?>(null)
    val supported: StateFlow<Boolean?> = _supported.asStateFlow()

    internal fun set(supported: Boolean) {
        _supported.value = supported
    }

    internal fun clear() {
        _supported.value = null
    }
}

/**
 * The raw value range of a UAC2 Feature Unit volume control.
 *
 * UAC2 expresses volume as a signed 16-bit fixed-point value in 1/256 dB units. The device is the
 * only authority on the endpoints: the driver reads GET_MIN and GET_MAX before it is allowed to
 * write anything, because a guessed range would be exactly how an inaudible setting becomes a
 * full-scale one.
 */
data class Uac2VolumeRange(
    val minRaw: Int,
    val maxRaw: Int,
    val resolutionRaw: Int? = null,
) {
    val isValid: Boolean get() = maxRaw > minRaw

    /** Width of the proven range, in raw 1/256 dB steps. */
    val span: Int get() = maxRaw - minRaw

    /**
     * Maps [raw] onto 0..100 using the *proven* endpoints. Values outside the range are clamped:
     * a device that reports a current value slightly outside its own advertised range must not
     * make the safety policy divide by a negative span.
     */
    fun percent(raw: Int): Int? {
        if (!isValid) return null
        val clamped = raw.coerceIn(minRaw, maxRaw)
        return (((clamped.toLong() - minRaw) * 100L) / span).toInt()
    }

    fun clamp(raw: Int): Int = raw.coerceIn(minRaw, maxRaw)

    fun contains(raw: Int): Boolean = raw in minRaw..maxRaw
}

data class Uac2VolumeObservation(
    val currentRaw: Int,
    val range: Uac2VolumeRange,
)

enum class Uac2VolumeAction {
    /** Current value is below the danger threshold and there is no history: record it. */
    RECORD_CURRENT,

    /** Below the danger threshold and a history exists: record the new current value. */
    KEEP_CURRENT,

    /** At/above the danger threshold with a usable history: write the history value. */
    RESTORE,

    /**
     * At/above the danger threshold with no history: write the -40 dB fail-safe point.
     *
     * This is the one case where a value that was never observed on this reader's DAC is written.
     * Leaving full scale untouched would reproduce the reported injury on a first connection, so
     * the policy chooses `clamp(-40 dB)` inside the *proven* range. A percentage of a narrow range
     * can still be loud, so the fail-safe is absolute in dB, not relative to the range width.
     */
    FIRST_RUN_FAIL_SAFE,

    /** GET_MIN/GET_MAX did not produce a usable range. */
    UNPROVEN_RANGE,

    /** Current value already equals history; nothing to write. */
    ALREADY_AT_HISTORY,

    /** History is outside the range this device currently reports. */
    HISTORY_OUT_OF_RANGE,
}

data class Uac2VolumeDecision(
    val action: Uac2VolumeAction,
    val targetRaw: Int? = null,
)

/**
 * Per-stream rate limit for post-start corrections: at most [maxCorrections] writes, at least
 * [minimumIntervalMs] apart. Extracted so the monitor policy can be tested without a DAC.
 */
class Uac2VolumeCorrectionGate(
    private val maxCorrections: Int = 3,
    private val minimumIntervalMs: Long = 2_000L,
) {
    private var corrections = 0
    private var lastCorrectionAtMs = 0L

    @Synchronized
    fun tryAcquire(nowMs: Long): Boolean {
        if (corrections >= maxCorrections) return false
        if (lastCorrectionAtMs != 0L && nowMs - lastCorrectionAtMs < minimumIntervalMs) {
            return false
        }
        corrections += 1
        lastCorrectionAtMs = nowMs
        return true
    }

    @Synchronized
    fun reset() {
        corrections = 0
        lastCorrectionAtMs = 0L
    }

    @Synchronized
    fun count(): Int = corrections
}

/**
 * Pure safety policy for restoring a DAC's hardware volume after a replug.
 *
 * The policy is intentionally conservative:
 * - no range proof => no write;
 * - danger is absolute as well as relative: raw >= -6 dB or >=95% of the proven range;
 * - no history and current below the danger threshold => record only;
 * - no history and current at/above the danger threshold => first-run fail-safe at
 *   `clamp(-40 dB)`, not a percentage of a possibly narrow range;
 * - history outside the current proven range => no write;
 * - a restore write is requested only when the current value is dangerous and the history is
 *   actually lower; the transport's fresh-GET guard is the final authority.
 */
object Uac2VolumePolicy {
    const val RESTORE_AT_OR_ABOVE_PERCENT = 95

    /** -6 dB in UAC2's signed 1/256 dB units. */
    const val DANGER_RAW = -6 * 256

    /** -40 dB in UAC2's signed 1/256 dB units; clamped to the device's proven range. */
    const val FAIL_SAFE_RAW = -40 * 256

    /** Absolute-or-relative danger test. The absolute arm catches a narrow range whose 95% is loud. */
    fun isDangerous(currentRaw: Int, range: Uac2VolumeRange): Boolean {
        if (!range.isValid) return false
        val currentPercent = range.percent(currentRaw) ?: return false
        return currentRaw >= DANGER_RAW || currentPercent >= RESTORE_AT_OR_ABOVE_PERCENT
    }

    /** The first-run target: -40 dB, clamped to the quiet end of the proven range. */
    fun failSafeRaw(range: Uac2VolumeRange): Int =
        if (range.isValid) range.clamp(FAIL_SAFE_RAW) else range.minRaw

    /**
     * The M1 transport guard: a SET_CUR is allowed only inside the proven range and only for a
     * target strictly below the fresh GET_CUR. A write-up is never allowed.
     */
    fun canWriteDown(
        target: Int,
        freshCurrent: Int,
        range: Uac2VolumeRange,
    ): Boolean = range.isValid && range.contains(target) && target < freshCurrent

    /**
     * Target for a dangerous post-start jump. Returns null when the jump is not dangerous or when
     * there is no value lower than the current one to write. The transport still re-reads GET_CUR
     * and is the final down-only gate.
     */
    fun correctionTarget(
        currentRaw: Int,
        range: Uac2VolumeRange,
        safeTargetRaw: Int?,
    ): Int? {
        if (!isDangerous(currentRaw, range)) return null
        val target = safeTargetRaw ?: failSafeRaw(range)
        if (target >= currentRaw) return null
        return range.clamp(target)
    }

    fun decide(
        currentRaw: Int,
        range: Uac2VolumeRange,
        historyRaw: Int?,
    ): Uac2VolumeDecision {
        if (!range.isValid) return Uac2VolumeDecision(Uac2VolumeAction.UNPROVEN_RANGE)
        val dangerous = isDangerous(currentRaw, range)

        if (historyRaw == null) {
            return if (dangerous) {
                Uac2VolumeDecision(
                    Uac2VolumeAction.FIRST_RUN_FAIL_SAFE,
                    targetRaw = failSafeRaw(range),
                )
            } else {
                Uac2VolumeDecision(Uac2VolumeAction.RECORD_CURRENT)
            }
        }

        if (!range.contains(historyRaw)) {
            return Uac2VolumeDecision(Uac2VolumeAction.HISTORY_OUT_OF_RANGE)
        }
        if (!dangerous) {
            return Uac2VolumeDecision(Uac2VolumeAction.KEEP_CURRENT)
        }
        if (historyRaw == currentRaw) {
            return Uac2VolumeDecision(Uac2VolumeAction.ALREADY_AT_HISTORY)
        }
        // Never request a write-up. If history is above the current value, the current value is
        // already the safer one; record it instead.
        if (historyRaw > currentRaw) {
            return Uac2VolumeDecision(Uac2VolumeAction.KEEP_CURRENT)
        }
        return Uac2VolumeDecision(Uac2VolumeAction.RESTORE, targetRaw = range.clamp(historyRaw))
    }
}

/** Decodes a signed 16-bit little-endian UAC2 volume value. */
internal fun decodeVolumeRaw(bytes: ByteArray): Int? {
    if (bytes.size < 2) return null
    val unsigned = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
    return if (unsigned >= 0x8000) unsigned - 0x10000 else unsigned
}

/** Decodes the unsigned 16-bit resolution value returned by GET_RES. */
internal fun decodeVolumeResolution(bytes: ByteArray): Int? {
    if (bytes.size < 2) return null
    return (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
}

/**
 * Encodes a signed raw value for SET_CUR. The value is clamped to the signed 16-bit domain: the
 * caller has already checked the device's own GET_MIN/GET_MAX, so this is only a wire-format
 * guard, not a substitute for the range proof.
 */
internal fun encodeVolumeRaw(raw: Int): ByteArray {
    val value = raw.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
    return byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
    )
}
