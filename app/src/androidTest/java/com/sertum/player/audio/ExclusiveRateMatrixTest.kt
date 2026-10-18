package com.sertum.player.audio

import android.media.AudioFormat
import android.media.AudioManager
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertWithMessage
import com.sertum.player.audio.backend.AaudioExclusiveBackend
import com.sertum.player.audio.backend.BackendAudioOutput
import com.sertum.player.domain.playback.StreamSpec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The rate and depth matrix PRD 7.2 asks for: 44.1, 48, 96 and 192 kHz at 16 and 24 bit, each
 * opened on its own exclusive stream, with the stream's own parameters read back and compared.
 *
 * Evidence items 2 and 3 of that section are "the backend's stream parameters match the source,
 * per track" and "a rate-switching matrix log covers 44.1 / 48 / 96 / 192". The audit next door
 * proves one point of it - 44.1 kHz at 24 bit - and this covers the rest, rebuilding the stream for
 * every rate rather than reusing one, which is also what a real rate change does.
 *
 * It also captures `dumpsys media.audio_flinger` while a stream is open, which is evidence item 1:
 * no resampler and no effects attached to our track. The whole dump is kept rather than asserted
 * against, because what the platform prints around it is not ours to pin.
 *
 * Skips itself with no USB audio device attached.
 *
 * Run with: adb shell am instrument -w -e class com.sertum.player.audio.ExclusiveRateMatrixTest \
 *           com.sertum.player.test/androidx.test.runner.AndroidJUnitRunner
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ExclusiveRateMatrixTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private data class Row(
        val requestedRate: Int,
        val requestedDepth: Int,
        val actualRate: Int,
        val actualFormat: Int,
        val sharingMode: Int,
        val deviceId: Int,
        val requestedDeviceId: Int,
        val framesPerBurst: Int,
        val expectedBytes: Long,
        val submittedBytes: Long,
        val accepted: Boolean,
        val note: String,
    ) {
        /** AAudio's format codes: 1 = PCM_I16, 3 = PCM_I24_PACKED. */
        val actualDepth: Int get() = when (actualFormat) {
            1 -> 16
            3 -> 24
            4 -> 32
            else -> 0
        }

        val rateMatches: Boolean get() = actualRate == requestedRate
        val deviceMatches: Boolean
            get() = requestedDeviceId <= 0 || deviceId == requestedDeviceId
        val depthMatches: Boolean get() = actualDepth == requestedDepth
        val isExclusive: Boolean get() = sharingMode == 0
        val bytesMatch: Boolean get() = submittedBytes == expectedBytes
    }

    @Test
    fun everyRateAndDepthOpensAnExclusiveStreamAtTheSourceRate() {
        Assume.assumeTrue("no USB audio device attached", hasUsbAudioDevice())

        val rows = mutableListOf<Row>()
        var flinger = ""
        val dumps = linkedMapOf<String, String>()

        for (depth in DEPTHS) {
            for (rate in RATES) {
                // Two ends of the matrix, while each stream is open.
                val capture = depth == 24 && (rate == 44_100 || rate == 192_000)
                val (row, dump) = measure(rate, depth, capture)
                rows += row
                if (dump.isNotEmpty()) {
                    dumps["flinger_${rate}_${depth}"] = dump
                    if (flinger.isEmpty()) flinger = dump
                }
            }
        }

        writeReport(rows, flinger, dumps)

        val failures = rows.filter { !(it.isExclusive && it.rateMatches && it.depthMatches && it.bytesMatch && it.deviceMatches) }
        assertWithMessage(
            buildString {
                appendLine("rate/depth matrix did not hold:")
                failures.forEach {
                    appendLine(
                        "  requested ${it.requestedRate}Hz/${it.requestedDepth}bit -> " +
                            "actual ${it.actualRate}Hz/${it.actualDepth}bit, " +
                            "exclusive=${it.isExclusive}, bytes ${it.submittedBytes}/${it.expectedBytes}" +
                            if (it.note.isNotEmpty()) " (${it.note})" else "",
                    )
                }
            },
        ).that(failures).isEmpty()

        assertWithMessage("the audio flinger dump was empty").that(flinger.length).isGreaterThan(1000)
    }


    /**
     * A mono 24-bit stream must report every frame it wrote.
     *
     * The frame size used to be a constant six bytes for 24-bit - true only for stereo - so a mono
     * write of 256 frames came back as 128 and the other half of the buffer was submitted again.
     * 256 frames at 96 kHz is under three milliseconds, so the stream accepts all of it in one go
     * and the number is the accounting, not the HAL.
     */
    @Test
    fun mono24ReportsEveryFrameItWrote() {
        Assume.assumeTrue("no USB audio device attached", hasUsbAudioDevice())
        val usbId = usbAudioDeviceId() ?: 0
        val backend = AaudioExclusiveBackend()
        backend.open(StreamSpec(96_000, 1, 24, isExclusive = true, deviceId = usbId)).getOrThrow()
        backend.play().getOrThrow()
        try {
            val frames = 256
            val bytes = ByteArray(frames * 3)
            val written = backend.writePcm(bytes, 0, bytes.size).getOrThrow()
            assertWithMessage("mono 24-bit frame accounting").that(written).isEqualTo(frames)
        } finally {
            backend.release()
        }
    }

    private fun measure(rate: Int, depth: Int, capture: Boolean): Pair<Row, String> {
        val backend = AaudioExclusiveBackend()
        val usbId = usbAudioDeviceId() ?: 0
        val opened = backend.open(StreamSpec(rate, CHANNELS, depth, isExclusive = true, deviceId = usbId))
        if (opened.isFailure) {
            return Row(
                rate, depth, 0, 0, 0, usbId, 0, 0, 0, 0, false,
                "open failed: ${opened.exceptionOrNull()?.message}",
            ).let { it to "" }
        }

        val config = AudioOutputProvider.OutputConfig.Builder()
            .setEncoding(C.ENCODING_PCM_FLOAT)
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setIsTunneling(false)
            .setIsOffload(false)
            .build()

        val frames = 4096
        var tappedBytes = 0L
        val output = BackendAudioOutput(
            backend,
            config,
            targetBitDepth = depth,
            playWhenReady = { true },
            pcmTap = { tappedBytes += it.size },
        )

        // A deterministic ramp: every sample is an exact multiple of 1/2^23, so a converter that
        // rounds or shifts shows up rather than being hidden inside the signal.
        val samples = FloatArray(frames * CHANNELS)
        for (i in samples.indices) samples[i] = (i % 4096) / 8_388_608f
        val buffer = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buffer.putFloat(it) }
        buffer.flip()

        val accepted = output.write(buffer, frames, C.TIME_END_OF_SOURCE)
        Thread.sleep(300)
        val info = backend.streamInfo
        // Captured while the stream is still open: after release the platform has nothing to show
        // about it, which is how the earlier dump ended up describing everyone else's audio.
        val flinger = if (capture) captureAudioFlinger() else ""
        output.release()

        val expected = frames.toLong() * CHANNELS * (depth / 8)
        val note = when {
            info.actualRate != rate -> "the device refused this rate"
            info.actualFormat != if (depth == 24) 3 else 1 -> "the device opened a different format"
            else -> ""
        }
        return Row(
            requestedRate = rate,
            requestedDepth = depth,
            actualRate = info.actualRate,
            actualFormat = info.actualFormat,
            sharingMode = info.sharingMode,
            deviceId = info.deviceId,
            requestedDeviceId = usbId,
            framesPerBurst = info.framesPerBurst,
            expectedBytes = expected,
            submittedBytes = tappedBytes,
            accepted = accepted,
            note = note,
        ).let { it to flinger }
    }

    private fun captureAudioFlinger(): String = captureShell("dumpsys media.audio_flinger")

    private fun captureShell(command: String): String = runCatching {
        val descriptor = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        java.io.FileInputStream(descriptor.fileDescriptor).use { stream ->
            stream.bufferedReader().readText()
        }
    }.getOrDefault("")

    private fun writeReport(rows: List<Row>, flinger: String, dumps: Map<String, String>) {
        val matrix = JSONArray()
        rows.forEach { row ->
            matrix.put(
                JSONObject().apply {
                    put("requestedRate", row.requestedRate)
                    put("requestedDepth", row.requestedDepth)
                    put("actualRate", row.actualRate)
                    put("actualFormat", row.actualFormat)
                    put("actualDepth", row.actualDepth)
                    put("sharingMode", row.sharingMode)
                    put("deviceId", row.deviceId)
                    put("requestedDeviceId", row.requestedDeviceId)
                    put("deviceMatches", row.deviceMatches)
                    put("framesPerBurst", row.framesPerBurst)
                    put("expectedBytes", row.expectedBytes)
                    put("submittedBytes", row.submittedBytes)
                    put("rateMatches", row.rateMatches)
                    put("depthMatches", row.depthMatches)
                    put("exclusive", row.isExclusive)
                    put("note", row.note)
                },
            )
        }
        val report = JSONObject().apply {
            put("channels", CHANNELS)
            put("rows", matrix)
            put("allMatch", rows.all { it.isExclusive && it.rateMatches && it.depthMatches && it.bytesMatch && it.deviceMatches })
        }
        val name = "exclusive-rate-matrix.json"
        runCatching { File(context.filesDir, name).writeText(report.toString(2)) }
        // Also beside the app's own files: the runner's additional-output directory only exists when
        // the test is driven by Gradle, and `am instrument` is how this is actually run.
        runCatching { File(context.filesDir, "audio_flinger.txt").writeText(flinger) }
        dumps.forEach { (tag, text) ->
            runCatching { File(context.filesDir, "$tag.txt").writeText(text) }
        }
        context.getExternalFilesDir(null)?.let { runCatching { File(it, name).writeText(report.toString(2)) } }
        InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { dir ->
            runCatching {
                File(dir).mkdirs()
                File(dir, name).writeText(report.toString(2))
                File(dir, "audio_flinger.txt").writeText(flinger)
            }
        }
    }

    /** The attached DAC's `AudioDeviceInfo.id`, the value the stream is told to open on. */
    private fun usbAudioDeviceId(): Int? = runCatching {
        context.getSystemService(AudioManager::class.java)
            ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.firstOrNull {
                it.type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE ||
                    it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
            }
            ?.id
    }.getOrNull()

    private fun hasUsbAudioDevice(): Boolean {
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return false
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }

    private companion object {
        val RATES = intArrayOf(44_100, 48_000, 96_000, 192_000)
        val DEPTHS = intArrayOf(16, 24)
        const val CHANNELS = 2
    }
}
