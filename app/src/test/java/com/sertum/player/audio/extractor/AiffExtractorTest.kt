package com.sertum.player.audio.extractor

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.EOFException

class AiffExtractorTest {

    @Test
    fun `parses 16-bit stereo 44100 AIFF and emits PCM samples`() {
        val frames = 1000
        val data = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val s = (1000 * i).toShort()
            data[i * 4] = (s.toInt() ushr 8).toByte()
            data[i * 4 + 1] = s.toByte()
            data[i * 4 + 2] = (s.toInt() ushr 8).toByte()
            data[i * 4 + 3] = s.toByte()
        }
        val aiff = buildAiff(44100, 2, 16, frames, data)

        val input = FakeExtractorInput(aiff)
        val output = FakeExtractorOutput()
        val extractor = AiffExtractor()
        assertThat(extractor.sniff(input)).isTrue()

        extractor.init(output)
        var result = Extractor.RESULT_CONTINUE
        var guard = 0
        while (result == Extractor.RESULT_CONTINUE && guard++ < 1000) {
            result = extractor.read(input, PositionHolder())
        }
        extractor.release()

        assertThat(result).isEqualTo(Extractor.RESULT_END_OF_INPUT)
        assertThat(output.format).isNotNull()
        assertThat(output.format!!.sampleRate).isEqualTo(44100)
        assertThat(output.format!!.channelCount).isEqualTo(2)
        assertThat(output.format!!.pcmEncoding).isEqualTo(C.ENCODING_PCM_16BIT)
        assertThat(output.seekMap).isNotNull()
        assertThat(output.seekMap!!.isSeekable).isTrue()
        assertThat(output.data.toByteArray()).isEqualTo(toLittleEndian16(data))
    }

    @Test
    fun `seek map converts a time to a sample byte offset`() {
        val frames = 1000
        val aiff = buildAiff(44100, 2, 16, frames, ByteArray(frames * 4))
        val output = parseToEnd(aiff)

        val map = output.seekMap!!
        // Duration comes from the COMM frame count: 1000 frames at 44100Hz.
        assertThat(map.durationUs).isEqualTo(22_675L)

        // Sample data begins after FORM(12) + COMM(26) + SSND header(16) = 54, and a frame is
        // 2 channels x 2 bytes. 10000us is 441 frames in.
        assertThat(map.getSeekPoints(10_000).first.position).isEqualTo(54L + 441L * 4L)
        assertThat(map.getSeekPoints(10_000).first.timeUs).isEqualTo(10_000L)
        // Past the end clamps to the last byte rather than seeking outside the file.
        assertThat(map.getSeekPoints(10_000_000).first.position).isEqualTo(54L + 4_000L)
    }

    @Test
    fun `seeking repositions the frame count and the remaining data`() {
        val frames = 1000
        val data = ByteArray(frames * 4)
        val aiff = buildAiff(44100, 2, 16, frames, data)
        val input = FakeExtractorInput(aiff)
        val output = FakeExtractorOutput()
        val extractor = AiffExtractor()
        extractor.init(output)

        // One chunk per read: header, COMM, SSND. After these the sample data is located.
        repeat(3) { extractor.read(input, PositionHolder()) }
        assertThat(input.position).isEqualTo(54L)

        // Seek to frame 500; the caller moves the input, the extractor keeps the bookkeeping.
        extractor.seek(54L + 500L * 4L, 500L * 1_000_000L / 44_100L)
        input.setRetryPosition(54L + 500L * 4L, RuntimeException("test reposition"))
        val result = extractor.read(input, PositionHolder())

        assertThat(result).isEqualTo(Extractor.RESULT_END_OF_INPUT)
        // 500 of the 1000 frames remain, so half the sample bytes and one timestamp at the end.
        assertThat(output.data.size()).isEqualTo(2_000)
        assertThat(output.lastMetadataTimeUs).isEqualTo(22_675L)
    }

    @Test
    fun `seeking before the sample data is located changes nothing`() {
        val frames = 1000
        val data = ByteArray(frames * 4)
        val aiff = buildAiff(44100, 2, 16, frames, data)
        val input = FakeExtractorInput(aiff)
        val output = FakeExtractorOutput()
        val extractor = AiffExtractor()
        extractor.init(output)

        extractor.seek(1_000L, 5_000L)

        var result = Extractor.RESULT_CONTINUE
        var guard = 0
        while (result == Extractor.RESULT_CONTINUE && guard++ < 1000) {
            result = extractor.read(input, PositionHolder())
        }
        assertThat(result).isEqualTo(Extractor.RESULT_END_OF_INPUT)
        assertThat(output.data.size()).isEqualTo(frames * 4)
    }

    private fun parseToEnd(aiff: ByteArray): FakeExtractorOutput {
        val input = FakeExtractorInput(aiff)
        val output = FakeExtractorOutput()
        val extractor = AiffExtractor()
        extractor.init(output)
        var result = Extractor.RESULT_CONTINUE
        var guard = 0
        while (result == Extractor.RESULT_CONTINUE && guard++ < 1000) {
            result = extractor.read(input, PositionHolder())
        }
        return output
    }

    @Test
    fun `parses runtime-style generated AIFF across the rate and depth matrix`() {
        for (rate in intArrayOf(44_100, 48_000, 96_000, 192_000)) {
            for (bits in intArrayOf(16, 24)) {
                val frames = rate / 10
                val bytesPerSample = bits / 8
                val data = ByteArray(frames * 2 * bytesPerSample) // silent audio is fine
                val aiff = buildAiff(rate, 2, bits, frames, data)

                val input = FakeExtractorInput(aiff)
                val output = FakeExtractorOutput()
                val extractor = AiffExtractor()
                assertThat(extractor.sniff(input)).isTrue()
                extractor.init(output)
                var result = Extractor.RESULT_CONTINUE
                var guard = 0
                while (result == Extractor.RESULT_CONTINUE && guard++ < 100_000) {
                    result = extractor.read(input, PositionHolder())
                }
                extractor.release()

                assertThat(result).isEqualTo(Extractor.RESULT_END_OF_INPUT)
                assertThat(output.format?.sampleRate).isEqualTo(rate)
                assertThat(output.format?.pcmEncoding).isEqualTo(
                    if (bits == 16) C.ENCODING_PCM_16BIT else C.ENCODING_PCM_24BIT,
                )
                assertThat(output.data.size()).isEqualTo(data.size)
            }
        }
    }

    private fun toLittleEndian16(be: ByteArray): ByteArray {
        val le = be.copyOf()
        var i = 0
        while (i + 1 < le.size) {
            val t = le[i]
            le[i] = le[i + 1]
            le[i + 1] = t
            i += 2
        }
        return le
    }

    private fun buildAiff(sampleRate: Int, channels: Int, bits: Int, frames: Int, pcm: ByteArray): ByteArray {
        val commSize = 18
        val ssndSize = 8 + pcm.size
        val formSize = 4 + (8 + commSize) + (8 + ssndSize + (ssndSize % 2))
        val out = ByteArrayOutputStream()
        out.write("FORM".toByteArray(Charsets.US_ASCII))
        writeU32(out, formSize.toLong())
        out.write("AIFF".toByteArray(Charsets.US_ASCII))
        out.write("COMM".toByteArray(Charsets.US_ASCII))
        writeU32(out, commSize.toLong())
        writeU16(out, channels)
        writeU32(out, frames.toLong())
        writeU16(out, bits)
        out.write(extended80(sampleRate.toDouble()))
        out.write("SSND".toByteArray(Charsets.US_ASCII))
        writeU32(out, ssndSize.toLong())
        writeU32(out, 0)
        writeU32(out, 0)
        out.write(pcm)
        if (ssndSize % 2 != 0) out.write(0)
        return out.toByteArray()
    }

    private fun extended80(value: Double): ByteArray {
        val b = ByteArray(10)
        if (value == 0.0) return b
        var exponent = 16383
        var mantissa = value
        while (mantissa >= 2.0) { mantissa /= 2.0; exponent++ }
        while (mantissa < 1.0) { mantissa *= 2.0; exponent-- }
        val fraction = mantissa - 1.0
        val fractionBits = (fraction * 9223372036854775808.0).toLong()
        val m = (1L shl 63) or (fractionBits and 0x7FFFFFFFFFFFFFFFL)
        b[0] = (exponent ushr 8).toByte()
        b[1] = exponent.toByte()
        for (i in 2..9) b[i] = ((m ushr ((9 - i) * 8)) and 0xFF).toByte()
        return b
    }

    private fun writeU16(out: ByteArrayOutputStream, v: Int) {
        out.write(v ushr 8)
        out.write(v and 0xFF)
    }

    private fun writeU32(out: ByteArrayOutputStream, v: Long) {
        out.write(((v ushr 24) and 0xFF).toInt())
        out.write(((v ushr 16) and 0xFF).toInt())
        out.write(((v ushr 8) and 0xFF).toInt())
        out.write((v and 0xFF).toInt())
    }

    private class FakeExtractorInput(private val data: ByteArray) : ExtractorInput {
        private var pos = 0
        private var peekPos = 0

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (pos >= data.size) return -1
            val n = minOf(length, data.size - pos)
            System.arraycopy(data, pos, buffer, offset, n)
            pos += n
            return n
        }

        override fun readFully(buffer: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            var total = 0
            while (total < length) {
                val n = read(buffer, offset + total, length - total)
                if (n == -1) return allowEndOfInput
                if (n == 0) return false
                total += n
            }
            return true
        }

        override fun readFully(buffer: ByteArray, offset: Int, length: Int) {
            if (!readFully(buffer, offset, length, false)) throw EOFException()
        }

        override fun skip(length: Int): Int {
            val n = minOf(length, data.size - pos).coerceAtLeast(0)
            pos += n
            return n
        }

        override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
            if (pos + length > data.size) return allowEndOfInput
            pos += length
            return true
        }

        override fun skipFully(length: Int) {
            if (!skipFully(length, false)) throw EOFException()
        }

        override fun peek(buffer: ByteArray, offset: Int, length: Int): Int {
            if (peekPos >= data.size) return -1
            val n = minOf(length, data.size - peekPos)
            System.arraycopy(data, peekPos, buffer, offset, n)
            return n
        }

        override fun peekFully(buffer: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            var total = 0
            while (total < length) {
                val n = peek(buffer, offset + total, length - total)
                if (n == -1) return allowEndOfInput
                if (n == 0) return false
                total += n
            }
            return true
        }

        override fun peekFully(buffer: ByteArray, offset: Int, length: Int) {
            if (!peekFully(buffer, offset, length, false)) throw EOFException()
        }

        override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean {
            if (peekPos + length > data.size) return allowEndOfInput
            peekPos += length
            return true
        }

        override fun advancePeekPosition(length: Int) {
            if (!advancePeekPosition(length, false)) throw EOFException()
        }

        override fun resetPeekPosition() { peekPos = 0 }
        override fun getPeekPosition(): Long = peekPos.toLong()
        override fun getPosition(): Long = pos.toLong()
        override fun getLength(): Long = data.size.toLong()

        override fun <E : Throwable> setRetryPosition(position: Long, error: E) {
            pos = position.toInt()
        }
    }

    private class FakeExtractorOutput : ExtractorOutput {
        var format: Format? = null
        var seekMap: SeekMap? = null
        var lastMetadataTimeUs = -1L
        val data = ByteArrayOutputStream()

        override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
            override fun format(format: Format) {
                this@FakeExtractorOutput.format = format
            }

            override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
                val buf = ByteArray(length)
                val read = input.read(buf, 0, length)
                if (read > 0) data.write(buf, 0, read)
                return read
            }

            override fun sampleData(input: ParsableByteArray, length: Int, sampleDataPart: Int) {
                val buf = ByteArray(length)
                input.readBytes(buf, 0, length)
                data.write(buf, 0, length)
            }

            override fun sampleMetadata(
                timeUs: Long,
                flags: Int,
                size: Int,
                offset: Int,
                cryptoData: TrackOutput.CryptoData?,
            ) {
                this@FakeExtractorOutput.lastMetadataTimeUs = timeUs
            }
        }

        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) {
            this.seekMap = seekMap
        }
    }
}
