package com.sertum.player.audio.extractor

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import android.util.Log
import java.io.EOFException

/**
 * Minimal AIFF/AIFC PCM extractor for Media3.
 *
 * Supports FORM AIFF/AIFC containers with COMM + SSND chunks, 16/24/32-bit
 * big-endian PCM. Seeking is arithmetic rather than an index: the frames are fixed width, so
 * a time maps straight to a byte offset and back.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class AiffExtractor : Extractor {

    companion object {
        private const val TAG = "SertumAiff"
        private const val STATE_HEADER = 0
        private const val STATE_CHUNKS = 1
        private const val STATE_DATA = 2

        fun sniffFormat(input: ExtractorInput): Boolean {
            val header = ByteArray(12)
            return try {
                input.peekFully(header, 0, 12, false)
                header[0] == 'F'.code.toByte() &&
                    header[1] == 'O'.code.toByte() &&
                    header[2] == 'R'.code.toByte() &&
                    header[3] == 'M'.code.toByte() &&
                    header[8] == 'A'.code.toByte() &&
                    header[9] == 'I'.code.toByte() &&
                    header[10] == 'F'.code.toByte() &&
                    (header[11] == 'F'.code.toByte() || header[11] == 'C'.code.toByte())
            } catch (e: Exception) {
                false
            }
        }
    }

    private var state = STATE_HEADER
    private var output: ExtractorOutput? = null
    private var trackOutput: TrackOutput? = null
    private var frameBytes = 0
    private var bytesPerSample = 2
    private var dataBytesRemaining = 0L
    private var framesFed = 0L
    private var sampleRate = 1

    /**
     * Where the sample data starts, and how long it is.
     *
     * -1 until the SSND chunk has been located. These exist for seeking: uncompressed PCM has a
     * fixed frame size, so time and byte offset convert by multiplying, and both directions need
     * to know where the bytes begin.
     */
    private var dataStartPosition = -1L
    private var dataTotalBytes = 0L
    private var durationUs = 0L

    override fun sniff(input: ExtractorInput): Boolean {
        val result = sniffFormat(input)
        Log.i(TAG, "sniff=$result")
        return result
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
    }

    override fun read(input: ExtractorInput, positionHolder: PositionHolder): Int {
        return when (state) {
            STATE_HEADER -> readHeader(input)
            STATE_CHUNKS -> readChunks(input)
            STATE_DATA -> readData(input)
            else -> Extractor.RESULT_END_OF_INPUT
        }
    }

    private fun readHeader(input: ExtractorInput): Int {
        val header = ByteArray(12)
        if (!input.readFully(header, 0, 12, false)) return Extractor.RESULT_END_OF_INPUT
        state = STATE_CHUNKS
        Log.i(TAG, "header read; entering chunks")
        return Extractor.RESULT_CONTINUE
    }

    private fun readChunks(input: ExtractorInput): Int {
        val chunkHeader = ByteArray(8)
        if (!input.readFully(chunkHeader, 0, 8, false)) return Extractor.RESULT_END_OF_INPUT
        val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
        val chunkSize = readU32(chunkHeader, 4)
        Log.i(TAG, "chunk=$chunkId size=$chunkSize")

        when (chunkId) {
            "COMM" -> {
                val comm = ByteArray(18)
                if (!input.readFully(comm, 0, 18, false)) return Extractor.RESULT_END_OF_INPUT
                val channelCount = readU16(comm, 0)
                val frameCount = readU32(comm, 2)
                val bitsPerSample = readU16(comm, 6)
                sampleRate = readExtended80(comm, 8).toInt().coerceAtLeast(1)
                bytesPerSample = bitsPerSample / 8
                frameBytes = channelCount * bytesPerSample
                val pcmEncoding = when (bitsPerSample) {
                    16 -> C.ENCODING_PCM_16BIT
                    24 -> C.ENCODING_PCM_24BIT
                    32 -> C.ENCODING_PCM_32BIT
                    else -> return Extractor.RESULT_END_OF_INPUT
                }
                val out = output ?: return Extractor.RESULT_END_OF_INPUT
                trackOutput = out.track(0, C.TRACK_TYPE_AUDIO).apply {
                    format(
                        Format.Builder()
                            .setSampleMimeType(MimeTypes.AUDIO_RAW)
                            .setSampleRate(sampleRate)
                            .setChannelCount(channelCount)
                            .setPcmEncoding(pcmEncoding)
                            .build(),
                    )
                }
                val durationUs = frameCount * 1_000_000L / sampleRate
                this.durationUs = durationUs
                // Seekable, because AIFF is uncompressed PCM at a fixed frame size: the position
                // for any time is one multiplication. The previous answer here - Unseekable - was
                // a scope cut, and it cost the reader the progress bar on every AIFF file.
                out.seekMap(LinearPcmSeekMap())
                out.endTracks()
            }

            "SSND" -> {
                val ssnd = ByteArray(8)
                if (!input.readFully(ssnd, 0, 8, false)) return Extractor.RESULT_END_OF_INPUT
                dataBytesRemaining = chunkSize - 8L
                dataTotalBytes = dataBytesRemaining
                // After the chunk header and its offset/block-size fields: the first sample.
                dataStartPosition = input.position
                state = STATE_DATA
                Log.i(TAG, "SSND dataBytesRemaining=$dataBytesRemaining frameBytes=$frameBytes bps=$bytesPerSample rate=$sampleRate")
            }

            else -> {
                val toSkip = chunkSize + (chunkSize and 1L)
                if (toSkip > Int.MAX_VALUE) return Extractor.RESULT_END_OF_INPUT
                input.skipFully(toSkip.toInt())
            }
        }
        return Extractor.RESULT_CONTINUE
    }

    private fun readData(input: ExtractorInput): Int {
        val out = trackOutput ?: return Extractor.RESULT_END_OF_INPUT
        if (dataBytesRemaining <= 0) return Extractor.RESULT_END_OF_INPUT

        // Media3 audio processors require buffers that contain whole samples;
        // align multibyte PCM blocks to a whole frame (channels * bytes).
        val maxBlock = 4096.coerceAtMost(dataBytesRemaining.toInt())
        val alignTo = if (bytesPerSample > 1) frameBytes.coerceAtLeast(1) else 1
        val block = maxBlock - (maxBlock % alignTo)
        if (block <= 0) return Extractor.RESULT_END_OF_INPUT
        val read: Int = if (bytesPerSample > 1) {
            // AIFF PCM is big-endian; Media3 PCM is little-endian.
            // Feed through a DataReader that byte-swaps on read. TrackOutput
            // may perform short reads, so loop until a whole frame-aligned
            // block is appended; sampleMetadata must never describe partial
            // frames (Media3's float pipeline requires whole samples).
            val swappedReader = object : DataReader {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    val read = input.read(buffer, offset, length)
                    if (read > 0) swapPcmBytes(buffer, offset, read, bytesPerSample)
                    return read
                }
            }
            var totalRead = 0
            while (totalRead < block) {
                val appended = out.sampleData(swappedReader, block - totalRead, false)
                if (appended <= 0) break
                totalRead += appended
            }
            if (totalRead == 0) Extractor.RESULT_END_OF_INPUT else totalRead - (totalRead % alignTo)
        } else {
            out.sampleData(input, block, false)
        }
        if (read <= 0) return Extractor.RESULT_END_OF_INPUT
        dataBytesRemaining -= read

        val frames = read / frameBytes
        framesFed += frames
        out.sampleMetadata(
            framesFed * 1_000_000L / sampleRate,
            C.BUFFER_FLAG_KEY_FRAME,
            read,
            0,
            null,
        )
        if (dataBytesRemaining <= 0) Log.i(TAG, "data finished framesFed=$framesFed")
        return if (dataBytesRemaining <= 0) Extractor.RESULT_END_OF_INPUT else Extractor.RESULT_CONTINUE
    }

    private fun swapPcmBytes(buffer: ByteArray, offset: Int, length: Int, bytesPerSample: Int) {
        var i = offset
        val end = offset + length
        while (i + bytesPerSample <= end) {
            when (bytesPerSample) {
                2 -> {
                    val t = buffer[i]
                    buffer[i] = buffer[i + 1]
                    buffer[i + 1] = t
                }
                3 -> {
                    val t = buffer[i]
                    buffer[i] = buffer[i + 2]
                    buffer[i + 2] = t
                }
                4 -> {
                    val t0 = buffer[i]
                    buffer[i] = buffer[i + 3]
                    buffer[i + 3] = t0
                    val t1 = buffer[i + 1]
                    buffer[i + 1] = buffer[i + 2]
                    buffer[i + 2] = t1
                }
            }
            i += bytesPerSample
        }
    }

    /**
     * Repositions the bookkeeping to [position].
     *
     * The caller opens the source at the new position, so nothing here moves the input; what has to
     * happen is that the frame count and the remaining byte count continue from the right place.
     * Skipping that is what makes timestamps wrong by the amount sought past, and makes the tail of
     * the file play with the wrong duration.
     */
    override fun seek(position: Long, timeUs: Long) {
        if (dataStartPosition < 0 || frameBytes <= 0) return
        val dataEnd = dataStartPosition + dataTotalBytes
        val target = (if (position < 0) positionForTimeUs(timeUs) else position)
            .coerceIn(dataStartPosition, dataEnd)
        val framesBefore = (target - dataStartPosition) / frameBytes
        framesFed = framesBefore
        dataBytesRemaining = dataTotalBytes - framesBefore * frameBytes
    }

    /** Byte offset of [timeUs] into the sample data, clamped to what the file holds. */
    private fun positionForTimeUs(timeUs: Long): Long {
        if (dataStartPosition < 0 || frameBytes <= 0) return 0L
        val dataEnd = dataStartPosition + dataTotalBytes
        // At or past the end: the end of the data, exactly. Converting through durationUs instead
        // loses the last frame, because the COMM frame count does not divide into whole
        // microseconds.
        if (timeUs >= durationUs) return dataEnd
        val frames = timeUs.coerceAtLeast(0L) * sampleRate / 1_000_000L
        return (dataStartPosition + frames * frameBytes).coerceAtMost(dataEnd)
    }

    /**
     * Time to byte offset, linearly.
     *
     * AIFF carries uncompressed PCM, so every frame is the same width and the mapping is exact -
     * this is the format's own structure, not an estimate. Resolves through the extractor's fields
     * because the map is installed when COMM is read and the sample data has not been located yet.
     */
    private inner class LinearPcmSeekMap : SeekMap {
        override fun isSeekable(): Boolean = true

        // Qualified deliberately: inside a SeekMap, the bare name resolves to this class's own
        // getDurationUs(), which would call itself.
        override fun getDurationUs(): Long = this@AiffExtractor.durationUs

        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints = SeekMap.SeekPoints(
            // SeekPoint takes (timeUs, position) - the opposite of the order it declares them in
            // the field list, which is an easy way to hand the player a time where it wants a byte.
            SeekPoint(
                timeUs.coerceIn(0L, this@AiffExtractor.durationUs),
                positionForTimeUs(timeUs),
            ),
        )
    }

    override fun release() {
        trackOutput = null
        output = null
    }

    private fun readU16(b: ByteArray, offset: Int): Int =
        ((b[offset].toInt() and 0xFF) shl 8) or (b[offset + 1].toInt() and 0xFF)

    private fun readU32(b: ByteArray, offset: Int): Long =
        ((b[offset].toLong() and 0xFF) shl 24) or
            ((b[offset + 1].toLong() and 0xFF) shl 16) or
            ((b[offset + 2].toLong() and 0xFF) shl 8) or
            (b[offset + 3].toLong() and 0xFF)

    /** 80-bit IEEE 754 extended float, big-endian, as used by AIFF COMM. */
    private fun readExtended80(b: ByteArray, offset: Int): Double {
        val sign = if ((b[offset].toInt() and 0x80) != 0) -1.0 else 1.0
        val exponent = ((b[offset].toInt() and 0x7F) shl 8) or (b[offset + 1].toInt() and 0xFF)
        var mantissa = 0L
        for (i in 2..9) {
            mantissa = (mantissa shl 8) or (b[offset + i].toLong() and 0xFF)
        }
        if (exponent == 0 && mantissa == 0L) return 0.0
        val integerPart = (mantissa ushr 63) and 1L
        val fraction = (mantissa and 0x7FFFFFFFFFFFFFFFL) / 9223372036854775808.0
        return sign * (integerPart + fraction) * Math.pow(2.0, (exponent - 16383).toDouble())
    }
}
