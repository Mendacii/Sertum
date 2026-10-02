package com.sertum.player.audio

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
import com.sertum.player.domain.playback.ResumePositionStore
import com.sertum.player.domain.playback.RoomResumePositionStore
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
)

enum class UsbRecoveryState { NONE, DETACHED, RECOVERING, RECOVERED, FAILED }

/**
 * Production playback wiring: library tracks -> Media3 -> routed output
 * (system or AAudio EXCLUSIVE) with the PRD 7.13 fault contract, resume
 * persistence and UI projection into [PlaybackStateHolder].
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackCoordinator(
    private val context: Context,
    private val engine: PlayerEngine,
    private val resumeStore: ResumePositionStore,
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
                if (!isPlaying && playlist.isNotEmpty()) saveResumePosition()
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
            val pruned = resumeStore.pruneOlderThan(System.currentTimeMillis() - RoomResumePositionStore.PRUNE_AFTER_MS)
            if (pruned > 0) diagnostics.log(DiagnosticLevel.INFO, "resume", "pruned $pruned stale positions")
        }
        scope.launch {
            while (isActive) {
                if (player.isPlaying || _outputMode.value == OutputMode.USB_EXCLUSIVE) updateUiState()
                delay(POSITION_POLL_MS)
            }
        }
        updateUiState()
    }

    // ---- playback control ----

    fun playTracks(tracks: List<PlayableTrack>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        ensureNotificationPermission()
        playlist = tracks
        queue.setQueue(tracks.map { it.id }, startIndex)
        startPlaybackService()
        scope.launch {
            val resume = resumeStore.get(tracks[startIndex].id) ?: 0L
            player.setMediaItems(tracks.map { it.toMediaItem() }, startIndex, resume)
            player.prepare()
            player.play()
            updateUiState()
        }
    }

    fun togglePlayPause() {
        if (playlist.isEmpty()) return
        if (player.isPlaying) {
            saveResumePosition()
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
        saveResumePosition()
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
     * resume/service bookkeeping of [togglePlayPause] so tapping a row works
     * even when playback was idle.
     */
    fun skipToQueueIndex(index: Int) {
        if (index !in playlist.indices) return
        if (player.currentMediaItemIndex != index) {
            saveResumePosition()
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

    fun switchOutputMode(mode: OutputMode) {
        if (mode == _outputMode.value) return
        _outputMode.value = mode
        val wasPlaying = player.isPlaying
        saveResumePosition()
        player.stop()
        engine.router.exclusiveEnabled = mode == OutputMode.USB_EXCLUSIVE
        diagnostics.log(DiagnosticLevel.INFO, "output", "switched to $mode (cold restart)")
        if (playlist.isNotEmpty()) {
            player.prepare()
            if (wasPlaying) player.play() else player.pause()
        }
        updateUiState()
    }

    fun setBluetoothConnected(connected: Boolean) {
        if (_bluetoothConnected.value == connected) return
        _bluetoothConnected.value = connected
        diagnostics.log(DiagnosticLevel.INFO, "output", "bluetooth=${if (connected) "on" else "off"}")
        updateUiState()
    }

    // ---- USB hotplug (PRD 7.13) ----

    fun onUsbDeviceDetached() {
        if (_outputMode.value != OutputMode.USB_EXCLUSIVE) return
        diagnostics.log(DiagnosticLevel.WARNING, "usb", "DAC detached; pausing and keeping queue/position")
        saveResumePosition()
        player.pause()
        _usbRecovery.value = UsbRecoveryState.DETACHED
        updateUiState()
    }

    fun onUsbDeviceAttached() {
        if (_outputMode.value != OutputMode.USB_EXCLUSIVE) return
        if (playlist.isEmpty()) {
            _usbRecovery.value = UsbRecoveryState.NONE
            return
        }
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
        saveResumePosition()
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

    private fun saveResumePosition() {
        val track = currentTrack() ?: return
        val position = player.currentPosition.coerceAtLeast(0L)
        scope.launch { resumeStore.put(track.id, position) }
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
                systemMusicVolume01() >= 1f
            ) {
                BitPerfectState.INTACT
            } else {
                BitPerfectState.DEGRADED
            }
        else -> BitPerfectState.NOT_APPLICABLE
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

    private fun PlayableTrack.toMediaItem(): MediaItem = MediaItem.Builder()
        .setUri(uri)
        .setMediaId(id.toString())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .build(),
        )
        .build()

    companion object {
        const val MAX_USB_RECONNECT_ATTEMPTS = 3
        const val RECONNECT_TIMEOUT_MS = 2_500L
        const val RECONNECT_BACKOFF_MS = 300L
        private const val POSITION_POLL_MS = 500L
    }
}
