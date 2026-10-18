package com.sertum.player.audio.backend

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.sertum.player.audio.toOutputRef
import com.sertum.player.audio.usbOutputDeviceId
import com.sertum.player.domain.playback.AudioOutputBackend

/**
 * Routes Media3 audio output between the system AudioTrack provider
 * (speaker / wired / Bluetooth) and the AAudio-exclusive backend (ADR-0001).
 * Switching is never hot: the coordinator stops the player, flips
 * [exclusiveEnabled], and re-prepares so the new output is configured
 * from scratch at the target sample rate (PRD 7.13).
 */
@androidx.annotation.OptIn(UnstableApi::class)
class RoutedAudioOutputProvider(context: Context) :
    androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider(
        androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider.Builder(context).build(),
    ) {

    private val appContext = context.applicationContext

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

    /**
     * Mime type of the last non-raw source seen by [getFormatSupport].
     * Media3 forces decoded output to float when the sink directly supports
     * PCM_FLOAT; the Xiaomi 12S ALAC decoders reject that configuration, so
     * ALAC is the one codec allowed to decode at its native depth.
     */
    @Volatile
    private var pendingSourceMimeType: String? = null

    private val exclusiveOutputs = java.util.concurrent.CopyOnWriteArrayList<BackendAudioOutput>()

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
        if (formatConfig.format.sampleMimeType != MimeTypes.AUDIO_RAW) {
            pendingSourceMimeType = formatConfig.format.sampleMimeType
            when (pcmEncoding) {
                C.ENCODING_PCM_16BIT -> pendingSourceBitDepth = 16
                C.ENCODING_PCM_24BIT -> pendingSourceBitDepth = 24
                C.ENCODING_PCM_32BIT -> pendingSourceBitDepth = 24
            }
        }
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
            pendingSourceBitDepth,
            playWhenReady = { playWhenReady },
            deviceId = usbDeviceId() ?: 0,
        ).also { exclusiveOutputs.add(it) }
    }

    override fun release() {
        exclusiveOutputs.forEach { it.release() }
        exclusiveOutputs.clear()
        super.release()
    }
}
