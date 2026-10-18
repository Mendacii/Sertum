package com.sertum.player.audio

import com.sertum.player.ui.settings.SettingsStateHolder
import com.sertum.player.audio.isUsbOutputDeviceId
import com.sertum.player.audio.usbOutputDeviceId
import com.sertum.player.audio.toOutputRef
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.sertum.player.audio.backend.AaudioExclusiveBackend
import com.sertum.player.audio.session.PlaybackService
import com.sertum.player.data.diagnostics.DiagnosticLevel
import com.sertum.player.data.diagnostics.DiagnosticsStore
import com.sertum.player.domain.playback.BitPerfectState
import com.sertum.player.domain.playback.PlaybackErrorPolicy
import com.sertum.player.domain.playback.QueueEngine
import com.sertum.player.domain.playback.RepeatMode
import com.sertum.player.ui.playback.OutputMode
import com.sertum.player.ui.playback.PlaybackStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Current decoded audio parameters, used by the UI and for rate-switch evidence. */
data class AudioStreamInfo(
    val sampleRate: Int = 0,
    val bitDepth: Int = 0,
    val channelCount: Int = 0,
)

data class PlayableTrack(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String?,
    val album: String?,
    val albumKey: String? = null,
    val coverRef: String? = null,
    /**
     * The span of [uri] this track occupies, for a track carved out of a single-file disc rip.
     *
     * Null for an ordinary track, which is the whole file. [endMs] is null on the last track of
     * a rip, whose end is the end of the file - clipping it to an invented end would cut the
     * disc short.
     */
    val startMs: Long? = null,
    val endMs: Long? = null,
)

enum class UsbRecoveryState { NONE, DETACHED, RECOVERING, RECOVERED, FAILED }

/**
 * Playback memory was removed at the user's request (2026-10-02). Tapping a
 * track used to restore the last stored position, so a track played to the end
 * resumed at the end and the user had to scrub back by hand.
 *
 * Every explicit play therefore starts at the beginning. This constant is the
 * single place that decision lives, so it is greppable and testable rather than
 * an inline `0L` buried in an argument list.
 *
 * Consequence, accepted by the user: pausing and picking the same track again
 * also restarts it.
 */
const val PLAYBACK_START_POSITION_MS: Long = 0L

/**
 * Production playback wiring: library tracks -> Media3 -> routed output
 * (system or AAudio EXCLUSIVE) with the PRD 7.13 fault contract and UI
 * projection into [PlaybackStateHolder].
 *
 * Holds no playback memory: see [PLAYBACK_START_POSITION_MS].
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackCoordinator(
    private val context: Context,
    private val engine: PlayerEngine,
    private val diagnostics: DiagnosticsStore,
    private val markTrackUnplayable: suspend (Long) -> Unit = {},
) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = QueueEngine()
    private val errorPolicy = PlaybackErrorPolicy()
    private val exclusiveBackend = AaudioExclusiveBackend()

    /** Set by MainActivity; requests POST_NOTIFICATIONS before the first playback. */
    @Volatile
    var notificationPermissionRequester: (() -> Unit)? = null
    private var notificationPermissionRequested = false

    private var playlist: List<PlayableTrack> = emptyList()

    private val _audioInfo = MutableStateFlow(AudioStreamInfo())
    val audioInfo: StateFlow<AudioStreamInfo> = _audioInfo.asStateFlow()

    private val _playerState = MutableStateFlow(Player.STATE_IDLE)
    val playerState: StateFlow<Int> = _playerState.asStateFlow()

    private val _outputMode = MutableStateFlow(OutputMode.STANDARD)
    val outputMode: StateFlow<OutputMode> = _outputMode.asStateFlow()

    private val _bluetoothConnected = MutableStateFlow(false)
    val bluetoothConnected: StateFlow<Boolean> = _bluetoothConnected.asStateFlow()

    private val _usbRecovery = MutableStateFlow(UsbRecoveryState.NONE)
    val usbRecovery: StateFlow<UsbRecoveryState> = _usbRecovery.asStateFlow()

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    val player: Player get() = engine.player

    init {
        engine.router.exclusiveBackend = exclusiveBackend
        // Output mode is intentionally not restored from storage: it starts on
        // the standard route every launch. Restoring "USB exclusive" without
        // checking that a DAC is actually attached would silently fall back to
        // a shared stream while the UI still claimed exclusive output.
        _outputMode.value = OutputMode.STANDARD
        engine.router.exclusiveEnabled = false
        engine.router.playWhenReady = player.playWhenReady

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateUiState()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                engine.router.playWhenReady = playWhenReady
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _playerState.value = playbackState
                updateUiState()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val id = mediaItem?.mediaId?.toLongOrNull()
                if (id != null) queue.seekTo(id)
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) errorPolicy.onTrackStarted()
                updateUiState()
            }

            override fun onPlayerError(error: PlaybackException) {
                handlePlaybackError(error)
            }
        })

        engine.player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                audioFormat: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                _audioInfo.value = AudioStreamInfo(
                    sampleRate = audioFormat.sampleRate,
                    bitDepth = when (audioFormat.pcmEncoding) {
                        C.ENCODING_PCM_24BIT -> 24
                        C.ENCODING_PCM_32BIT -> 32
                        else -> 16
                    },
                    channelCount = audioFormat.channelCount,
                )
                updateUiState()
            }
        })

        scope.launch {
            while (isActive) {
                if (player.isPlaying || _outputMode.value == OutputMode.USB_EXCLUSIVE) updateUiState()
                delay(POSITION_POLL_MS)
            }
        }
        updateUiState()
    }

    // ---- playback control ----

    /**
     * Starts [tracks] at [startIndex] **from the beginning**.
     *
     * Playback memory was removed at the user's request (2026-10-02): tapping a
     * track used to restore the last position, so a track that had been played to
     * the end resumed at the end and had to be scrubbed back by hand. Every
     * explicit play now starts at 0.
     *
     * Consequence worth knowing: pausing and picking the same track again also
     * restarts it. That follows from "remove the memory" and is the intended
     * behaviour, not an oversight.
     */
    fun playTracks(tracks: List<PlayableTrack>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        ensureNotificationPermission()
        playlist = tracks
        queue.setQueue(tracks.map { it.id }, startIndex)
        startPlaybackService()
        scope.launch {
            player.setMediaItems(
                tracks.map { it.toMediaItem() },
                startIndex,
                PLAYBACK_START_POSITION_MS,
            )
            player.prepare()
            player.play()
            updateUiState()
        }
    }

    fun togglePlayPause() {
        if (playlist.isEmpty()) return
        if (player.isPlaying) {
            player.pause()
        } else {
            ensureNotificationPermission()
            startPlaybackService()
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
        updateUiState()
    }

    fun pause() {
        player.pause()
        updateUiState()
    }

    fun skipToNext() {
        if (playlist.isEmpty()) return
        if (player.currentMediaItemIndex < playlist.size - 1 || player.repeatMode == Player.REPEAT_MODE_ALL) {
            player.seekToNextMediaItem()
        } else {
            player.seekTo(player.currentMediaItemIndex, C.TIME_END_OF_SOURCE)
        }
    }

    fun skipToPrevious() {
        if (playlist.isEmpty()) return
        player.seekToPreviousMediaItem()
    }

    /**
     * Jump straight to a queue row (user feedback 2026-10-02). Shares the
     * service bookkeeping of [togglePlayPause] so tapping a row works even when
     * playback was idle. Seeks to the start of the row, matching [playTracks].
     */
    fun skipToQueueIndex(index: Int) {
        if (index !in playlist.indices) return
        if (player.currentMediaItemIndex != index) {
            player.seekTo(index, 0L)
        }
        ensureNotificationPermission()
        startPlaybackService()
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
        updateUiState()
    }

    fun setRepeat(mode: RepeatMode) {
        queue.setRepeat(mode)
        player.repeatMode = when (mode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
    }

    fun seekTo(positionMs: Long) {
        if (playlist.isNotEmpty()) player.seekTo(positionMs.coerceAtLeast(0L))
        updateUiState()
    }

    fun removeQueueItem(index: Int) {
        if (index !in playlist.indices) return
        player.removeMediaItem(index)
        playlist = playlist.toMutableList().apply { removeAt(index) }.toList()
        queue.removeAt(index)
        if (playlist.isEmpty()) player.stop()
        updateUiState()
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in playlist.indices || toIndex !in playlist.indices) return
        player.moveMediaItem(fromIndex, toIndex)
        val mutable = playlist.toMutableList()
        val item = mutable.removeAt(fromIndex)
        mutable.add(toIndex, item)
        playlist = mutable.toList()
        queue.move(fromIndex, toIndex)
        updateUiState()
    }

    fun clearQueue() {
        player.clearMediaItems()
        playlist = emptyList()
        queue.clear()
        updateUiState()
    }

    // ---- output routing (PRD 7.13: stop, clear state, reopen at target rate) ----

    /**
     * Switches the output route, cold-restarting the player.
     *
     * Returns whether the route actually changed: a refusal (no DAC for the exclusive route) must
     * not leave the caller believing it succeeded, or the UI state it keeps drifts from the audio
     * state it describes.
     */
    fun switchOutputMode(mode: OutputMode): Boolean {
        if (mode == _outputMode.value) return true
        // Exclusive output is exclusive *on the DAC*. Asking for it with nothing plugged in used to
        // flip the flag anyway, and the stream then opened exclusively on whatever the default
        // output was - the phone's own speaker - while the UI reported USB exclusive. Refuse here,
        // before anything is stopped or reconfigured.
        if (mode == OutputMode.USB_EXCLUSIVE && attachedUsbDeviceId() == null) {
            diagnostics.log(DiagnosticLevel.WARNING, "output", "USB exclusive refused: no USB audio device")
            _userMessage.value = appContext.getString(com.sertum.player.R.string.usb_exclusive_requires_dac)
            updateUiState()
            return false
        }
        _outputMode.value = mode
        val wasPlaying = player.isPlaying
        player.stop()
        engine.router.exclusiveEnabled = mode == OutputMode.USB_EXCLUSIVE
        diagnostics.log(DiagnosticLevel.INFO, "output", "switched to $mode (cold restart)")
        if (playlist.isNotEmpty()) {
            player.prepare()
            if (wasPlaying) player.play() else player.pause()
        }
        updateUiState()
        return true
    }

    /** The attached DAC's `AudioDeviceInfo.id`, or null when there is none. */
    private fun attachedUsbDeviceId(): Int? = runCatching {
        usbOutputDeviceId(
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.toOutputRef() },
        )
    }.getOrNull()

    fun setBluetoothConnected(connected: Boolean) {
        if (_bluetoothConnected.value == connected) return
        _bluetoothConnected.value = connected
        diagnostics.log(DiagnosticLevel.INFO, "output", "bluetooth=${if (connected) "on" else "off"}")
        updateUiState()
    }

    // ---- USB hotplug (PRD 7.13) ----

    fun onUsbDeviceDetached() {
        if (_outputMode.value != OutputMode.USB_EXCLUSIVE) return
        player.pause()
        // Release the sink that was opened on the device that just left. The queue and the position
        // survive; the next play opens a fresh output on whatever route is there by then. Preparing
        // here instead would race the audio system still tearing the old route down.
        player.stop()
        // Fall back rather than hold the mode: with the DAC gone, "USB exclusive" describes a device
        // that is not attached, and the badge would be a claim the audio path cannot back.
        _outputMode.value = OutputMode.STANDARD
        engine.router.exclusiveEnabled = false
        _usbRecovery.value = UsbRecoveryState.DETACHED
        diagnostics.log(DiagnosticLevel.WARNING, "usb", "DAC detached; paused, queue kept, route back to standard")
        updateUiState()
    }

    fun onUsbDeviceAttached() {
        // A DAC arriving is the moment the exclusive route becomes possible. With the preference on,
        // plugging it in is enough - and because the hotplug controller reports the devices already
        // present at startup, a launch with the DAC connected lands on the exclusive route too.
        if (_outputMode.value != OutputMode.USB_EXCLUSIVE) {
            // Any detach state is settled the moment a DAC is back: either the route switches to
            // exclusive below, or it stays standard because the preference is off.
            _usbRecovery.value = UsbRecoveryState.NONE
            if (
                shouldAutoSwitchToExclusive(
                    autoSwitchEnabled = SettingsStateHolder.state.value.autoExclusiveOnUsbAttach,
                    alreadyExclusive = false,
                    dacPresent = attachedUsbDeviceId() != null,
                )
            ) {
                diagnostics.log(DiagnosticLevel.INFO, "usb", "DAC attached; selecting USB exclusive")
                switchOutputMode(OutputMode.USB_EXCLUSIVE)
            }
            diagnostics.log(DiagnosticLevel.INFO, "usb", "DAC attached; route left as ${_outputMode.value}")
            // The switch stopped and re-prepared on its own; there is no detach to recover from.
            return
        }
        if (playlist.isEmpty()) {
            diagnostics.log(DiagnosticLevel.INFO, "usb", "DAC attached; nothing queued to recover")
            _usbRecovery.value = UsbRecoveryState.NONE
            return
        }
        diagnostics.log(
            DiagnosticLevel.INFO,
            "usb",
            "DAC attached; recovering ${playlist.size} queued track(s), state=$_usbRecovery",
        )
        if (_usbRecovery.value == UsbRecoveryState.RECOVERING) return
        scope.launch {
            var recovered = false
            for (attempt in 1..MAX_USB_RECONNECT_ATTEMPTS) {
                _usbRecovery.value = UsbRecoveryState.RECOVERING
                diagnostics.log(DiagnosticLevel.INFO, "usb", "reconnect attempt $attempt/$MAX_USB_RECONNECT_ATTEMPTS")
                player.stop()
                player.prepare()
                recovered = waitForPlayerState(Player.STATE_READY, RECONNECT_TIMEOUT_MS)
                if (recovered) break
                delay(RECONNECT_BACKOFF_MS shl (attempt - 1))
            }
            if (recovered) {
                player.pause() // recoverable state; never auto-resume (PRD 7.13)
                _usbRecovery.value = UsbRecoveryState.RECOVERED
                diagnostics.log(DiagnosticLevel.INFO, "usb", "reconnected; waiting for user")
            } else {
                _usbRecovery.value = UsbRecoveryState.FAILED
                _userMessage.value = appContext.getString(com.sertum.player.R.string.usb_reconnect_failed_message)
                diagnostics.log(DiagnosticLevel.ERROR, "usb", "reconnect failed after 3 attempts")
            }
            updateUiState()
        }
    }

    // ---- messages ----

    fun consumeUserMessage() {
        _userMessage.value = null
    }

    fun release() {
        scope.cancel()
        player.release() // releases the routed audio output on the playback thread
    }

    // ---- internals ----

    private fun handlePlaybackError(error: PlaybackException) {
        val track = currentTrack()
        diagnostics.log(
            DiagnosticLevel.ERROR,
            "playback",
            "track=${track?.title ?: "?"} code=${error.errorCodeName} message=${error.message ?: ""}",
        )
        track?.let { scope.launch { markTrackUnplayable(it.id) } }
        val decision = errorPolicy.onTrackFailed()
        if (decision.notifyUser) {
            _userMessage.value = appContext.getString(com.sertum.player.R.string.error_streak_message)
        }
        val nextIndex = player.currentMediaItemIndex + 1
        if (decision.skipToNext && nextIndex < playlist.size) {
            player.seekTo(nextIndex, 0L)
            player.prepare()
            player.play()
        } else if (decision.skipToNext) {
            player.stop()
        }
        updateUiState()
    }

    private suspend fun waitForPlayerState(target: Int, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            _playerState.filter { it == target }.first()
            true
        } ?: false

    private fun currentTrack(): PlayableTrack? {
        val index = player.currentMediaItemIndex
        return playlist.getOrNull(index) ?: playlist.firstOrNull()
    }

    private fun displayOutputMode(): OutputMode = when {
        _outputMode.value == OutputMode.USB_EXCLUSIVE -> OutputMode.USB_EXCLUSIVE
        _bluetoothConnected.value -> OutputMode.BLUETOOTH
        else -> OutputMode.STANDARD
    }

    private fun bitPerfectState(): BitPerfectState = when (displayOutputMode()) {
        OutputMode.USB_EXCLUSIVE ->
            if (
                exclusiveBackend.capabilities.isExclusive &&
                streamIsOnUsbDevice() &&
                systemMusicVolume01() >= 1f
            ) {
                BitPerfectState.INTACT
            } else {
                BitPerfectState.DEGRADED
            }
        else -> BitPerfectState.NOT_APPLICABLE
    }

    /**
     * Whether the open stream is actually on the attached DAC.
     *
     * Exclusivity alone is not enough: an exclusive stream on the default output is still exclusive,
     * and with nothing plugged in that default is the phone's own speaker. The badge only goes green
     * when the stream's own device id names a USB audio output.
     */
    private fun streamIsOnUsbDevice(): Boolean {
        val streamDeviceId = exclusiveBackend.observedStreamInfo().deviceId
        if (streamDeviceId <= 0) return false
        return runCatching {
            isUsbOutputDeviceId(
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.toOutputRef() },
                streamDeviceId,
            )
        }.getOrDefault(false)
    }

    /**
     * System MUSIC stream volume as a 0..1 fraction. AAudio EXCLUSIVE is
     * still attenuated by the system music volume (device evidence
     * docs/evidence/m4-r1/volume-*.txt), so per the 2026-08-16 user decision
     * the USB badge turns yellow whenever it is below 100%.
     */
    private fun systemMusicVolume01(): Float {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 1f
        return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    private fun updateUiState() {
        val current = currentTrack()
        PlaybackStateHolder.update { state ->
            state.copy(
                trackTitle = current?.title.orEmpty(),
                artist = current?.artist ?: "—",
                album = current?.album ?: "—",
                coverRef = current?.coverRef,
                isPlaying = player.isPlaying,
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = if (player.duration > 0) player.duration else 0L,
                sampleRate = _audioInfo.value.sampleRate,
                bitDepth = _audioInfo.value.bitDepth,
                outputMode = displayOutputMode(),
                bitPerfectState = bitPerfectState(),
                queue = playlist.map { it.title },
            )
        }
    }

    private fun startPlaybackService() {
        val intent = Intent(appContext, PlaybackService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            appContext.startForegroundService(intent)
        } else {
            appContext.startService(intent)
        }
    }

    private fun ensureNotificationPermission() {
        if (notificationPermissionRequested) return
        if (android.os.Build.VERSION.SDK_INT < 33) return
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                appContext,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionRequested = true
            return
        }
        notificationPermissionRequested = true
        notificationPermissionRequester?.invoke()
    }

    private fun PlayableTrack.toMediaItem(): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(uri)
            .setMediaId(id.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .build(),
            )
        // A track carved out of a single-file disc rip is a span of the file rather than a file
        // of its own, so playback is limited to that span. The next track then reads from the
        // same file at its own offset, which is why a split disc plays continuously.
        if (startMs != null) {
            val clipping = MediaItem.ClippingConfiguration.Builder()
                // Seeking to a non-keyframe leaves a gap of silence at the start, and every
                // boundary here is a seek into a lossless stream.
                .setStartsAtKeyFrame(true)
                .setStartPositionMs(startMs)
            // Absent on the final track of a rip, whose end is the end of the file.
            endMs?.let { clipping.setEndPositionMs(it) }
            builder.setClippingConfiguration(clipping.build())
        }
        return builder.build()
    }

    companion object {
        const val MAX_USB_RECONNECT_ATTEMPTS = 3
        const val RECONNECT_TIMEOUT_MS = 2_500L
        const val RECONNECT_BACKOFF_MS = 300L
        private const val POSITION_POLL_MS = 500L
    }
}
