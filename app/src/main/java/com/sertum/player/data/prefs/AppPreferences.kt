package com.sertum.player.data.prefs

import android.content.Context
import com.sertum.player.ui.playback.OutputMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App preferences that must outlive the process.
 *
 * [com.sertum.player.ui.settings.SettingsStateHolder] stays the in-memory
 * reactive projection the UI reads, while this is the durable source of truth.
 * The split matters for two settings in particular:
 *
 * - `scanOnStartup` is consumed in `Application.onCreate`, before any UI
 *   exists, so it cannot live only in a Compose-observed holder.
 * - `outputMode` used to reset to `STANDARD` on every launch, which silently
 *   dropped the user's USB-exclusive selection and made the same track sound
 *   different across restarts.
 */
class AppPreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("sertum_app_prefs", Context.MODE_PRIVATE)

    private val _scanOnStartup = MutableStateFlow(
        prefs.getBoolean(KEY_SCAN_ON_STARTUP, DEFAULT_SCAN_ON_STARTUP),
    )

    /** Cold-start library rescan. Defaults to the historical always-scan behaviour. */
    val scanOnStartup: StateFlow<Boolean> = _scanOnStartup.asStateFlow()

    private val _outputMode = MutableStateFlow(readOutputMode())

    val outputMode: StateFlow<OutputMode> = _outputMode.asStateFlow()

    fun setScanOnStartup(enabled: Boolean) {
        if (_scanOnStartup.value == enabled) return
        _scanOnStartup.value = enabled
        prefs.edit().putBoolean(KEY_SCAN_ON_STARTUP, enabled).apply()
    }

    fun setOutputMode(mode: OutputMode) {
        if (_outputMode.value == mode) return
        _outputMode.value = mode
        prefs.edit().putString(KEY_OUTPUT_MODE, mode.name).apply()
    }

    /**
     * Seed the in-memory projections from storage. Call once during
     * `Application.onCreate`, before anything observes them.
     *
     * `PlaybackStateHolder` uses a plain holder rather than a Flow collector so
     * the restored value is visible to the first composition instead of one
     * frame later.
     */
    fun restoreIntoMemory() {
        com.sertum.player.ui.playback.PlaybackStateHolder.update {
            it.copy(outputMode = _outputMode.value)
        }
    }

    private fun readOutputMode(): OutputMode {
        val stored = prefs.getString(KEY_OUTPUT_MODE, null) ?: return OutputMode.STANDARD
        return runCatching { OutputMode.valueOf(stored) }.getOrNull() ?: OutputMode.STANDARD
    }

    companion object {
        const val DEFAULT_SCAN_ON_STARTUP = true

        internal const val KEY_SCAN_ON_STARTUP = "scan_on_startup"
        internal const val KEY_OUTPUT_MODE = "output_mode"
    }
}
