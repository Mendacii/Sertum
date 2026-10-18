package com.sertum.player.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * PRD 7.13: watches USB audio attach/detach (AudioManager device callback
 * plus UsbManager broadcasts as belt-and-braces) and Bluetooth output
 * presence. Coordinates the reaction; never shows system notifications here.
 *
 * Presence is derived from the *current device list* on every notification, never from the
 * notification's own payload. The payloads describe a delta, and a delta that does not mention the
 * USB device - which happens - left the previous flag standing, so the next attach looked like no
 * change and was dropped. Re-reading the list cannot drift out of sync with the device.
 */
class UsbHotplugController(
    context: Context,
    private val onUsbAttached: () -> Unit,
    private val onUsbDetached: () -> Unit,
    private val onBluetoothChanged: (Boolean) -> Unit,
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var usbPresent = false
    private var bluetoothPresent = false
    private var closed = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            rescan()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            rescan()
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED,
                -> rescan()
            }
        }
    }

    init {
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(appContext, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        rescan()
    }

    /**
     * Re-derives USB and Bluetooth presence from the live output device list and reports only the
     * transitions, so a notification that changes nothing stays silent.
     */
    private fun rescan() {
        if (closed) return
        val devices = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.toOutputRef() }
        }.getOrNull() ?: return

        val usbNow = usbOutputDeviceId(devices) != null
        if (usbNow != usbPresent) {
            usbPresent = usbNow
            if (usbNow) onUsbAttached() else onUsbDetached()
        }

        val bluetoothNow = devices.any { it.isBluetoothAudio() }
        if (bluetoothNow != bluetoothPresent) {
            bluetoothPresent = bluetoothNow
            onBluetoothChanged(bluetoothNow)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        try {
            appContext.unregisterReceiver(usbReceiver)
        } catch (_: IllegalArgumentException) {
            // not registered
        }
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
    }
}
