package com.sertum.player.audio

import android.media.AudioDeviceInfo

/**
 * An output device reduced to the two fields the routing decisions need.
 *
 * `AudioDeviceInfo` cannot be constructed off-device, so the decision itself lives here over this
 * plain record and the Android type is adapted at the edge. That keeps "is this a DAC" testable
 * without a phone.
 */
internal data class OutputDeviceRef(val id: Int, val type: Int)

internal fun OutputDeviceRef.isUsbAudio(): Boolean =
    type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_HEADSET

/** The DAC's device id, or null when nothing USB is attached. */
internal fun usbOutputDeviceId(devices: List<OutputDeviceRef>): Int? =
    devices.firstOrNull { it.isUsbAudio() }?.id

/**
 * Whether [id] names one of the currently attached USB audio outputs.
 *
 * Used to answer "did the stream actually open on the DAC", which is not the same question as "is
 * the mode set to USB exclusive" - the platform opens the stream wherever it likes unless it is
 * told which device to use.
 */
internal fun isUsbOutputDeviceId(devices: List<OutputDeviceRef>, id: Int): Boolean =
    id > 0 && devices.any { it.id == id && it.isUsbAudio() }

internal fun AudioDeviceInfo.toOutputRef(): OutputDeviceRef = OutputDeviceRef(id = id, type = type)
