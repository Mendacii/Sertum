package com.sertum.player.audio.uac2

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.os.SystemClock

/**
 * One claimed DAC: the connection, its descriptor facts, and the open isochronous stream.
 *
 * Claiming the audio interfaces detaches the kernel's `snd-usb-audio`, which is what makes the
 * platform stop driving the device. From then on the alt setting and the clock are ours to set.
 */
class Uac2Session internal constructor(
    val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    val descriptors: Uac2DescriptorInfo,
    private val interfaces: List<UsbInterface>,
) {
    val fileDescriptor: Int get() = connection.fileDescriptor

    /**
     * When the session was created, which is immediately after the interface claim.
     *
     * The volume monitor reports offsets against this instant so an asynchronous DAC volume jump
     * can be lined up with the claim/start timeline in diagnostics.
     */
    val claimedAtElapsedMs: Long = SystemClock.elapsedRealtime()

    /** Where claim/alt/clock outcomes are reported, so a failed step is never silent. */
    var onEvent: ((String) -> Unit)? = null

    /** Reports a driver fact to the diagnostic owner without making it a playback failure. */
    fun report(message: String) {
        onEvent?.invoke(message)
    }

    /**
     * Whether a SET_CUR is allowed at all.
     *
     * The descriptor's D1 bit, or a successful runtime GET range (real-device proof), is required.
     * The DAWN PRO2 advertises no volume bit and STALLs GET_CUR; this returns false and no write is
     * ever attempted.
     */
    fun hasVolumeControl(): Boolean =
        descriptors.featureUnit?.supportsVolume == true || runtimeVolumeProven

    @Volatile
    private var runtimeVolumeProven = false

    data class OpenStream(val handle: Long, val format: Uac2Format, val sampleRate: Int)

    /**
     * Reads the Feature Unit's current volume and the range that makes it meaningful.
     *
     * GET_CUR alone is not enough to write anything: without GET_MIN/GET_MAX there is no way to
     * know whether a value is quiet or full scale. The three required transfers are therefore
     * treated as one observation, and a failure in any of them returns null so the caller can
     * degrade to "read-only, do not touch the DAC". GET_RES is best-effort and only logged.
     *
     * This runs after the interface claim (the connection exists) and before the isochronous
     * stream is opened, so a failure here cannot race the audio stream.
     */
    @Synchronized
    fun observeVolume(): Uac2VolumeObservation? {
        val unit = descriptors.featureUnit
        if (unit == null) {
            report("volume: descriptor has no FEATURE_UNIT")
            return null
        }
        val index = volumeIndex(unit.unitId) ?: run {
            report("volume: no AudioControl interface number")
            return null
        }
        val current = readVolumeValue(index, REQUEST_GET_CUR, "GET_CUR") ?: return null
        val min = readVolumeValue(index, REQUEST_GET_MIN, "GET_MIN") ?: return null
        val max = readVolumeValue(index, REQUEST_GET_MAX, "GET_MAX") ?: return null
        val resolution = readVolumeValue(index, REQUEST_GET_RES, "GET_RES", unsigned = true)
        val range = Uac2VolumeRange(min, max, resolution)
        if (range.isValid) runtimeVolumeProven = true
        report(
            "volume: unit=0x${unit.unitId.toString(16)} descriptorVolume=${unit.supportsVolume} " +
                "current=$current min=$min max=$max " +
                "res=${resolution ?: "unavailable"} percent=${range.percent(current) ?: "invalid"}",
        )
        return Uac2VolumeObservation(currentRaw = current, range = range)
    }

    /** Fresh GET_CUR: the write-down gate before SET_CUR, and the readback check after it. */
    @Synchronized
    fun readVolumeCurrent(): Int? {
        val unit = descriptors.featureUnit ?: return null
        val index = volumeIndex(unit.unitId) ?: return null
        return readVolumeValue(index, REQUEST_GET_CUR, "GET_CUR(fresh)")
    }

    /**
     * One quiet GET_CUR for the 500ms volume monitor.
     *
     * The monitor itself owns the "log a failure once" decision; this method must not write a line
     * per poll when a DAC STALLs the request.
     */
    @Synchronized
    fun readVolumeForMonitor(): Int? {
        val unit = descriptors.featureUnit ?: return null
        val index = volumeIndex(unit.unitId) ?: return null
        return readVolumeValue(
            index = index,
            request = REQUEST_GET_CUR,
            label = "GET_CUR(monitor)",
            logFailure = false,
        )
    }

    /**
     * Writes a raw volume value only when all of the following hold:
     *
     * - the Feature Unit advertises Volume (D1) or a runtime GET range proved it;
     * - the range is valid and contains the target;
     * - a fresh GET_CUR, taken by the caller immediately before this call, is still greater than
     *   the target. This is the write-down-only rule: a range change, a different device behind
     *   the same vendorId/productId, or a TOCTOU change between GET and SET can never make this
     *   method raise the volume.
     *
     * The range and fresh-current parameters are deliberately part of the signature so a caller
     * cannot reach SET_CUR without carrying both proofs.
     */
    @Synchronized
    fun writeVolume(raw: Int, range: Uac2VolumeRange, freshCurrent: Int): Boolean {
        val unit = descriptors.featureUnit ?: run {
            report("volume: SET_CUR skipped, no FEATURE_UNIT")
            return false
        }
        val index = volumeIndex(unit.unitId) ?: run {
            report("volume: SET_CUR skipped, no AudioControl interface number")
            return false
        }
        if (!hasVolumeControl()) {
            report("volume: SET_CUR refused, no advertised or runtime-proven volume control")
            return false
        }
        if (!Uac2VolumePolicy.canWriteDown(raw, freshCurrent, range)) {
            report(
                "volume: SET_CUR refused raw=$raw fresh=$freshCurrent " +
                    "range=${range.minRaw}..${range.maxRaw} (write-down only)",
            )
            return false
        }
        val payload = encodeVolumeRaw(raw)
        val written = runCatching {
            connection.controlTransfer(
                REQUEST_TYPE_CLASS_INTERFACE_OUT,
                REQUEST_SET_CUR,
                VOLUME_CONTROL,
                index,
                payload,
                payload.size,
                VOLUME_TIMEOUT_MS,
            )
        }.getOrElse {
            report("volume: SET_CUR failed: ${it.message}")
            return false
        }
        val ok = written == payload.size
        report("volume: SET_CUR raw=$raw ok=$ok written=$written")
        return ok
    }

    /**
     * Switches to the format that carries [bitDepth], sets the clock, and opens the transport.
     *
     * The alt setting goes through the Java `setInterface` call on purpose: only that path
     * allocates isochronous bandwidth in the xHCI scheduler, and without the allocation the URBs
     * complete without anything reaching the bus.
     */
    @Synchronized
    fun openStream(sampleRate: Int, channels: Int, bitDepth: Int): OpenStream? {
        val format = descriptors.formatForBitDepth(bitDepth) ?: return null
        val target = interfaces.firstOrNull {
            it.alternateSetting == format.alternateSetting && it.interfaceSubclass == SUBCLASS_AUDIOSTREAMING
        } ?: return null
        if (!connection.setInterface(target)) {
            onEvent?.invoke("setInterface(alt=${format.alternateSetting}) refused")
            return null
        }
        val clockWritten = setSampleRate(sampleRate)
        onEvent?.invoke(
            "alt=${format.alternateSetting} ${format.bitDepth}bit subslot=${format.subslotBytes} " +
                "rate=$sampleRate clockWritten=$clockWritten",
        )
        val handle = Uac2Transport.nativeOpen(
            usbFd = connection.fileDescriptor,
            endpoint = format.endpointAddress,
            sampleRate = sampleRate,
            channels = channels,
            subslotBytes = format.subslotBytes,
            feedbackEndpoint = descriptors.feedbackEndpoint ?: 0,
        )
        if (handle == 0L) {
            runCatching { setIdle() }
            return null
        }
        return OpenStream(handle, format, sampleRate)
    }

    @Synchronized
    fun closeStream(stream: OpenStream) {
        Uac2Transport.nativeStop(stream.handle)
        Uac2Transport.nativeClose(stream.handle)
        runCatching { setIdle() }
    }

    /** Back to the zero-bandwidth alt setting, so the device stops expecting a stream. */
    private fun setIdle() {
        interfaces.firstOrNull {
            it.interfaceSubclass == SUBCLASS_AUDIOSTREAMING && it.alternateSetting == 0
        }?.let { connection.setInterface(it) }
    }

    private fun volumeIndex(unitId: Int): Int? {
        val acInterface = descriptors.audioControlInterfaceNumber ?: return null
        // UAC2 control requests address the entity in the high byte and the AudioControl
        // interface number in the low byte.
        return (unitId shl 8) or acInterface
    }

    /**
     * One Feature Unit control transfer. A negative return is the platform's STALL/errno path;
     * both are logged with the request name so a device that does not implement volume shows up
     * in diagnostics rather than failing silently.
     */
    private fun readVolumeValue(
        index: Int,
        request: Int,
        label: String,
        unsigned: Boolean = false,
        logFailure: Boolean = true,
    ): Int? {
        val buffer = ByteArray(VOLUME_BYTES)
        val read = runCatching {
            connection.controlTransfer(
                REQUEST_TYPE_CLASS_INTERFACE_IN,
                request,
                VOLUME_CONTROL,
                index,
                buffer,
                buffer.size,
                VOLUME_TIMEOUT_MS,
            )
        }.getOrElse {
            if (logFailure) report("volume: $label failed: ${it.message}")
            return null
        }
        if (read != buffer.size) {
            if (logFailure) report("volume: $label failed r=$read")
            return null
        }
        return if (unsigned) decodeVolumeResolution(buffer) else decodeVolumeRaw(buffer)
    }

    /**
     * UAC2 clock request: SET_CUR sampling frequency on the clock source entity.
     *
     * The device takes the write and does not answer reads (GET_CUR stalls on this DAC), so the
     * rate is trusted rather than read back; the transport's per-microframe sizing follows the same
     * value.
     */
    private fun setSampleRate(rate: Int): Boolean {
        val clockId = descriptors.clockSourceId ?: return false
        val acInterface = descriptors.audioControlInterfaceNumber ?: return false
        val index = (clockId shl 8) or acInterface
        val payload = byteArrayOf(
            (rate and 0xFF).toByte(),
            ((rate shr 8) and 0xFF).toByte(),
            ((rate shr 16) and 0xFF).toByte(),
            ((rate shr 24) and 0xFF).toByte(),
        )
        val written = connection.controlTransfer(
            REQUEST_TYPE_CLASS_INTERFACE_OUT,
            REQUEST_SET_CUR,
            SAMPLING_FREQUENCY_CONTROL,
            index,
            payload,
            payload.size,
            CONTROL_TIMEOUT_MS,
        )
        return written == payload.size
    }

    @Synchronized
    fun release() {
        runCatching { connection.close() }
    }

    private companion object {
        const val SUBCLASS_AUDIOSTREAMING = 0x02
        const val REQUEST_TYPE_CLASS_INTERFACE_OUT = 0x21
        const val REQUEST_TYPE_CLASS_INTERFACE_IN = 0xA1
        const val REQUEST_SET_CUR = 0x01
        const val REQUEST_GET_CUR = 0x81
        const val REQUEST_GET_MIN = 0x82
        const val REQUEST_GET_MAX = 0x83
        const val REQUEST_GET_RES = 0x84
        const val SAMPLING_FREQUENCY_CONTROL = 0x0100
        const val VOLUME_CONTROL = 0x0200
        const val VOLUME_BYTES = 2
        const val CONTROL_TIMEOUT_MS = 1_000

        /**
         * Volume reads happen while the user is waiting for playback to start. A device that
         * STALLs must not cost a second per transfer, and the DAWN PRO2 descriptor does not even
         * advertise volume, so this is deliberately shorter than the clock timeout. A normal
         * control-transfer round trip on this bus is milliseconds; 200ms is plenty to tell a slow
         * answer from a missing one.
         */
        const val VOLUME_TIMEOUT_MS = 200
    }
}