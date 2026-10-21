package com.sertum.player.audio.backend

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.sertum.player.data.prefs.AppPreferences

/**
 * Pure gain mapping for the A-22 software-volume fallback.
 *
 * The curve is `(index / max)^2`, a cheap perceptual approximation. It is deliberately not
 * presented as bit-perfect: any value below max changes the samples before they reach the DAC.
 */
object SoftwareVolume {
    const val FALLBACK_POLL_INTERVAL_MS = 250L

    /** 0 -> mute, max -> unity. A bad/zero max is treated as unity rather than as mute. */
    fun gainFor(index: Int, max: Int): Float {
        if (max <= 0) return 1f
        val fraction = index.coerceIn(0, max).toFloat() / max
        return fraction * fraction
    }
}

/**
 * Supplies the current software-volume gain while the reader has enabled the fallback.
 *
 * The source is the system music stream, not a second in-app volume control. This class:
 * - reads the index once at construction;
 * - listens for `VOLUME_CHANGED_ACTION` for STREAM_MUSIC (the cheap path);
 * - falls back to a 250ms poll while playback is writing buffers if no broadcast has been seen
 *   recently, so a ROM that does not deliver the broadcast still changes the gain.
 *
 * It never changes the system volume itself. The settings toggle does the one-time 0 -> 40%
 * rescue when it is turned on.
 */
class SoftwareVolumeController(
    context: Context,
    private val enabled: () -> Boolean = {
        AppPreferences.current()?.softwareVolumeEnabled?.value == true
    },
) {
    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    @Volatile
    private var cachedGain = 1f

    @Volatile
    private var lastPolledAtMs = 0L

    @Volatile
    private var lastBroadcastAtMs = 0L

    @Volatile
    private var closed = false

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (intent?.action != VOLUME_CHANGED_ACTION) return
            // -1 is not a stream type; it keeps an intent without the extra from being treated as
            // a music change.
            val stream = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)
            if (stream != AudioManager.STREAM_MUSIC) return
            lastBroadcastAtMs = SystemClock.elapsedRealtime()
            refresh()
        }
    }

    init {
        refresh()
        registered = runCatching {
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter(VOLUME_CHANGED_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            true
        }.getOrDefault(false)
    }

    fun isEnabled(): Boolean = runCatching { enabled() }.getOrDefault(false)

    /**
     * Current gain for the frame about to be packed. Called on the playback thread; the poll is
     * time-gated so a hot buffer loop does not issue an AudioManager query per buffer.
     */
    fun gain(): Float {
        if (closed || !isEnabled()) return 1f
        val now = SystemClock.elapsedRealtime()
        val broadcastRecentlySeen = now - lastBroadcastAtMs <= BROADCAST_GRACE_MS
        if (!broadcastRecentlySeen && now - lastPolledAtMs >= SoftwareVolume.FALLBACK_POLL_INTERVAL_MS) {
            refresh()
        }
        return cachedGain
    }

    fun close() {
        if (closed) return
        closed = true
        if (registered) {
            runCatching { appContext.unregisterReceiver(receiver) }
            registered = false
        }
    }

    private fun refresh() {
        val max = runCatching {
            audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
        }.getOrDefault(0)
        val index = runCatching {
            audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        }.getOrDefault(0)
        cachedGain = SoftwareVolume.gainFor(index, max)
        lastPolledAtMs = SystemClock.elapsedRealtime()
    }

    private companion object {
        const val BROADCAST_GRACE_MS = 1_000L

        /**
         * `AudioManager.VOLUME_CHANGED_ACTION` / `EXTRA_VOLUME_STREAM_TYPE` are hidden framework
         * constants, so they are spelled here rather than referenced. If a ROM does not deliver
         * this broadcast, [gain] falls back to the 250ms poll above.
         */
        const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
    }
}
