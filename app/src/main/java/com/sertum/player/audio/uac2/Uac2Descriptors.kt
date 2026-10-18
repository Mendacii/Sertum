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

data class Uac2DescriptorInfo(
    val clockSourceId: Int?,
    val streamingInterfaceNumber: Int?,
    val formats: List<Uac2Format>,
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
private const val CS_FORMAT_TYPE = 0x02
private const val FORMAT_TYPE_I = 0x01
private const val ENDPOINT_TYPE_MASK = 0x03
private const val ENDPOINT_TYPE_ISOCHRONOUS = 0x01

/** Walks the descriptor chain in [raw] (a device's raw configuration descriptors). */
fun parseUac2Descriptors(raw: ByteArray): Uac2DescriptorInfo {
    var clockSourceId: Int? = null
    var streamingInterface: Int? = null
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
                if (interfaceClass == CLASS_AUDIO && currentSubclass == SUBCLASS_AUDIOSTREAMING) {
                    streamingInterface = number
                }
                pendingSubslot = 0
                pendingBitDepth = 0
            }
            DESCRIPTOR_CS_INTERFACE -> when {
                currentSubclass == SUBCLASS_AUDIOCONTROL && length >= 4 -> {
                    if ((raw[payload].toInt() and 0xFF) == CS_CLOCK_SOURCE) {
                        clockSourceId = raw[payload + 1].toInt() and 0xFF
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
                if (currentSubclass == SUBCLASS_AUDIOSTREAMING && length >= 7 && pendingSubslot > 0) {
                    val address = raw[payload].toInt() and 0xFF
                    val attributes = raw[payload + 1].toInt() and 0xFF
                    val isIsoOut = (attributes and ENDPOINT_TYPE_MASK) == ENDPOINT_TYPE_ISOCHRONOUS &&
                        (address and 0x80) == 0
                    if (isIsoOut) {
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
    return Uac2DescriptorInfo(clockSourceId, streamingInterface, formats)
}