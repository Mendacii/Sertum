package com.sertum.player.audio.backend

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.sertum.player.BuildConfig
import com.sertum.player.audio.toOutputRef
import com.sertum.player.audio.usbOutputDeviceId
import com.sertum.player.data.diagnostics.DiagnosticLevel
import com.sertum.player.data.diagnostics.DiagnosticsStore
import com.sertum.player.domain.playback.AudioOutputBackend
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Routes Media3 audio output between the system AudioTrack provider
 * (speaker / wired / Bluetooth) and the AAudio-exclusive backend (ADR-0001).
 * Switching is never hot: the coordinator stops the player, flips
 * [exclusiveEnabled], and re-prepares so the new output is configured
 * from scratch at the target sample rate (PRD 7.13).
 */
@androidx.annotation.OptIn(UnstableApi::class)
class RoutedAudioOutputProvider(
    context: Context,
    /**
     * Diagnostics sink for the debug-only PCM tap. `null` keeps every build on the no-tap path; the
     * app always injects its single store, so the tap's lines land in the same file as the transport
     * and route logs.
     */
    private val diagnostics: DiagnosticsStore? = null,
) : androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider(
    androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider.Builder(context).build(),
) {

    private val appContext = context.applicationContext

    /** A-22 fallback: system music volume -> gain, only when the reader turns it on. */
    private val softwareVolume = SoftwareVolumeController(appContext)

    /**
     * Writer for the debug PCM tap, or null when debug is off or no store was injected.
     *
     * The tap computes its numbers on the playback thread (the bytes are there), but a diagnostics
     * line is file I/O and has no business delaying an audio callback. One daemon thread serialises
     * the lines in order; release shuts it down.
     */
    private val pcmTapWriter: ExecutorService? =
        if (BuildConfig.DEBUG && diagnostics != null) {
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "sertum-pcm-tap").apply { isDaemon = true }
            }
        } else {
            null
        }

    @Volatile
    var exclusiveBackend: AudioOutputBackend? = null

    @Volatile
    var exclusiveEnabled: Boolean = false

    /**
     * Mirrors ExoPlayer.playWhenReady (set by PlaybackCoordinator). The
     * AAudio exclusive stream cannot be written before AAudioStream_requestStart;
     * [BackendAudioOutput] uses this to lazily start the stream before the
     * first PCM write instead of blocking forever.
     */
    @Volatile
    var playWhenReady: Boolean = false

    /**
     * Source bit depth of the last compressed format seen by
     * [getFormatSupport]. Media3 converts 24/32-bit decoded sources to float;
     * this side channel lets [BackendAudioOutput] pack float back to the
     * source depth instead of blindly using 24-bit.
     */
    @Volatile
    private var pendingSourceBitDepth = 24

    /** Depth to pack at for the output being configured; set by [getOutputConfig]. */
    @Volatile
    private var pendingOutputBitDepth = 24

    /**
     * Mime type of the last non-raw source seen by [getFormatSupport].
     * Media3 forces decoded output to float when the sink directly supports
     * PCM_FLOAT; the Xiaomi 12S ALAC decoders reject that configuration, so
     * ALAC is the one codec allowed to decode at its native depth.
     */
    @Volatile
    private var pendingSourceMimeType: String? = null

    private val exclusiveOutputs = java.util.concurrent.CopyOnWriteArrayList<BackendAudioOutput>()

    /**
     * Whether the current source's bit depth survives the route.
     *
     * Delivering 24 bits to a 32-bit source is a real conversion, so the badge must not call that
     * route bit-perfect. Nothing in the library is 32-bit today; this keeps the claim honest if one
     * ever is.
     */
    fun isSourceDepthPreserved(): Boolean = depthIsPreserved(pendingSourceBitDepth)

    private fun isExclusiveRoute(): Boolean =
        exclusiveEnabled && exclusiveBackend != null && usbDeviceId() != null

    /**
     * The attached DAC's device id, or null when there is none.
     *
     * The exclusive route requires one: without a named device the stream would open on the
     * default output - the phone's own speaker - and still report itself as exclusive.
     */
    private fun usbDeviceId(): Int? = runCatching {
        val audioManager = appContext.getSystemService(android.media.AudioManager::class.java)
            ?: return null
        usbOutputDeviceId(
            audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).map { it.toOutputRef() },
        )
    }.getOrNull()

    override fun getFormatSupport(
        formatConfig: androidx.media3.exoplayer.audio.AudioOutputProvider.FormatConfig,
    ): androidx.media3.exoplayer.audio.AudioOutputProvider.FormatSupport {
        if (!isExclusiveRoute()) return super.getFormatSupport(formatConfig)
        val pcmEncoding = formatConfig.format.pcmEncoding
        // Only the container/codec format describes the source. Media3 also probes *candidate
        // output* formats here - `audio/raw` at 16-bit among them - and letting those write this
        // field made a 24-bit source pack down to 16 bits, which is exactly the fidelity the
        // exclusive path exists to keep.
        if (formatConfig.format.sampleMimeType != MimeTypes.AUDIO_RAW) {
            pendingSourceMimeType = formatConfig.format.sampleMimeType
            pendingSourceBitDepth = when (pcmEncoding) {
                C.ENCODING_PCM_16BIT -> 16
                C.ENCODING_PCM_24BIT -> 24
                C.ENCODING_PCM_32BIT -> 32
                else -> pendingSourceBitDepth
            }
        }
        // A 32-bit PCM stream is offered as raw too, and that probe is skipped above; catching it
        // here keeps the depth honest for raw 32-bit without letting 16-bit probes lower the source.
        if (pcmEncoding == C.ENCODING_PCM_32BIT) pendingSourceBitDepth = 32
        android.util.Log.d(
            "SertumRoute",
            "support mime=${formatConfig.format.sampleMimeType} pcm=${formatConfig.format.pcmEncoding} " +
                "rate=${formatConfig.format.sampleRate} ch=${formatConfig.format.channelCount} " +
                "pendingDepth=$pendingSourceBitDepth",
        )
        // Direct support only for raw PCM families the AAudio backend
        // consumes. Compressed formats carry pcmEncoding metadata too
        // (e.g. FLAC stream info), so sampleMimeType must be audio/raw —
        // everything else must go through MediaCodec decoding first.
        val isRawPcm = MimeTypes.AUDIO_RAW == formatConfig.format.sampleMimeType
        val level = when {
            !isRawPcm ->
                androidx.media3.exoplayer.audio.AudioOutputProvider.FORMAT_UNSUPPORTED
            pcmEncoding == C.ENCODING_PCM_16BIT || pcmEncoding == C.ENCODING_PCM_24BIT ->
                androidx.media3.exoplayer.audio.AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY
            pcmEncoding == C.ENCODING_PCM_FLOAT && pendingSourceMimeType == MimeTypes.AUDIO_ALAC ->
                androidx.media3.exoplayer.audio.AudioOutputProvider.FORMAT_SUPPORTED_WITH_TRANSCODING
            pcmEncoding == C.ENCODING_PCM_FLOAT ->
                androidx.media3.exoplayer.audio.AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY
            else ->
                androidx.media3.exoplayer.audio.AudioOutputProvider.FORMAT_UNSUPPORTED
        }
        return androidx.media3.exoplayer.audio.AudioOutputProvider.FormatSupport.Builder()
            .setFormatSupportLevel(level)
            .build()
    }

    override fun getOutputConfig(
        formatConfig: androidx.media3.exoplayer.audio.AudioOutputProvider.FormatConfig,
    ): androidx.media3.exoplayer.audio.AudioOutputProvider.OutputConfig {
        if (!isExclusiveRoute()) return super.getOutputConfig(formatConfig)
        val encoding = when (formatConfig.format.pcmEncoding) {
            C.ENCODING_PCM_24BIT -> C.ENCODING_PCM_24BIT
            C.ENCODING_PCM_FLOAT -> C.ENCODING_PCM_FLOAT
            else -> C.ENCODING_PCM_16BIT
        }
        // The depth to pack at follows the format being configured, not a value accumulated from
        // probes: float and 24-bit int both carry at most a 24-bit sample, and anything narrower is
        // 16. This is stored for the getAudioOutput call that always follows this one.
        pendingOutputBitDepth = when (formatConfig.format.pcmEncoding) {
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_32BIT -> 24
            else -> 16
        }
        android.util.Log.i(
            "SertumRoute",
            "config -> encoding=$encoding depth=$pendingOutputBitDepth " +
                "for pcm=${formatConfig.format.pcmEncoding} rate=${formatConfig.format.sampleRate}",
        )
        val channelMask = when (formatConfig.format.channelCount) {
            2 -> android.media.AudioFormat.CHANNEL_OUT_STEREO
            else -> android.media.AudioFormat.CHANNEL_OUT_MONO
        }
        return androidx.media3.exoplayer.audio.AudioOutputProvider.OutputConfig.Builder()
            .setEncoding(encoding)
            .setSampleRate(formatConfig.format.sampleRate)
            .setChannelMask(channelMask)
            .setIsTunneling(false)
            .setIsOffload(false)
            .setBufferSize(formatConfig.preferredBufferSize)
            .setAudioAttributes(formatConfig.audioAttributes)
            .setAudioSessionId(formatConfig.audioSessionId)
            .setVirtualDeviceId(formatConfig.virtualDeviceId)
            .setUsePlaybackParameters(formatConfig.enablePlaybackParameters)
            .build()
    }

    override fun getAudioOutput(
        outputConfig: androidx.media3.exoplayer.audio.AudioOutputProvider.OutputConfig,
    ): androidx.media3.exoplayer.audio.AudioOutput {
        if (!isExclusiveRoute()) return super.getAudioOutput(outputConfig)
        val backend = exclusiveBackend
            ?: throw androidx.media3.exoplayer.audio.AudioOutputProvider.InitializationException(
                IllegalStateException("exclusive backend missing"),
            )
        return BackendAudioOutput(
            backend,
            outputConfig,
            pendingOutputBitDepth,
            playWhenReady = { playWhenReady },
            deviceId = usbDeviceId() ?: 0,
            pcmTap = debugPcmTap(outputConfig, Integer.bitCount(outputConfig.channelMask)),
            softwareVolumeEnabled = { softwareVolume.isEnabled() },
            softwareVolumeGain = { softwareVolume.gain() },
        ).also { exclusiveOutputs.add(it) }
    }

    /**
     * The debug-only per-second PCM fingerprint, or null where it must not exist.
     *
     * `BuildConfig.DEBUG` is the compile-time gate: in release this body reduces to `return null`,
     * so the adapter takes its no-tap path and nothing is buffered, hashed or written.
     */
    private fun debugPcmTap(
        outputConfig: androidx.media3.exoplayer.audio.AudioOutputProvider.OutputConfig,
        channels: Int,
    ): ((ByteArray) -> Unit)? {
        if (!BuildConfig.DEBUG) return null
        val writer = pcmTapWriter ?: return null
        val store = diagnostics ?: return null
        val sampleRate = outputConfig.sampleRate
        if (sampleRate <= 0 || channels <= 0) return null
        val aggregator = PcmTapAggregator(sampleRate, channels, pendingOutputBitDepth) { line ->
            // The aggregator calls this from the playback thread; hop to the writer so file I/O
            // never sits inside an audio drain. A shutdown race is swallowed by the aggregator's
            // sink guard and only costs that one line.
            writer.execute { store.log(DiagnosticLevel.INFO, "pcm", line) }
        }
        return aggregator::accept
    }

    override fun release() {
        exclusiveOutputs.forEach { it.release() }
        exclusiveOutputs.clear()
        softwareVolume.close()
        pcmTapWriter?.shutdown()
        super.release()
    }
}
