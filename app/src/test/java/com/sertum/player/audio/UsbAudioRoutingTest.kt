package com.sertum.player.audio

import android.media.AudioDeviceInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Which attached device, if any, counts as the DAC.
 *
 * This is the difference between "the mode says USB exclusive" and "the stream is on the DAC".
 * The second question is the one the badge has to answer, and it is answered by comparing the
 * stream's own device id against these refs.
 */
class UsbAudioRoutingTest {

    private val speaker = OutputDeviceRef(id = 1, type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
    private val usbDac = OutputDeviceRef(id = 7, type = AudioDeviceInfo.TYPE_USB_DEVICE)
    private val usbHeadset = OutputDeviceRef(id = 9, type = AudioDeviceInfo.TYPE_USB_HEADSET)

    @Test
    fun `the first usb output is the dac`() {
        assertThat(usbOutputDeviceId(listOf(speaker, usbDac))).isEqualTo(7)
        // A USB headset is an audio output like any other DAC as far as this route is concerned.
        assertThat(usbOutputDeviceId(listOf(speaker, usbHeadset))).isEqualTo(9)
    }

    @Test
    fun `with nothing usb there is no dac`() {
        assertThat(usbOutputDeviceId(listOf(speaker))).isNull()
        assertThat(usbOutputDeviceId(emptyList())).isNull()
    }

    @Test
    fun `only the named usb device matches`() {
        val devices = listOf(speaker, usbDac)
        assertThat(isUsbOutputDeviceId(devices, 7)).isTrue()
        // The speaker is attached, but it is not the DAC.
        assertThat(isUsbOutputDeviceId(devices, 1)).isFalse()
        // 0 is the platform's "choose for me", which is exactly what must not pass as the DAC.
        assertThat(isUsbOutputDeviceId(devices, 0)).isFalse()
        // An id from a device that has since been unplugged.
        assertThat(isUsbOutputDeviceId(devices, 99)).isFalse()
    }

    @Test
    fun `a usb device that disappeared no longer matches`() {
        assertThat(isUsbOutputDeviceId(listOf(speaker), 7)).isFalse()
    }
}
