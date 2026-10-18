package com.sertum.player.audio.uac2

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Finds the DAC, obtains USB permission, and claims its audio interfaces.
 *
 * Every failure is reported rather than swallowed: the caller falls back to AAudio and says so,
 * because a claimed-looking session that never opened is exactly the "silent downgrade" the PRD
 * forbids.
 */
class Uac2DeviceManager(private val context: Context) {

    private val usbManager = context.getSystemService(UsbManager::class.java)

    private val _permissionGranted = MutableStateFlow(false)
    val permissionGranted: StateFlow<Boolean> = _permissionGranted.asStateFlow()

    @Volatile
    private var session: Uac2Session? = null

    /** Where claim outcomes are reported; set by whoever owns diagnostics. */
    var onEvent: ((String) -> Unit)? = null

    val activeSession: Uac2Session? get() = session

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (intent?.action == ACTION_USB_PERMISSION) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                _permissionGranted.value = granted
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            context.applicationContext,
            permissionReceiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** The attached UAC2 audio device, or null when there is none. */
    fun findDevice(): UsbDevice? = usbManager.deviceList.values.firstOrNull { device ->
        (0 until device.interfaceCount).any {
            val intf = device.getInterface(it)
            intf.interfaceClass == USB_CLASS_AUDIO && intf.interfaceSubclass == SUBCLASS_AUDIOSTREAMING
        }
    }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    /** Asks the platform for access; the answer arrives on [permissionGranted]. */
    fun requestPermission(device: UsbDevice) {
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        usbManager.requestPermission(
            device,
            PendingIntent.getBroadcast(
                context.applicationContext,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
    }

    /**
     * Opens the DAC and claims every interface.
     *
     * All interfaces are claimed, not just the audio ones: a HID interface left with the kernel
     * steals bandwidth from the isochronous endpoint.
     */
    fun open(): Result<Uac2Session> {
        session?.let { return Result.success(it) }
        val device = findDevice() ?: return Result.failure(IllegalStateException("no USB audio device"))
        if (!usbManager.hasPermission(device)) {
            requestPermission(device)
            return Result.failure(IllegalStateException("USB permission not granted yet"))
        }
        val connection = usbManager.openDevice(device)
            ?: return Result.failure(IllegalStateException("openDevice failed"))
        val interfaces = (0 until device.interfaceCount).map { device.getInterface(it) }
        var claimed = 0
        for (intf in interfaces) {
            if (connection.claimInterface(intf, true)) claimed++
        }
        // force=true disconnects whatever kernel driver held the interface (snd-usb-audio), which
        // is the step that takes the device away from the platform.
        onEvent?.invoke("claimed $claimed/${interfaces.size} interfaces (force=true)")
        if (claimed == 0) {
            runCatching { connection.close() }
            return Result.failure(IllegalStateException("no interface could be claimed"))
        }
        val info = parseUac2Descriptors(connection.rawDescriptors ?: ByteArray(0))
        if (info.formats.isEmpty()) {
            runCatching { connection.close() }
            return Result.failure(IllegalStateException("descriptors expose no streaming format"))
        }
        val opened = Uac2Session(device, connection, info, interfaces)
        opened.onEvent = { onEvent?.invoke(it) }
        session = opened
        _permissionGranted.value = true
        return Result.success(opened)
    }

    fun close() {
        session?.release()
        session = null
    }

    private companion object {
        const val ACTION_USB_PERMISSION = "com.sertum.player.USB_PERMISSION"
        const val USB_CLASS_AUDIO = 0x01
        const val SUBCLASS_AUDIOSTREAMING = 0x02
    }
}