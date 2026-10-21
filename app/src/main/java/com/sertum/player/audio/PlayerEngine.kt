package com.sertum.player.audio

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes as M3AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.sertum.player.audio.backend.PreferHardwareCodecSelector
import com.sertum.player.audio.backend.RoutedAudioOutputProvider
import com.sertum.player.audio.extractor.SertumExtractorsFactory
import com.sertum.player.data.diagnostics.DiagnosticsStore

/**
 * Owns the Media3 player. Audio output is routed by [router]:
 * system AudioTrack by default, AAudio EXCLUSIVE when the user selects
 * USB-exclusive mode (ADR-0001). Float output keeps 24-bit sources
 * bit-exact through the exclusive path (BackendAudioOutput packs float
 * back to 24-bit PCM).
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerEngine(
    context: Context,
    /**
     * The app's single diagnostics store. Only the debug PCM tap reads it; the router treats it as
     * an optional sink, so a test or a future caller without one stays on the no-tap path.
     */
    diagnostics: DiagnosticsStore? = null,
) {

    val router = RoutedAudioOutputProvider(context.applicationContext, diagnostics)

    private val renderersFactory = DefaultRenderersFactory(context)
        .setEnableAudioFloatOutput(true)
        .setMediaCodecSelector(PreferHardwareCodecSelector)

    val player: ExoPlayer = ExoPlayer.Builder(context, renderersFactory)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(context, SertumExtractorsFactory()),
        )
        .setAudioOutputProvider(router)
        .setAudioAttributes(
            M3AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    fun playUri(uri: Uri) {
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.play()
    }

    fun release() {
        // ExoPlayer.release() releases the routed AudioOutputProvider through
        // DefaultAudioSink on the correct playback thread; do not release it
        // again from the calling thread.
        player.release()
    }
}
