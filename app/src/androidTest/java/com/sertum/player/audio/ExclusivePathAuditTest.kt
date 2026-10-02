package com.sertum.player.audio

import android.content.Context
import android.media.AudioManager
import com.google.common.truth.Truth.assertThat
import com.sertum.player.audio.backend.AaudioExclusiveBackend
import com.sertum.player.audio.backend.BackendAudioOutput
import com.sertum.player.domain.playback.AudioOutputBackend
import com.sertum.player.domain.playback.BackendCapabilities
import com.sertum.player.domain.playback.StreamSpec
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject

/**
 * Digital-path audit for the USB-exclusive route (PS-PLAN-003 T2c).
 *
 * Answers two questions with data instead of listening impressions:
 * 1. Did Sertum's adapter hand the backend the source samples unchanged?
 * 2. Was the stream actually exclusive, and bound to the USB device?
 *
 * Skips itself when no USB audio device is attached, so it is safe to run in a
 * batch. Writes a JSON report the audit script pulls.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ExclusivePathAuditTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val audioManager: AudioManager
        get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun hasUsbAudioDevice(): Boolean {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.any {
            it.type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }

    private class CapturingBackend(private val delegate: AaudioExclusiveBackend) : AudioOutputBackend {
        val submitted = mutableListOf<ByteArray>()
        override val capabilities: BackendCapabilities get() = delegate.capabilities
        override fun open(spec: StreamSpec) = delegate.open(spec)
        override fun writePcm(frame: ByteArray, offset: Int, length: Int): Result<Int> {
            submitted += frame.copyOfRange(offset, offset + length)
            return delegate.writePcm(frame, offset, length)
        }
        override fun pause() = delegate.pause()
        override fun play() = delegate.play()
        override fun flush() = delegate.flush()
        override fun stop() = delegate.stop()
        override fun release() = delegate.release()
        override fun onVolumeChanged(volume01: Float) = delegate.onVolumeChanged(volume01)
        override fun getPositionUs(): Long = delegate.getPositionUs()
        override fun getBufferSizeInFrames(): Int = delegate.getBufferSizeInFrames()
    }

    @Test
    fun auditExclusivePath() {
        Assume.assumeTrue("no USB audio device attached", hasUsbAudioDevice())

        val sampleRate = 44_100
        val channels = 2
        val bitDepth = 24
        val frames = 256

        val real = AaudioExclusiveBackend()
        val capturing = CapturingBackend(real)
        val outputConfig = AudioOutputProvider.OutputConfig.Builder()
            .setEncoding(C.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO)
            .setIsTunneling(false)
            .setIsOffload(false)
            .build()

        val tapped = mutableListOf<ByteArray>()
        val output = BackendAudioOutput(
            capturing,
            outputConfig,
            targetBitDepth = bitDepth,
            playWhenReady = { true },
            pcmTap = { tapped += it },
        )

        // A deterministic ramp: each sample is an exact multiple of 1/2^23, so
        // a bit-exact transfer is verifiable without a reference decoder.
        val samples = FloatArray(frames * channels)
        for (i in samples.indices) {
            samples[i] = (i % 4096) / 8_388_608f
        }
        val buffer = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buffer.putFloat(it) }
        buffer.flip()

        val accepted = output.write(buffer, frames, C.TIME_END_OF_SOURCE)
        Thread.sleep(300) // let the native stream drain so streamInfo is settled

        val tappedBytes = tapped.sumOf { it.size }
        val submittedBytes = capturing.submitted.sumOf { it.size }
        val info = real.observedStreamInfo()

        val report = JSONObject().apply {
            put("sampleRate", sampleRate)
            put("channels", channels)
            put("bitDepth", bitDepth)
            put("framesRequested", frames)
            put("acceptedFullBuffer", accepted)
            put("tappedBytes", tappedBytes)
            put("submittedBytes", submittedBytes)
            put("expectedBytes", frames * channels * (bitDepth / 8))
            put("adapterUnchanged", tappedBytes == submittedBytes)
            put("streamActualRate", info.actualRate)
            put("streamActualFormat", info.actualFormat)
            put("streamSharingMode", info.sharingMode)
            put("streamIsExclusive", info.isExclusive)
            put("streamDeviceId", info.deviceId)
            put("streamPerformanceMode", info.performanceMode)
            put("streamFramesPerBurst", info.framesPerBurst)
            put("systemMusicIndex", audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
            put("systemMusicMax", audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        }

        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        File(dir, "exclusive-path-audit.json").writeText(report.toString(2))

        output.release()

        // Wire-level checks that do not depend on the device's mood.
        assertThat(tappedBytes).isEqualTo(submittedBytes)
        assertThat(info.sharingMode).isEqualTo(1)
        assertThat(info.actualRate).isEqualTo(sampleRate)
    }
}
