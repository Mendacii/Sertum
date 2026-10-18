package com.sertum.player.audio.uac2

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface

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

    /** Where claim/alt/clock outcomes are reported, so a failed step is never silent. */
    var onEvent: ((String) -> Unit)? = null

    data class OpenStream(val handle: Long, val format: Uac2Format, val sampleRate: Int)

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
        const val REQUEST_SET_CUR = 0x01
        const val SAMPLING_FREQUENCY_CONTROL = 0x0100
        const val CONTROL_TIMEOUT_MS = 1_000
    }
}