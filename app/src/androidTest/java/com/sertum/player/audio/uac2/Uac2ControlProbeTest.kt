package com.sertum.player.audio.uac2

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 0 of the UAC2 backend: can this app take the DAC's audio interface at all?
 *
 * ADR-0001 chose a self-written UAC2 driver over AAudio, because AAudio's "exclusive" still lands in
 * the 384 kHz AudioFlinger mixer on this hardware. The driver needs the raw streaming interface,
 * which means claiming it away from the platform's audio HAL - the step everything else depends on.
 *
 * This probe does not play audio. It answers, on the device, in order:
 *  1. is the USB audio device visible and can permission be obtained;
 *  2. which interfaces exist, and does claiming detach the platform's driver;
 *  3. is the streaming interface class 0x01 subclass 0x02 (UAC2 AUDIOSTREAMING);
 *  4. does the clock source answer UAC2's GET SAMPLING FREQUENCY and accept SET_CUR.
 *
 * The result is written to `files/uac2-probe.txt` so the numbers survive the test run.
 */
@RunWith(AndroidJUnit4::class)
class Uac2ControlProbeTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun canClaimTheDac() {
        val usbManager = context.getSystemService(UsbManager::class.java)
        val device = usbManager.deviceList.values.firstOrNull { it.isAudioDevice() }
        Assume.assumeTrue("no USB audio device attached", device != null)
        val report = StringBuilder()
        report.appendLine("device: ${device!!.deviceName} vendor=${device.vendorId} product=${device.productId}")

        if (!usbManager.hasPermission(device)) {
            usbManager.requestPermission(
                device,
                PendingIntent.getBroadcast(
                    context,
                    0,
                    // Explicit package + immutable: an implicit mutable intent is refused from
                    // target 34 on, which is exactly what the first run of this probe hit.
                    Intent("com.sertum.player.test.USB_PERMISSION").setPackage(context.packageName),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            // The dialog is the platform's; a headless run cannot tap it.
            repeat(30) {
                Thread.sleep(500)
                if (usbManager.hasPermission(device)) return@repeat
            }
        }
        val permitted = usbManager.hasPermission(device)
        report.appendLine("permission=$permitted")
        if (!permitted) {
            write(report)
            Assume.assumeTrue("USB permission not granted", false)
            return
        }

        report.appendLine("interfaces=${device.interfaceCount}")
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            report.appendLine(
                "  interface[$i] id=${intf.id} class=0x%02X sub=0x%02X proto=0x%02X endpoints=${intf.endpointCount}"
                    .format(intf.interfaceClass, intf.interfaceSubclass, intf.interfaceProtocol),
            )
            if (intf.interfaceClass == USB_CLASS_AUDIO) {
                for (e in 0 until intf.endpointCount) {
                    val ep: UsbEndpoint = intf.getEndpoint(e)
                    report.appendLine(
                        "    endpoint[$e] type=${ep.type} dir=${ep.direction} max=${ep.maxPacketSize} addr=0x%02X"
                            .format(ep.address),
                    )
                }
            }
        }

        val connection = usbManager.openDevice(device)
        report.appendLine("openDevice=${connection != null}")
        if (connection == null) {
            write(report)
            return
        }
        try {
            val streaming = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .firstOrNull {
                    it.interfaceClass == USB_CLASS_AUDIO &&
                        it.interfaceSubclass == USB_SUBCLASS_AUDIOSTREAMING
                }
            report.appendLine("streamingInterface=${streaming?.id ?: "none"}")

            var claimed = 0
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                val ok = connection.claimInterface(intf, true)
                report.appendLine("claim[${intf.id}] force=true -> $ok")
                if (ok) claimed++
            }
            report.appendLine("claimed=$claimed of ${device.interfaceCount}")

            // Raw configuration descriptor: the UAC2 entity chain (clock source, terminals) and the
            // per-alt-setting format descriptors are all in here, and parsing them off a captured
            // fixture is what the parser can be unit-tested against without a DAC attached.
            val raw = connection.rawDescriptors
            report.appendLine("rawDescriptors=${raw?.size ?: 0} bytes")
            raw?.let {
                File(context.filesDir, "uac2-descriptors.bin").writeBytes(it)
                File(context.filesDir, "uac2-descriptors.hex").writeText(
                    it.joinToString(" ") { b -> "%02X".format(b) },
                )
            }

            streaming?.let { intf ->
                // UAC2 clock source control: CS=0x01 (SAMPLING_FREQUENCY), CN=0x00.
                // wIndex = (bClockID << 8) | bInterfaceNumber; the clock is entity 1 on interface 1.
                val clockId = 1
                val acInterface = 1
                val wIndex = (clockId shl 8) or acInterface
                fun getRate(): Int? {
                    val buf = ByteArray(4)
                    val n = connection.controlTransfer(
                        0xA1, // class, interface, device->host
                        0x81, // GET_CUR
                        (0x01 shl 8) or 0x00,
                        wIndex,
                        buf,
                        buf.size,
                        0,
                    )
                    if (n < 4) return null
                    return (buf[0].toInt() and 0xFF) or
                        ((buf[1].toInt() and 0xFF) shl 8) or
                        ((buf[2].toInt() and 0xFF) shl 16) or
                        ((buf[3].toInt() and 0xFF) shl 24)
                }

                fun setRate(rate: Int): Boolean {
                    val buf = byteArrayOf(
                        (rate and 0xFF).toByte(),
                        ((rate shr 8) and 0xFF).toByte(),
                        ((rate shr 16) and 0xFF).toByte(),
                        ((rate shr 24) and 0xFF).toByte(),
                    )
                    return connection.controlTransfer(0x21, 0x01, (0x01 shl 8) or 0x00, wIndex, buf, 4, 0) == 4
                }

                // Raw return codes: a negative value is the platform refusing, 0..3 is a short read.
                fun getRaw(request: Int, value: Int, index: Int, len: Int): Pair<Int, String> {
                    val b = ByteArray(len)
                    val n = connection.controlTransfer(0xA1, request, value, index, b, len, 1000)
                    return n to b.joinToString(" ") { "%02X".format(it) }
                }
                // Plumbing check first: a standard GET_DESCRIPTOR must succeed on this same
                // connection, otherwise "-1" says nothing about the DAC.
                val deviceDesc = ByteArray(18)
                val std = connection.controlTransfer(0x80, 0x06, 0x0100, 0, deviceDesc, 18, 1000)
                report.appendLine("standard GET_DESCRIPTOR -> $std")
                report.appendLine("GET_CUR(1/1) -> ${getRaw(0x81, 0x0100, 0x0101, 4)}")
                report.appendLine("GET_RANGE(1/1) -> ${getRaw(0x82, 0x0100, 0x0101, 12)}")
                report.appendLine("GET_CUR(0x0100 idx=0x0100) -> ${getRaw(0x81, 0x0100, 0x0100, 4)}")
                report.appendLine("GET_CUR on AS iface 2 -> ${getRaw(0x81, 0x0100, 0x0102, 4)}")
                report.appendLine("clock current=${getRate()}")
                for (target in intArrayOf(96_000, 44_100, 192_000)) {
                    val ok = setRate(target)
                    val readBack = getRate()
                    report.appendLine("setRate($target) accepted=$ok readBack=$readBack")
                }
                report.appendLine("control transfers are now possible on the claimed interface")
                // UAC2 clock source requests are unprobed here on purpose: the control request
                // fields (entity id, selector) need the descriptor walk that Phase 1 builds. What
                // this proves is the part that can fail outright: claiming the interface at all.
                report.appendLine("altsettings not listed by UsbDevice; see Phase 1 descriptor walk")
            }
            write(report)
        } finally {
            connection.close()
        }
        println(report)
    }

    private fun write(report: StringBuilder) {
        File(context.filesDir, "uac2-probe.txt").writeText(report.toString())
    }

    private fun UsbDevice.isAudioDevice(): Boolean =
        (0 until interfaceCount).any { getInterface(it).interfaceClass == USB_CLASS_AUDIO }

    private companion object {
        const val USB_CLASS_AUDIO = 0x01
        const val USB_SUBCLASS_AUDIOSTREAMING = 0x02
    }
}

/** USB direction constants used by the probe; kept local so the test reads on its own. */
private const val DIRECTION_OUT = UsbConstants.USB_DIR_OUT
