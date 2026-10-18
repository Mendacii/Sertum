package com.sertum.player.audio

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.sertum.player.audio.extractor.SertumExtractorsFactory
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Plays a real AIFF file through the player this app ships, seeks in it, and checks the result.
 *
 * The unit tests cover the extractor's arithmetic; this covers the claim - that an AIFF can be
 * dragged through - end to end, through Media3's own seek plumbing rather than ours alone. The
 * file is written by the test rather than pushed beforehand, so the check stands on its own and
 * there is no fixture to forget. MediaStore does not index AIFF on this ROM, so it is opened by
 * path instead of through the library.
 *
 * Everything touching the player runs on the main thread: ExoPlayer verifies its accessor thread,
 * and the instrumentation runner's own thread is not it.
 *
 * Run with: adb shell am instrument -w -e class com.sertum.player.audio.AiffSeekDeviceTest \
 *           com.sertum.player.test/androidx.test.runner.AndroidJUnitRunner
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class AiffSeekDeviceTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aiffPlaysAndCanBeSeekedInto() {
        val file = writeAiff(seconds = 6)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ready = CountDownLatch(1)
        lateinit var player: ExoPlayer

        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(
                context,
                DefaultMediaSourceFactory(context, SertumExtractorsFactory()),
            ).build()
            player.addListener(
                object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) ready.countDown()
                    }
                },
            )
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.prepare()
        }

        try {
            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue()

            var duration = 0L
            var seekable = false
            instrumentation.runOnMainSync {
                duration = player.duration
                seekable = player.isCurrentMediaItemSeekable
                // Halfway in, so a seek that did nothing is unmistakable.
                player.seekTo(3_000)
            }

            val deadline = System.currentTimeMillis() + 5_000
            var position = 0L
            while (System.currentTimeMillis() < deadline) {
                instrumentation.runOnMainSync { position = player.currentPosition }
                if (position >= 2_800) break
                Thread.sleep(50)
            }

            assertThat(seekable).isTrue()
            assertThat(duration).isIn(5_900L..6_100L)
            assertThat(position).isAtLeast(2_800L)
        } finally {
            instrumentation.runOnMainSync { player.release() }
            file.delete()
        }
    }

    /** A valid 16-bit stereo 44.1kHz AIFF, PCM big-endian, with a ramp so it is not silence. */
    private fun writeAiff(seconds: Int): File {
        val frames = 44_100 * seconds
        val pcm = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val sample = (i * 7 % 30_000).toShort()
            pcm[i * 4] = (sample.toInt() ushr 8).toByte()
            pcm[i * 4 + 1] = sample.toByte()
            pcm[i * 4 + 2] = (sample.toInt() ushr 8).toByte()
            pcm[i * 4 + 3] = sample.toByte()
        }

        val commSize = 18
        val ssndSize = 8 + pcm.size
        val formSize = 4 + (8 + commSize) + 8 + ssndSize

        val out = ByteArrayOutputStream()
        out.write("FORM".toByteArray(Charsets.US_ASCII))
        writeU32(out, formSize)
        out.write("AIFF".toByteArray(Charsets.US_ASCII))
        out.write("COMM".toByteArray(Charsets.US_ASCII))
        writeU32(out, commSize)
        writeU16(out, 2) // channels
        writeU32(out, frames)
        writeU16(out, 16) // bits per sample
        out.write(extended80(44_100.0))
        out.write("SSND".toByteArray(Charsets.US_ASCII))
        writeU32(out, ssndSize)
        writeU32(out, 0) // offset
        writeU32(out, 0) // block size
        out.write(pcm)

        val file = File(context.cacheDir, "sertum-aiff-seek-test.aiff")
        file.writeBytes(out.toByteArray())
        return file
    }

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write(value ushr 8)
        out.write(value and 0xFF)
    }

    private fun writeU32(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    /** The 80-bit IEEE extended float AIFF uses for the sample rate. */
    private fun extended80(value: Double): ByteArray {
        val bytes = ByteArray(10)
        if (value == 0.0) return bytes
        var exponent = 16383
        var mantissa = value
        while (mantissa >= 2.0) {
            mantissa /= 2.0
            exponent++
        }
        while (mantissa < 1.0) {
            mantissa *= 2.0
            exponent--
        }
        val fractionBits = ((mantissa - 1.0) * 9223372036854775808.0).toLong()
        val m = (1L shl 63) or (fractionBits and 0x7FFFFFFFFFFFFFFFL)
        bytes[0] = (exponent ushr 8).toByte()
        bytes[1] = exponent.toByte()
        for (i in 2..9) bytes[i] = ((m ushr ((9 - i) * 8)) and 0xFF).toByte()
        return bytes
    }
}
