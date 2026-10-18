package com.sertum.player.audio.uac2

import android.app.PendingIntent
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 1: does a real PCM stream reach the DAC over isochronous transfers?
 *
 * The alt setting comes from the descriptor parse rather than a guess, so the stream layout the
 * transport packs and the layout the device expects cannot disagree - the failure mode that made
 * an earlier attempt at this look successful while sending the wrong bytes.
 *
 * While it runs the DAC's audio interface is claimed away from the platform, so AudioFlinger has no
 * USB output to drive. That is the observable difference from AAudio's "exclusive", which still
 * mixes at 384 kHz.
 */
@RunWith(AndroidJUnit4::class)
class Uac2IsoStreamTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun feedbackDecodesToPpm() {
        // Ratio convention: 0x00010000 is exactly nominal, and one LSB is about 15 ppm.
        assertThat(Uac2Transport.nativeFeedbackPpm(0x00010000, 96_000)).isEqualTo(0)
        assertThat(Uac2Transport.nativeFeedbackPpm(0x0000FFFF, 96_000)).isEqualTo(-15)
        // Absolute convention: this DAWN PRO2 reports 12.0 frames per microframe at 96 kHz.
        assertThat(Uac2Transport.nativeFeedbackRate(0x000C0000, 96_000)).isEqualTo(96_000)
        // 12.0027 frames per microframe is about +224 ppm off nominal.
        assertThat(Uac2Transport.nativeFeedbackPpm(0x000C00B0, 96_000)).isWithin(2).of(224)
    }

    @Test
    fun streamsPcmToTheDac() {
        val usbManager = context.getSystemService(UsbManager::class.java)
        val device = usbManager.deviceList.values.firstOrNull { it.hasAudioStreaming() }
        Assume.assumeTrue("no USB audio device attached", device != null)
        if (!usbManager.hasPermission(device)) {
            usbManager.requestPermission(
                device,
                PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent("com.sertum.player.test.USB_PERMISSION").setPackage(context.packageName),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            repeat(30) { Thread.sleep(500) }
        }
        Assume.assumeTrue("USB permission not granted", usbManager.hasPermission(device))

        val report = StringBuilder()
        val connection = usbManager.openDevice(device)
        Assume.assumeTrue("openDevice failed", connection != null)
        var handle = 0L
        try {
            val info = parseUac2Descriptors(connection!!.rawDescriptors ?: ByteArray(0))
            report.appendLine(
                "clock=${info.clockSourceId} streamingIface=${info.streamingInterfaceNumber} " +
                    "feedbackEp=${info.feedbackEndpoint?.let { "0x%02X".format(it) } ?: "none"}",
            )
            info.formats.forEach { report.appendLine("  alt=${it.alternateSetting} ${it.bitDepth}bit subslot=${it.subslotBytes} ep=0x%02X max=%d".format(it.endpointAddress, it.maxPacketSize)) }

            val format = info.formatForBitDepth(SOURCE_BITS)
            Assume.assumeTrue("the DAC exposes no streaming format", format != null)
            val target = format!!
            val intf = (0 until device!!.interfaceCount)
                .map { device.getInterface(it) }
                .firstOrNull {
                    it.interfaceClass == USB_CLASS_AUDIO &&
                        it.interfaceSubclass == USB_SUBCLASS_AUDIOSTREAMING &&
                        it.alternateSetting == target.alternateSetting
                }
            Assume.assumeTrue("alt ${target.alternateSetting} not found", intf != null)

            assertThat(connection.claimInterface(intf!!, true)).isTrue()
            // Java setInterface is what allocates isochronous bandwidth in the xHCI scheduler; the
            // native ioctl equivalent does not.
            assertThat(connection.setInterface(intf)).isTrue()

            handle = Uac2Transport.nativeOpen(
                usbFd = connection.fileDescriptor,
                endpoint = target.endpointAddress,
                sampleRate = SAMPLE_RATE,
                channels = CHANNELS,
                subslotBytes = target.subslotBytes,
                feedbackEndpoint = info.feedbackEndpoint ?: 0,
            )
            report.appendLine("nativeOpen=$handle alt=${target.alternateSetting}")
            Assume.assumeTrue("transport refused to open", handle != 0L)
            assertThat(Uac2Transport.nativeStart(handle)).isTrue()

            val frameBytes = CHANNELS * target.subslotBytes
            val framesPerChunk = SAMPLE_RATE / 50 // 20 ms
            val chunk = ByteArray(framesPerChunk * frameBytes)
            // A low-level ramp rather than silence: a stuck zero buffer would hide a write path
            // that never reaches the device.
            for (i in chunk.indices) chunk[i] = ((i / frameBytes) % 128).toByte()

            val startedAt = System.currentTimeMillis()
            while (System.currentTimeMillis() - startedAt < STREAM_MS) {
                val accepted = Uac2Transport.nativeWrite(handle, chunk, 0, chunk.size)
                if (accepted <= 0) Thread.sleep(5)
            }
            Thread.sleep(200)

            val stats = Uac2Transport.nativeStats(handle)
            report.appendLine(
                "submitted=${stats[0]} completed=${stats[1]} failed=${stats[2]} " +
                    "frames=${stats[3]} lastErrno=${stats[4]} underruns=${stats[5]} " +
                    "feedbackSeen=${stats[6]} ratePpm=${stats[7]} primed=${stats[8]}",
            )
            write(report)

            assertThat(stats[0]).isGreaterThan(0)   // URBs submitted
            assertThat(stats[1]).isGreaterThan(0)   // URBs completed by the kernel
            assertThat(stats[2]).isEqualTo(0)       // none failed
            assertThat(stats[4]).isEqualTo(0)       // no ioctl error
            // Silence while the pipeline primes is normal; a drop after real PCM flowed is not.
            assertThat(stats[8]).isGreaterThan(0)
            assertThat(stats[3]).isGreaterThan(SAMPLE_RATE.toLong() / 2) // over half a second sent
            if (info.feedbackEndpoint != null) {
                // The device reports its clock on the feedback endpoint; over three seconds it
                // must have answered at least once, and its ratio must be a real crystal's.
                assertThat(stats[6]).isGreaterThan(0)
                assertThat(Math.abs(stats[7])).isLessThan(5_000)
            }
        } finally {
            if (handle != 0L) {
                Uac2Transport.nativeStop(handle)
                Uac2Transport.nativeClose(handle)
            }
            runCatching {
                val idle = (0 until device!!.interfaceCount)
                    .map { device.getInterface(it) }
                    .firstOrNull {
                        it.interfaceClass == USB_CLASS_AUDIO &&
                            it.interfaceSubclass == USB_SUBCLASS_AUDIOSTREAMING &&
                            it.alternateSetting == 0
                    }
                if (idle != null) connection.setInterface(idle)
            }
            connection.close()
        }
        println(report)
    }

    private fun write(report: StringBuilder) {
        File(context.filesDir, "uac2-iso-stream.txt").writeText(report.toString())
    }

    private fun UsbDevice.hasAudioStreaming(): Boolean =
        (0 until interfaceCount).any {
            val i = getInterface(it)
            i.interfaceClass == USB_CLASS_AUDIO && i.interfaceSubclass == USB_SUBCLASS_AUDIOSTREAMING
        }

    private companion object {
        const val USB_CLASS_AUDIO = 0x01
        const val USB_SUBCLASS_AUDIOSTREAMING = 0x02
        const val SAMPLE_RATE = 96_000
        const val CHANNELS = 2
        const val SOURCE_BITS = 24
        const val STREAM_MS = 3_000L
    }
}