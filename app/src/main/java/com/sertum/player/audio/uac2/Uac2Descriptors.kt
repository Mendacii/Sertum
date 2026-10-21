package com.sertum.player.audio.uac2

/**
 * The UAC2 facts a driver has to know, read out of the configuration descriptor.
 *
 * Parsed rather than assumed: the clock entity id is not guessable (a DAC will accept a clock
 * request addressed to a non-existent entity without returning STALL), the streaming alt settings
 * are what carry the bit depth, and the endpoint address is what the transport writes to.
 */
data class Uac2Format(
    val alternateSetting: Int,
    val subslotBytes: Int,
    val bitDepth: Int,
    val endpointAddress: Int,
    val maxPacketSize: Int,
)

/**
 * One UAC2 Feature Unit, as advertised by the AudioControl interface.
 *
 * The control bitmaps are 32-bit little-endian values. D1 is Volume on the master control
 * (`bmaControls(0)`) and on each per-channel control. A device can expose the unit for other
 * controls - the DAWN PRO2 fixture advertises `0x0c` (bass/mid) on its two channels and no volume
 * bit at all - so [supportsVolume] must be read rather than assumed from the unit's presence.
 *
 * The bitmap is not used as the final gate for a write: the runtime GET_MIN/GET_MAX result is,
 * because the descriptor and the control endpoint can disagree. The bitmap is logged so any
 * mismatch is visible in diagnostics, but a successful range read is the evidence that a write is
 * allowed.
 */
data class Uac2FeatureUnit(
    val unitId: Int,
    val sourceId: Int,
    val masterControlBitmap: Int,
    val channelControlBitmaps: List<Int>,
) {
    val supportsVolume: Boolean
        get() = (masterControlBitmap and CONTROL_VOLUME) != 0 ||
            channelControlBitmaps.any { (it and CONTROL_VOLUME) != 0 }

    companion object {
        /** D1 in the UAC2 Feature Unit bmaControls bitmap. */
        const val CONTROL_VOLUME: Int = 0x0002
    }
}

data class Uac2DescriptorInfo(
    val clockSourceId: Int?,
    val audioControlInterfaceNumber: Int?,
    val streamingInterfaceNumber: Int?,
    /**
     * The asynchronous feedback endpoint (isochronous IN), or null for a device that does not
     * report its clock. When present it carries one 16.16 fixed-point rate per interval.
     */
    val feedbackEndpoint: Int?,
    val formats: List<Uac2Format>,
    /**
     * The first Feature Unit that advertises volume, or the first Feature Unit when none does.
     * Null when the AudioControl interface has no Feature Unit at all.
     */
    val featureUnit: Uac2FeatureUnit? = null,
) {
    /** The deepest format that does not exceed [bitDepth], falling back to the shallowest one. */
    fun formatForBitDepth(bitDepth: Int): Uac2Format? =
        formats.filter { it.bitDepth <= bitDepth }.maxByOrNull { it.bitDepth }
            ?: formats.minByOrNull { it.bitDepth }
}

private const val DESCRIPTOR_INTERFACE = 0x04
private const val DESCRIPTOR_ENDPOINT = 0x05
private const val DESCRIPTOR_CS_INTERFACE = 0x24
private const val CLASS_AUDIO = 0x01
private const val SUBCLASS_AUDIOCONTROL = 0x01
private const val SUBCLASS_AUDIOSTREAMING = 0x02
private const val CS_CLOCK_SOURCE = 0x0A
private const val CS_FEATURE_UNIT = 0x06
private const val CS_FORMAT_TYPE = 0x02
private const val FORMAT_TYPE_I = 0x01
private const val ENDPOINT_TYPE_MASK = 0x03
private const val ENDPOINT_TYPE_ISOCHRONOUS = 0x01

/** Walks the descriptor chain in [raw] (a device's raw configuration descriptors). */
fun parseUac2Descriptors(raw: ByteArray): Uac2DescriptorInfo {
    var clockSourceId: Int? = null
    var audioControlInterface: Int? = null
    var streamingInterface: Int? = null
    var feedbackEndpoint: Int? = null
    var featureUnit: Uac2FeatureUnit? = null
    val formats = mutableListOf<Uac2Format>()

    var currentSubclass = -1
    var currentAlt = -1
    var pendingSubslot = 0
    var pendingBitDepth = 0

    var index = 0
    while (index + 1 < raw.size) {
        val length = raw[index].toInt() and 0xFF
        if (length < 2 || index + length > raw.size) break
        val type = raw[index + 1].toInt() and 0xFF
        val payload = index + 2

        when (type) {
            DESCRIPTOR_INTERFACE -> if (length >= 9) {
                // Layout: bInterfaceNumber, bAlternateSetting, bNumEndpoints, bInterfaceClass,
                // bInterfaceSubclass, bInterfaceProtocol, iInterface. Class and subclass sit after
                // the endpoint count, not immediately after the alt setting.
                val number = raw[payload].toInt() and 0xFF
                currentAlt = raw[payload + 1].toInt() and 0xFF
                val interfaceClass = raw[payload + 3].toInt() and 0xFF
                currentSubclass = raw[payload + 4].toInt() and 0xFF
                if (interfaceClass == CLASS_AUDIO) {
                    if (currentSubclass == SUBCLASS_AUDIOCONTROL) audioControlInterface = number
                    if (currentSubclass == SUBCLASS_AUDIOSTREAMING) streamingInterface = number
                }
                pendingSubslot = 0
                pendingBitDepth = 0
            }
            DESCRIPTOR_CS_INTERFACE -> when {
                currentSubclass == SUBCLASS_AUDIOCONTROL && length >= 4 -> {
                    when (raw[payload].toInt() and 0xFF) {
                        CS_CLOCK_SOURCE -> clockSourceId = raw[payload + 1].toInt() and 0xFF
                        CS_FEATURE_UNIT -> if (length >= 10) {
                            // Layout after the 2-byte header: bDescriptorSubtype, bUnitID,
                            // bSourceID, then one 32-bit bmaControls per master/channel, then
                            // iFeature. For the DAWN PRO2 this is unit 0x0a, source 0x03,
                            // master=0x00000000, channels=0x0000000c each.
                            val unitId = raw[payload + 1].toInt() and 0xFF
                            val sourceId = raw[payload + 2].toInt() and 0xFF
                            val controlBytes = length - 6
                            val controlCount = controlBytes / 4
                            if (controlCount >= 1) {
                                val bitmaps = (0 until controlCount).map { index ->
                                    readControlBitmap(raw, payload + 3 + index * 4)
                                }
                                val candidate = Uac2FeatureUnit(
                                    unitId = unitId,
                                    sourceId = sourceId,
                                    masterControlBitmap = bitmaps.first(),
                                    channelControlBitmaps = bitmaps.drop(1),
                                )
                                val existing = featureUnit
                                if (existing == null ||
                                    (!existing.supportsVolume && candidate.supportsVolume)
                                ) {
                                    featureUnit = candidate
                                }
                            }
                        }
                    }
                }
                currentSubclass == SUBCLASS_AUDIOSTREAMING && length >= 6 -> {
                    val subtype = raw[payload].toInt() and 0xFF
                    val formatType = raw[payload + 1].toInt() and 0xFF
                    if (subtype == CS_FORMAT_TYPE && formatType == FORMAT_TYPE_I) {
                        pendingSubslot = raw[payload + 2].toInt() and 0xFF
                        pendingBitDepth = raw[payload + 3].toInt() and 0xFF
                    }
                }
            }
            DESCRIPTOR_ENDPOINT -> {
                if (currentSubclass == SUBCLASS_AUDIOSTREAMING && length >= 7) {
                    val address = raw[payload].toInt() and 0xFF
                    val attributes = raw[payload + 1].toInt() and 0xFF
                    val isIso = (attributes and ENDPOINT_TYPE_MASK) == ENDPOINT_TYPE_ISOCHRONOUS
                    val isOut = (address and 0x80) == 0
                    if (isIso && !isOut) {
                        // The asynchronous feedback stream: the device telling us its real clock.
                        feedbackEndpoint = address
                    }
                    if (isIso && isOut && pendingSubslot > 0) {
                        val maxPacket = (raw[payload + 2].toInt() and 0xFF) or
                            ((raw[payload + 3].toInt() and 0xFF) shl 8)
                        formats += Uac2Format(
                            alternateSetting = currentAlt,
                            subslotBytes = pendingSubslot,
                            bitDepth = pendingBitDepth,
                            endpointAddress = address,
                            maxPacketSize = maxPacket,
                        )
                        pendingSubslot = 0
                        pendingBitDepth = 0
                    }
                }
            }
        }
        index += length
    }
    return Uac2DescriptorInfo(
        clockSourceId,
        audioControlInterface,
        streamingInterface,
        feedbackEndpoint,
        formats,
        featureUnit,
    )
}

private fun readControlBitmap(raw: ByteArray, offset: Int): Int =
    (raw[offset].toInt() and 0xFF) or
        ((raw[offset + 1].toInt() and 0xFF) shl 8) or
        ((raw[offset + 2].toInt() and 0xFF) shl 16) or
        ((raw[offset + 3].toInt() and 0xFF) shl 24)